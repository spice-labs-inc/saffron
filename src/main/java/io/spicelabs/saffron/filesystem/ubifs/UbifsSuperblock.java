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

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * UBIFS "superblock": detection validates the superblock node at LEB 0.
 *
 * <p>A region is recognized as UBIFS when its first node carries the UBIFS
 * node magic {@code 0x06101831}, has node type SB (6), a valid header CRC,
 * and a plausible geometry (format version 4/5, sane LEB size and count).
 */
public final class UbifsSuperblock {

    private final UbifsNode.Superblock sb;

    private UbifsSuperblock(UbifsNode.Superblock sb) {
        this.sb = sb;
    }

    public UbifsNode.Superblock sb() {
        return sb;
    }

    /** The LEB size reported by the superblock. */
    public int blockSize() {
        return sb.lebSize();
    }

    /**
     * Reads and validates the UBIFS superblock node from a region (a bare
     * UBIFS volume or a UBI volume region).
     *
     * @param region the candidate region
     * @return the validated superblock, or empty if not UBIFS
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<UbifsSuperblock> read(@NotNull DiskRegion region)
            throws IOException {
        if (region.size() < UbifsNode.CH_SIZE * 2) {
            return Optional.empty();
        }
        // Bound the read to the region: the superblock node length comes
        // from the header, but the read itself must never exceed the
        // available bytes (prefix probes may be small).
        int readLen = (int) Math.min(4096L, region.size());
        byte[] head = new byte[readLen];
        ByteBuffer buf = region.read(0, readLen);
        buf.get(head);
        UbifsNode.Header header = UbifsNode.parseHeader(head, 0, readLen);
        if (header == null || header.nodeType() != UbifsNode.SB_NODE
                || header.len() > readLen) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int minIoSize = b.getInt(UbifsNode.SB_MIN_IO_SIZE);
        int lebSize = b.getInt(UbifsNode.SB_LEB_SIZE);
        long lebCnt = b.getInt(UbifsNode.SB_LEB_CNT) & 0xffffffffL;
        int fanout = b.getInt(UbifsNode.SB_FANOUT);
        int fmtVersion = b.getInt(UbifsNode.SB_FMT_VERSION);
        int defaultCompr = b.getShort(UbifsNode.SB_DEFAULT_COMPR) & 0xffff;
        long flags = b.getInt(UbifsNode.SB_FLAGS) & 0xffffffffL;
        byte[] uuid = new byte[16];
        System.arraycopy(head, UbifsNode.SB_UUID, uuid, 0, 16);

        if (fmtVersion != 4 && fmtVersion != 5) {
            return Optional.empty();
        }
        if (lebSize < 15360 || lebSize > 64 * 1024 * 1024) {
            return Optional.empty();
        }
        if (lebCnt < 8 || lebCnt > 0xffffffffL / lebSize) {
            return Optional.empty();
        }
        if (fanout < 2 || fanout > 1024) {
            return Optional.empty();
        }
        // Note: the region may be smaller than leb_size * leb_cnt (prefix
        // probes); the superblock node itself lives at LEB 0 and fits the
        // bounded read above.
        return Optional.of(new UbifsSuperblock(new UbifsNode.Superblock(
                minIoSize, lebSize, lebCnt, fanout, fmtVersion, defaultCompr, flags, uuid)));
    }
}
