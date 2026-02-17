/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.hfsplus;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the HFS+ Volume Header, located at offset 1024 from the start of the volume.
 *
 * <p>The volume header is 512 bytes and contains critical filesystem metadata including
 * the block size, extent records for special files (catalog, extents overflow, allocation),
 * and volume statistics.
 *
 * <p>HFS+ is big-endian.
 */
public record HfsPlusVolumeHeader(
        short signature,
        short version,
        int attributes,
        int blockSize,
        long totalBlocks,
        long freeBlocks,
        int fileCount,
        int folderCount,
        List<HfsPlusExtent> catalogExtents,
        List<HfsPlusExtent> extentsOverflowExtents,
        List<HfsPlusExtent> allocationExtents,
        List<HfsPlusExtent> attributesExtents,
        long catalogLogicalSize,
        long extentsOverflowLogicalSize,
        long allocationLogicalSize,
        long attributesLogicalSize,
        int catalogClumpSize,
        int extentsClumpSize,
        byte[] finderInfo
) {
    /** HFS+ signature "H+" */
    public static final short HFSPLUS_SIGNATURE = 0x482B;
    /** HFSX signature "HX" (case-sensitive variant) */
    public static final short HFSX_SIGNATURE = 0x4858;

    /** Offset of volume header from partition start */
    public static final int VOLUME_HEADER_OFFSET = 1024;
    /** Size of volume header */
    public static final int VOLUME_HEADER_SIZE = 512;

    /**
     * Reads the volume header from a disk region.
     */
    public static @NotNull HfsPlusVolumeHeader read(@NotNull DiskRegion region) throws IOException {
        ByteBuffer buf = region.read(VOLUME_HEADER_OFFSET, VOLUME_HEADER_SIZE);
        buf.order(ByteOrder.BIG_ENDIAN);

        short signature = buf.getShort(0);
        if (signature != HFSPLUS_SIGNATURE && signature != HFSX_SIGNATURE) {
            throw new IOException("Invalid HFS+ signature: 0x" + Integer.toHexString(signature & 0xFFFF));
        }

        short version = buf.getShort(2);
        int attributes = buf.getInt(4);

        // Block size at offset 40, total blocks at offset 44 (uint32), free blocks at 48 (uint32)
        int blockSize = buf.getInt(40);
        long totalBlocks = buf.getInt(44) & 0xFFFFFFFFL;
        long freeBlocks = buf.getInt(48) & 0xFFFFFFFFL;

        // File count at offset 32, folder count at offset 36
        int fileCount = buf.getInt(32);
        int folderCount = buf.getInt(36);

        // finderInfo at offset 80 (32 bytes, 8 uint32 fields)
        byte[] finderInfo = new byte[32];
        buf.position(80);
        buf.get(finderInfo);

        // Special file fork records (HFSPlusForkData, 80 bytes each):
        // logicalSize(8) + clumpSize(4) + totalBlocks(4) + 8 extents(8 bytes each = 64)
        // Allocation file at offset 112
        long allocationLogicalSize = buf.getLong(112);
        int allocationClumpSize = buf.getInt(120);
        List<HfsPlusExtent> allocationExtents = readExtents(buf, 128);

        // Extents overflow file at offset 192
        long extentsOverflowLogicalSize = buf.getLong(192);
        int extentsClumpSize = buf.getInt(200);
        List<HfsPlusExtent> extentsOverflowExtents = readExtents(buf, 208);

        // Catalog file at offset 272
        long catalogLogicalSize = buf.getLong(272);
        int catalogClumpSize = buf.getInt(280);
        List<HfsPlusExtent> catalogExtents = readExtents(buf, 288);

        // Attributes file at offset 352
        long attributesLogicalSize = buf.getLong(352);
        List<HfsPlusExtent> attributesExtents = readExtents(buf, 368);

        return new HfsPlusVolumeHeader(
                signature, version, attributes, blockSize, totalBlocks, freeBlocks,
                fileCount, folderCount,
                catalogExtents, extentsOverflowExtents, allocationExtents, attributesExtents,
                catalogLogicalSize, extentsOverflowLogicalSize, allocationLogicalSize, attributesLogicalSize,
                catalogClumpSize, extentsClumpSize, finderInfo
        );
    }

    /**
     * Reads 8 extent descriptors from the buffer at the given offset.
     * Each extent is 8 bytes: startBlock (4) + blockCount (4).
     */
    private static List<HfsPlusExtent> readExtents(ByteBuffer buf, int offset) {
        List<HfsPlusExtent> extents = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int startBlock = buf.getInt(offset + i * 8);
            int blockCount = buf.getInt(offset + i * 8 + 4);
            if (blockCount > 0) {
                extents.add(new HfsPlusExtent(startBlock & 0xFFFFFFFFL, blockCount & 0xFFFFFFFFL));
            }
        }
        return extents;
    }

    public boolean isHfsx() {
        return signature == HFSX_SIGNATURE;
    }

    public long totalSize() {
        return totalBlocks * blockSize;
    }

    /**
     * Returns the volume UUID derived from finderInfo[6] and finderInfo[7] (bytes 24-31).
     * Returns empty if the UUID bytes are all zeros.
     */
    public java.util.Optional<String> volumeUuid() {
        boolean allZero = true;
        for (int i = 24; i < 32; i++) {
            if (finderInfo[i] != 0) { allZero = false; break; }
        }
        if (allZero) return java.util.Optional.empty();
        return java.util.Optional.of(String.format(
                "%02X%02X%02X%02X-%02X%02X%02X%02X",
                finderInfo[24] & 0xFF, finderInfo[25] & 0xFF, finderInfo[26] & 0xFF, finderInfo[27] & 0xFF,
                finderInfo[28] & 0xFF, finderInfo[29] & 0xFF, finderInfo[30] & 0xFF, finderInfo[31] & 0xFF));
    }
}
