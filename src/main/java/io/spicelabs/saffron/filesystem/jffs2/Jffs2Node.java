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
package io.spicelabs.saffron.filesystem.jffs2;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * On-disk constants and parsed-node records for JFFS2.
 *
 * <p>JFFS2 is a log-structured flash filesystem with no superblock. An image
 * is a stream of self-describing nodes, each starting with a 12-byte common
 * header. All multi-byte fields are little-endian. The layout mirrors the
 * Linux kernel header {@code include/uapi/linux/jffs2.h}.
 *
 * <p>Common node header:
 * <pre>
 * offset  size  field
 * 0       2     magic      (0x1985)
 * 2       2     nodetype   (DIRENT/INODE/CLEANMARKER/PADDING/...)
 * 4       4     totlen     (total node length including header, multiple of 4)
 * 8       4     hdr_crc    (JFFS2 CRC of bytes 0..7)
 * </pre>
 *
 * <p>The JFFS2 CRC is the standard reflected CRC-32 (polynomial 0xedb88320)
 * with initial value 0 and no final XOR, distinct from the standard zlib
 * CRC-32.
 */
public final class Jffs2Node {

    private Jffs2Node() {
        // Constants only
    }

    /** Magic number shared by all JFFS2 nodes. */
    public static final int MAGIC = 0x1985;

    /** Legacy magic; must NOT be accepted. */
    public static final int OLD_MAGIC = 0x1984;

    /** Erased-flash marker; terminates the node scan. */
    public static final int EMPTY_BITMASK = 0xffff;

    /** On-disk node types (include the compat feature bits). */
    public static final int NODETYPE_DIRENT = 0xe001;
    public static final int NODETYPE_INODE = 0xe002;
    public static final int NODETYPE_CLEANMARKER = 0x2003;
    public static final int NODETYPE_PADDING = 0x2004;
    public static final int NODETYPE_SUMMARY = 0x2006;
    public static final int NODETYPE_XATTR = 0xe008;
    public static final int NODETYPE_XREF = 0xe009;

    /** Compression ids ({@code compr} field of the raw inode). */
    public static final int COMPR_NONE = 0x00;
    public static final int COMPR_ZERO = 0x01;
    public static final int COMPR_RTIME = 0x02;
    public static final int COMPR_RUBINMIPS = 0x03;
    public static final int COMPR_COPY = 0x04;
    public static final int COMPR_DYNRUBIN = 0x05;
    public static final int COMPR_ZLIB = 0x06;
    public static final int COMPR_LZO = 0x07;

    /** Size of the common node header. */
    public static final int COMMON_HEADER_SIZE = 12;

    /** Size of the raw inode node header (before the data payload). */
    public static final int INODE_HEADER_SIZE = 68;

    /** Size of the raw dirent node header (before the name bytes). */
    public static final int DIRENT_HEADER_SIZE = 40;

    /** The root directory always has inode number 1. */
    public static final long ROOT_INO = 1;

    /** Kernel constant: names are at most 254 bytes. */
    public static final int MAX_NAME_LEN = 254;

    /** Dirent type values (Linux {@code DT_*} constants). */
    public static final int DT_UNKNOWN = 0;
    public static final int DT_FIFO = 1;
    public static final int DT_CHR = 2;
    public static final int DT_DIR = 4;
    public static final int DT_BLK = 6;
    public static final int DT_REG = 8;
    public static final int DT_LNK = 10;
    public static final int DT_SOCK = 12;
    public static final int DT_WHT = 14;

    /** Inode mode type bits (Linux {@code S_IF*} constants). */
    public static final int S_IFMT = 0xf000;
    public static final int S_IFIFO = 0x1000;
    public static final int S_IFCHR = 0x2000;
    public static final int S_IFDIR = 0x4000;
    public static final int S_IFBLK = 0x6000;
    public static final int S_IFREG = 0x8000;
    public static final int S_IFLNK = 0xa000;
    public static final int S_IFSOCK = 0xc000;

    /**
     * A data fragment of an inode (one {@code JFFS2_NODETYPE_INODE} node).
     *
     * @param ino inode number
     * @param version node version (higher wins for the same byte range)
     * @param offset byte offset of this fragment within the file
     * @param csize compressed payload size in bytes
     * @param dsize decompressed payload size in bytes
     * @param compr compression id
     * @param dataOffset absolute offset of the compressed payload within the
     *        source region
     */
    public record InodeFragment(long ino, long version, long offset,
                                long csize, long dsize, int compr, long dataOffset) {
    }

    /**
     * The winning metadata of an inode (from its highest-version node).
     *
     * @param ino inode number
     * @param version node version of the winning metadata
     * @param mode mode bits (file type + permissions)
     * @param uid owner uid
     * @param gid owner gid
     * @param isize logical file size in bytes
     * @param mtime modification time (seconds since epoch)
     */
    public record InodeMeta(long ino, long version, int mode, int uid, int gid, long isize, long mtime) {
    }

    /**
     * A resolved directory entry.
     *
     * @param pino parent directory inode number
     * @param version winning version
     * @param ino inode number the name resolves to (0 = deleted)
     * @param type dirent type ({@code DT_*} constant)
     * @param name entry name
     */
    public record Dirent(long pino, long version, long ino, int type, @NotNull String name) {
    }

    /**
     * Returns whether the buffer begins with the JFFS2 node magic
     * {@code 0x1985} in little-endian.
     *
     * <p>This is a lightweight, presence-only check for stream-based probing;
     * it does not validate the rest of the node header.
     *
     * @param buffer the prefix bytes of the artifact
     * @return true if the buffer starts with the JFFS2 magic
     */
    public static boolean isJffs2Magic(@NotNull ByteBuffer buffer) {
        if (buffer.remaining() < 2) {
            return false;
        }
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        return b.getShort(0) == MAGIC;
    }

    /**
     * Returns whether the 16-bit on-disk node type is one JFFS2 defines.
     *
     * @param nodetype the on-disk node type (including compat bits)
     * @return true if known
     */
    public static boolean isKnownNodeType(int nodetype) {
        return switch (nodetype) {
            case NODETYPE_DIRENT, NODETYPE_INODE, NODETYPE_CLEANMARKER,
                 NODETYPE_PADDING, NODETYPE_SUMMARY, NODETYPE_XATTR, NODETYPE_XREF -> true;
            default -> false;
        };
    }

    /**
     * Computes the JFFS2 CRC-32 over a byte range: standard reflected CRC-32
     * (polynomial 0xedb88320), initial value 0, no final XOR.
     *
     * @param data the buffer
     * @param offset start offset
     * @param length number of bytes
     * @return the JFFS2 CRC-32 value
     */
    public static int crc32(@NotNull byte[] data, int offset, int length) {
        long crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xff);
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xedb88320L : 0);
            }
        }
        return (int) (crc & 0xffffffffL);
    }
}
