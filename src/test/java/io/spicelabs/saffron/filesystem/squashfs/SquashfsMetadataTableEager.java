/*
 * Copyright 2026 Spice Labs, Inc.
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
 * The pre-phase-6 EAGER squashfs metadata table, preserved in test scope
 * as the permanent golden oracle for {@link SquashfsMetadataTable}'s lazy
 * implementation.
 *
 * <h2>LLM section</h2>
 * <p>This class is a verbatim copy of the eager implementation (whole
 * table decompressed at construction). The property test compares lazy
 * reader output against this oracle byte-for-byte.</p>
 */
final class SquashfsMetadataTableEager {

    private static final int METADATA_BLOCK_SIZE = 8192;

    private final byte[] data;
    private final Map<Long, Integer> blockOffsets;

    private SquashfsMetadataTableEager(byte[] data, Map<Long, Integer> blockOffsets) {
        this.data = data;
        this.blockOffsets = blockOffsets;
    }

    static @NotNull SquashfsMetadataTableEager read(@NotNull DiskRegion region,
                                                     @NotNull SquashfsCompressor compressor,
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

        return new SquashfsMetadataTableEager(result, blockOffsets);
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

    private record MetadataBlock(byte[] data, long nextOffset) {
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
}
