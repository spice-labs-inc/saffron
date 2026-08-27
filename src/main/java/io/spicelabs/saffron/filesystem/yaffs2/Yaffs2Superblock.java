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

import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * YAFFS2 "superblock": YAFFS2 has no superblock, so detection infers the
 * chunk geometry (page + spare sizes) and tag/data endianness from the
 * image itself.
 *
 * <p>Detection requires:
 * <ul>
 *   <li>the image size is an exact multiple of {@code page + spare} for one
 *       of the candidate geometries;</li>
 *   <li>every chunk carries a plausible tag (non-zero object id, sane chunk
 *       id) under one of the two tag encodings;</li>
 *   <li>at least one object header (chunk id 0) with a known type and a
 *       sane name, and at least one entry whose parent is the root.</li>
 * </ul>
 *
 * @param pageSize data bytes per chunk
 * @param spareSize spare (OOB) bytes per chunk
 * @param bigEndianTags true when tags use the big-endian encoding
 * @param bigEndianData true when object headers use big-endian fields
 * @param totalSize the image size in bytes
 * @param chunkCount the number of chunks
 */
public record Yaffs2Superblock(
        int pageSize,
        int spareSize,
        boolean bigEndianTags,
        boolean bigEndianData,
        long totalSize,
        long chunkCount) {

    /** Chunk stride. */
    public long chunkSize() {
        return SafeMath.safeAdd((long) pageSize, (long) spareSize);
    }

    /** The page size, reported as the filesystem block size. */
    public static int blockSize(Yaffs2Superblock sb) {
        return sb.pageSize();
    }

    /**
     * Detects the YAFFS2 geometry of a region.
     *
     * @param region the candidate region
     * @return the detected geometry, or empty if not YAFFS2
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<Yaffs2Superblock> read(@NotNull DiskRegion region)
            throws IOException {
        long size = region.size();
        if (size < (512 + 16) * 2) {
            return Optional.empty();
        }

        for (int page : Yaffs2Node.PAGES) {
            for (int spare : Yaffs2Node.SPARES) {
                long chunkSize = (long) page + spare;
                if (size % chunkSize != 0) {
                    continue;
                }
                long chunkCount = size / chunkSize;
                if (chunkCount < 2) {
                    continue;
                }
                for (int endianCombo = 0; endianCombo < 4; endianCombo++) {
                    boolean beTags = (endianCombo & 1) != 0;
                    boolean beData = (endianCombo & 2) != 0;
                    if (matches(region, page, spare, chunkCount, beTags, beData)) {
                        return Optional.of(new Yaffs2Superblock(
                                page, spare, beTags, beData, size, chunkCount));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean matches(DiskRegion region, int page, int spare,
                                   long chunkCount, boolean beTags, boolean beData)
            throws IOException {
        long chunkSize = (long) page + spare;
        int headers = 0;
        int rootChildren = 0;
        long limit = Math.min(chunkCount, 4096); // bound the scan

        for (long c = 0; c < limit; c++) {
            ByteBuffer spareBuf = region.read(c * chunkSize + page, 16);
            byte[] spareBytes = new byte[16];
            spareBuf.get(spareBytes);
            Yaffs2Node.Tag tag = Yaffs2Node.decodeTag(spareBytes, beTags);
            if (tag == null || tag.objId() == 0) {
                return false;
            }
            if (tag.chunkId() == 0xffffffffL) {
                continue; // Erased/unused chunk (trailing padding).
            }
            if (tag.chunkId() > chunkCount) {
                return false;
            }
            if (tag.chunkId() != 0 || page < Yaffs2Node.HEADER_SIZE) {
                continue;
            }
            // Object header at the start of this chunk's data area.
            ByteBuffer head = region.read(c * chunkSize, Yaffs2Node.HEADER_SIZE);
            byte[] headBytes = new byte[Yaffs2Node.HEADER_SIZE];
            head.get(headBytes);
            int type = (int) Yaffs2Node.dataU32(headBytes, Yaffs2Node.HDR_TYPE, beData);
            if (!Yaffs2Node.isKnownType(type)) {
                return false;
            }
            long parent = Yaffs2Node.normalizeId(
                    Yaffs2Node.dataU32(headBytes, Yaffs2Node.HDR_PARENT, beData));
            if (parent == 0 && type != Yaffs2Node.TYPE_DIRECTORY) {
                return false;
            }
            String name = readName(headBytes);
            if (name.isEmpty() && tag.objId() != Yaffs2Node.ROOT_OBJ_ID) {
                return false;
            }
            headers++;
            if (parent == Yaffs2Node.ROOT_OBJ_ID) {
                rootChildren++;
            }
        }
        return headers >= 1 && (headers >= 2 || rootChildren >= 1);
    }

    private static String readName(byte[] head) {
        int len = 0;
        int end = Math.min(Yaffs2Node.HDR_NAME + Yaffs2Node.HDR_NAME_LEN, head.length);
        for (int i = Yaffs2Node.HDR_NAME; i < end; i++) {
            if (head[i] == 0) {
                break;
            }
            len++;
        }
        return new String(head, Yaffs2Node.HDR_NAME, len, StandardCharsets.UTF_8);
    }
}
