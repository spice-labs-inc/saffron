/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.apfs;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the APFS Container Superblock (nx_superblock_t).
 *
 * <p>Located at block 0 of the container. Contains the block size,
 * object map OID, and list of volume OIDs.
 *
 * <p>Layout after obj_phys_t header (32 bytes):
 * <pre>
 * Offset  Size  Description
 * 32      4     nx_magic ("NXSB")
 * 36      4     nx_block_size
 * 40      8     nx_block_count
 * ...
 * 152     8     nx_spaceman_oid
 * 160     8     nx_omap_oid (physical OID of container object map)
 * 168     8     nx_reaper_oid
 * 176     4     nx_test_type
 * 180     4     nx_max_file_systems
 * 184     ...   nx_fs_oid[] (array of volume OIDs, virtual)
 * </pre>
 */
public record ApfsContainerSuperblock(
        ApfsObjectHeader header,
        int blockSize,
        long blockCount,
        long omapOid,
        List<Long> volumeOids,
        long xid,
        byte[] containerUuid
) {
    public static final int MAGIC = 0x4253584E; // "NXSB" in little-endian

    /**
     * Reads the container superblock from block 0.
     */
    public static @NotNull ApfsContainerSuperblock read(@NotNull DiskRegion region) throws IOException {
        // Read enough for the superblock (first 4096 bytes should be sufficient for most block sizes)
        int readSize = Math.min(4096, (int) Math.min(region.size(), 4096));
        ByteBuffer buf = region.read(0, readSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        ApfsObjectHeader objHeader = ApfsObjectHeader.parse(buf);

        int magic = buf.getInt(32);
        if (magic != MAGIC) {
            throw new IOException("Invalid APFS container magic: 0x" + Integer.toHexString(magic));
        }

        int blockSize = buf.getInt(36);
        long blockCount = buf.getLong(40);

        // Container UUID at offset 72 (16 bytes)
        byte[] containerUuid = new byte[16];
        buf.position(72);
        buf.get(containerUuid);

        long omapOid = buf.getLong(160);

        // nx_max_file_systems at offset 180, nx_fs_oid[] at offset 184
        int maxFileSystems = buf.getInt(180);
        if (maxFileSystems > 100) maxFileSystems = 100; // Safety limit

        List<Long> volumeOids = new ArrayList<>();
        for (int i = 0; i < maxFileSystems; i++) {
            int offset = 184 + i * 8;
            if (offset + 8 > readSize) break;
            long oid = buf.getLong(offset);
            if (oid != 0) {
                volumeOids.add(oid);
            }
        }

        return new ApfsContainerSuperblock(objHeader, blockSize, blockCount, omapOid,
                volumeOids, objHeader.xid(), containerUuid);
    }

    /**
     * Returns the container UUID as a formatted string, or empty if all zeros.
     */
    public @NotNull java.util.Optional<String> uuid() {
        boolean allZero = true;
        for (byte b : containerUuid) {
            if (b != 0) { allZero = false; break; }
        }
        if (allZero) return java.util.Optional.empty();
        return java.util.Optional.of(String.format(
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                containerUuid[0] & 0xFF, containerUuid[1] & 0xFF, containerUuid[2] & 0xFF, containerUuid[3] & 0xFF,
                containerUuid[4] & 0xFF, containerUuid[5] & 0xFF,
                containerUuid[6] & 0xFF, containerUuid[7] & 0xFF,
                containerUuid[8] & 0xFF, containerUuid[9] & 0xFF,
                containerUuid[10] & 0xFF, containerUuid[11] & 0xFF, containerUuid[12] & 0xFF, containerUuid[13] & 0xFF, containerUuid[14] & 0xFF, containerUuid[15] & 0xFF));
    }
}
