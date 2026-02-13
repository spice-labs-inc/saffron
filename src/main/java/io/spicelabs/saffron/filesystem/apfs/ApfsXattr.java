/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.apfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Represents an APFS extended attribute (xattr) record from the filesystem B-tree.
 *
 * <p>Xattr records have key type 0xC (XATTR_VAL). The key is:
 * <pre>
 * obj_id_and_type(8) + name_len(2) + name(variable, null-terminated)
 * </pre>
 * where the upper 4 bits of obj_id_and_type contain 0xC and the lower 60 bits
 * contain the inode OID.
 *
 * <p>The value is:
 * <pre>
 * flags(2) + xdata_len(2) + xdata(variable)
 * </pre>
 *
 * <p>Flags:
 * <ul>
 *   <li>XATTR_DATA_STREAM (0x0001): data is in a data stream (resource fork), xdata contains a dstream record</li>
 *   <li>XATTR_DATA_EMBEDDED (0x0002): data is embedded directly in the xattr value</li>
 * </ul>
 */
public record ApfsXattr(
        long inodeOid,
        String name,
        int flags,
        byte[] data
) {
    public static final long KEY_TYPE_XATTR = 0xC000000000000000L;
    public static final long KEY_TYPE_MASK = 0xF000000000000000L;

    /** Xattr data is stored in a data stream (resource fork) */
    public static final int XATTR_DATA_STREAM = 0x0001;
    /** Xattr data is embedded directly in the value */
    public static final int XATTR_DATA_EMBEDDED = 0x0002;

    /**
     * Parses an xattr record from a filesystem B-tree leaf entry.
     */
    public static ApfsXattr parse(byte[] key, byte[] val) {
        if (key.length < 10 || val.length < 4) return null;

        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);

        long oidAndType = keyBuf.getLong(0);
        long inodeOid = oidAndType & 0x0FFFFFFFFFFFFFFFL;

        // name_len at offset 8 (2 bytes), then name
        int nameLen = keyBuf.getShort(8) & 0xFFFF;
        int nameStart = 10;
        // Read null-terminated name
        int nameEnd = nameStart;
        while (nameEnd < key.length && key[nameEnd] != 0) {
            nameEnd++;
        }
        String name = new String(key, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);

        ByteBuffer valBuf = ByteBuffer.wrap(val);
        valBuf.order(ByteOrder.LITTLE_ENDIAN);

        int flags = valBuf.getShort(0) & 0xFFFF;
        int xdataLen = valBuf.getShort(2) & 0xFFFF;

        byte[] data;
        if (xdataLen > 0 && 4 + xdataLen <= val.length) {
            data = new byte[xdataLen];
            System.arraycopy(val, 4, data, 0, xdataLen);
        } else if (val.length > 4) {
            // Fallback: use remaining bytes after the 4-byte header
            data = new byte[val.length - 4];
            System.arraycopy(val, 4, data, 0, data.length);
        } else {
            data = new byte[0];
        }

        return new ApfsXattr(inodeOid, name, flags, data);
    }

    /**
     * Checks if a B-tree key represents an xattr record for the given inode OID.
     */
    public static boolean isXattrForOid(byte[] key, long oid) {
        if (key.length < 10) return false;
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);
        long oidAndType = keyBuf.getLong(0);
        long keyOid = oidAndType & 0x0FFFFFFFFFFFFFFFL;
        long keyType = oidAndType & KEY_TYPE_MASK;
        return keyOid == oid && keyType == KEY_TYPE_XATTR;
    }

    /**
     * Checks if a B-tree key represents an xattr record for the given OID and name.
     */
    public static boolean isXattrForOidAndName(byte[] key, long oid, String xattrName) {
        if (!isXattrForOid(key, oid)) return false;
        // Parse the name from the key to check
        int nameStart = 10;
        int nameEnd = nameStart;
        while (nameEnd < key.length && key[nameEnd] != 0) {
            nameEnd++;
        }
        String name = new String(key, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
        return name.equals(xattrName);
    }

    /**
     * Returns true if the xattr data is stored in a data stream (resource fork).
     */
    public boolean isDataStream() {
        return (flags & XATTR_DATA_STREAM) != 0;
    }

    /**
     * Returns true if the xattr data is embedded directly in the value.
     */
    public boolean isEmbedded() {
        return (flags & XATTR_DATA_EMBEDDED) != 0;
    }
}
