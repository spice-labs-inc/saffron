/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Represents a Btrfs DIR_ITEM or DIR_INDEX entry.
 */
public record BtrfsDirectoryEntry(
        BtrfsKey location,
        long transid,
        int dataLen,
        int nameLen,
        int type,
        String name
) {
    // File type constants (matches inode mode >> 12)
    public static final int FT_UNKNOWN = 0;
    public static final int FT_REG_FILE = 1;
    public static final int FT_DIR = 2;
    public static final int FT_CHRDEV = 3;
    public static final int FT_BLKDEV = 4;
    public static final int FT_FIFO = 5;
    public static final int FT_SOCK = 6;
    public static final int FT_SYMLINK = 7;
    public static final int FT_XATTR = 8;

    /**
     * Parses a DIR_ITEM or DIR_INDEX from raw data.
     */
    public static BtrfsDirectoryEntry parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Location key (17 bytes)
        BtrfsKey location = BtrfsKey.read(buf);

        long transid = buf.getLong();
        int dataLen = buf.getShort() & 0xFFFF;
        int nameLen = buf.getShort() & 0xFFFF;
        int type = buf.get() & 0xFF;

        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);

        return new BtrfsDirectoryEntry(location, transid, dataLen, nameLen, type, name);
    }

    /**
     * Returns true if this entry points to a directory.
     */
    public boolean isDirectory() {
        return type == FT_DIR;
    }

    /**
     * Returns true if this entry points to a regular file.
     */
    public boolean isRegularFile() {
        return type == FT_REG_FILE;
    }

    /**
     * Returns true if this entry points to a symbolic link.
     */
    public boolean isSymlink() {
        return type == FT_SYMLINK;
    }

    /**
     * Returns the object ID this entry points to.
     */
    public long targetObjectId() {
        return location.objectId();
    }

    /**
     * Returns a human-readable type name.
     */
    public String typeName() {
        return switch (type) {
            case FT_UNKNOWN -> "unknown";
            case FT_REG_FILE -> "file";
            case FT_DIR -> "dir";
            case FT_CHRDEV -> "chrdev";
            case FT_BLKDEV -> "blkdev";
            case FT_FIFO -> "fifo";
            case FT_SOCK -> "socket";
            case FT_SYMLINK -> "symlink";
            case FT_XATTR -> "xattr";
            default -> "type_" + type;
        };
    }
}
