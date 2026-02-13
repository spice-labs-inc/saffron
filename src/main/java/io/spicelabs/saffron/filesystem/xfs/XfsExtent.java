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
 * <p>XFS extents are 128-bit packed records (from xfs_bmbt_disk_get_all):
 * <pre>{@code
 * l0 (first 8 bytes, big-endian):
 *   Bit 63:     extent flag (1=unwritten/preallocated)
 *   Bits 62-9:  logical file block offset (54 bits)
 *   Bits 8-0:   high 9 bits of physical start block
 *
 * l1 (second 8 bytes, big-endian):
 *   Bits 63-21: low 43 bits of physical start block
 *   Bits 20-0:  block count (21 bits)
 *
 * physical = (l0[8:0] << 43) | (l1 >> 21)   → 52 bits
 * blockCount = l1 & 0x1FFFFF                 → 21 bits
 * }</pre>
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
     * Matches the Linux kernel's xfs_bmbt_disk_get_all() in libxfs/xfs_bmap_btree.c.
     */
    private static XfsExtent parseOne(ByteBuffer buf) {
        // Read 16 bytes as two big-endian longs
        long l0 = buf.getLong();
        long l1 = buf.getLong();

        // Bit 63 of l0: extent flag (unwritten/preallocated)
        boolean prealloc = (l0 & 0x8000000000000000L) != 0;

        // Bits 62-9 of l0: logical file block offset (54 bits)
        long logicalOffset = (l0 & 0x7FFFFFFFFFFFFFFFL) >>> 9;

        // Physical start block (52 bits): l0 bits 8-0 are high 9 bits, l1 bits 63-21 are low 43 bits
        long physicalBlock = ((l0 & 0x1FFL) << 43) | (l1 >>> 21);

        // Block count (21 bits): l1 bits 20-0
        int blockCount = (int) (l1 & 0x1FFFFFL);

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

        // xfs_bmdr_block layout: level(2) + numrecs(2) + keys[maxrecs] + ptrs[maxrecs]
        // Keys start at offset 4. Pointers start at 4 + maxrecs * 8.
        // maxrecs = (dataForkSize - 4) / 16  (each record = key(8) + ptr(8))
        // CRITICAL: ptrs are at maxrecs offset, NOT numrecs offset.
        List<BtreeKey> keys = new ArrayList<>();
        List<Long> pointers = new ArrayList<>();

        int keyOffset = 4;
        int maxrecs = (dataFork.length - 4) / 16;
        int ptrOffset = 4 + maxrecs * 8;

        for (int i = 0; i < numrecs; i++) {
            if (keyOffset + i * 8 + 8 > dataFork.length) break;
            long startoff = buf.getLong(keyOffset + i * 8);
            keys.add(new BtreeKey(startoff));
        }

        for (int i = 0; i < numrecs; i++) {
            if (ptrOffset + i * 8 + 8 > dataFork.length) break;
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
