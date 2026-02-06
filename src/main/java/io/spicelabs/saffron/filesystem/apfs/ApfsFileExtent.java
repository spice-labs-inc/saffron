/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.apfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents an APFS file extent record from the filesystem B-tree.
 *
 * <p>File extent records have key type 0x80 (FILE_EXTENT_VAL). The key is:
 * <pre>
 * private_id(8) + type_and_offset(8)
 * </pre>
 * where the lower 60 bits of type_and_offset contain the logical byte offset in the file.
 *
 * <p>The value is:
 * <pre>
 * flags_and_length(8) + phys_block_num(8) + crypto_id(8)
 * </pre>
 * where the lower 56 bits of flags_and_length contain the extent length in bytes.
 */
public record ApfsFileExtent(
        long privateId,
        long logicalOffset,
        long physicalBlock,
        long length
) {
    public static final long KEY_TYPE_FILE_EXTENT = 0x8000000000000000L;

    /**
     * Parses a file extent record.
     */
    public static ApfsFileExtent parse(byte[] key, byte[] val) {
        if (key.length < 16 || val.length < 16) return null;

        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);

        long privateId = keyBuf.getLong(0) & 0x0FFFFFFFFFFFFFFFL;
        long typeAndOffset = keyBuf.getLong(8);
        long logicalOffset = typeAndOffset & 0x0FFFFFFFFFFFFFFFL;

        ByteBuffer valBuf = ByteBuffer.wrap(val);
        valBuf.order(ByteOrder.LITTLE_ENDIAN);

        long flagsAndLength = valBuf.getLong(0);
        long length = flagsAndLength & 0x00FFFFFFFFFFFFFFL;
        long physicalBlock = valBuf.getLong(8);

        return new ApfsFileExtent(privateId, logicalOffset, physicalBlock, length);
    }

    /**
     * Checks if a key is a file extent key for the given private ID.
     */
    public static boolean isFileExtentForId(byte[] key, long privateId) {
        if (key.length < 16) return false;
        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);
        long oidAndType = keyBuf.getLong(0);
        long keyOid = oidAndType & 0x0FFFFFFFFFFFFFFFL;
        long keyType = oidAndType & 0xF000000000000000L;
        return keyOid == privateId && keyType == KEY_TYPE_FILE_EXTENT;
    }
}
