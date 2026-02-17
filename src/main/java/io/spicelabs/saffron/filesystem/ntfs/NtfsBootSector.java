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
package io.spicelabs.saffron.filesystem.ntfs;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents the NTFS boot sector (Volume Boot Record).
 *
 * <p>The boot sector is located at the first sector of the NTFS volume
 * and contains critical filesystem parameters.
 *
 * <p>Boot sector structure:
 * <pre>
 * Offset  Size  Description
 * 0       3     Jump instruction
 * 3       8     OEM ID ("NTFS    ")
 * 11      2     Bytes per sector
 * 13      1     Sectors per cluster
 * 40      8     Total sectors
 * 48      8     $MFT cluster number
 * 56      8     $MFTMirr cluster number
 * 64      4     Clusters per MFT record (signed, negative = 2^|n| bytes)
 * 68      4     Clusters per index record (signed)
 * 72      8     Volume serial number
 * </pre>
 */
public record NtfsBootSector(
        int bytesPerSector,
        int sectorsPerCluster,
        long totalSectors,
        long mftClusterNumber,
        long mftMirrClusterNumber,
        int clustersPerMftRecord,
        int clustersPerIndexRecord,
        long volumeSerialNumber
) {

    /** NTFS OEM ID */
    public static final String OEM_ID = "NTFS";

    /** Boot sector size */
    public static final int BOOT_SECTOR_SIZE = 512;

    /**
     * Reads the NTFS boot sector from the specified offset.
     *
     * @param disk the virtual disk to read from
     * @param partitionOffset the byte offset where the partition starts
     * @return the parsed boot sector
     * @throws IOException if an I/O error occurs or OEM ID is invalid
     */
    public static @NotNull NtfsBootSector read(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return read(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Reads the NTFS boot sector from a DiskRegion.
     *
     * @param region the disk region containing the filesystem
     * @return the parsed boot sector
     * @throws IOException if an I/O error occurs or OEM ID is invalid
     */
    public static @NotNull NtfsBootSector read(@NotNull DiskRegion region) throws IOException {
        ByteBuffer boot = region.read(0, BOOT_SECTOR_SIZE);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        // Check OEM ID at offset 3
        byte[] oemBytes = new byte[8];
        boot.position(3);
        boot.get(oemBytes);
        String oemId = new String(oemBytes).trim();
        if (!oemId.equals(OEM_ID)) {
            throw new IOException("Invalid NTFS OEM ID: " + oemId);
        }

        // Parse BPB
        int bytesPerSector = boot.getShort(11) & 0xFFFF;
        int sectorsPerCluster = boot.get(13) & 0xFF;
        long totalSectors = boot.getLong(40);
        long mftCluster = boot.getLong(48);
        long mftMirrCluster = boot.getLong(56);

        // Clusters per MFT record (signed byte - can be negative for byte size)
        int clustersPerMftRecord = boot.get(64);

        // Clusters per index record (signed byte)
        int clustersPerIndexRecord = boot.get(68);

        // Volume serial number
        long serialNumber = boot.getLong(72);

        return new NtfsBootSector(
                bytesPerSector,
                sectorsPerCluster,
                totalSectors,
                mftCluster,
                mftMirrCluster,
                clustersPerMftRecord,
                clustersPerIndexRecord,
                serialNumber
        );
    }

    /**
     * Returns the cluster size in bytes.
     */
    public int clusterSize() {
        return bytesPerSector * sectorsPerCluster;
    }

    /**
     * Returns the total volume size in bytes.
     */
    public long totalSizeBytes() {
        return totalSectors * bytesPerSector;
    }

    /**
     * Returns the MFT record size in bytes.
     */
    public int mftRecordSize() {
        if (clustersPerMftRecord > 0) {
            return clustersPerMftRecord * clusterSize();
        } else {
            // Negative value means 2^|n| bytes
            return 1 << (-clustersPerMftRecord);
        }
    }

    /**
     * Returns the index record size in bytes.
     */
    public int indexRecordSize() {
        if (clustersPerIndexRecord > 0) {
            return clustersPerIndexRecord * clusterSize();
        } else {
            return 1 << (-clustersPerIndexRecord);
        }
    }

    /**
     * Returns the MFT start offset in bytes.
     */
    public long mftOffsetBytes() {
        return mftClusterNumber * clusterSize();
    }

    /**
     * Returns the volume serial number as a formatted string.
     */
    public @NotNull String serialNumberString() {
        return String.format("%04X-%04X",
                (int) ((volumeSerialNumber >> 16) & 0xFFFF),
                (int) (volumeSerialNumber & 0xFFFF));
    }

    /**
     * Returns the UUID (volume serial in hex format).
     */
    public @NotNull String uuid() {
        return String.format("%016X", volumeSerialNumber);
    }
}
