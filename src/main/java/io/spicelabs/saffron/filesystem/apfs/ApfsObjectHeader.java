/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.apfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents an APFS object header (obj_phys_t).
 *
 * <p>Every APFS on-disk object starts with this 32-byte header.
 * APFS is little-endian.
 *
 * <p>Layout:
 * <pre>
 * Offset  Size  Description
 * 0       8     o_cksum (Fletcher-64 checksum)
 * 8       8     o_oid (object identifier)
 * 16      8     o_xid (transaction identifier)
 * 24      4     o_type (object type + storage type flags)
 * 28      4     o_subtype
 * </pre>
 */
public record ApfsObjectHeader(
        long checksum,
        long oid,
        long xid,
        int type,
        int subtype
) {
    public static final int SIZE = 32;

    // Object types (lower 16 bits of o_type)
    public static final int OBJECT_TYPE_NX_SUPERBLOCK = 0x01;
    public static final int OBJECT_TYPE_BTREE = 0x02;
    public static final int OBJECT_TYPE_BTREE_NODE = 0x03;
    public static final int OBJECT_TYPE_SPACEMAN = 0x05;
    public static final int OBJECT_TYPE_OMAP = 0x08;
    public static final int OBJECT_TYPE_FS = 0x0D;

    // Storage type flags (bits 31-30 of o_type)
    public static final int OBJ_PHYSICAL = 0x00000000;
    public static final int OBJ_VIRTUAL = 0x40000000;
    public static final int OBJ_EPHEMERAL = 0x80000000;

    public static ApfsObjectHeader parse(ByteBuffer buf) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        long checksum = buf.getLong(0);
        long oid = buf.getLong(8);
        long xid = buf.getLong(16);
        int type = buf.getInt(24);
        int subtype = buf.getInt(28);
        return new ApfsObjectHeader(checksum, oid, xid, type, subtype);
    }

    public static ApfsObjectHeader parse(byte[] data) {
        return parse(ByteBuffer.wrap(data));
    }

    public int objectType() {
        return type & 0x0000FFFF;
    }

    public int storageType() {
        return type & 0xC0000000;
    }

    public boolean isPhysical() {
        return storageType() == OBJ_PHYSICAL;
    }

    public boolean isVirtual() {
        return storageType() == OBJ_VIRTUAL;
    }
}
