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

import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * JFFS2 "superblock": JFFS2 has no superblock, so detection validates the
 * first node header of the candidate region instead.
 *
 * <p>A candidate region is recognized as JFFS2 when its first bytes form a
 * well-formed node header:
 * <ul>
 *   <li>magic {@code 0x1985} at offset 0 (the legacy {@code 0x1984} magic is
 *       rejected);</li>
 *   <li>a known node type (dirent, inode, cleanmarker, padding, ...);</li>
 *   <li>{@code totlen >= 12} and within the region (the length is NOT
 *       necessarily 4-aligned: real mkfs.jffs2 images store the true node
 *       length and pad the node body to the next 4-byte boundary with
 *       0xFF);</li>
 *   <li>a valid header CRC.</li>
 * </ul>
 *
 * @param totalSize the size of the source region in bytes
 * @param firstNodeType the on-disk node type of the first node
 */
public record Jffs2Superblock(long totalSize, int firstNodeType) {

    /** Minimum region size needed to hold one node header. */
    public static final int MIN_SIZE = Jffs2Node.COMMON_HEADER_SIZE;

    /**
     * Reads and validates the first node header of the region.
     *
     * @param region the candidate region (a partition, logical volume, or a
     *        whole bare-filesystem image)
     * @return the validated header, or empty if the region does not start
     *         with a valid JFFS2 node
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<Jffs2Superblock> read(@NotNull DiskRegion region)
            throws IOException {
        long size = region.size();
        if (size < MIN_SIZE) {
            return Optional.empty();
        }

        ByteBuffer buf = region.read(0, MIN_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getShort(0) & 0xffff;
        if (magic != Jffs2Node.MAGIC) {
            return Optional.empty();
        }

        int nodetype = buf.getShort(2) & 0xffff;
        if (!Jffs2Node.isKnownNodeType(nodetype)) {
            return Optional.empty();
        }

        long totlen = buf.getInt(4) & 0xffffffffL;
        if (totlen < MIN_SIZE || totlen > size) {
            return Optional.empty();
        }

        // Header CRC over the first 8 bytes (magic, nodetype, totlen).
        byte[] header = new byte[8];
        buf.position(0);
        buf.get(header);
        int storedCrc = buf.getInt(8);
        if (storedCrc != Jffs2Node.crc32(header, 0, header.length)) {
            return Optional.empty();
        }

        return Optional.of(new Jffs2Superblock(size, nodetype));
    }

    /**
     * Returns the JFFS2 minimum erase block size (4 KiB). JFFS2 does not
     * record its erase block size on the medium; the minimum permitted by
     * the format is reported for informational purposes.
     *
     * @return 4096
     */
    public static int blockSize() {
        return SafeMath.safeToInt(4096);
    }
}
