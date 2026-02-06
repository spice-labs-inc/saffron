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
import java.nio.charset.StandardCharsets;

/**
 * Represents an APFS Volume Superblock (apfs_superblock_t / fs_phys_t).
 *
 * <p>Each volume within an APFS container has its own superblock, resolved
 * through the container's object map from the volume OID.
 *
 * <p>Layout after obj_phys_t header (32 bytes):
 * <pre>
 * Offset  Size  Description
 * 32      4     apfs_magic ("APSB")
 * 36      4     apfs_fs_index
 * 40      8     apfs_features
 * 48      8     apfs_readonly_compatible_features
 * 56      8     apfs_incompatible_features
 * 64      8     apfs_unmount_time
 * 72      8     apfs_fs_reserve_block_count
 * 80      8     apfs_fs_quota_block_count
 * 88      8     apfs_fs_alloc_count
 * 96      20    apfs_meta_crypto (wrapped_meta_crypto_state_t)
 * 116     4     apfs_root_tree_type
 * 120     4     apfs_extentref_tree_type
 * 124     4     apfs_snap_meta_tree_type
 * 128     8     apfs_omap_oid (virtual OID of volume's own object map)
 * 136     8     apfs_root_tree_oid (virtual OID of filesystem B-tree root)
 * ...
 * 704     256   apfs_volname (null-terminated UTF-8 volume name)
 * </pre>
 */
public record ApfsVolumeSuperblock(
        ApfsObjectHeader header,
        int fsIndex,
        long omapOid,
        long rootTreeOid,
        String volumeName,
        long xid,
        long allocCount
) {
    public static final int MAGIC = 0x42535041; // "APSB" in little-endian

    /**
     * Reads a volume superblock from a physical block.
     */
    public static @NotNull ApfsVolumeSuperblock read(@NotNull DiskRegion region, int blockSize,
                                                       long physicalBlock) throws IOException {
        ByteBuffer buf = region.read(physicalBlock * blockSize, blockSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        ApfsObjectHeader objHeader = ApfsObjectHeader.parse(buf);

        int magic = buf.getInt(32);
        if (magic != MAGIC) {
            throw new IOException("Invalid APFS volume magic: 0x" + Integer.toHexString(magic));
        }

        int fsIndex = buf.getInt(36);
        long allocCount = buf.getLong(88);
        long omapOid = buf.getLong(128);
        long rootTreeOid = buf.getLong(136);

        // Volume name at offset 704, null-terminated UTF-8, max 256 bytes
        byte[] nameBytes = new byte[256];
        buf.position(704);
        buf.get(nameBytes);
        int nameLen = 0;
        while (nameLen < nameBytes.length && nameBytes[nameLen] != 0) {
            nameLen++;
        }
        String volumeName = nameLen > 0 ? new String(nameBytes, 0, nameLen, StandardCharsets.UTF_8) : "Untitled";

        return new ApfsVolumeSuperblock(objHeader, fsIndex, omapOid, rootTreeOid,
                volumeName, objHeader.xid(), allocCount);
    }
}
