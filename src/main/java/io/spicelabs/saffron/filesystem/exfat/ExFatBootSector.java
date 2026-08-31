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
package io.spicelabs.saffron.filesystem.exfat;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Represents the exFAT boot sector (Volume Boot Record).
 *
 * <p>The exFAT boot sector contains critical filesystem parameters:
 * <pre>
 * Offset  Size  Description
 * 0       3     Jump instruction
 * 3       8     File system name "EXFAT   "
 * 11      53    Must be zero (reserved)
 * 64      8     Partition offset
 * 72      8     Volume length (sectors)
 * 80      4     FAT offset (sectors)
 * 84      4     FAT length (sectors)
 * 88      4     Cluster heap offset (sectors)
 * 92      4     Cluster count
 * 96      4     First cluster of root directory
 * 100     4     Volume serial number
 * 104     2     File system revision
 * 106     2     Volume flags
 * 108     1     Bytes per sector shift (power of 2)
 * 109     1     Sectors per cluster shift (power of 2)
 * 110     1     Number of FATs (1 or 2)
 * 111     1     Drive select
 * 112     1     Percent in use
 * 113     7     Reserved
 * 120     390   Boot code
 * 510     2     Boot signature (0xAA55)
 * </pre>
 */
public record ExFatBootSector(
        long partitionOffset,
        long volumeLength,
        int fatOffset,
        int fatLength,
        int clusterHeapOffset,
        int clusterCount,
        int rootDirectoryCluster,
        long volumeSerialNumber,
        int fileSystemRevision,
        int volumeFlags,
        int bytesPerSectorShift,
        int sectorsPerClusterShift,
        int numberOfFats,
        int percentInUse
) {

    /** Boot sector size */
    public static final int BOOT_SECTOR_SIZE = 512;

    /** exFAT signature "EXFAT   " */
    public static final String EXFAT_SIGNATURE = "EXFAT   ";

    /** Boot signature value */
    public static final int BOOT_SIGNATURE = 0xAA55;

    /**
     * Reads the exFAT boot sector from the specified offset.
     *
     * @param disk the virtual disk to read from
     * @param partitionOffset the byte offset where the partition starts
     * @return the parsed boot sector
     * @throws IOException if an I/O error occurs or format is invalid
     */
    public static @NotNull ExFatBootSector read(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return read(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Reads the exFAT boot sector from a DiskRegion.
     *
     * @param region the disk region containing the filesystem
     * @return the parsed boot sector
     * @throws IOException if an I/O error occurs or format is invalid
     */
    public static @NotNull ExFatBootSector read(@NotNull DiskRegion region) throws IOException {
        ByteBuffer boot = region.read(0, BOOT_SECTOR_SIZE);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        // Check jump instruction
        byte jump = boot.get(0);
        if (jump != (byte) 0xEB && jump != (byte) 0xE9) {
            throw new IOException("Invalid exFAT jump instruction");
        }

        // Check file system name at offset 3
        byte[] fsNameBytes = new byte[8];
        boot.position(3);
        boot.get(fsNameBytes);
        String fsName = new String(fsNameBytes, StandardCharsets.US_ASCII);
        if (!fsName.equals(EXFAT_SIGNATURE)) {
            throw new IOException("Invalid exFAT signature: " + fsName);
        }

        // Check that bytes 11-63 are zero (exFAT requirement)
        for (int i = 11; i < 64; i++) {
            if (boot.get(i) != 0) {
                throw new IOException("Invalid exFAT boot sector: non-zero byte at offset " + i);
            }
        }

        // Check boot signature
        int bootSig = boot.getShort(510) & 0xFFFF;
        if (bootSig != BOOT_SIGNATURE) {
            throw new IOException("Invalid exFAT boot signature");
        }

        // Parse boot sector fields
        long partOffset = boot.getLong(64);
        long volumeLength = boot.getLong(72);
        int fatOffset = boot.getInt(80);
        int fatLength = boot.getInt(84);
        int clusterHeapOffset = boot.getInt(88);
        int clusterCount = boot.getInt(92);
        int rootDirCluster = boot.getInt(96);
        long volumeSerial = boot.getInt(100) & 0xFFFFFFFFL;
        int fsRevision = boot.getShort(104) & 0xFFFF;
        int volumeFlags = boot.getShort(106) & 0xFFFF;
        int bytesPerSectorShift = boot.get(108) & 0xFF;
        int sectorsPerClusterShift = boot.get(109) & 0xFF;
        int numFats = boot.get(110) & 0xFF;
        int percentInUse = boot.get(112) & 0xFF;

        // Validate shifts
        if (bytesPerSectorShift < 9 || bytesPerSectorShift > 12) {
            throw new IOException("Invalid bytes per sector shift: " + bytesPerSectorShift);
        }
        if (sectorsPerClusterShift > 25 - bytesPerSectorShift) {
            throw new IOException("Invalid sectors per cluster shift: " + sectorsPerClusterShift);
        }
        // Memory budget: a single cluster read must stay within 16 MiB.
        long clusterSize = (1L << bytesPerSectorShift) << sectorsPerClusterShift;
        if (clusterSize > 16 * 1024 * 1024) {
            throw new IOException("exFAT cluster size exceeds the 16 MB read budget: "
                    + clusterSize);
        }

        return new ExFatBootSector(
                partOffset,
                volumeLength,
                fatOffset,
                fatLength,
                clusterHeapOffset,
                clusterCount,
                rootDirCluster,
                volumeSerial,
                fsRevision,
                volumeFlags,
                bytesPerSectorShift,
                sectorsPerClusterShift,
                numFats,
                percentInUse
        );
    }

    /**
     * Returns bytes per sector.
     */
    public int bytesPerSector() {
        return 1 << bytesPerSectorShift;
    }

    /**
     * Returns sectors per cluster.
     */
    public int sectorsPerCluster() {
        return 1 << sectorsPerClusterShift;
    }

    /**
     * Returns the cluster size in bytes.
     */
    public int clusterSize() {
        return bytesPerSector() * sectorsPerCluster();
    }

    /**
     * Returns the total volume size in bytes.
     */
    public long totalSizeBytes() {
        return volumeLength * bytesPerSector();
    }

    /**
     * Returns the FAT region offset in bytes from partition start.
     */
    public long fatOffsetBytes() {
        return (long) fatOffset * bytesPerSector();
    }

    /**
     * Returns the cluster heap offset in bytes from partition start.
     */
    public long clusterHeapOffsetBytes() {
        return (long) clusterHeapOffset * bytesPerSector();
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
        return String.format("%08X", volumeSerialNumber);
    }

    /**
     * Returns the file system revision as a string.
     */
    public @NotNull String revisionString() {
        int major = (fileSystemRevision >> 8) & 0xFF;
        int minor = fileSystemRevision & 0xFF;
        return major + "." + minor;
    }

    /**
     * Returns true if the active FAT is the second FAT.
     */
    public boolean useSecondFat() {
        return (volumeFlags & 0x01) != 0;
    }

    /**
     * Returns true if the volume is dirty.
     */
    public boolean isDirty() {
        return (volumeFlags & 0x02) != 0;
    }

    /**
     * Returns true if media failure occurred.
     */
    public boolean hasMediaFailure() {
        return (volumeFlags & 0x04) != 0;
    }
}
