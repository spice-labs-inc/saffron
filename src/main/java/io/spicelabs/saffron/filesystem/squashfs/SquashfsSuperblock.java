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
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

public record SquashfsSuperblock(
        long magic,
        int inodeCount,
        long modificationTime,
        int blockSize,
        int fragmentEntryCount,
        int compressionId,
        int blockLog,
        int flags,
        int idCount,
        int versionMajor,
        int versionMinor,
        long rootInodeRef,
        long bytesUsed,
        long idTableStart,
        long xattrIdTableStart,
        long inodeTableStart,
        long directoryTableStart,
        long fragmentTableStart,
        long exportTableStart,
        long imageSize
) {

    public static final int SQUASHFS_MAGIC = 0x73717368;
    public static final int SUPERBLOCK_SIZE = 0x60;

    public static final int FLAG_UNCOMPRESSED_INODES = 0x0001;
    public static final int FLAG_UNCOMPRESSED_DATA = 0x0002;
    public static final int FLAG_UNCOMPRESSED_FRAGMENTS = 0x0008;
    public static final int FLAG_NO_FRAGMENTS = 0x0010;
    public static final int FLAG_ALWAYS_FRAGMENTS = 0x0020;
    public static final int FLAG_EXPORTABLE = 0x0080;
    public static final int FLAG_COMPRESSOR_OPTIONS = 0x0400;
    public static final int FLAG_UNCOMPRESSED_IDS = 0x0800;

    public static @NotNull Optional<SquashfsSuperblock> read(@NotNull DiskRegion region) throws IOException {
        long size = region.size();
        if (size < SUPERBLOCK_SIZE) {
            return Optional.empty();
        }

        ByteBuffer buf = region.read(0, SUPERBLOCK_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt(0);
        if (magic != SQUASHFS_MAGIC) {
            return Optional.empty();
        }

        int inodeCount = buf.getInt(4);
        if (inodeCount < 0) {
            return Optional.empty();
        }

        long modificationTime = buf.getInt(8) & 0xffffffffL;
        int blockSize = buf.getInt(0x0c);
        int fragmentEntryCount = buf.getInt(0x10);
        int compressionId = buf.getShort(0x14) & 0xffff;
        int blockLog = buf.getShort(0x16) & 0xffff;
        int flags = buf.getShort(0x18) & 0xffff;
        int idCount = buf.getShort(0x1a) & 0xffff;
        int versionMajor = buf.getShort(0x1c) & 0xffff;
        int versionMinor = buf.getShort(0x1e) & 0xffff;
        long rootInodeRef = buf.getLong(0x20);
        long bytesUsed = buf.getLong(0x28);
        long idTableStart = buf.getLong(0x30);
        long xattrIdTableStart = buf.getLong(0x38);
        long inodeTableStart = buf.getLong(0x40);
        long directoryTableStart = buf.getLong(0x48);
        long fragmentTableStart = buf.getLong(0x50);
        long exportTableStart = buf.getLong(0x58);

        if (versionMajor != 4) {
            return Optional.empty();
        }

        if (blockLog < 12 || blockLog > 20 || (1L << blockLog) != (blockSize & 0xffffffffL)) {
            return Optional.empty();
        }

        if (inodeCount == 0 || inodeCount > bytesUsed) {
            return Optional.empty();
        }

        if (bytesUsed <= 0 || bytesUsed > size) {
            return Optional.empty();
        }

        if (inodeTableStart < SUPERBLOCK_SIZE || inodeTableStart > bytesUsed
                || directoryTableStart < SUPERBLOCK_SIZE || directoryTableStart > bytesUsed
                || fragmentTableStart < SUPERBLOCK_SIZE || fragmentTableStart > bytesUsed
                || idTableStart < SUPERBLOCK_SIZE || idTableStart > bytesUsed) {
            return Optional.empty();
        }
        if (exportTableStart != 0xffffffffffffffffL
                && (exportTableStart < SUPERBLOCK_SIZE || exportTableStart > bytesUsed)) {
            return Optional.empty();
        }
        if (directoryTableStart < inodeTableStart || fragmentTableStart < directoryTableStart) {
            return Optional.empty();
        }
        if (idTableStart + 8L > bytesUsed) {
            return Optional.empty();
        }

        return Optional.of(new SquashfsSuperblock(
                magic & 0xffffffffL,
                inodeCount,
                modificationTime,
                blockSize,
                fragmentEntryCount,
                compressionId,
                blockLog,
                flags,
                idCount,
                versionMajor,
                versionMinor,
                rootInodeRef,
                bytesUsed,
                idTableStart,
                xattrIdTableStart,
                inodeTableStart,
                directoryTableStart,
                fragmentTableStart,
                exportTableStart,
                size
        ));
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public boolean hasFragments() {
        return !hasFlag(FLAG_NO_FRAGMENTS);
    }

    public boolean hasCompressorOptions() {
        return hasFlag(FLAG_COMPRESSOR_OPTIONS);
    }

    public boolean uncompressedIds() {
        return hasFlag(FLAG_UNCOMPRESSED_IDS) || hasFlag(FLAG_UNCOMPRESSED_INODES);
    }

    public @NotNull String compressionName() {
        return switch (compressionId) {
            case 0 -> "none";
            case 1 -> "gzip";
            case 2 -> "lzma";
            case 3 -> "lzo";
            case 4 -> "xz";
            case 5 -> "lz4";
            case 6 -> "zstd";
            default -> "unknown";
        };
    }
}
