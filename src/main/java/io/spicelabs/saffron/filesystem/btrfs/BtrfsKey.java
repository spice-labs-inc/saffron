/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.btrfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a Btrfs key, which uniquely identifies items in B-trees.
 *
 * <p>Keys are 17 bytes: objectId (8) + type (1) + offset (8).
 * Keys are compared lexicographically: objectId first, then type, then offset.
 */
public record BtrfsKey(long objectId, int type, long offset) implements Comparable<BtrfsKey> {

    /** Size of a key in bytes. */
    public static final int SIZE = 17;

    // Item types
    public static final int INODE_ITEM = 1;
    public static final int INODE_REF = 12;
    public static final int INODE_EXTREF = 13;
    public static final int XATTR_ITEM = 24;
    public static final int ORPHAN_ITEM = 48;
    public static final int DIR_LOG_ITEM = 60;
    public static final int DIR_LOG_INDEX = 72;
    public static final int DIR_ITEM = 84;
    public static final int DIR_INDEX = 96;
    public static final int EXTENT_DATA = 108;
    public static final int EXTENT_CSUM = 128;
    public static final int ROOT_ITEM = 132;
    public static final int ROOT_BACKREF = 144;
    public static final int ROOT_REF = 156;
    public static final int EXTENT_ITEM = 168;
    public static final int METADATA_ITEM = 169;
    public static final int TREE_BLOCK_REF = 176;
    public static final int EXTENT_DATA_REF = 178;
    public static final int SHARED_BLOCK_REF = 182;
    public static final int SHARED_DATA_REF = 184;
    public static final int BLOCK_GROUP_ITEM = 192;
    public static final int FREE_SPACE_INFO = 198;
    public static final int FREE_SPACE_EXTENT = 199;
    public static final int FREE_SPACE_BITMAP = 200;
    public static final int DEV_EXTENT = 204;
    public static final int DEV_ITEM = 216;
    public static final int CHUNK_ITEM = 228;
    public static final int QGROUP_STATUS = 240;
    public static final int QGROUP_INFO = 242;
    public static final int QGROUP_LIMIT = 244;
    public static final int QGROUP_RELATION = 246;

    // Well-known object IDs
    public static final long ROOT_TREE_OBJECTID = 1;
    public static final long EXTENT_TREE_OBJECTID = 2;
    public static final long CHUNK_TREE_OBJECTID = 3;
    public static final long DEV_TREE_OBJECTID = 4;
    public static final long FS_TREE_OBJECTID = 5;
    public static final long ROOT_TREE_DIR_OBJECTID = 6;
    public static final long CSUM_TREE_OBJECTID = 7;
    public static final long QUOTA_TREE_OBJECTID = 8;
    public static final long UUID_TREE_OBJECTID = 9;
    public static final long FREE_SPACE_TREE_OBJECTID = 10;
    public static final long FIRST_FREE_OBJECTID = 256;
    public static final long FIRST_CHUNK_TREE_OBJECTID = 256;

    /**
     * Reads a key from a ByteBuffer at the current position.
     */
    public static BtrfsKey read(ByteBuffer buf) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        long objectId = buf.getLong();
        int type = buf.get() & 0xFF;
        long offset = buf.getLong();
        return new BtrfsKey(objectId, type, offset);
    }

    /**
     * Returns a human-readable name for the item type.
     */
    public String typeName() {
        return switch (type) {
            case INODE_ITEM -> "INODE_ITEM";
            case INODE_REF -> "INODE_REF";
            case INODE_EXTREF -> "INODE_EXTREF";
            case XATTR_ITEM -> "XATTR_ITEM";
            case DIR_ITEM -> "DIR_ITEM";
            case DIR_INDEX -> "DIR_INDEX";
            case EXTENT_DATA -> "EXTENT_DATA";
            case ROOT_ITEM -> "ROOT_ITEM";
            case ROOT_REF -> "ROOT_REF";
            case ROOT_BACKREF -> "ROOT_BACKREF";
            case CHUNK_ITEM -> "CHUNK_ITEM";
            case DEV_ITEM -> "DEV_ITEM";
            case DEV_EXTENT -> "DEV_EXTENT";
            case EXTENT_ITEM -> "EXTENT_ITEM";
            case BLOCK_GROUP_ITEM -> "BLOCK_GROUP_ITEM";
            default -> "TYPE_" + type;
        };
    }

    @Override
    public int compareTo(BtrfsKey other) {
        int cmp = Long.compareUnsigned(this.objectId, other.objectId);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.type, other.type);
        if (cmp != 0) return cmp;
        return Long.compareUnsigned(this.offset, other.offset);
    }

    @Override
    public String toString() {
        return String.format("BtrfsKey(%d, %s, %d)", objectId, typeName(), offset);
    }
}
