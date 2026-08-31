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
package io.spicelabs.saffron.filesystem.ubi;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * UBI on-flash structures (mirrors {@code drivers/mtd/ubi/ubi-media.h}).
 * All multi-byte fields are big-endian.
 */
public final class UbiNode {

    private UbiNode() {
        // Constants only
    }

    /** EC header magic, ASCII "UBI#". */
    public static final int EC_HDR_MAGIC = 0x55424923;
    /** VID header magic, ASCII "UBI!". */
    public static final int VID_HDR_MAGIC = 0x55424921;
    /** Layout volume id (holds the volume table). */
    public static final long LAYOUT_VOLUME_ID = 0x7FFFEFFFL;
    /** UBI format version. */
    public static final int VERSION = 1;

    public static final int EC_HDR_SIZE = 64;
    public static final int VID_HDR_SIZE = 64;
    public static final int VTBL_RECORD_SIZE = 172;
    public static final int VOL_NAME_MAX = 127;

    public static final int VOL_TYPE_DYNAMIC = 1;
    public static final int VOL_TYPE_STATIC = 2;

    /** Erase counter header. */
    public record EcHdr(int version, long ec, int vidHdrOffset, int dataOffset,
                        long imageSeq, boolean erased) {

        static @Nullable EcHdr parse(byte[] b) {
            if (b == null || b.length < EC_HDR_SIZE) {
                return null;
            }
            boolean erased = true;
            for (int i = 0; i < 4; i++) {
                if (b[i] != (byte) 0xff) {
                    erased = false;
                    break;
                }
            }
            if (erased) {
                return new EcHdr(0, 0, 0, 0, 0, true);
            }
            ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            if (buf.getInt(0) != EC_HDR_MAGIC) {
                return null;
            }
            int version = b[4] & 0xff;
            if (version != VERSION) {
                return null;
            }
            if (crc32(b, 0, 60) != buf.getInt(60)) {
                return null;
            }
            return new EcHdr(version, buf.getLong(8), buf.getInt(16), buf.getInt(20),
                    buf.getInt(24) & 0xffffffffL, false);
        }
    }

    /** Volume identifier header. */
    public record VidHdr(int volType, boolean copyFlag, int compat, long volId,
                         long lnum, long dataSize, long usedEbs, long dataPad,
                         long sqnum, boolean erased) {

        static @Nullable VidHdr parse(byte[] b) {
            if (b == null || b.length < VID_HDR_SIZE) {
                return null;
            }
            boolean erased = true;
            for (int i = 0; i < 4; i++) {
                if (b[i] != (byte) 0xff) {
                    erased = false;
                    break;
                }
            }
            if (erased) {
                return new VidHdr(0, false, 0, 0, 0, 0, 0, 0, 0, true);
            }
            ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            if (buf.getInt(0) != VID_HDR_MAGIC) {
                return null;
            }
            int version = b[4] & 0xff;
            if (version != VERSION) {
                return null;
            }
            if (crc32(b, 0, 60) != buf.getInt(60)) {
                return null;
            }
            return new VidHdr(b[5] & 0xff, b[6] != 0, b[7] & 0xff,
                    buf.getInt(8) & 0xffffffffL, buf.getInt(12) & 0xffffffffL,
                    buf.getInt(16) & 0xffffffffL, buf.getInt(20) & 0xffffffffL,
                    buf.getInt(24) & 0xffffffffL, buf.getLong(32), false);
        }
    }

    /** Volume table record. */
    public record VtblRecord(long reservedPebs, int alignment, long dataPad,
                             int volType, @NotNull String name, int flags,
                             boolean empty) {

        static @Nullable VtblRecord parse(byte[] b) {
            if (b == null || b.length < VTBL_RECORD_SIZE) {
                return null;
            }
            boolean empty = true;
            for (int i = 0; i < VTBL_RECORD_SIZE; i++) {
                if (b[i] != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                return new VtblRecord(0, 0, 0, 0, "", 0, true);
            }
            ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
            if (crc32(b, 0, VTBL_RECORD_SIZE - 4) != buf.getInt(VTBL_RECORD_SIZE - 4)) {
                return null;
            }
            int nameLen = buf.getShort(14) & 0xffff;
            if (nameLen > VOL_NAME_MAX) {
                return null;
            }
            String name = new String(b, 16, nameLen, StandardCharsets.UTF_8);
            return new VtblRecord(buf.getInt(0) & 0xffffffffL, buf.getInt(4),
                    buf.getInt(8) & 0xffffffffL, b[12] & 0xff, name, b[144] & 0xff, false);
        }
    }

    /**
     * The kernel CRC-32 variant used by UBI headers: standard reflected
     * CRC-32 with initial value 0xFFFFFFFF but WITHOUT the final XOR —
     * equivalently, {@code java.util.zip.CRC32 ^ 0xFFFFFFFF}.
     */
    public static int crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) (crc.getValue() ^ 0xffffffffL);
    }

    /** Reads a byte range from a region at an absolute offset. */
    static byte[] readBytes(DiskRegion region, long offset, int length) throws IOException {
        ByteBuffer buf = region.read(offset, length);
        byte[] out = new byte[length];
        buf.get(out);
        return out;
    }
}
