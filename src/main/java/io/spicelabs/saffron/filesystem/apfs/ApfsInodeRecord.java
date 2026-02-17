/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.apfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Represents an APFS inode record from the filesystem B-tree.
 *
 * <p>Inode records have key type 0x30 (INODE_VAL). The key is:
 * <pre>
 * oid(8) + type(8)  where type high nibble = 0x3
 * </pre>
 *
 * <p>The value contains the inode data:
 * <pre>
 * parent_id(8) + private_id(8) + create_time(8) + mod_time(8)
 * + change_time(8) + access_time(8) + internal_flags(8)
 * + nchildren_or_nlink(4) + default_protection_class(4)
 * + write_generation_counter(4) + bsd_flags(4)
 * + uid(4) + gid(4) + mode(2) + pad1(2) + uncompressed_size(8)
 * + [xfields...]
 * </pre>
 *
 * <p>APFS timestamps are nanoseconds since Unix epoch.
 */
public record ApfsInodeRecord(
        long oid,
        long parentId,
        long privateId,
        long createTime,
        long modTime,
        long changeTime,
        long accessTime,
        long internalFlags,
        int nchildrenOrNlink,
        int bsdFlags,
        int uid,
        int gid,
        int mode,
        long uncompressedSize,
        String name,
        long dataStreamSize
) {
    public static final long KEY_TYPE_INODE = 0x3000000000000000L;
    public static final long KEY_TYPE_MASK = 0xF000000000000000L;

    /**
     * Parses an inode record from a filesystem B-tree leaf entry.
     */
    public static ApfsInodeRecord parse(byte[] key, byte[] val) {
        if (key.length < 8 || val.length < 92) return null;

        ByteBuffer keyBuf = ByteBuffer.wrap(key);
        keyBuf.order(ByteOrder.LITTLE_ENDIAN);
        long oid = keyBuf.getLong(0) & 0x0FFFFFFFFFFFFFFFL;

        ByteBuffer valBuf = ByteBuffer.wrap(val);
        valBuf.order(ByteOrder.LITTLE_ENDIAN);

        long parentId = valBuf.getLong(0);
        long privateId = valBuf.getLong(8);
        long createTime = valBuf.getLong(16);
        long modTime = valBuf.getLong(24);
        long changeTime = valBuf.getLong(32);
        long accessTime = valBuf.getLong(40);
        long internalFlags = valBuf.getLong(48);
        int nchildrenOrNlink = valBuf.getInt(56);
        // default_protection_class at 60
        // write_generation_counter at 64
        int bsdFlags = valBuf.getInt(68);
        int uid = valBuf.getInt(72);
        int gid = valBuf.getInt(76);
        int mode = valBuf.getShort(80) & 0xFFFF;
        // pad1 at 82
        long uncompressedSize = val.length >= 92 ? valBuf.getLong(84) : 0;

        // Parse xfields for name and dstream
        String name = "";
        long dataStreamSize = 0;

        if (val.length > 92) {
            int xfieldOffset = 92;
            // xfield header: count(2) + used_data_len(2)
            if (xfieldOffset + 4 <= val.length) {
                int xfCount = valBuf.getShort(xfieldOffset) & 0xFFFF;
                xfieldOffset += 4;

                // Parse xfield descriptors
                int[] xfTypes = new int[xfCount];
                int[] xfSizes = new int[xfCount];
                for (int i = 0; i < xfCount && xfieldOffset + 4 <= val.length; i++) {
                    xfTypes[i] = valBuf.get(xfieldOffset) & 0xFF;
                    // flags at xfieldOffset + 1
                    xfSizes[i] = valBuf.getShort(xfieldOffset + 2) & 0xFFFF;
                    xfieldOffset += 4;
                }

                // Parse xfield data
                for (int i = 0; i < xfCount; i++) {
                    if (xfieldOffset + xfSizes[i] > val.length) break;

                    switch (xfTypes[i]) {
                        case 0x04: // INO_EXT_TYPE_NAME
                            int nameLen = xfSizes[i];
                            // Null-terminated string
                            while (nameLen > 0 && val[xfieldOffset + nameLen - 1] == 0) nameLen--;
                            if (nameLen > 0) {
                                name = new String(val, xfieldOffset, nameLen, StandardCharsets.UTF_8);
                            }
                            break;
                        case 0x08: // INO_EXT_TYPE_DSTREAM
                            if (xfSizes[i] >= 8) {
                                dataStreamSize = valBuf.getLong(xfieldOffset);
                            }
                            break;
                    }

                    xfieldOffset += xfSizes[i];
                    // Align to 8 bytes
                    xfieldOffset = (xfieldOffset + 7) & ~7;
                }
            }
        }

        return new ApfsInodeRecord(oid, parentId, privateId, createTime, modTime,
                changeTime, accessTime, internalFlags, nchildrenOrNlink, bsdFlags, uid, gid, mode,
                uncompressedSize, name, dataStreamSize);
    }

    public boolean isDirectory() {
        return (mode & 0xF000) == 0x4000;
    }

    public boolean isRegularFile() {
        return (mode & 0xF000) == 0x8000;
    }

    public boolean isSymbolicLink() {
        return (mode & 0xF000) == 0xA000;
    }

    /**
     * Checks if this inode represents a compressed file.
     * APFS uses UF_COMPRESSED (0x0020) in bsd_flags and/or
     * INODE_IS_COMPRESSED (0x20) in internal_flags.
     */
    public boolean isCompressed() {
        return (bsdFlags & 0x0020) != 0;
    }

    public Optional<Instant> creationTime() {
        return apfsTimeToInstant(createTime);
    }

    public Optional<Instant> modificationTime() {
        return apfsTimeToInstant(modTime);
    }

    public Optional<Instant> accessTimeInstant() {
        return apfsTimeToInstant(accessTime);
    }

    private static Optional<Instant> apfsTimeToInstant(long nanoseconds) {
        if (nanoseconds == 0) return Optional.empty();
        long seconds = nanoseconds / 1_000_000_000L;
        int nanos = (int) (nanoseconds % 1_000_000_000L);
        if (nanos < 0) {
            seconds--;
            nanos += 1_000_000_000;
        }
        return Optional.of(Instant.ofEpochSecond(seconds, nanos));
    }
}
