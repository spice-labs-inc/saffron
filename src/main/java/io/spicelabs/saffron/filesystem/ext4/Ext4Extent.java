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
package io.spicelabs.saffron.filesystem.ext4;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents ext4 extent structures.
 *
 * <p>Extent header (12 bytes):
 * <pre>
 * Offset  Size  Description
 * 0       2     eh_magic (0xF30A)
 * 2       2     eh_entries (number of valid entries)
 * 4       2     eh_max (maximum number of entries)
 * 6       2     eh_depth (depth of tree, 0 = leaf)
 * 8       4     eh_generation
 * </pre>
 *
 * <p>Extent leaf entry (12 bytes):
 * <pre>
 * Offset  Size  Description
 * 0       4     ee_block (first logical block)
 * 4       2     ee_len (number of blocks)
 * 6       2     ee_start_hi (high 16 bits of physical block)
 * 8       4     ee_start_lo (low 32 bits of physical block)
 * </pre>
 *
 * <p>Extent index entry (12 bytes):
 * <pre>
 * Offset  Size  Description
 * 0       4     ei_block (first logical block covered)
 * 4       4     ei_leaf_lo (low 32 bits of physical block of extent node)
 * 8       2     ei_leaf_hi (high 16 bits of physical block)
 * 10      2     ei_unused
 * </pre>
 */
public class Ext4Extent {
    /** Extent magic number */
    public static final short EXTENT_MAGIC = (short) 0xF30A;

    /** Size of extent header */
    public static final int HEADER_SIZE = 12;

    /** Size of each extent entry */
    public static final int ENTRY_SIZE = 12;

    /**
     * Extent header.
     */
    public record Header(
            short magic,
            int entries,
            int max,
            int depth,
            int generation
    ) {
        public static Header parse(ByteBuffer buffer) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int pos = buffer.position();
            short magic = buffer.getShort(pos + 0);
            int entries = buffer.getShort(pos + 2) & 0xFFFF;
            int max = buffer.getShort(pos + 4) & 0xFFFF;
            int depth = buffer.getShort(pos + 6) & 0xFFFF;
            int generation = buffer.getInt(pos + 8);
            return new Header(magic, entries, max, depth, generation);
        }

        public boolean isValid() {
            return magic == EXTENT_MAGIC;
        }

        public boolean isLeaf() {
            return depth == 0;
        }
    }

    /**
     * Extent leaf entry (actual data extent).
     */
    public record Leaf(
            long logicalBlock,
            int length,
            long physicalBlock,
            boolean uninitialized
    ) {
        public static Leaf parse(ByteBuffer buffer) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int pos = buffer.position();
            long block = buffer.getInt(pos + 0) & 0xFFFFFFFFL;
            int len = buffer.getShort(pos + 4) & 0xFFFF;
            int startHi = buffer.getShort(pos + 6) & 0xFFFF;
            long startLo = buffer.getInt(pos + 8) & 0xFFFFFFFFL;

            // High bit of length indicates uninitialized extent
            boolean uninit = (len & 0x8000) != 0;
            int actualLen = len & 0x7FFF;

            long physicalBlock = startLo | ((long) startHi << 32);
            return new Leaf(block, actualLen, physicalBlock, uninit);
        }
    }

    /**
     * Extent index entry (pointer to extent node at deeper level).
     */
    public record Index(
            long logicalBlock,
            long leafBlock
    ) {
        public static Index parse(ByteBuffer buffer) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int pos = buffer.position();
            long block = buffer.getInt(pos + 0) & 0xFFFFFFFFL;
            long leafLo = buffer.getInt(pos + 4) & 0xFFFFFFFFL;
            int leafHi = buffer.getShort(pos + 8) & 0xFFFF;
            long leafBlock = leafLo | ((long) leafHi << 32);
            return new Index(block, leafBlock);
        }
    }

    /**
     * Parses extent entries from the inode's block data.
     *
     * @param blockData the 60-byte i_block data from inode
     * @return the list of leaf extents or index entries
     */
    public static @NotNull List<Leaf> parseLeafExtents(@NotNull byte[] blockData) {
        ByteBuffer buffer = ByteBuffer.wrap(blockData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        Header header = Header.parse(buffer);
        if (!header.isValid()) {
            return List.of();
        }

        List<Leaf> extents = new ArrayList<>();
        if (header.isLeaf()) {
            for (int i = 0; i < header.entries(); i++) {
                buffer.position(HEADER_SIZE + (i * ENTRY_SIZE));
                extents.add(Leaf.parse(buffer));
            }
        }
        return extents;
    }

    /**
     * Parses extent indices from the inode's block data.
     *
     * @param blockData the extent data
     * @return the list of index entries
     */
    public static @NotNull List<Index> parseIndexExtents(@NotNull byte[] blockData) {
        ByteBuffer buffer = ByteBuffer.wrap(blockData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        Header header = Header.parse(buffer);
        if (!header.isValid() || header.isLeaf()) {
            return List.of();
        }

        List<Index> indices = new ArrayList<>();
        for (int i = 0; i < header.entries(); i++) {
            buffer.position(HEADER_SIZE + (i * ENTRY_SIZE));
            indices.add(Index.parse(buffer));
        }
        return indices;
    }

    /**
     * Returns the header from extent data.
     */
    public static Header parseHeader(@NotNull byte[] blockData) {
        ByteBuffer buffer = ByteBuffer.wrap(blockData);
        return Header.parse(buffer);
    }
}
