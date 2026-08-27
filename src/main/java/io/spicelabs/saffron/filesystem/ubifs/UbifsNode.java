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
package io.spicelabs.saffron.filesystem.ubifs;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * UBIFS on-flash constants (mirrors {@code fs/ubifs/ubifs-media.h}). All
 * multi-byte fields are little-endian.
 */
public final class UbifsNode {

    private UbifsNode() {
        // Constants only
    }

    /** UBIFS node magic. */
    public static final int NODE_MAGIC = 0x06101831;

    /** Common node header size. */
    public static final int CH_SIZE = 24;

    /** Node types. */
    public static final int INO_NODE = 0;
    public static final int DATA_NODE = 1;
    public static final int DENT_NODE = 2;
    public static final int XENT_NODE = 3;
    public static final int TRUN_NODE = 4;
    public static final int PAD_NODE = 5;
    public static final int SB_NODE = 6;
    public static final int MST_NODE = 7;
    public static final int REF_NODE = 8;
    public static final int IDX_NODE = 9;
    public static final int CS_NODE = 10;
    public static final int ORPH_NODE = 11;

    /** Inode types. */
    public static final int ITYPE_REG = 0;
    public static final int ITYPE_DIR = 1;
    public static final int ITYPE_LNK = 2;
    public static final int ITYPE_BLK = 3;
    public static final int ITYPE_CHR = 4;
    public static final int ITYPE_FIFO = 5;
    public static final int ITYPE_SOCK = 6;

    /** Compression ids. */
    public static final int COMPR_NONE = 0;
    public static final int COMPR_LZO = 1;
    public static final int COMPR_ZLIB = 2;
    public static final int COMPR_ZSTD = 3;

    /** Key types (upper 3 bits of the second key word). */
    public static final int KEY_INO = 0;
    public static final int KEY_DATA = 1;
    public static final int KEY_DENT = 2;
    public static final int KEY_XENT = 3;
    public static final int KEY_BLOCK_BITS = 29;
    public static final int KEY_BLOCK_MASK = 0x1FFFFFFF;

    /** Master node flags. */
    public static final int MST_DIRTY = 1;

    /** Superblock flags. */
    public static final int FLG_ENCRYPTION = 0x10;
    public static final int FLG_AUTHENTICATION = 0x20;

    /** Data block size. */
    public static final int BLOCK_SIZE = 4096;

    public static final long ROOT_INO = 1;
    public static final int MAX_NAME_LEN = 255;

    /** SB node field offsets (after the 24-byte common header). */
    public static final int SB_FLAGS = 28;
    public static final int SB_MIN_IO_SIZE = 32;
    public static final int SB_LEB_SIZE = 36;
    public static final int SB_LEB_CNT = 40;
    public static final int SB_FANOUT = 72;
    public static final int SB_FMT_VERSION = 80;
    public static final int SB_DEFAULT_COMPR = 84;
    public static final int SB_UUID = 94;

    /** MST node field offsets. */
    public static final int MST_HIGHEST_INUM = 24;
    public static final int MST_CMT_NO = 32;
    public static final int MST_FLAGS = 40;
    public static final int MST_LOG_LNUM = 44;
    public static final int MST_ROOT_LNUM = 48;
    public static final int MST_ROOT_OFFS = 52;
    public static final int MST_ROOT_LEN = 56;
    public static final int MST_INDEX_SIZE = 72;
    public static final int MST_LEB_CNT = 124;

    /** INO node field offsets. */
    public static final int INO_KEY = 24;
    public static final int INO_SIZE = 48;
    public static final int INO_NLINK = 92;
    public static final int INO_UID = 96;
    public static final int INO_GID = 100;
    public static final int INO_MODE = 104;
    public static final int INO_DATA_LEN = 112;
    public static final int INO_COMPR_TYPE = 132;

    /** DATA node field offsets. */
    public static final int DATA_KEY = 24;
    public static final int DATA_SIZE = 40;
    public static final int DATA_COMPR_TYPE = 44;
    public static final int DATA_OFFSET = 48;

    /** DENT node field offsets. */
    public static final int DENT_KEY = 24;
    public static final int DENT_INUM = 40;
    public static final int DENT_TYPE = 49;
    public static final int DENT_NLEN = 50;
    public static final int DENT_NAME = 56;

    /** IDX node field offsets. */
    public static final int IDX_CHILD_CNT = 24;
    public static final int IDX_LEVEL = 26;
    public static final int IDX_BRANCHES = 28;

    /** Parsed common node header. */
    public record Header(int nodeType, long len, long sqnum) {
    }

    /** Parsed superblock node. */
    public record Superblock(int minIoSize, int lebSize, long lebCnt, int fanout,
                             int fmtVersion, int defaultCompr, long flags,
                             @NotNull byte[] uuid) {
    }

    /** Parsed master node. */
    public record Master(long cmtNo, long flags, long logLnum, long rootLnum,
                         long rootOffs, long rootLen) {
    }

    /** An index branch: (lnum, offs, len, key). */
    public record Branch(long lnum, long offs, long len, long keyWord0, long keyWord1) {
    }

    /**
     * Parses a common node header at {@code offset} within a buffer. The
     * header CRC covers the whole node (bytes 8..len, CRC field zeroed), so
     * the caller must provide the full node.
     */
    public static Header parseHeader(byte[] buf, int offset, int available) {
        if (buf == null || offset + CH_SIZE > buf.length) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(buf, offset, available).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != NODE_MAGIC) {
            return null;
        }
        int storedCrc = b.getInt(4);
        long len = b.getInt(16) & 0xffffffffL;
        if (len < CH_SIZE || len > available || offset + len > buf.length) {
            return null;
        }
        byte[] copy = new byte[(int) len];
        System.arraycopy(buf, offset, copy, 0, (int) len);
        copy[4] = 0;
        copy[5] = 0;
        copy[6] = 0;
        copy[7] = 0;
        if (storedCrc != crc32(copy, 8, (int) len - 8)) {
            return null;
        }
        return new Header(b.get(20) & 0xff, len, b.getLong(8));
    }

    /**
     * The mtd-utils/kernel CRC-32 variant used by UBIFS node headers:
     * standard reflected CRC-32 with initial value 0xFFFFFFFF but WITHOUT
     * the final XOR ({@code mtd_crc32}) — equivalently,
     * {@code java.util.zip.CRC32 ^ 0xFFFFFFFF}.
     */
    public static int crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) (crc.getValue() ^ 0xffffffffL);
    }

    /** The UBIFS R5 hash used in directory-entry keys. */
    public static int r5Hash(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        int a = 0;
        for (byte bb : bytes) {
            a += (bb << 4) & 0xff0;
            a += (bb >> 4) & 0xf;
            a *= 11;
        }
        return a;
    }

    /** Extracts the key type from the second key word. */
    public static int keyType(long word1) {
        return (int) ((word1 >>> KEY_BLOCK_BITS) & 0x7);
    }

    /** Extracts the block/hash part from the second key word. */
    public static long keyBlock(long word1) {
        return word1 & KEY_BLOCK_MASK;
    }
}
