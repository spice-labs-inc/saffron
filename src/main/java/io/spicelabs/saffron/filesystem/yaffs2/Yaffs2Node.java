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
package io.spicelabs.saffron.filesystem.yaffs2;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * On-disk constants for YAFFS2.
 *
 * <p>YAFFS2 has no magic number and no superblock. An image is a sequence of
 * chunks: {@code page} data bytes followed by {@code spare} bytes of out-of
 * band area. The first 16 spare bytes carry the chunk tags (no ECC in image
 * files produced by the reference tools). Two tag encodings exist in the
 * wild:
 * <ul>
 *   <li>little-endian: {@code (seq_number, obj_id, chunk_id, n_bytes)}</li>
 *   <li>big-endian: {@code (n_bytes, seq_number, obj_id, chunk_id)}</li>
 * </ul>
 *
 * <p>Chunk 0 of every object holds the 512-byte object header
 * ({@code yaffs_obj_hdr}): type, parent object id, 2-byte sum, 256-byte
 * name, mode/uid/gid/times, file size, hardlink equiv id, symlink alias,
 * and rdev. Header tags use {@code n_bytes == 0xffff} as a marker.
 *
 * <p>Object ids may be stored plain or shifted left 16 bits with a serial
 * number in the low half ({@code mkyaffs2image -s}). Parents are normalized
 * the same way. Objects whose parent id is {@code YAFFS_OBJECTID_UNLINKED}
 * (3) or {@code YAFFS_OBJECTID_DELETED} (4) are deleted.
 */
public final class Yaffs2Node {

    private Yaffs2Node() {
        // Constants only
    }

    /** Object types (enum yaffs_obj_type). */
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_FILE = 1;
    public static final int TYPE_SYMLINK = 2;
    public static final int TYPE_DIRECTORY = 3;
    public static final int TYPE_HARDLINK = 4;
    public static final int TYPE_SPECIAL = 5;

    /** Root directory object id. */
    public static final long ROOT_OBJ_ID = 1;

    /** Parent ids marking deleted objects. */
    public static final long OBJID_UNLINKED = 3;
    public static final long OBJID_DELETED = 4;

    /** Object header size on flash (data in chunk 0 starts after it). */
    public static final int HEADER_SIZE = 512;

    /** Header field offsets (little-endian layout). */
    public static final int HDR_TYPE = 0;
    public static final int HDR_PARENT = 4;
    public static final int HDR_SUM = 8;
    public static final int HDR_NAME = 10;
    public static final int HDR_NAME_LEN = 256;
    // Offsets after the name are 4-byte aligned: name ends at 266, two pad
    // bytes follow, so all subsequent fields sit 2 bytes later than a naive
    // packed struct would suggest. Verified against mkyaffs2image output.
    public static final int HDR_MODE = 268;
    public static final int HDR_UID = 272;
    public static final int HDR_GID = 276;
    public static final int HDR_MTIME = 284;
    public static final int HDR_FILE_SIZE = 292;
    public static final int HDR_EQUIV_ID = 296;
    public static final int HDR_ALIAS = 300;
    public static final int HDR_ALIAS_LEN = 160;
    public static final int HDR_RDEV = 460;

    /** Header tag n_bytes marker. */
    public static final long HEADER_NBYTES_MARKER = 0xffffL;

    /** Candidate page sizes for geometry detection. */
    public static final int[] PAGES = {512, 1024, 2048, 4096, 8192, 16384};

    /** Candidate spare sizes for geometry detection. */
    public static final int[] SPARES = {16, 32, 64, 128, 256, 512};

    /**
     * Normalizes a raw on-flash object id: images made with a serial number
     * store {@code (id << 16) | serial}.
     */
    public static long normalizeId(long raw) {
        if (raw >= (1L << 16)) {
            return raw >>> 16;
        }
        return raw;
    }

    /**
     * A decoded chunk tag.
     *
     * @param seq sequence number (higher wins for the same chunk)
     * @param objId normalized object id
     * @param chunkId chunk index within the object (0 = header)
     * @param nBytes valid bytes in this chunk (0xffff for headers)
     */
    public record Tag(long seq, long objId, long chunkId, long nBytes) {
    }

    /**
     * A decoded object header.
     *
     * @param objId normalized object id
     * @param type object type
     * @param parentId normalized parent object id
     * @param name entry name
     * @param mode mode bits (0 when absent)
     * @param uid uid
     * @param gid gid
     * @param mtime modification time (seconds)
     * @param fileSize file size (0 when absent)
     * @param equivId hardlink target object id (0 = none)
     * @param alias symlink target string (empty when not a symlink)
     * @param rdev device number for special files
     */
    public record Header(
            long objId,
            int type,
            long parentId,
            @NotNull String name,
            int mode,
            long uid,
            long gid,
            long mtime,
            long fileSize,
            long equivId,
            @NotNull String alias,
            long rdev) {
    }

    /** Returns whether the object type is one YAFFS2 defines. */
    public static boolean isKnownType(int type) {
        return type >= TYPE_FILE && type <= TYPE_SPECIAL;
    }

    /** Returns whether the parent id marks the object as deleted. */
    public static boolean isDeletedParent(long parentId) {
        return parentId == OBJID_UNLINKED || parentId == OBJID_DELETED;
    }

    /** Returns the object type as a readable name. */
    public static @NotNull String typeName(int type) {
        return switch (type) {
            case TYPE_FILE -> "file";
            case TYPE_SYMLINK -> "symlink";
            case TYPE_DIRECTORY -> "directory";
            case TYPE_HARDLINK -> "hardlink";
            case TYPE_SPECIAL -> "special";
            default -> "unknown";
        };
    }

    /**
     * Returns the tag record at the start of a spare area, or null when the
     * spare does not contain a plausible tag.
     *
     * <p>Images made with a serial number ({@code mkyaffs2image -s}) store
     * every tag field as {@code value << 16 | serial}; plain images store
     * the raw value. Both forms are normalized here.
     */
    public static @Nullable Tag decodeTag(byte[] spare, boolean bigEndian) {
        if (spare == null || spare.length < 16) {
            return null;
        }
        long a = u32(spare, 0, bigEndian);
        long b = u32(spare, 4, bigEndian);
        long c = u32(spare, 8, bigEndian);
        long d = u32(spare, 12, bigEndian);
        long seqRaw;
        long objRaw;
        long cidRaw;
        long nbRaw;
        if (bigEndian) {
            nbRaw = a;
            seqRaw = b;
            objRaw = c;
            cidRaw = d;
        } else {
            seqRaw = a;
            objRaw = b;
            cidRaw = c;
            nbRaw = d;
        }
        if (objRaw == 0) {
            return null;
        }
        long objId = normalizeId(objRaw);
        long chunkId = normalizeField(cidRaw);
        long seq = normalizeField(seqRaw);
        long nBytes;
        if (nbRaw == HEADER_NBYTES_MARKER) {
            nBytes = HEADER_NBYTES_MARKER;
        } else {
            nBytes = normalizeField(nbRaw);
        }
        return new Tag(seq, objId, chunkId, nBytes);
    }

    /**
     * Normalizes a tag field: shifted-serial images store
     * {@code value << 16 | serial}; plain images store the value directly.
     */
    private static long normalizeField(long raw) {
        if (raw >= (1L << 16) && (raw & 0xffff) == 0) {
            return raw >>> 16;
        }
        return raw;
    }

    private static long u32(byte[] b, int off, boolean bigEndian) {
        if (bigEndian) {
            return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16)
                    | ((b[off + 2] & 0xffL) << 8) | (b[off + 3] & 0xffL);
        }
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    /** Reads a u32 from a data buffer honoring the image endianness. */
    static long dataU32(byte[] data, int off, boolean bigEndian) {
        return u32(data, off, bigEndian);
    }
}
