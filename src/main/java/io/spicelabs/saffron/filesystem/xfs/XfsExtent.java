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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.xfs;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an XFS extent (BMBT record).
 *
 * <p>XFS extents are 128-bit packed records:
 * <pre>
 * Bits 0-8:    extent flag (1 bit) + logical block offset high (8 bits)
 * Bits 9-72:   logical block offset low (43 bits) + block count (21 bits)
 * Bits 73-127: physical block number (52 bits)
 *
 * Layout in 16 bytes (big-endian):
 * Byte 0:      [flag:1][logicalHi:7]
 * Bytes 1-6:   [logicalLo:43]
 * Bytes 6-8:   [blockCount:21]
 * Bytes 8-15:  [physicalBlock:52]
 * </pre>
 *
 * The extent is "packed" meaning fields span byte boundaries.
 */
public record XfsExtent(
        boolean prealloc,      // Extent is preallocated (unwritten)
        long logicalOffset,    // Logical block offset in file
        long physicalBlock,    // Physical block number on disk
        int blockCount         // Number of blocks
) {

    /** Size of a packed extent record */
    public static final int EXTENT_SIZE = 16;

    /**
     * Parses extent records from the data fork.
     *
     * @param dataFork the data fork bytes
     * @param extentCount the number of extents to parse
     * @return list of extents
     */
    public static @NotNull List<XfsExtent> parseExtents(byte[] dataFork, int extentCount) {
        List<XfsExtent> extents = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(dataFork);
        buf.order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < extentCount && buf.remaining() >= EXTENT_SIZE; i++) {
            extents.add(parseOne(buf));
        }

        return extents;
    }

    /**
     * Parses a single extent from the buffer at current position.
     */
    private static XfsExtent parseOne(ByteBuffer buf) {
        // Read 16 bytes as two longs
        long high = buf.getLong();
        long low = buf.getLong();

        // Unpack the fields
        // Bit 127 (MSB of high): extent flag
        boolean prealloc = (high & 0x8000000000000000L) != 0;

        // Bits 73-126 (54 bits): logical offset
        // high bits 0-53 contain: [flag:1][logical:53]
        long logicalOffset = (high >>> 9) & 0x001FFFFFFFFFFFFFL;

        // Bits 52-72 (21 bits): block count
        // Spread across high (low 9 bits) and low (high 12 bits)
        int blockCount = (int) (((high & 0x1FF) << 12) | ((low >>> 52) & 0xFFF));

        // Bits 0-51 (52 bits): physical block
        long physicalBlock = low & 0x000FFFFFFFFFFFFFL;

        return new XfsExtent(prealloc, logicalOffset, physicalBlock, blockCount);
    }

    /**
     * Parses a B+tree root from the data fork.
     *
     * @param dataFork the data fork bytes
     * @return the B+tree root header
     */
    public static @NotNull BtreeRoot parseBtreeRoot(byte[] dataFork) {
        ByteBuffer buf = ByteBuffer.wrap(dataFork);
        buf.order(ByteOrder.BIG_ENDIAN);

        int level = buf.getShort(0) & 0xFFFF;
        int numrecs = buf.getShort(2) & 0xFFFF;

        // Keys start at offset 4
        // Pointers start after keys
        List<BtreeKey> keys = new ArrayList<>();
        List<Long> pointers = new ArrayList<>();

        int keyOffset = 4;
        int ptrOffset = 4 + numrecs * 8; // Each key is 8 bytes

        for (int i = 0; i < numrecs; i++) {
            long startoff = buf.getLong(keyOffset + i * 8);
            keys.add(new BtreeKey(startoff));
        }

        for (int i = 0; i < numrecs; i++) {
            long ptr = buf.getLong(ptrOffset + i * 8);
            pointers.add(ptr);
        }

        return new BtreeRoot(level, numrecs, keys, pointers);
    }

    /**
     * B+tree root in the data fork.
     */
    public record BtreeRoot(
            int level,
            int numrecs,
            List<BtreeKey> keys,
            List<Long> pointers
    ) {
        public boolean isLeaf() {
            return level == 0;
        }
    }

    /**
     * B+tree key (just the starting offset for BMBT).
     */
    public record BtreeKey(long startOffset) {}

    /**
     * B+tree block header (for reading blocks from disk).
     */
    public record BtreeBlockHeader(
            int magic,
            int level,
            int numrecs,
            long leftSibling,
            long rightSibling
    ) {
        public static final int MAGIC_BMAP = 0x424D4150; // "BMAP"
        public static final int MAGIC_BMA3 = 0x424D4133; // "BMA3" (v5)

        public static @NotNull BtreeBlockHeader parse(@NotNull ByteBuffer buf, boolean isV5) {
            buf.order(ByteOrder.BIG_ENDIAN);
            int magic = buf.getInt(0);
            int level = buf.getShort(4) & 0xFFFF;
            int numrecs = buf.getShort(6) & 0xFFFF;
            long leftSibling = buf.getLong(8);
            long rightSibling = buf.getLong(16);
            return new BtreeBlockHeader(magic, level, numrecs, leftSibling, rightSibling);
        }

        public int headerSize(boolean isV5) {
            return isV5 ? 72 : 24;
        }

        public boolean isValid() {
            return magic == MAGIC_BMAP || magic == MAGIC_BMA3;
        }
    }
}
