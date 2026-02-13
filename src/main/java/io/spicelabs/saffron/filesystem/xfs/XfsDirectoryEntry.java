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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an XFS directory entry.
 *
 * <p>XFS has multiple directory formats:
 * <ul>
 *   <li>Shortform: small directories inline in inode data fork</li>
 *   <li>Block: single filesystem block directory</li>
 *   <li>Leaf: multi-block directory with leaf entries</li>
 *   <li>Node: B+tree directory structure</li>
 * </ul>
 */
public record XfsDirectoryEntry(
        long inode,
        @NotNull String name,
        int fileType
) {

    // File type values (same as ext4)
    public static final int FT_UNKNOWN = 0;
    public static final int FT_REG_FILE = 1;
    public static final int FT_DIR = 2;
    public static final int FT_CHRDEV = 3;
    public static final int FT_BLKDEV = 4;
    public static final int FT_FIFO = 5;
    public static final int FT_SOCK = 6;
    public static final int FT_SYMLINK = 7;

    public boolean isDot() {
        return ".".equals(name);
    }

    public boolean isDotDot() {
        return "..".equals(name);
    }

    /**
     * Parses shortform directory entries from the data fork.
     *
     * <p>Shortform directory structure:
     * <pre>
     * Offset  Size  Description
     * 0       1     count (number of entries, not counting . and ..)
     * 1       1     i8count (entries with 8-byte inodes)
     * 2       4/8   parent inode
     * ...           entries
     * </pre>
     *
     * <p>Each shortform entry:
     * <pre>
     * 0       1     namelen
     * 1       2     offset (hash value for lookups)
     * 3       N     name bytes
     * 3+N     4/8   inode number
     * </pre>
     */
    public static @NotNull List<XfsDirectoryEntry> parseShortform(byte[] dataFork, long parentInode, boolean isV5) {
        List<XfsDirectoryEntry> entries = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(dataFork);
        buf.order(ByteOrder.BIG_ENDIAN);

        if (buf.remaining() < 6) {
            return entries;
        }

        int count = buf.get(0) & 0xFF;
        int i8count = buf.get(1) & 0xFF;
        boolean use8ByteInodes = i8count > 0;

        // Parent inode at offset 2
        int offset = 2;
        long parentIno;
        if (use8ByteInodes) {
            parentIno = buf.getLong(offset);
            offset += 8;
        } else {
            parentIno = buf.getInt(offset) & 0xFFFFFFFFL;
            offset += 4;
        }

        // Add . and .. entries
        // We need the current directory's inode, but for shortform we use the parent
        entries.add(new XfsDirectoryEntry(parentInode, ".", FT_DIR));
        entries.add(new XfsDirectoryEntry(parentIno, "..", FT_DIR));

        // Parse each entry
        for (int i = 0; i < count && offset < dataFork.length - 4; i++) {
            int namelen = buf.get(offset) & 0xFF;
            offset += 1;

            // Skip offset field (2 bytes)
            offset += 2;

            if (offset + namelen > dataFork.length) break;

            byte[] nameBytes = new byte[namelen];
            buf.position(offset);
            buf.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            offset += namelen;

            // File type (only in v5)
            int fileType = FT_UNKNOWN;
            if (isV5 && offset < dataFork.length) {
                fileType = buf.get(offset) & 0xFF;
                offset += 1;
            }

            // Inode number
            long inode;
            if (use8ByteInodes) {
                if (offset + 8 > dataFork.length) break;
                inode = buf.getLong(offset);
                offset += 8;
            } else {
                if (offset + 4 > dataFork.length) break;
                inode = buf.getInt(offset) & 0xFFFFFFFFL;
                offset += 4;
            }

            entries.add(new XfsDirectoryEntry(inode, name, fileType));
        }

        return entries;
    }

    /**
     * Parses block directory entries from a data block.
     *
     * <p>Block directory data block structure:
     * <pre>
     * Header (v4: 16 bytes, v5: 64 bytes)
     * Entries...
     * Free space
     * Leaf entries and tail at end of block
     * </pre>
     *
     * <p>Each data entry:
     * <pre>
     * 0       8     inode number
     * 8       1     namelen
     * 9       N     name
     * 9+N     1     file type (v5 only, padded to 8-byte boundary)
     * </pre>
     */
    public static @NotNull List<XfsDirectoryEntry> parseBlock(byte[] block, int blockSize, boolean isV5) {
        List<XfsDirectoryEntry> entries = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(block);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Check for data block magic
        int magic = buf.getInt(0);
        int headerSize;
        if (magic == 0x58444233     // XDB3 - v5 single-block directory
                || magic == 0x58444433) { // XDD3 - v5 multi-block data block
            // v5 header: xfs_dir3_data_hdr = xfs_dir3_blk_hdr(48) + bestfree[3](12) + pad(4) = 64
            headerSize = 64;
        } else if (magic == 0x58443242     // XD2B - v4 single-block directory
                || magic == 0x58443244) {  // XD2D - v4 multi-block data block
            // v4 header: magic (4 bytes) + bestfree[3] (12 bytes) = 16
            headerSize = 16;
        } else {
            // Try parsing anyway, might be a different block type
            headerSize = 16;
        }

        // Determine the end of the data entries area.
        // Single-block dirs (XDB3/XD2B) have a tail + leaf entries at the block end.
        // Multi-block data blocks (XDD3/XD2D) have no tail; entries fill the block.
        int offset = headerSize;
        int maxOffset;
        boolean isSingleBlock = (magic == 0x58444233 || magic == 0x58443242);
        if (isSingleBlock) {
            // Single-block dir: xfs_dir2_block_tail at blockSize-8 has count(4)+stale(4)
            // Leaf entries (count * 8 bytes) precede the tail
            int tailCount = buf.getInt(blockSize - 8);
            if (tailCount > 0 && tailCount < blockSize / 8) {
                maxOffset = blockSize - 8 - tailCount * 8;
            } else {
                maxOffset = blockSize - 8; // Just skip the tail itself
            }
        } else {
            // Multi-block data block or unknown: entries can fill the entire block
            maxOffset = blockSize;
        }

        while (offset < maxOffset) {
            // Need room for at least freetag(2) or inode(8)+namelen(1)
            if (offset + 9 > block.length) break;

            int freetag = buf.getShort(offset) & 0xFFFF;
            if (freetag == 0xFFFF) {
                // Free space - skip it
                int length = buf.getShort(offset + 2) & 0xFFFF;
                if (length == 0) break;
                offset += length;
                continue;
            }

            // Read entry
            long inode = buf.getLong(offset);
            if (inode == 0) {
                offset += 8;
                continue;
            }

            int namelen = buf.get(offset + 8) & 0xFF;
            if (namelen == 0 || offset + 9 + namelen > block.length) {
                break;
            }

            byte[] nameBytes = new byte[namelen];
            buf.position(offset + 9);
            buf.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            // File type follows name (padded to 8-byte boundary)
            int fileType = FT_UNKNOWN;
            if (isV5) {
                int ftOffset = offset + 9 + namelen;
                if (ftOffset < block.length) {
                    fileType = buf.get(ftOffset) & 0xFF;
                }
            }

            entries.add(new XfsDirectoryEntry(inode, name, fileType));

            // Move to next entry (8-byte aligned)
            // XFS data entry: inumber(8) + namelen(1) + name(N) + [ftype(1) if v5] + tag(2)
            // Matches kernel XFS_DIR3_DATA_ENTSIZE(n) = roundup(12+n, 8) for v5
            //         kernel XFS_DIR2_DATA_ENTSIZE(n) = roundup(11+n, 8) for v4
            int entrySize = isV5 ? (12 + namelen) : (11 + namelen);
            offset += (entrySize + 7) & ~7; // Round up to 8-byte boundary
        }

        return entries;
    }

    /**
     * Parses a leaf directory block to extract entries.
     * Leaf blocks contain hash->address mappings that point to data blocks.
     */
    public static @NotNull LeafBlock parseLeafBlock(byte[] block, boolean isV5) {
        ByteBuffer buf = ByteBuffer.wrap(block);
        buf.order(ByteOrder.BIG_ENDIAN);

        int magic = buf.getInt(0);
        int headerSize;

        // Check magic
        if (magic == 0x58444C33) { // "XDL3" (v5 leaf)
            headerSize = 64;
        } else if (magic == 0x5844324C) { // "XD2L" (v4 leaf)
            headerSize = 16;
        } else {
            return new LeafBlock(0, List.of());
        }

        // Leaf info at end of header
        int count = buf.getShort(headerSize - 4) & 0xFFFF;
        int stale = buf.getShort(headerSize - 2) & 0xFFFF;

        List<LeafEntry> leafEntries = new ArrayList<>();
        int offset = headerSize;

        for (int i = 0; i < count && offset + 8 <= block.length; i++) {
            int hashval = buf.getInt(offset);
            int address = buf.getInt(offset + 4);
            leafEntries.add(new LeafEntry(hashval, address));
            offset += 8;
        }

        return new LeafBlock(count - stale, leafEntries);
    }

    /**
     * Leaf block containing hash->address mappings.
     */
    public record LeafBlock(int activeCount, List<LeafEntry> entries) {}

    /**
     * Single leaf entry mapping hash to data block address.
     */
    public record LeafEntry(int hashval, int address) {
        public boolean isStale() {
            return address == 0xFFFFFFFF;
        }
    }
}
