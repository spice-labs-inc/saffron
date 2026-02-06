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
import java.time.Instant;
import java.util.Optional;

/**
 * Represents an XFS inode (dinode).
 *
 * <p>XFS inode structure (v3 format, big-endian):
 * <pre>
 * Offset  Size  Description
 * 0       2     di_magic (0x494E = "IN")
 * 2       2     di_mode (file type and permissions)
 * 4       1     di_version (1, 2, or 3)
 * 5       1     di_format (data fork format)
 * 6       2     di_onlink (v1) or padding
 * 8       4     di_uid
 * 12      4     di_gid
 * 16      4     di_nlink
 * 20      2     di_projid_lo
 * 22      2     di_projid_hi
 * 24      8     padding (v3) or di_pad
 * 32      2     di_flushiter (v1/v2)
 * 34      4     di_atime (seconds)
 * 38      4     di_atime (nanoseconds)
 * 42      4     di_mtime (seconds)
 * 46      4     di_mtime (nanoseconds)
 * 50      4     di_ctime (seconds)
 * 54      4     di_ctime (nanoseconds)
 * 58      8     di_size (file size)
 * 66      8     di_nblocks (block count)
 * 74      4     di_extsize (extent size hint)
 * 78      4     di_nextents (data fork extent count)
 * 82      2     di_anextents (attr fork extent count)
 * 84      1     di_forkoff (attr fork offset)
 * 85      1     di_aformat (attr fork format)
 * 86      4     di_dmevmask
 * 90      2     di_dmstate
 * 92      2     di_flags
 * 94      4     di_gen (generation)
 *
 * For v3 inodes (additional fields at offset 98):
 * 98      4     di_changecount
 * 102     8     di_lsn
 * 110     8     di_flags2
 * 118     4     di_cowextsize
 * 122     12    padding
 * 134     4     di_crtime (seconds)
 * 138     4     di_crtime (nanoseconds)
 * 142     8     di_ino
 * 150     16    di_uuid
 *
 * Data fork starts at offset 100 (v1/v2) or 176 (v3)
 * </pre>
 */
public record XfsInode(
        int magic,
        int mode,
        int version,
        int format,
        int uid,
        int gid,
        int nlink,
        @NotNull Instant accessTime,
        @NotNull Instant modificationTime,
        @NotNull Instant changeTime,
        long size,
        long blockCount,
        int extentCount,
        int attrExtentCount,
        int forkOffset,
        int attrFormat,
        int flags,
        int generation,
        @NotNull Optional<Instant> creationTime,
        long inodeNumber,
        byte[] dataFork,
        int dataForkOffset
) {

    /** Inode magic number "IN" */
    public static final int MAGIC = 0x494E;

    // Data fork format types
    public static final int FMT_DEV = 0;      // Device (special file)
    public static final int FMT_LOCAL = 1;    // Local (inline data)
    public static final int FMT_EXTENTS = 2;  // Extent list
    public static final int FMT_BTREE = 3;    // B+tree root
    public static final int FMT_UUID = 4;     // UUID (not used for data fork)

    // File type bits in mode
    public static final int S_IFMT = 0170000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFBLK = 0060000;
    public static final int S_IFCHR = 0020000;
    public static final int S_IFIFO = 0010000;
    public static final int S_IFSOCK = 0140000;

    /**
     * Parses an XFS inode from buffer.
     *
     * @param buffer the buffer containing the inode data
     * @param inodeSize the inode size in bytes
     * @param inodeNumber the inode number
     * @return the parsed inode
     */
    public static @NotNull XfsInode parse(@NotNull ByteBuffer buffer, int inodeSize, long inodeNumber) {
        buffer.order(ByteOrder.BIG_ENDIAN);

        int magic = buffer.getShort(0) & 0xFFFF;
        int mode = buffer.getShort(2) & 0xFFFF;
        int version = buffer.get(4) & 0xFF;
        int format = buffer.get(5) & 0xFF;
        int uid = buffer.getInt(8);
        int gid = buffer.getInt(12);
        int nlink = buffer.getInt(16);

        // Timestamps
        long atimeSec = buffer.getInt(34) & 0xFFFFFFFFL;
        int atimeNsec = buffer.getInt(38);
        Instant accessTime = Instant.ofEpochSecond(atimeSec, atimeNsec);

        long mtimeSec = buffer.getInt(42) & 0xFFFFFFFFL;
        int mtimeNsec = buffer.getInt(46);
        Instant modificationTime = Instant.ofEpochSecond(mtimeSec, mtimeNsec);

        long ctimeSec = buffer.getInt(50) & 0xFFFFFFFFL;
        int ctimeNsec = buffer.getInt(54);
        Instant changeTime = Instant.ofEpochSecond(ctimeSec, ctimeNsec);

        long size = buffer.getLong(58);
        long blockCount = buffer.getLong(66);
        int extentCount = buffer.getInt(78);
        int attrExtentCount = buffer.getShort(82) & 0xFFFF;
        int forkOffset = buffer.get(84) & 0xFF;
        int attrFormat = buffer.get(85) & 0xFF;
        int flags = buffer.getShort(92) & 0xFFFF;
        int generation = buffer.getInt(94);

        // V3 inodes have creation time
        Optional<Instant> creationTime = Optional.empty();
        int dataForkOffset;
        if (version >= 3) {
            long crtimeSec = buffer.getInt(134) & 0xFFFFFFFFL;
            int crtimeNsec = buffer.getInt(138);
            creationTime = Optional.of(Instant.ofEpochSecond(crtimeSec, crtimeNsec));
            dataForkOffset = 176;
        } else {
            dataForkOffset = 100;
        }

        // Calculate data fork size
        int dataForkSize;
        if (forkOffset > 0) {
            // forkOffset is in 8-byte units from the end of the inode core
            dataForkSize = forkOffset * 8;
        } else {
            // No attr fork, data fork extends to end of inode
            dataForkSize = inodeSize - dataForkOffset;
        }

        // Read data fork
        byte[] dataFork = new byte[dataForkSize];
        buffer.position(dataForkOffset);
        buffer.get(dataFork, 0, Math.min(dataForkSize, buffer.remaining()));

        return new XfsInode(
                magic, mode, version, format, uid, gid, nlink,
                accessTime, modificationTime, changeTime,
                size, blockCount, extentCount, attrExtentCount,
                forkOffset, attrFormat, flags, generation,
                creationTime, inodeNumber, dataFork, dataForkOffset
        );
    }

    public boolean isValid() {
        return magic == MAGIC;
    }

    public boolean isDirectory() {
        return (mode & S_IFMT) == S_IFDIR;
    }

    public boolean isRegularFile() {
        return (mode & S_IFMT) == S_IFREG;
    }

    public boolean isSymbolicLink() {
        return (mode & S_IFMT) == S_IFLNK;
    }

    public boolean isBlockDevice() {
        return (mode & S_IFMT) == S_IFBLK;
    }

    public boolean isCharacterDevice() {
        return (mode & S_IFMT) == S_IFCHR;
    }

    public boolean isFifo() {
        return (mode & S_IFMT) == S_IFIFO;
    }

    public boolean isSocket() {
        return (mode & S_IFMT) == S_IFSOCK;
    }

    public boolean hasInlineData() {
        return format == FMT_LOCAL;
    }

    public boolean hasExtents() {
        return format == FMT_EXTENTS;
    }

    public boolean hasBtree() {
        return format == FMT_BTREE;
    }

    /**
     * For symbolic links with inline data, returns the target path.
     */
    public @NotNull Optional<String> inlineSymlinkTarget() {
        if (isSymbolicLink() && hasInlineData() && size <= dataFork.length) {
            return Optional.of(new String(dataFork, 0, (int) size));
        }
        return Optional.empty();
    }
}
