/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import io.spicelabs.saffron.lvm.DiskRegion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents a Btrfs superblock.
 *
 * <p>The primary superblock is located at offset 65536 (64 KB) from the start
 * of the filesystem. Backup copies exist at 64 MB and 256 GB.
 */
public record BtrfsSuperblock(
        byte[] csum,              // 32 bytes: checksum
        byte[] fsid,              // 16 bytes: filesystem UUID
        long bytenr,              // Physical address of this superblock
        long flags,               // Superblock flags
        long generation,          // Transaction generation
        long rootTreeRoot,        // Logical address of root tree root
        long chunkTreeRoot,       // Logical address of chunk tree root
        long logTreeRoot,         // Logical address of log tree root
        long logRootTransid,      // Log root transaction ID
        long totalBytes,          // Total filesystem size
        long bytesUsed,           // Bytes used
        long rootDirObjectId,     // Root directory object ID
        long numDevices,          // Number of devices
        int sectorSize,           // Sector size (typically 4096)
        int nodeSize,             // Node size (typically 16384)
        int leafSize,             // Leaf size (same as node size)
        int stripeSize,           // Stripe size
        int sysChunkArraySize,    // Size of embedded system chunk array
        long chunkRootGeneration, // Chunk root generation
        long compatFlags,         // Compatible feature flags
        long compatRoFlags,       // Compatible read-only flags
        long incompatFlags,       // Incompatible feature flags
        int csumType,             // Checksum type (0=CRC32C)
        byte rootLevel,           // Root tree level
        byte chunkRootLevel,      // Chunk tree level
        byte logRootLevel,        // Log tree level
        long cacheGeneration,     // Cache generation
        long uuidTreeGeneration,  // UUID tree generation
        String label,             // Volume label
        byte[] sysChunkArray      // Embedded system chunk array for bootstrap
) {

    /** Superblock offset from partition start. */
    public static final long SUPERBLOCK_OFFSET = 65536;

    /** Btrfs magic bytes: "_BHRfS_M" */
    public static final byte[] MAGIC = "_BHRfS_M".getBytes(StandardCharsets.US_ASCII);

    /** Offset of magic within superblock. */
    public static final int MAGIC_OFFSET = 64;

    /** Superblock size to read. */
    public static final int SUPERBLOCK_SIZE = 4096;

    /**
     * Reads the superblock from the given disk region.
     *
     * @param region the disk region to read from
     * @param partitionOffset offset of the partition containing the filesystem
     * @return the parsed superblock
     * @throws IOException if an I/O error occurs or the superblock is invalid
     */
    public static BtrfsSuperblock read(DiskRegion region, long partitionOffset) throws IOException {
        ByteBuffer buf = region.read(partitionOffset + SUPERBLOCK_OFFSET, SUPERBLOCK_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Check magic
        byte[] magic = new byte[8];
        buf.position(MAGIC_OFFSET);
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Invalid Btrfs magic: " + new String(magic, StandardCharsets.US_ASCII));
        }

        // Parse fields
        buf.position(0);
        byte[] csum = new byte[32];
        buf.get(csum);

        byte[] fsid = new byte[16];
        buf.get(fsid);

        long bytenr = buf.getLong();
        long flags = buf.getLong();

        // Skip magic (already validated)
        buf.position(buf.position() + 8);

        long generation = buf.getLong();
        long rootTreeRoot = buf.getLong();
        long chunkTreeRoot = buf.getLong();
        long logTreeRoot = buf.getLong();
        long logRootTransid = buf.getLong();
        long totalBytes = buf.getLong();
        long bytesUsed = buf.getLong();
        long rootDirObjectId = buf.getLong();
        long numDevices = buf.getLong();

        int sectorSize = buf.getInt();
        int nodeSize = buf.getInt();
        int leafSize = buf.getInt();
        int stripeSize = buf.getInt();
        int sysChunkArraySize = buf.getInt();

        long chunkRootGeneration = buf.getLong();
        long compatFlags = buf.getLong();
        long compatRoFlags = buf.getLong();
        long incompatFlags = buf.getLong();

        int csumType = buf.getShort() & 0xFFFF;

        byte rootLevel = buf.get();
        byte chunkRootLevel = buf.get();
        byte logRootLevel = buf.get();

        // Skip dev_item (98 bytes at offset 0xC3)
        buf.position(0xC3 + 98);

        // Label at offset 0x12B (299), 256 bytes
        buf.position(0x12B);
        byte[] labelBytes = new byte[256];
        buf.get(labelBytes);
        int labelLen = 0;
        while (labelLen < labelBytes.length && labelBytes[labelLen] != 0) {
            labelLen++;
        }
        String label = new String(labelBytes, 0, labelLen, StandardCharsets.UTF_8);

        // Cache generation at offset 0x22B (555)
        buf.position(0x22B);
        long cacheGeneration = buf.getLong();
        long uuidTreeGeneration = buf.getLong();

        // System chunk array at offset 0x32B (811)
        buf.position(0x32B);
        byte[] sysChunkArray = new byte[Math.min(sysChunkArraySize, 2048)];
        buf.get(sysChunkArray);

        return new BtrfsSuperblock(
                csum, fsid, bytenr, flags, generation,
                rootTreeRoot, chunkTreeRoot, logTreeRoot, logRootTransid,
                totalBytes, bytesUsed, rootDirObjectId, numDevices,
                sectorSize, nodeSize, leafSize, stripeSize, sysChunkArraySize,
                chunkRootGeneration, compatFlags, compatRoFlags, incompatFlags,
                csumType, rootLevel, chunkRootLevel, logRootLevel,
                cacheGeneration, uuidTreeGeneration, label, sysChunkArray
        );
    }

    /**
     * Checks if a Btrfs superblock exists at the given offset.
     */
    public static boolean isBtrfs(DiskRegion region, long partitionOffset) throws IOException {
        if (partitionOffset + SUPERBLOCK_OFFSET + MAGIC_OFFSET + 8 > region.size()) {
            return false;
        }
        ByteBuffer buf = region.read(partitionOffset + SUPERBLOCK_OFFSET + MAGIC_OFFSET, 8);
        byte[] magic = new byte[8];
        buf.get(magic);
        return Arrays.equals(magic, MAGIC);
    }

    /**
     * Returns the filesystem UUID as a formatted string.
     */
    public String uuid() {
        return String.format(
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                fsid[0] & 0xFF, fsid[1] & 0xFF, fsid[2] & 0xFF, fsid[3] & 0xFF,
                fsid[4] & 0xFF, fsid[5] & 0xFF,
                fsid[6] & 0xFF, fsid[7] & 0xFF,
                fsid[8] & 0xFF, fsid[9] & 0xFF,
                fsid[10] & 0xFF, fsid[11] & 0xFF, fsid[12] & 0xFF,
                fsid[13] & 0xFF, fsid[14] & 0xFF, fsid[15] & 0xFF
        );
    }

    /**
     * Returns the free space in bytes.
     */
    public long freeBytes() {
        return totalBytes - bytesUsed;
    }
}
