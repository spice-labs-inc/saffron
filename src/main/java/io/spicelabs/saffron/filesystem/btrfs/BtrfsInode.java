/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.btrfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;

/**
 * Represents a Btrfs INODE_ITEM.
 */
public record BtrfsInode(
        long generation,
        long transid,
        long size,
        long nbytes,
        long blockGroup,
        int nlink,
        int uid,
        int gid,
        int mode,
        long rdev,
        long flags,
        long sequence,
        Instant atime,
        Instant ctime,
        Instant mtime,
        Instant otime
) {
    /** INODE_ITEM size in bytes. */
    public static final int SIZE = 160;

    // Mode type masks (same as POSIX)
    public static final int S_IFMT = 0170000;
    public static final int S_IFSOCK = 0140000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFBLK = 0060000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFCHR = 0020000;
    public static final int S_IFIFO = 0010000;

    /**
     * Parses an INODE_ITEM from raw data.
     */
    public static BtrfsInode parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        long generation = buf.getLong();
        long transid = buf.getLong();
        long size = buf.getLong();
        long nbytes = buf.getLong();
        long blockGroup = buf.getLong();
        int nlink = buf.getInt();
        int uid = buf.getInt();
        int gid = buf.getInt();
        int mode = buf.getInt();
        long rdev = buf.getLong();
        long flags = buf.getLong();
        long sequence = buf.getLong();

        // Reserved (32 bytes)
        buf.position(buf.position() + 32);

        // Timestamps: sec (8) + nsec (4) = 12 bytes each
        Instant atime = readTimestamp(buf);
        Instant ctime = readTimestamp(buf);
        Instant mtime = readTimestamp(buf);
        Instant otime = readTimestamp(buf);

        return new BtrfsInode(generation, transid, size, nbytes, blockGroup,
                nlink, uid, gid, mode, rdev, flags, sequence,
                atime, ctime, mtime, otime);
    }

    private static Instant readTimestamp(ByteBuffer buf) {
        long sec = buf.getLong();
        int nsec = buf.getInt();
        return Instant.ofEpochSecond(sec, nsec);
    }

    /**
     * Returns true if this is a regular file.
     */
    public boolean isRegularFile() {
        return (mode & S_IFMT) == S_IFREG;
    }

    /**
     * Returns true if this is a directory.
     */
    public boolean isDirectory() {
        return (mode & S_IFMT) == S_IFDIR;
    }

    /**
     * Returns true if this is a symbolic link.
     */
    public boolean isSymlink() {
        return (mode & S_IFMT) == S_IFLNK;
    }

    /**
     * Returns true if this is a block device.
     */
    public boolean isBlockDevice() {
        return (mode & S_IFMT) == S_IFBLK;
    }

    /**
     * Returns true if this is a character device.
     */
    public boolean isCharDevice() {
        return (mode & S_IFMT) == S_IFCHR;
    }

    /**
     * Returns true if this is a FIFO.
     */
    public boolean isFifo() {
        return (mode & S_IFMT) == S_IFIFO;
    }

    /**
     * Returns true if this is a socket.
     */
    public boolean isSocket() {
        return (mode & S_IFMT) == S_IFSOCK;
    }

    /**
     * Returns the file type portion of mode.
     */
    public int fileType() {
        return (mode & S_IFMT) >> 12;
    }

    /**
     * Returns the permissions portion of mode.
     */
    public int permissions() {
        return mode & 0777;
    }
}
