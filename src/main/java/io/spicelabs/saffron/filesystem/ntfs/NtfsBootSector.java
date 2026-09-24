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

    /** Smallest cluster NTFS can have (one 512-byte sector). */
    public static final int MIN_CLUSTER_SIZE = 512;

    /** Largest cluster NTFS can have (2 MiB, Windows 10 1709+ / ntfs-3g 2021+). */
    public static final int MAX_CLUSTER_SIZE = 2 * 1024 * 1024;

    /**
     * Decodes the sectors-per-cluster byte at BPB offset 13. Values up to
     * 0x80 are the literal count; larger values are a negative signed byte
     * {@code n} meaning {@code 2^(-n)} sectors per cluster (0xF8 = -8 = 256
     * sectors; 0xF4 = -12 = 4096 sectors = 2 MiB at 512-byte sectors). The
     * result is not validated here; callers check the derived cluster size.
     *
     * @param raw the unsigned byte value (0..255)
     * @return the sectors per cluster, or 0 for an undecodable value
     */
    public static int decodeSectorsPerCluster(int raw) {
        if (raw <= 0x80) {
            return raw;
        }
        int shift = 256 - raw;          // -n for the signed byte n
        return shift <= 30 ? 1 << shift : 0;
    }

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
        int sectorsPerClusterRaw = boot.get(13) & 0xFF;
        long totalSectors = boot.getLong(40);
        long mftCluster = boot.getLong(48);
        long mftMirrCluster = boot.getLong(56);

        // Validate BPB before any derived arithmetic (clusterSize() is
        // used for division and multiplication throughout the driver).
        if (bytesPerSector < 512 || bytesPerSector > 4096
                || (bytesPerSector & (bytesPerSector - 1)) != 0) {
            throw new IOException("Invalid NTFS bytes per sector: " + bytesPerSector);
        }
        int sectorsPerCluster = decodeSectorsPerCluster(sectorsPerClusterRaw);
        long clusterBytes = (long) bytesPerSector * sectorsPerCluster;
        if (sectorsPerCluster < 1 || (sectorsPerCluster & (sectorsPerCluster - 1)) != 0
                || clusterBytes < MIN_CLUSTER_SIZE || clusterBytes > MAX_CLUSTER_SIZE) {
            throw new IOException("Invalid NTFS cluster size: " + clusterBytes
                    + " bytes (sectorsPerCluster byte 0x"
                    + Integer.toHexString(sectorsPerClusterRaw) + ")");
        }

        // Clusters per MFT record (signed byte - can be negative for byte size)
        int clustersPerMftRecord = boot.get(64);

        // Clusters per index record (signed byte)
        int clustersPerIndexRecord = boot.get(68);

        // Validate the derived MFT and index record sizes before they are
        // used for allocations (2^|n| for negative n can overflow int, and
        // a positive count times a 2 MiB cluster is well beyond any real
        // record size).
        long mftRecordSize = decodeRecordSize(clustersPerMftRecord, clusterBytes);
        if (mftRecordSize < 256 || mftRecordSize > 1024 * 1024) {
            throw new IOException("Invalid NTFS MFT record size: " + mftRecordSize
                    + " (clustersPerMftRecord=" + clustersPerMftRecord + ")");
        }
        // Zero (unset, seen in hand-made boot sectors) means "unknown" and
        // falls back to the standard 4 KiB in indexRecordSize().
        if (clustersPerIndexRecord != 0) {
            long indexRecordSize = decodeRecordSize(clustersPerIndexRecord, clusterBytes);
            if (indexRecordSize < 256 || indexRecordSize > 1024 * 1024) {
                throw new IOException("Invalid NTFS index record size: " + indexRecordSize
                        + " (clustersPerIndexRecord=" + clustersPerIndexRecord + ")");
            }
        }

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
     * Decodes a clusters-per-record byte (BPB offsets 64 and 68): positive
     * values count clusters, negative values {@code n} mean {@code 2^|n|}
     * bytes. Returns a long so hostile values cannot overflow.
     */
    private static long decodeRecordSize(int clustersPerRecord, long clusterBytes) {
        if (clustersPerRecord > 0) {
            return clustersPerRecord * clusterBytes;
        }
        int shift = -clustersPerRecord;
        return shift <= 62 ? 1L << shift : Long.MAX_VALUE;
    }

    /**
     * Returns the cluster size in bytes (512 B .. 2 MiB, validated at read).
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
     * Returns the MFT record size in bytes (256 B .. 1 MiB, validated at read).
     */
    public int mftRecordSize() {
        return (int) decodeRecordSize(clustersPerMftRecord, clusterSize());
    }

    /** Index record size Windows has always used; assumed when the BPB field is 0. */
    public static final int DEFAULT_INDEX_RECORD_SIZE = 4096;

    /**
     * Returns the index record size in bytes (256 B .. 1 MiB, validated at
     * read; {@link #DEFAULT_INDEX_RECORD_SIZE} when the BPB field is unset).
     */
    public int indexRecordSize() {
        if (clustersPerIndexRecord == 0) {
            return DEFAULT_INDEX_RECORD_SIZE;
        }
        return (int) decodeRecordSize(clustersPerIndexRecord, clusterSize());
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
