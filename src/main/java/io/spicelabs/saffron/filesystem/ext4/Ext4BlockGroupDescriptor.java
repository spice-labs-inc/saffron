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
package io.spicelabs.saffron.filesystem.ext4;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents an ext4 block group descriptor.
 *
 * <p>Block group descriptor structure:
 * <pre>
 * Offset  Size  Description
 * 0       4     bg_block_bitmap_lo (block bitmap block, lower 32 bits)
 * 4       4     bg_inode_bitmap_lo (inode bitmap block, lower 32 bits)
 * 8       4     bg_inode_table_lo (inode table block, lower 32 bits)
 * 12      2     bg_free_blocks_count_lo
 * 14      2     bg_free_inodes_count_lo
 * 16      2     bg_used_dirs_count_lo
 * 18      2     bg_flags
 * 20      4     bg_exclude_bitmap_lo
 * 24      2     bg_block_bitmap_csum_lo
 * 26      2     bg_inode_bitmap_csum_lo
 * 28      2     bg_itable_unused_lo
 * 30      2     bg_checksum
 *
 * For 64-bit filesystems (descriptor size 64):
 * 32      4     bg_block_bitmap_hi
 * 36      4     bg_inode_bitmap_hi
 * 40      4     bg_inode_table_hi
 * 44      2     bg_free_blocks_count_hi
 * 46      2     bg_free_inodes_count_hi
 * 48      2     bg_used_dirs_count_hi
 * 50      2     bg_itable_unused_hi
 * </pre>
 */
public record Ext4BlockGroupDescriptor(
        long blockBitmap,
        long inodeBitmap,
        long inodeTable,
        int freeBlocksCount,
        int freeInodesCount,
        int usedDirsCount,
        int flags,
        int itableUnused
) {
    /** Standard descriptor size (32 bytes) */
    public static final int DESCRIPTOR_SIZE_32 = 32;

    /** Extended descriptor size for 64-bit filesystems (64 bytes) */
    public static final int DESCRIPTOR_SIZE_64 = 64;

    /**
     * Parses a block group descriptor from a ByteBuffer.
     *
     * @param buffer the buffer containing the descriptor
     * @param is64Bit whether this is a 64-bit filesystem
     * @return the parsed descriptor
     */
    public static @NotNull Ext4BlockGroupDescriptor parse(@NotNull ByteBuffer buffer, boolean is64Bit) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int startPos = buffer.position();

        long blockBitmapLo = buffer.getInt(startPos + 0) & 0xFFFFFFFFL;
        long inodeBitmapLo = buffer.getInt(startPos + 4) & 0xFFFFFFFFL;
        long inodeTableLo = buffer.getInt(startPos + 8) & 0xFFFFFFFFL;
        int freeBlocksCountLo = buffer.getShort(startPos + 12) & 0xFFFF;
        int freeInodesCountLo = buffer.getShort(startPos + 14) & 0xFFFF;
        int usedDirsCountLo = buffer.getShort(startPos + 16) & 0xFFFF;
        int flags = buffer.getShort(startPos + 18) & 0xFFFF;
        int itableUnusedLo = buffer.getShort(startPos + 28) & 0xFFFF;

        long blockBitmap = blockBitmapLo;
        long inodeBitmap = inodeBitmapLo;
        long inodeTable = inodeTableLo;
        int freeBlocksCount = freeBlocksCountLo;
        int freeInodesCount = freeInodesCountLo;
        int usedDirsCount = usedDirsCountLo;
        int itableUnused = itableUnusedLo;

        if (is64Bit && buffer.remaining() >= 32) {
            long blockBitmapHi = buffer.getInt(startPos + 32) & 0xFFFFFFFFL;
            long inodeBitmapHi = buffer.getInt(startPos + 36) & 0xFFFFFFFFL;
            long inodeTableHi = buffer.getInt(startPos + 40) & 0xFFFFFFFFL;
            int freeBlocksCountHi = buffer.getShort(startPos + 44) & 0xFFFF;
            int freeInodesCountHi = buffer.getShort(startPos + 46) & 0xFFFF;
            int usedDirsCountHi = buffer.getShort(startPos + 48) & 0xFFFF;
            int itableUnusedHi = buffer.getShort(startPos + 50) & 0xFFFF;

            blockBitmap |= (blockBitmapHi << 32);
            inodeBitmap |= (inodeBitmapHi << 32);
            inodeTable |= (inodeTableHi << 32);
            freeBlocksCount |= (freeBlocksCountHi << 16);
            freeInodesCount |= (freeInodesCountHi << 16);
            usedDirsCount |= (usedDirsCountHi << 16);
            itableUnused |= (itableUnusedHi << 16);
        }

        return new Ext4BlockGroupDescriptor(blockBitmap, inodeBitmap, inodeTable,
                freeBlocksCount, freeInodesCount, usedDirsCount, flags, itableUnused);
    }

    /**
     * Returns the descriptor size for a given filesystem.
     */
    public static int descriptorSize(boolean is64Bit) {
        return is64Bit ? DESCRIPTOR_SIZE_64 : DESCRIPTOR_SIZE_32;
    }
}
