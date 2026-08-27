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
package io.spicelabs.saffron.filesystem.fat32;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents the FAT12/FAT16/FAT32 boot sector (BPB - BIOS Parameter Block).
 *
 * <p>The boot sector is located at the first sector of the FAT volume
 * and contains critical filesystem parameters.
 *
 * <p>Common BPB fields:
 * <pre>
 * Offset  Size  Description
 * 0       3     Jump instruction
 * 3       8     OEM ID
 * 11      2     Bytes per sector
 * 13      1     Sectors per cluster
 * 14      2     Reserved sectors
 * 16      1     Number of FATs
 * 17      2     Root directory entries (FAT12/16)
 * 19      2     Total sectors (16-bit)
 * 21      1     Media type
 * 22      2     Sectors per FAT (FAT12/16)
 * 24      2     Sectors per track
 * 26      2     Number of heads
 * 28      4     Hidden sectors
 * 32      4     Total sectors (32-bit)
 * </pre>
 *
 * <p>FAT32-specific fields at offset 36+:
 * <pre>
 * 36      4     Sectors per FAT (FAT32)
 * 40      2     Extended flags
 * 42      2     Filesystem version
 * 44      4     Root directory cluster
 * 48      2     FSInfo sector
 * 50      2     Backup boot sector
 * 64      1     Drive number
 * 66      1     Boot signature (0x29)
 * 67      4     Volume serial number
 * 71      11    Volume label
 * 82      8     Filesystem type string
 * </pre>
 */
public record FatBootSector(
        @NotNull String oemId,
        int bytesPerSector,
        int sectorsPerCluster,
        int reservedSectors,
        int numberOfFats,
        int rootDirectoryEntries,
        long totalSectors,
        int mediaType,
        long sectorsPerFat,
        int rootDirectoryCluster,
        int fsInfoSector,
        long volumeSerialNumber,
        @Nullable String volumeLabel,
        @NotNull String fatType
) {

    /** Boot sector size */
    public static final int BOOT_SECTOR_SIZE = 512;

    /** Boot signature value */
    public static final int BOOT_SIGNATURE = 0xAA55;

    /**
     * Reads the FAT boot sector from the specified offset.
     *
     * @param disk the virtual disk to read from
     * @param partitionOffset the byte offset where the partition starts
     * @return the parsed boot sector
     * @throws IOException if an I/O error occurs or format is invalid
     */
    public static @NotNull FatBootSector read(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return read(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Reads the FAT boot sector from a DiskRegion.
     *
     * @param region the disk region containing the filesystem
     * @return the parsed boot sector
     * @throws IOException if an I/O error occurs or format is invalid
     */
    public static @NotNull FatBootSector read(@NotNull DiskRegion region) throws IOException {
        ByteBuffer boot = region.read(0, BOOT_SECTOR_SIZE);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        // Check jump instruction
        byte jump = boot.get(0);
        if (jump != (byte) 0xEB && jump != (byte) 0xE9) {
            throw new IOException("Invalid FAT jump instruction");
        }

        // Check boot signature
        int bootSig = boot.getShort(510) & 0xFFFF;
        if (bootSig != BOOT_SIGNATURE) {
            throw new IOException("Invalid FAT boot signature");
        }

        // OEM ID
        byte[] oemBytes = new byte[8];
        boot.position(3);
        boot.get(oemBytes);
        String oemId = new String(oemBytes).trim();

        // Common BPB fields
        int bytesPerSector = boot.getShort(11) & 0xFFFF;
        int sectorsPerCluster = boot.get(13) & 0xFF;
        if (bytesPerSector < 512 || bytesPerSector > 4096
                || (bytesPerSector & (bytesPerSector - 1)) != 0) {
            throw new IOException("Invalid FAT bytes per sector: " + bytesPerSector);
        }
        if (sectorsPerCluster < 1 || sectorsPerCluster > 128
                || (sectorsPerCluster & (sectorsPerCluster - 1)) != 0) {
            throw new IOException("Invalid FAT sectors per cluster: " + sectorsPerCluster);
        }
        int reservedSectors = boot.getShort(14) & 0xFFFF;
        int numberOfFats = boot.get(16) & 0xFF;
        int rootDirectoryEntries = boot.getShort(17) & 0xFFFF;
        int totalSectors16 = boot.getShort(19) & 0xFFFF;
        int mediaType = boot.get(21) & 0xFF;
        int sectorsPerFat16 = boot.getShort(22) & 0xFFFF;
        long totalSectors32 = boot.getInt(32) & 0xFFFFFFFFL;

        // Determine FAT type and read type-specific fields
        long totalSectors = totalSectors16 != 0 ? totalSectors16 : totalSectors32;
        long sectorsPerFat;
        int rootDirectoryCluster = 0;
        int fsInfoSector = 0;
        long volumeSerial;
        String volumeLabel;
        String fatType;

        if (sectorsPerFat16 != 0) {
            // FAT12 or FAT16
            sectorsPerFat = sectorsPerFat16;
            volumeSerial = boot.getInt(39) & 0xFFFFFFFFL;

            byte[] labelBytes = new byte[11];
            boot.position(43);
            boot.get(labelBytes);
            volumeLabel = new String(labelBytes).trim();
            if (volumeLabel.equals("NO NAME")) {
                volumeLabel = null;
            }

            // Determine FAT12 vs FAT16
            int rootDirSectors = ((rootDirectoryEntries * 32) + (bytesPerSector - 1)) / bytesPerSector;
            long dataSectors = totalSectors - (reservedSectors + (numberOfFats * sectorsPerFat) + rootDirSectors);
            long countOfClusters = dataSectors / sectorsPerCluster;

            if (countOfClusters < 4085) {
                fatType = "FAT12";
            } else {
                fatType = "FAT16";
            }
        } else {
            // FAT32
            sectorsPerFat = boot.getInt(36) & 0xFFFFFFFFL;
            rootDirectoryCluster = boot.getInt(44);
            fsInfoSector = boot.getShort(48) & 0xFFFF;
            volumeSerial = boot.getInt(67) & 0xFFFFFFFFL;

            byte[] labelBytes = new byte[11];
            boot.position(71);
            boot.get(labelBytes);
            volumeLabel = new String(labelBytes).trim();
            if (volumeLabel.equals("NO NAME")) {
                volumeLabel = null;
            }

            fatType = "FAT32";
        }

        return new FatBootSector(
                oemId,
                bytesPerSector,
                sectorsPerCluster,
                reservedSectors,
                numberOfFats,
                rootDirectoryEntries,
                totalSectors,
                mediaType,
                sectorsPerFat,
                rootDirectoryCluster,
                fsInfoSector,
                volumeSerial,
                volumeLabel,
                fatType
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
     * Returns the FAT region size in bytes.
     */
    public long fatSizeBytes() {
        return sectorsPerFat * bytesPerSector * numberOfFats;
    }

    /**
     * Returns the data region start offset in bytes.
     */
    public long dataRegionOffset() {
        long rootDirSectors = ((rootDirectoryEntries * 32L) + (bytesPerSector - 1)) / bytesPerSector;
        return (reservedSectors + (numberOfFats * sectorsPerFat) + rootDirSectors) * bytesPerSector;
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
     * Returns whether this is a FAT32 filesystem.
     */
    public boolean isFat32() {
        return fatType.equals("FAT32");
    }

    /**
     * Returns the media type description.
     */
    public @NotNull String mediaTypeDescription() {
        return switch (mediaType) {
            case 0xF0 -> "3.5\" 1.44MB floppy or removable media";
            case 0xF8 -> "Fixed disk";
            case 0xF9 -> "3.5\" 720KB floppy";
            case 0xFA -> "5.25\" 320KB floppy";
            case 0xFB -> "3.5\" 640KB floppy";
            case 0xFC -> "5.25\" 180KB floppy";
            case 0xFD -> "5.25\" 360KB floppy";
            case 0xFE -> "5.25\" 160KB floppy";
            case 0xFF -> "5.25\" 320KB floppy";
            default -> String.format("Unknown (0x%02X)", mediaType);
        };
    }
}
