/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.io.BinaryReader;
import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A squashfs metadata table.
 *
 * <p>Metadata tables (inode table, directory table, etc.) are made up of
 * compressed metadata blocks. Each block begins with a two-byte header that
 * gives the on-disk size and whether the block is compressed. The contents of
 * all blocks are concatenated to form a logical table, but references into the
 * table use the <em>on-disk</em> block start offset together with an offset into
 * the decompressed block.</p>
 *
 * <p>This class keeps a mapping from on-disk block start (relative to the
 * table start) to the corresponding position in the decompressed table, so
 * callers can resolve squashfs references correctly.</p>
 */
final class SquashfsMetadataTable {

    private static final int METADATA_BLOCK_SIZE = 8192;

    private final byte[] data;
    private final Map<Long, Integer> blockOffsets;

    private SquashfsMetadataTable(byte[] data, Map<Long, Integer> blockOffsets) {
        this.data = data;
        this.blockOffsets = blockOffsets;
    }

    static @NotNull SquashfsMetadataTable read(@NotNull DiskRegion region, @NotNull SquashfsCompressor compressor,
                                                long start, long end) throws IOException {
        if (start < 0 || end < start || end > region.size()) {
            throw new IOException("Invalid squashfs table range");
        }

        List<byte[]> blocks = new ArrayList<>();
        Map<Long, Integer> blockOffsets = new HashMap<>();
        long offset = start;
        int dataPosition = 0;
        while (offset + 2 <= end) {
            long remaining = end - offset;
            ByteBuffer headerBuf = region.read(offset, 2);
            headerBuf.order(ByteOrder.LITTLE_ENDIAN);
            int header = headerBuf.getShort(0) & 0xffff;
            int size = header & 0x7fff;
            if (size == 0 || size > METADATA_BLOCK_SIZE || size + 2 > remaining) {
                break;
            }
            long relativeDiskStart = offset - start;
            MetadataBlock block = readMetadataBlock(region, compressor, offset);
            blockOffsets.put(relativeDiskStart, dataPosition);
            blocks.add(block.data);
            dataPosition = SafeMath.safeAdd(dataPosition, block.data.length);
            offset = block.nextOffset;
            if (offset >= end) {
                break;
            }
        }

        int total = 0;
        for (byte[] b : blocks) {
            total = SafeMath.safeAdd(total, b.length);
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] b : blocks) {
            System.arraycopy(b, 0, result, pos, b.length);
            pos += b.length;
        }

        return new SquashfsMetadataTable(result, blockOffsets);
    }

    private static MetadataBlock readMetadataBlock(DiskRegion region, SquashfsCompressor compressor, long offset) throws IOException {
        if (offset < 0 || offset + 2 > region.size()) {
            throw new IOException("Metadata block header out of bounds");
        }
        ByteBuffer headerBuf = region.read(offset, 2);
        headerBuf.order(ByteOrder.LITTLE_ENDIAN);
        int header = headerBuf.getShort(0) & 0xffff;
        int size = header & 0x7fff;
        boolean compressed = (header & 0x8000) == 0;
        if (size == 0 || size > METADATA_BLOCK_SIZE) {
            throw new IOException("Invalid squashfs metadata block size: " + size);
        }
        long dataOffset = SafeMath.safeAdd(offset, 2);
        long nextOffset = SafeMath.safeAdd(dataOffset, size);
        if (nextOffset > region.size()) {
            throw new IOException("Squashfs metadata block exceeds image size");
        }
        byte[] data = new byte[size];
        region.read(dataOffset, size).get(data);
        if (compressed) {
            data = compressor.decompress(data, METADATA_BLOCK_SIZE);
        }
        return new MetadataBlock(data, nextOffset);
    }

    @NotNull BinaryReader readerAt(long blockStart, int blockOffset) throws IOException {
        Integer dataOffset = blockOffsets.get(blockStart);
        if (dataOffset == null) {
            throw new IOException("Metadata block not found: " + blockStart);
        }
        int position = SafeMath.safeAdd(dataOffset, blockOffset);
        if (position < 0 || position >= data.length) {
            throw new IOException("Metadata block offset out of bounds: " + blockStart + " + " + blockOffset);
        }
        BinaryReader reader = BinaryReader.littleEndian(new ByteArrayInputStream(data));
        reader.skip(position);
        return reader;
    }

    int size() {
        return data.length;
    }

    private record MetadataBlock(byte[] data, long nextOffset) {
    }
}
