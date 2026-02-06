/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.apfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Represents an APFS directory record (DREC) from the filesystem B-tree.
 *
 * <p>Directory records have key type 0x9. The key is:
 * <pre>
 * obj_id_and_type(8) + name_len_and_hash(4) + name(variable, null-terminated)
 * </pre>
 * where the upper 4 bits of obj_id_and_type contain 0x9 and the lower 60 bits
 * contain the parent directory's inode OID.
 *
 * <p>The value is:
 * <pre>
 * file_id(8) + date_added(8) + flags(2) + [xfields...]
 * </pre>
 */
public record ApfsDirectoryRecord(
        long parentOid,
        long fileId,
        String name,
        long dateAdded,
        int flags
) {
    public static final long KEY_TYPE_DREC = 0x9000000000000000L;
    public static final long KEY_TYPE_MASK = 0xF000000000000000L;

    /**
     * Parses a directory record from a filesystem B-tree leaf entry.
     */
    public static ApfsDirectoryRecord parse(byte[] key, byte[] val) {
        if (key.length < 12 || val.length < 18) return null;

        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);

        long parentOid = keyBuf.getLong(0) & 0x0FFFFFFFFFFFFFFFL;
        // name_len_and_hash at offset 8 (4 bytes), then name at offset 12

        // Name starts at offset 12, null-terminated UTF-8
        int nameStart = 12;
        if (nameStart >= key.length) return null;
        int nameEnd = nameStart;
        while (nameEnd < key.length && key[nameEnd] != 0) {
            nameEnd++;
        }
        String name = new String(key, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);

        ByteBuffer valBuf = ByteBuffer.wrap(val);
        valBuf.order(ByteOrder.LITTLE_ENDIAN);

        long fileId = valBuf.getLong(0);
        long dateAdded = valBuf.getLong(8);
        int flags = valBuf.getShort(16) & 0xFFFF;

        return new ApfsDirectoryRecord(parentOid, fileId, name, dateAdded, flags);
    }

    /**
     * Checks if a B-tree key represents a directory record for the given parent OID.
     */
    public static boolean isDrecForParent(byte[] key, long parentOid) {
        if (key.length < 12) return false;
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);
        long oidAndType = keyBuf.getLong(0);
        long keyOid = oidAndType & 0x0FFFFFFFFFFFFFFFL;
        long keyType = oidAndType & KEY_TYPE_MASK;
        return keyOid == parentOid && keyType == KEY_TYPE_DREC;
    }

    /**
     * Extracts the OID from a B-tree key (strips the type bits).
     */
    public static long getKeyOid(byte[] key) {
        if (key.length < 8) return 0;
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);
        return keyBuf.getLong(0) & 0x0FFFFFFFFFFFFFFFL;
    }

    /**
     * Gets the key type from a B-tree key.
     */
    public static long getKeyType(byte[] key) {
        if (key.length < 8) return 0;
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);
        return keyBuf.getLong(0) & KEY_TYPE_MASK;
    }
}
