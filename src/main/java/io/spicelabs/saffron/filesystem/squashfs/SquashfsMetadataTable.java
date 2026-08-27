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

import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.io.BinaryReader;
import io.spicelabs.saffron.io.SafeMath;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A squashfs metadata table, lazily decompressed.
 *
 * <p>Metadata tables (inode table, directory table, etc.) are sequences of
 * compressed metadata blocks. Construction reads ONLY the 2-byte block
 * headers (building the offset map); decompressed block contents are
 * produced on demand and cached in a bounded LRU of
 * {@value #MAX_CACHED_BLOCKS} blocks. References into the table use the
 * on-disk block start plus an offset into the decompressed block.</p>
 *
 * <h2>LLM section</h2>
 * <p>This replaces the eager whole-table decompression (phase 6). The
 * decompressed byte stream is identical to the eager implementation,
 * including reads that cross block boundaries (the lazy stream chains
 * blocks). The pre-change eager implementation is preserved in
 * {@code src/test} as the golden oracle.</p>
 */
final class SquashfsMetadataTable {

    private static final int METADATA_BLOCK_SIZE = 8192;

    /** Bounded cache of decompressed blocks (LRU). */
    static final int MAX_CACHED_BLOCKS = 32;

    /** One block: decompressed data position + on-disk extent. */
    private record Block(long dataPosition, long diskOffset, int diskLength,
                         int decompressedLength) {
    }

    private final DiskRegion region;
    private final SquashfsCompressor compressor;
    private final long tableStart;
    private final long tableEnd;
    private final Map<Long, Integer> blockOffsets; // relativeDiskStart -> dataPosition
    private final List<Block> blocks;              // sorted by dataPosition

    private final LinkedHashMap<Long, byte[]> blockCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                    return size() > MAX_CACHED_BLOCKS;
                }
            };

    private SquashfsMetadataTable(@NotNull DiskRegion region, @NotNull SquashfsCompressor compressor,
                                  long tableStart, long tableEnd,
                                  @NotNull Map<Long, Integer> blockOffsets,
                                  @NotNull List<Block> blocks) {
        this.region = region;
        this.compressor = compressor;
        this.tableStart = tableStart;
        this.tableEnd = tableEnd;
        this.blockOffsets = blockOffsets;
        this.blocks = blocks;
    }

    static @NotNull SquashfsMetadataTable read(@NotNull DiskRegion region,
                                                @NotNull SquashfsCompressor compressor,
                                                long start, long end) throws IOException {
        if (start < 0 || end < start || end > region.size()) {
            throw new IOException("Invalid squashfs table range");
        }

        Map<Long, Integer> blockOffsets = new LinkedHashMap<>();
        List<Block> blocks = new ArrayList<>();
        long offset = start;
        int dataPosition = 0;
        while (offset + 2 <= end) {
            long remaining = end - offset;
            ByteBuffer headerBuf = region.read(offset, 2);
            headerBuf.order(ByteOrder.LITTLE_ENDIAN);
            int header = headerBuf.getShort(0) & 0xffff;
            int size = header & 0x7fff;
            boolean compressed = (header & 0x8000) == 0;
            if (size == 0 || size > METADATA_BLOCK_SIZE || size + 2 > remaining) {
                break;
            }
            boolean isLast = offset + 2 + size >= end;
            int decompressedLength = compressed ? METADATA_BLOCK_SIZE : size;
            if (compressed && isLast) {
                // The final block may decompress short; learn its true
                // length eagerly (single block, <= 8 KiB).
                ByteBuffer buf = region.read(offset, size + 2);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                byte[] raw = new byte[size + 2];
                buf.get(raw);
                byte[] payload = new byte[size];
                System.arraycopy(raw, 2, payload, 0, size);
                decompressedLength = compressor.decompress(payload, METADATA_BLOCK_SIZE).length;
            }
            long relativeDiskStart = offset - start;
            blockOffsets.put(relativeDiskStart, dataPosition);
            blocks.add(new Block(dataPosition, offset, size, decompressedLength));
            dataPosition = SafeMath.safeAdd(dataPosition, decompressedLength);
            offset = SafeMath.safeAdd(offset, size + 2);
            if (offset >= end) {
                break;
            }
        }

        return new SquashfsMetadataTable(region, compressor, start, end, blockOffsets, blocks);
    }

    /**
     * Returns a reader positioned at the decompressed offset corresponding
     * to (blockStart, blockOffset). Reads may cross block boundaries; the
     * underlying stream decompresses blocks lazily.
     */
    @NotNull BinaryReader readerAt(long blockStart, int blockOffset) throws IOException {
        Integer dataOffset = blockOffsets.get(blockStart);
        if (dataOffset == null) {
            throw new IOException("Metadata block not found: " + blockStart);
        }
        int position = SafeMath.safeAdd((int) dataOffset, blockOffset);
        return BinaryReader.littleEndian(new LazyTableStream(position));
    }

    /** Number of resident decompressed blocks (test/observation seam). */
    int cachedBlockCount() {
        synchronized (blockCache) {
            return blockCache.size();
        }
    }

    /** Decompresses (or serves from cache) the block at a data position. */
    private byte[] blockAt(int dataPosition) throws IOException {
        // Find the greatest block whose dataPosition <= dataPosition.
        int idx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).dataPosition() <= dataPosition) {
                idx = i;
            } else {
                break;
            }
        }
        if (idx < 0) {
            throw new IOException("Metadata position not in table: " + dataPosition);
        }
        Block block = blocks.get(idx);
        synchronized (blockCache) {
            byte[] cached = blockCache.get(block.dataPosition());
            if (cached != null) {
                return cached;
            }
            ByteBuffer buf = region.read(block.diskOffset(), block.diskLength() + 2);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            byte[] raw = new byte[block.diskLength() + 2];
            buf.get(raw);
            byte[] data = new byte[block.diskLength()];
            System.arraycopy(raw, 2, data, 0, block.diskLength());
            if (block.decompressedLength() != block.diskLength()) {
                data = compressor.decompress(data, block.decompressedLength());
            }
            blockCache.put(block.dataPosition(), data);
            return data;
        }
    }

    /** Lazy concatenating stream over the table's decompressed blocks. */
    private final class LazyTableStream extends InputStream {
        private long position;

        LazyTableStream(int position) {
            this.position = position;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (position >= totalDecompressedLength()) {
                return -1;
            }
            int total = 0;
            while (total < len) {
                if (position >= totalDecompressedLength()) {
                    break;
                }
                byte[] block = blockAt((int) position);
                // find block start for position
                long blockStart = -1;
                for (int i = 0; i < blocks.size(); i++) {
                    if (blocks.get(i).dataPosition() <= position) {
                        blockStart = blocks.get(i).dataPosition();
                    } else {
                        break;
                    }
                }
                int within = (int) (position - blockStart);
                long n = Math.min(Math.min(len - total, block.length - within),
                        totalDecompressedLength() - position);
                int count = (int) n;
                System.arraycopy(block, within, b, off + total, count);
                position += count;
                total += count;
            }
            return total == 0 ? -1 : total;
        }

        private long totalDecompressedLength() {
            if (blocks.isEmpty()) {
                return 0;
            }
            Block last = blocks.get(blocks.size() - 1);
            return last.dataPosition() + last.decompressedLength();
        }
    }
}
