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

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Represents the XFS superblock.
 *
 * <p>The superblock is located at the first sector of the XFS filesystem
 * and is replicated in each allocation group.
 *
 * <p>Superblock structure (selected fields, big-endian):
 * <pre>
 * Offset  Size  Description
 * 0       4     sb_magicnum (0x58465342 = "XFSB")
 * 4       4     sb_blocksize
 * 8       8     sb_dblocks (total data blocks)
 * 16      8     sb_rblocks (realtime blocks)
 * 24      8     sb_rextents (realtime extents)
 * 32      16    sb_uuid
 * 48      8     sb_logstart
 * 56      8     sb_rootino (root inode)
 * 84      4     sb_agblocks (blocks per AG)
 * 88      4     sb_agcount (number of AGs)
 * 100     4     sb_sectsize (sector size)
 * 104     4     sb_inodesize
 * 108     12    sb_fname (volume label)
 * 120     1     sb_blocklog
 * 121     1     sb_sectlog
 * 122     1     sb_inodelog
 * 123     1     sb_inopblog
 * 124     1     sb_agblklog
 * </pre>
 */
public record XfsSuperblock(
        int blockSize,
        long totalBlocks,
        long realtimeBlocks,
        @NotNull String uuid,
        long rootInode,
        int blocksPerAg,
        int agCount,
        int sectorSize,
        int inodeSize,
        @Nullable String volumeLabel,
        int versionNum,
        long inodeCount,
        long freeBlockCount
) {

    /** XFS magic number "XFSB" */
    public static final int MAGIC = 0x58465342;

    /** Superblock size to read */
    public static final int SUPERBLOCK_SIZE = 512;

    // Version flags
    public static final int VERSION_ATTRBIT = 0x0010;
    public static final int VERSION_NLINKBIT = 0x0020;
    public static final int VERSION_QUOTABIT = 0x0040;
    public static final int VERSION_ALIGNBIT = 0x0080;
    public static final int VERSION_DALIGNBIT = 0x0100;
    public static final int VERSION_LOGV2BIT = 0x0400;
    public static final int VERSION_SECTORBIT = 0x0800;
    public static final int VERSION_MOREBITSBIT = 0x8000;

    /**
     * Reads the XFS superblock from the specified offset.
     *
     * @param disk the virtual disk to read from
     * @param partitionOffset the byte offset where the partition starts
     * @return the parsed superblock
     * @throws IOException if an I/O error occurs or magic is invalid
     */
    public static @NotNull XfsSuperblock read(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        ByteBuffer sb = disk.read(partitionOffset, SUPERBLOCK_SIZE);
        sb.order(ByteOrder.BIG_ENDIAN); // XFS is big-endian

        // Check magic
        int magic = sb.getInt(0);
        if (magic != MAGIC) {
            throw new IOException("Invalid XFS magic: " + String.format("0x%08X", magic));
        }

        // Parse fields
        int blockSize = sb.getInt(4);
        long totalBlocks = sb.getLong(8);
        long realtimeBlocks = sb.getLong(16);

        // UUID at offset 32
        byte[] uuidBytes = new byte[16];
        sb.position(32);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        // Root inode
        long rootInode = sb.getLong(56);

        // AG info
        int blocksPerAg = sb.getInt(84);
        int agCount = sb.getInt(88);

        // Version and sizes (note: sb_versionnum is at 100, sectsize at 102, inodesize at 104)
        // But these are 2-byte fields
        int sectorSize = sb.getShort(102) & 0xFFFF;
        int inodeSize = sb.getShort(104) & 0xFFFF;

        // Volume label at offset 108
        byte[] labelBytes = new byte[12];
        sb.position(108);
        sb.get(labelBytes);
        String volumeLabel = parseNullTerminated(labelBytes);

        // Version number at offset 52 (sb_versionnum)
        sb.position(52);
        int versionNum = sb.getShort() & 0xFFFF;

        // Inode count at offset 128 (sb_icount) for v5
        long inodeCount = 0;
        long freeBlockCount = 0;
        if (sb.capacity() > 140) {
            sb.position(128);
            inodeCount = sb.getLong();
            // Free blocks - need to check exact offset based on version
        }

        return new XfsSuperblock(
                blockSize,
                totalBlocks,
                realtimeBlocks,
                uuid,
                rootInode,
                blocksPerAg,
                agCount,
                sectorSize,
                inodeSize,
                volumeLabel,
                versionNum,
                inodeCount,
                freeBlockCount
        );
    }

    /**
     * Reads the XFS superblock from a DiskRegion.
     *
     * @param region the disk region containing the XFS filesystem
     * @return the parsed superblock
     * @throws IOException if an I/O error occurs or magic is invalid
     */
    public static @NotNull XfsSuperblock read(@NotNull DiskRegion region)
            throws IOException {
        ByteBuffer sb = region.read(0, SUPERBLOCK_SIZE);
        sb.order(ByteOrder.BIG_ENDIAN);

        int magic = sb.getInt(0);
        if (magic != MAGIC) {
            throw new IOException("Invalid XFS magic: " + String.format("0x%08X", magic));
        }

        int blockSize = sb.getInt(4);
        long totalBlocks = sb.getLong(8);
        long realtimeBlocks = sb.getLong(16);

        byte[] uuidBytes = new byte[16];
        sb.position(32);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        long rootInode = sb.getLong(56);
        int blocksPerAg = sb.getInt(84);
        int agCount = sb.getInt(88);
        int sectorSize = sb.getShort(102) & 0xFFFF;
        int inodeSize = sb.getShort(104) & 0xFFFF;

        byte[] labelBytes = new byte[12];
        sb.position(108);
        sb.get(labelBytes);
        String volumeLabel = parseNullTerminated(labelBytes);

        int versionNum = sb.getShort(100) & 0xFFFF;

        long inodeCount = 0;
        long freeBlockCount = 0;
        if (sb.capacity() > 140) {
            sb.position(128);
            inodeCount = sb.getLong();
        }

        return new XfsSuperblock(
                blockSize,
                totalBlocks,
                realtimeBlocks,
                uuid,
                rootInode,
                blocksPerAg,
                agCount,
                sectorSize,
                inodeSize,
                volumeLabel,
                versionNum,
                inodeCount,
                freeBlockCount
        );
    }

    /**
     * Returns the total size in bytes.
     */
    public long totalSizeBytes() {
        return totalBlocks * blockSize;
    }

    /**
     * Returns the XFS version string.
     */
    public @NotNull String version() {
        int majorVersion = versionNum & 0x000F;
        return "v" + majorVersion;
    }

    /**
     * Returns the set of version feature flags.
     */
    public @NotNull Set<String> features() {
        Set<String> features = new HashSet<>();
        if ((versionNum & VERSION_ATTRBIT) != 0) features.add("attr");
        if ((versionNum & VERSION_NLINKBIT) != 0) features.add("nlink");
        if ((versionNum & VERSION_QUOTABIT) != 0) features.add("quota");
        if ((versionNum & VERSION_ALIGNBIT) != 0) features.add("align");
        if ((versionNum & VERSION_DALIGNBIT) != 0) features.add("dalign");
        if ((versionNum & VERSION_LOGV2BIT) != 0) features.add("logv2");
        if ((versionNum & VERSION_SECTORBIT) != 0) features.add("sector");
        if ((versionNum & VERSION_MOREBITSBIT) != 0) features.add("morebits");
        return features;
    }

    /**
     * Returns whether this is a v5 (CRC-enabled) filesystem.
     */
    public boolean isV5() {
        return (versionNum & 0x000F) >= 5;
    }

    /**
     * Returns the allocation group size in bytes.
     */
    public long agSizeBytes() {
        return (long) blocksPerAg * blockSize;
    }

    private static String formatUuid(byte[] bytes) {
        return String.format("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF,
                bytes[4] & 0xFF, bytes[5] & 0xFF,
                bytes[6] & 0xFF, bytes[7] & 0xFF,
                bytes[8] & 0xFF, bytes[9] & 0xFF,
                bytes[10] & 0xFF, bytes[11] & 0xFF, bytes[12] & 0xFF, bytes[13] & 0xFF,
                bytes[14] & 0xFF, bytes[15] & 0xFF);
    }

    private static String parseNullTerminated(byte[] bytes) {
        int length = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) break;
            length++;
        }
        if (length == 0) return null;
        return new String(bytes, 0, length).trim();
    }
}
