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

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Represents the ext2/ext3/ext4 superblock.
 *
 * <p>The superblock is located at byte offset 1024 from the start of the
 * filesystem and contains critical filesystem metadata.
 *
 * <p>Superblock structure (selected fields):
 * <pre>
 * Offset  Size  Description
 * 0       4     s_inodes_count
 * 4       4     s_blocks_count_lo
 * 12      4     s_free_blocks_count_lo
 * 16      4     s_free_inodes_count
 * 24      4     s_log_block_size (block size = 1024 << value)
 * 56      2     s_magic (0xEF53)
 * 92      4     s_feature_compat
 * 96      4     s_feature_incompat
 * 100     4     s_feature_ro_compat
 * 104     16    s_uuid
 * 120     16    s_volume_name
 * 136     64    s_last_mounted
 * 200     4     s_mkfs_time
 * 204     4     s_wtime (last write time)
 * </pre>
 */
public record Ext4Superblock(
        long inodeCount,
        long blockCount,
        long freeBlockCount,
        long freeInodeCount,
        int blockSize,
        int compatFeatures,
        int incompatFeatures,
        int roCompatFeatures,
        @NotNull String uuid,
        @Nullable String volumeName,
        @Nullable String lastMounted,
        @Nullable Instant mkfsTime,
        @Nullable Instant lastWriteTime
) {

    /** ext2/3/4 magic number */
    public static final short MAGIC = (short) 0xEF53;

    /** Superblock offset from filesystem start */
    public static final int SUPERBLOCK_OFFSET = 1024;

    /** Minimum superblock size to read */
    public static final int SUPERBLOCK_SIZE = 1024;

    // Compatible features
    public static final int COMPAT_DIR_PREALLOC = 0x0001;
    public static final int COMPAT_HAS_JOURNAL = 0x0004;
    public static final int COMPAT_EXT_ATTR = 0x0008;
    public static final int COMPAT_RESIZE_INODE = 0x0010;
    public static final int COMPAT_DIR_INDEX = 0x0020;
    public static final int COMPAT_SPARSE_SUPER2 = 0x0200;

    // Incompatible features
    public static final int INCOMPAT_COMPRESSION = 0x0001;
    public static final int INCOMPAT_FILETYPE = 0x0002;
    public static final int INCOMPAT_RECOVER = 0x0004;
    public static final int INCOMPAT_JOURNAL_DEV = 0x0008;
    public static final int INCOMPAT_META_BG = 0x0010;
    public static final int INCOMPAT_EXTENTS = 0x0040;
    public static final int INCOMPAT_64BIT = 0x0080;
    public static final int INCOMPAT_MMP = 0x0100;
    public static final int INCOMPAT_FLEX_BG = 0x0200;
    public static final int INCOMPAT_INLINE_DATA = 0x8000;

    // Read-only compatible features
    public static final int RO_COMPAT_SPARSE_SUPER = 0x0001;
    public static final int RO_COMPAT_LARGE_FILE = 0x0002;
    public static final int RO_COMPAT_HUGE_FILE = 0x0008;
    public static final int RO_COMPAT_METADATA_CSUM = 0x0400;

    /**
     * Reads the superblock from the specified offset.
     *
     * @param disk the virtual disk to read from
     * @param partitionOffset the byte offset where the partition starts
     * @return the parsed superblock
     * @throws IOException if an I/O error occurs or magic is invalid
     */
    public static @NotNull Ext4Superblock read(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return read(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Reads the superblock from a DiskRegion.
     *
     * @param region the disk region containing the filesystem
     * @return the parsed superblock
     * @throws IOException if an I/O error occurs or magic is invalid
     */
    public static @NotNull Ext4Superblock read(@NotNull DiskRegion region) throws IOException {
        ByteBuffer sb = region.read(SUPERBLOCK_OFFSET, SUPERBLOCK_SIZE);
        sb.order(ByteOrder.LITTLE_ENDIAN);

        // Check magic
        short magic = sb.getShort(56);
        if (magic != MAGIC) {
            throw new IOException("Invalid ext superblock magic: " +
                    String.format("0x%04X", magic & 0xFFFF));
        }

        // Parse fields
        long inodeCount = sb.getInt(0) & 0xFFFFFFFFL;
        long blockCountLo = sb.getInt(4) & 0xFFFFFFFFL;
        long freeBlockCountLo = sb.getInt(12) & 0xFFFFFFFFL;
        long freeInodeCount = sb.getInt(16) & 0xFFFFFFFFL;
        int blockSizeShift = sb.getInt(24);
        int blockSize = 1024 << blockSizeShift;

        int compatFeatures = sb.getInt(92);
        int incompatFeatures = sb.getInt(96);
        int roCompatFeatures = sb.getInt(100);

        // UUID
        byte[] uuidBytes = new byte[16];
        sb.position(104);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        // Volume name
        byte[] nameBytes = new byte[16];
        sb.position(120);
        sb.get(nameBytes);
        String volumeName = parseNullTerminated(nameBytes);

        // Last mounted path
        byte[] mountedBytes = new byte[64];
        sb.position(136);
        sb.get(mountedBytes);
        String lastMounted = parseNullTerminated(mountedBytes);

        // Timestamps
        int mkfsTimestamp = sb.getInt(200);
        int writeTimestamp = sb.getInt(204);
        Instant mkfsTime = mkfsTimestamp > 0 ? Instant.ofEpochSecond(mkfsTimestamp) : null;
        Instant lastWriteTime = writeTimestamp > 0 ? Instant.ofEpochSecond(writeTimestamp) : null;

        // Handle 64-bit block count
        long blockCount = blockCountLo;
        long freeBlockCount = freeBlockCountLo;
        if ((incompatFeatures & INCOMPAT_64BIT) != 0 && sb.capacity() > 340) {
            long blockCountHi = sb.getInt(336) & 0xFFFFFFFFL;
            long freeBlockCountHi = sb.getInt(340) & 0xFFFFFFFFL;
            blockCount |= (blockCountHi << 32);
            freeBlockCount |= (freeBlockCountHi << 32);
        }

        return new Ext4Superblock(
                inodeCount,
                blockCount,
                freeBlockCount,
                freeInodeCount,
                blockSize,
                compatFeatures,
                incompatFeatures,
                roCompatFeatures,
                uuid,
                volumeName,
                lastMounted,
                mkfsTime,
                lastWriteTime
        );
    }

    /**
     * Returns the ext version (ext2, ext3, or ext4).
     */
    public @NotNull String extVersion() {
        if ((incompatFeatures & INCOMPAT_EXTENTS) != 0) {
            return "ext4";
        } else if ((compatFeatures & COMPAT_HAS_JOURNAL) != 0) {
            return "ext3";
        }
        return "ext2";
    }

    /**
     * Returns the total size in bytes.
     */
    public long totalSizeBytes() {
        return blockCount * blockSize;
    }

    /**
     * Returns the free size in bytes.
     */
    public long freeSizeBytes() {
        return freeBlockCount * blockSize;
    }

    /**
     * Returns the used size in bytes.
     */
    public long usedSizeBytes() {
        return totalSizeBytes() - freeSizeBytes();
    }

    /**
     * Returns whether this filesystem has a journal.
     */
    public boolean hasJournal() {
        return (compatFeatures & COMPAT_HAS_JOURNAL) != 0;
    }

    /**
     * Returns whether this filesystem uses extents.
     */
    public boolean hasExtents() {
        return (incompatFeatures & INCOMPAT_EXTENTS) != 0;
    }

    /**
     * Returns whether this is a 64-bit filesystem.
     */
    public boolean is64Bit() {
        return (incompatFeatures & INCOMPAT_64BIT) != 0;
    }

    /**
     * Returns the set of compatible feature names.
     */
    public @NotNull Set<String> compatFeatureNames() {
        Set<String> features = EnumSet.noneOf(FeatureName.class).isEmpty() ?
                new java.util.HashSet<>() : new java.util.HashSet<>();
        if ((compatFeatures & COMPAT_DIR_PREALLOC) != 0) features.add("dir_prealloc");
        if ((compatFeatures & COMPAT_HAS_JOURNAL) != 0) features.add("has_journal");
        if ((compatFeatures & COMPAT_EXT_ATTR) != 0) features.add("ext_attr");
        if ((compatFeatures & COMPAT_RESIZE_INODE) != 0) features.add("resize_inode");
        if ((compatFeatures & COMPAT_DIR_INDEX) != 0) features.add("dir_index");
        if ((compatFeatures & COMPAT_SPARSE_SUPER2) != 0) features.add("sparse_super2");
        return features;
    }

    /**
     * Returns the set of incompatible feature names.
     */
    public @NotNull Set<String> incompatFeatureNames() {
        Set<String> features = new java.util.HashSet<>();
        if ((incompatFeatures & INCOMPAT_COMPRESSION) != 0) features.add("compression");
        if ((incompatFeatures & INCOMPAT_FILETYPE) != 0) features.add("filetype");
        if ((incompatFeatures & INCOMPAT_RECOVER) != 0) features.add("needs_recovery");
        if ((incompatFeatures & INCOMPAT_JOURNAL_DEV) != 0) features.add("journal_dev");
        if ((incompatFeatures & INCOMPAT_META_BG) != 0) features.add("meta_bg");
        if ((incompatFeatures & INCOMPAT_EXTENTS) != 0) features.add("extents");
        if ((incompatFeatures & INCOMPAT_64BIT) != 0) features.add("64bit");
        if ((incompatFeatures & INCOMPAT_MMP) != 0) features.add("mmp");
        if ((incompatFeatures & INCOMPAT_FLEX_BG) != 0) features.add("flex_bg");
        if ((incompatFeatures & INCOMPAT_INLINE_DATA) != 0) features.add("inline_data");
        return features;
    }

    private enum FeatureName { } // Helper for type inference

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
