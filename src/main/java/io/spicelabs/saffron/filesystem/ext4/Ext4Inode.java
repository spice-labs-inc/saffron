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
import java.time.Instant;
import java.util.Optional;

/**
 * Represents an ext4 inode structure.
 *
 * <p>Inode structure (128 bytes minimum, 256 bytes for ext4):
 * <pre>
 * Offset  Size  Description
 * 0       2     i_mode (file type and permissions)
 * 2       2     i_uid (owner UID low)
 * 4       4     i_size_lo (size in bytes, lower 32 bits)
 * 8       4     i_atime (access time)
 * 12      4     i_ctime (change time)
 * 16      4     i_mtime (modification time)
 * 20      4     i_dtime (deletion time)
 * 24      2     i_gid (group ID low)
 * 26      2     i_links_count (hard links count)
 * 28      4     i_blocks_lo (blocks count, lower 32 bits)
 * 32      4     i_flags
 * 40      60    i_block (12 direct + 1 indirect + 1 double + 1 triple OR extent tree)
 * 100     4     i_generation
 * 104     4     i_file_acl_lo (extended attributes block, lower 32 bits)
 * 108     4     i_size_hi (size in bytes, upper 32 bits for regular files)
 * 116     2     i_uid_hi (owner UID high)
 * 118     2     i_gid_hi (group ID high)
 * 128     2     i_extra_isize (for ext4)
 * 132     4     i_ctime_extra (nanoseconds + epoch)
 * 136     4     i_mtime_extra
 * 140     4     i_atime_extra
 * 144     4     i_crtime (creation time)
 * 148     4     i_crtime_extra
 * </pre>
 */
public record Ext4Inode(
        int mode,
        int uid,
        int gid,
        long size,
        Instant accessTime,
        Instant changeTime,
        Instant modificationTime,
        Instant creationTime,
        int linksCount,
        long blocksCount,
        int flags,
        byte[] blockData,
        long inodeNumber
) {
    // File type masks (from i_mode)
    public static final int S_IFMT = 0xF000;   // File type mask
    public static final int S_IFSOCK = 0xC000; // Socket
    public static final int S_IFLNK = 0xA000;  // Symbolic link
    public static final int S_IFREG = 0x8000;  // Regular file
    public static final int S_IFBLK = 0x6000;  // Block device
    public static final int S_IFDIR = 0x4000;  // Directory
    public static final int S_IFCHR = 0x2000;  // Character device
    public static final int S_IFIFO = 0x1000;  // FIFO

    // Permission masks
    public static final int S_ISUID = 0x0800;  // Set-UID
    public static final int S_ISGID = 0x0400;  // Set-GID
    public static final int S_ISVTX = 0x0200;  // Sticky bit

    // Inode flags
    public static final int EXT4_EXTENTS_FL = 0x00080000;
    public static final int EXT4_INLINE_DATA_FL = 0x10000000;

    /**
     * Parses an inode from a ByteBuffer.
     *
     * @param buffer the buffer containing inode data
     * @param inodeSize the size of each inode in bytes
     * @param inodeNumber the inode number
     * @return the parsed inode
     */
    public static @NotNull Ext4Inode parse(@NotNull ByteBuffer buffer, int inodeSize, long inodeNumber) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int startPos = buffer.position();

        int mode = buffer.getShort(startPos + 0) & 0xFFFF;
        int uidLo = buffer.getShort(startPos + 2) & 0xFFFF;
        long sizeLo = buffer.getInt(startPos + 4) & 0xFFFFFFFFL;
        int atime = buffer.getInt(startPos + 8);
        int ctime = buffer.getInt(startPos + 12);
        int mtime = buffer.getInt(startPos + 16);
        int gidLo = buffer.getShort(startPos + 24) & 0xFFFF;
        int linksCount = buffer.getShort(startPos + 26) & 0xFFFF;
        long blocksLo = buffer.getInt(startPos + 28) & 0xFFFFFFFFL;
        int flags = buffer.getInt(startPos + 32);

        // Block data (60 bytes at offset 40)
        byte[] blockData = new byte[60];
        buffer.position(startPos + 40);
        buffer.get(blockData);

        // High 32 bits of size (for regular files only)
        long sizeHi = buffer.getInt(startPos + 108) & 0xFFFFFFFFL;
        long size = sizeLo | (sizeHi << 32);

        // High bits of UID/GID (if inode size >= 128)
        int uidHi = 0;
        int gidHi = 0;
        if (inodeSize >= 128) {
            uidHi = buffer.getShort(startPos + 116) & 0xFFFF;
            gidHi = buffer.getShort(startPos + 118) & 0xFFFF;
        }

        int uid = uidLo | (uidHi << 16);
        int gid = gidLo | (gidHi << 16);

        // Parse timestamps
        Instant accessTime = Instant.ofEpochSecond(atime & 0xFFFFFFFFL);
        Instant changeTime = Instant.ofEpochSecond(ctime & 0xFFFFFFFFL);
        Instant modificationTime = Instant.ofEpochSecond(mtime & 0xFFFFFFFFL);
        Instant creationTime = null;

        // Extended timestamps (ext4, if inode size > 128)
        if (inodeSize > 128) {
            int extraIsize = buffer.getShort(startPos + 128) & 0xFFFF;
            if (extraIsize >= 28) {
                // Creation time at offset 144
                int crtime = buffer.getInt(startPos + 144);
                if (crtime > 0) {
                    creationTime = Instant.ofEpochSecond(crtime & 0xFFFFFFFFL);
                }
            }
        }

        return new Ext4Inode(mode, uid, gid, size, accessTime, changeTime,
                modificationTime, creationTime, linksCount, blocksLo, flags, blockData, inodeNumber);
    }

    /**
     * Returns the file type from the mode.
     */
    public int fileType() {
        return mode & S_IFMT;
    }

    /**
     * Returns whether this is a regular file.
     */
    public boolean isRegularFile() {
        return fileType() == S_IFREG;
    }

    /**
     * Returns whether this is a directory.
     */
    public boolean isDirectory() {
        return fileType() == S_IFDIR;
    }

    /**
     * Returns whether this is a symbolic link.
     */
    public boolean isSymbolicLink() {
        return fileType() == S_IFLNK;
    }

    /**
     * Returns whether this is a block device.
     */
    public boolean isBlockDevice() {
        return fileType() == S_IFBLK;
    }

    /**
     * Returns whether this is a character device.
     */
    public boolean isCharacterDevice() {
        return fileType() == S_IFCHR;
    }

    /**
     * Returns whether this is a FIFO.
     */
    public boolean isFifo() {
        return fileType() == S_IFIFO;
    }

    /**
     * Returns whether this is a socket.
     */
    public boolean isSocket() {
        return fileType() == S_IFSOCK;
    }

    /**
     * Returns whether this inode uses extents.
     */
    public boolean usesExtents() {
        return (flags & EXT4_EXTENTS_FL) != 0;
    }

    /**
     * Returns whether this inode has inline data.
     */
    public boolean hasInlineData() {
        return (flags & EXT4_INLINE_DATA_FL) != 0;
    }

    /**
     * Returns the permission bits (lower 12 bits of mode).
     */
    public int permissions() {
        return mode & 0xFFF;
    }

    /**
     * Returns the optional creation time.
     */
    public Optional<Instant> optionalCreationTime() {
        return Optional.ofNullable(creationTime);
    }

    /**
     * For symbolic links with inline data, returns the target path.
     */
    public Optional<String> inlineSymlinkTarget() {
        if (!isSymbolicLink() || size > 60) {
            return Optional.empty();
        }
        // Symlink target is stored inline in i_block for short links
        int len = (int) Math.min(size, 60);
        return Optional.of(new String(blockData, 0, len));
    }
}
