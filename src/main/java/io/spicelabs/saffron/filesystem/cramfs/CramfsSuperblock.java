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
package io.spicelabs.saffron.filesystem.cramfs;

import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * cramfs superblock and format constants (mirrors the kernel
 * {@code include/uapi/linux/cramfs_fs.h}).
 *
 * <p>cramfs images are written in <em>host byte order</em> by mkcramfs; the
 * byte order is detected from the magic ({@code 0x28cd3d45} = little-endian,
 * {@code 0x453dcd28} = big-endian).
 *
 * @param bigEndian true if the image uses big-endian byte order
 * @param size filesystem size in bytes (meaningful when
 *        {@code FLAG_FSID_VERSION_2} is set)
 * @param flags feature flags
 * @param edition fsid edition
 * @param blocks fsid block count
 * @param files fsid file count
 * @param name user-defined filesystem name (16 bytes)
 * @param rootMode root inode mode
 * @param rootSize root directory size (bytes of sequential entries)
 * @param rootOffset root entries offset / 4 (0 = empty filesystem)
 */
public record CramfsSuperblock(
        boolean bigEndian,
        long size,
        int flags,
        int edition,
        long blocks,
        long files,
        @NotNull String name,
        int rootMode,
        long rootSize,
        long rootOffset) {

    public static final int MAGIC = 0x28cd3d45;
    public static final int MAGIC_WEND = 0x453dcd28;

    /** File data block size. */
    public static final int BLOCK_SIZE = 4096;

    /** Size of the on-disk superblock (including the root inode). */
    public static final int SUPERBLOCK_SIZE = 76;

    public static final int FLAG_FSID_V2 = 0x00000001;
    public static final int FLAG_SORTED_DIRS = 0x00000002;
    public static final int FLAG_HOLES = 0x00000100;
    public static final int FLAG_WRONG_SIGNATURE = 0x00000200;
    public static final int FLAG_SHIFTED_ROOT_OFFSET = 0x00000400;
    public static final int FLAG_EXT_BLOCK_POINTERS = 0x00000800;

    /**
     * OpenRG (Actiontec MI424WR era) extension: block size in bits 11..13
     * ({@code blockSize = 4096 << value}) and compression method in bits
     * 14..15 (0 = none, 1 = gzip, 2 = lzma). Not part of the mainline
     * format; images carrying these flags appear in OpenRG router firmware
     * ("cramfs-lzma").
     */
    public static final int FLAG_BLKSZ_MASK = 0x00003800;
    public static final int FLAG_BLKSZ_SHIFT = 11;
    public static final int FLAG_COMP_METHOD_MASK = 0x0000c000;
    public static final int FLAG_COMP_METHOD_SHIFT = 14;
    public static final int COMP_METHOD_NONE = 0;
    public static final int COMP_METHOD_GZIP = 1;
    public static final int COMP_METHOD_LZMA = 2;

    /** Feature flags the read-only implementation understands. */
    public static final int SUPPORTED_FLAGS = 0x000000ff | FLAG_HOLES
            | FLAG_WRONG_SIGNATURE | FLAG_SHIFTED_ROOT_OFFSET | FLAG_EXT_BLOCK_POINTERS
            | FLAG_BLKSZ_MASK | FLAG_COMP_METHOD_MASK;

    /** Block pointer flags (bits 30/31 of the pointer value). */
    public static final int BLK_FLAG_UNCOMPRESSED = 0x80000000;
    public static final int BLK_FLAG_DIRECT_PTR = 0x40000000;
    public static final int BLK_FLAGS = BLK_FLAG_UNCOMPRESSED | BLK_FLAG_DIRECT_PTR;

    /** Maximum name length (6-bit namelen * 4, minus padding). */
    public static final int MAX_NAME_LEN = 252;

    /** inode mode type bits. */
    public static final int S_IFMT = 0xf000;
    public static final int S_IFIFO = 0x1000;
    public static final int S_IFCHR = 0x2000;
    public static final int S_IFDIR = 0x4000;
    public static final int S_IFBLK = 0x6000;
    public static final int S_IFREG = 0x8000;
    public static final int S_IFLNK = 0xa000;
    public static final int S_IFSOCK = 0xc000;

    private static final byte[] SIGNATURE =
            "Compressed ROMFS".getBytes(StandardCharsets.US_ASCII);

    /**
     * Reads and validates the cramfs superblock from a region.
     *
     * @param region the candidate region (partition or bare image)
     * @return the validated superblock, or empty if not cramfs
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<CramfsSuperblock> read(@NotNull DiskRegion region)
            throws IOException {
        long regionSize = region.size();
        if (regionSize < SUPERBLOCK_SIZE) {
            return Optional.empty();
        }

        // The kernel accepts the superblock at offset 0 or offset 512 (some
        // images carry a 512-byte boot-sector prefix).
        long sbOffset = -1;
        boolean bigEndian = false;
        for (long candidate : new long[] {0, 512}) {
            if (regionSize < candidate + 4) {
                continue;
            }
            ByteBuffer head = region.read(candidate, 4);
            head.order(ByteOrder.LITTLE_ENDIAN);
            int leMagic = head.getInt(0);
            if (leMagic == MAGIC) {
                sbOffset = candidate;
                bigEndian = false;
                break;
            } else if (leMagic == MAGIC_WEND) {
                sbOffset = candidate;
                bigEndian = true;
                break;
            }
        }
        if (sbOffset < 0 || regionSize < sbOffset + SUPERBLOCK_SIZE) {
            return Optional.empty();
        }

        ByteBuffer sb = region.read(sbOffset, SUPERBLOCK_SIZE);
        sb.order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

        long size = sb.getInt(4) & 0xffffffffL;
        int flags = sb.getInt(8);

        // Signature: accept a wrong signature only with the WRONG_SIGNATURE
        // flag (some historical mkcramfs versions wrote a different string).
        byte[] sig = new byte[16];
        sb.position(16);
        sb.get(sig);
        boolean signatureOk = java.util.Arrays.equals(sig, SIGNATURE);
        if (!signatureOk && (flags & FLAG_WRONG_SIGNATURE) == 0) {
            return Optional.empty();
        }

        if ((flags & ~SUPPORTED_FLAGS) != 0) {
            return Optional.empty();
        }

        // fsid
        int edition = sb.getInt(36);
        long blocks = sb.getInt(40) & 0xffffffffL;
        long files = sb.getInt(44) & 0xffffffffL;

        byte[] nameBytes = new byte[16];
        sb.position(48);
        sb.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        int nul = name.indexOf('\0');
        if (nul >= 0) {
            name = name.substring(0, nul);
        }

        // Root inode (bitfields pack low-bits-first on little-endian images
        // and high-bits-first on big-endian images).
        long rootWord0 = sb.getInt(64) & 0xffffffffL;
        long rootWord1 = sb.getInt(68) & 0xffffffffL;
        long rootWord2 = sb.getInt(72) & 0xffffffffL;
        int rootMode;
        long rootSize;
        long rootOffset;
        if (bigEndian) {
            rootMode = (int) (rootWord0 >>> 16);
            rootSize = (rootWord1 >>> 8) & 0xffffffL;
            rootOffset = rootWord2 & 0x3ffffffL;
        } else {
            rootMode = (int) (rootWord0 & 0xffff);
            rootSize = rootWord1 & 0xffffffL;
            rootOffset = (rootWord2 >> 6) & 0x3ffffffL;
        }

        // Empty filesystem (root offset 0) or a directory root.
        if (rootOffset != 0 && (rootMode & S_IFMT) != S_IFDIR) {
            return Optional.empty();
        }

        // Root offset sanity (kernel parity) unless SHIFTED_ROOT_OFFSET.
        // The stored field is in units of 4 bytes.
        long rootEntriesOffset = SafeMath.safeMultiply(rootOffset, 4);
        if (rootOffset != 0 && (flags & FLAG_SHIFTED_ROOT_OFFSET) == 0
                && rootEntriesOffset != SUPERBLOCK_SIZE
                && rootEntriesOffset != 512 + SUPERBLOCK_SIZE) {
            return Optional.empty();
        }

        long effectiveSize = (flags & FLAG_FSID_V2) != 0 ? size : regionSize;
        if (effectiveSize <= 0 || effectiveSize > regionSize) {
            return Optional.empty();
        }

        return Optional.of(new CramfsSuperblock(
                bigEndian, effectiveSize, flags, edition, blocks, files,
                name, rootMode, rootSize, rootOffset));
    }

    /** The block size of the filesystem (fixed 4096). */
    public static int blockSize() {
        return SafeMath.safeToInt(BLOCK_SIZE);
    }

    /**
     * Returns true when the image uses the OpenRG flag extension. The
     * extension is active when the compression-method bits are set, or when
     * block-size bits other than bit 11 alone are set: bit 11 (0x800)
     * overlaps the mainline {@link #FLAG_EXT_BLOCK_POINTERS} flag.
     */
    public boolean isOpenRG() {
        return (flags & FLAG_COMP_METHOD_MASK) != 0
                || ((flags & FLAG_BLKSZ_MASK) != 0
                    && (flags & FLAG_BLKSZ_MASK) != FLAG_EXT_BLOCK_POINTERS);
    }

    /**
     * Returns the data block size from the OpenRG extension flags: bits
     * 11..13 hold a shift value ({@code 4096 << value}); mainline images
     * use the standard 4096.
     */
    public long blockSizeFromFlags() {
        if (!isOpenRG()) {
            return BLOCK_SIZE;
        }
        return 4096L << ((flags & FLAG_BLKSZ_MASK) >>> FLAG_BLKSZ_SHIFT);
    }

    /**
     * Returns the compression method from the OpenRG extension flags:
     * 0 = none, 1 = gzip/zlib, 2 = lzma; mainline images use zlib (1).
     */
    public int compressionMethod() {
        if (!isOpenRG()) {
            return COMP_METHOD_GZIP;
        }
        return (flags & FLAG_COMP_METHOD_MASK) >>> FLAG_COMP_METHOD_SHIFT;
    }

    /**
     * Returns the compression method name for reporting.
     */
    public @NotNull String compressionName() {
        return switch (compressionMethod()) {
            case COMP_METHOD_NONE -> "none";
            case COMP_METHOD_LZMA -> "lzma";
            default -> "zlib";
        };
    }

    /** Returns the byte offset of the root directory entries. */
    public long rootEntriesOffset() {
        return SafeMath.safeMultiply(rootOffset, 4);
    }
}
