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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.btrfs.BtrfsSuperblock;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.partition.Partition;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Detects filesystem types from virtual disk images or partitions.
 *
 * <p>This class examines magic bytes and superblock signatures to identify
 * the filesystem type present on a disk or partition.
 *
 * <p>Supported filesystems:
 * <ul>
 *   <li>ext2/ext3/ext4 - Linux extended filesystem</li>
 *   <li>NTFS - Windows NT filesystem</li>
 *   <li>FAT12/FAT16/FAT32 - DOS/Windows FAT filesystems</li>
 *   <li>XFS - Linux XFS filesystem</li>
 *   <li>Btrfs - Linux B-tree filesystem</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try (VirtualDisk disk = DiskReader.open(path)) {
 *     Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);
 *     if (info.isPresent()) {
 *         System.out.println("Filesystem: " + info.get().type());
 *         System.out.println("Label: " + info.get().label().orElse("(none)"));
 *     }
 * }
 * }</pre>
 */
public final class FilesystemDetector {

    private FilesystemDetector() {
        // Static utility class
    }

    /**
     * Detects the filesystem at the specified offset in the disk.
     *
     * @param disk the virtual disk to examine
     * @param offset the byte offset where the filesystem starts
     * @return information about the detected filesystem, or empty if unknown
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FilesystemInfo> detect(@NotNull VirtualDisk disk, long offset)
            throws IOException {
        // Try each filesystem type
        Optional<FilesystemInfo> result;

        result = tryDetectExt(disk, offset);
        if (result.isPresent()) return result;

        result = tryDetectNtfs(disk, offset);
        if (result.isPresent()) return result;

        result = tryDetectXfs(disk, offset);
        if (result.isPresent()) return result;

        result = tryDetectExFat(disk, offset);
        if (result.isPresent()) return result;

        result = tryDetectFat(disk, offset);
        if (result.isPresent()) return result;

        result = tryDetectBtrfs(disk, offset);
        if (result.isPresent()) return result;

        result = tryDetectHfsPlus(disk, offset);
        if (result.isPresent()) return result;

        result = tryDetectApfs(disk, offset);
        if (result.isPresent()) return result;

        return Optional.empty();
    }

    /**
     * Detects the filesystem on a partition.
     *
     * @param disk the virtual disk containing the partition
     * @param partition the partition to examine
     * @return information about the detected filesystem, or empty if unknown
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FilesystemInfo> detect(@NotNull VirtualDisk disk,
                                                            @NotNull Partition partition)
            throws IOException {
        return detect(disk, partition.startLba() * 512);
    }

    /**
     * Detects the filesystem on a DiskRegion (supports LVM logical volumes).
     *
     * @param region the disk region to examine
     * @return information about the detected filesystem, or empty if unknown
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FilesystemInfo> detect(@NotNull DiskRegion region) throws IOException {
        // Try each filesystem type
        Optional<FilesystemInfo> result;

        result = tryDetectExtFromRegion(region);
        if (result.isPresent()) return result;

        result = tryDetectNtfsFromRegion(region);
        if (result.isPresent()) return result;

        result = tryDetectXfsFromRegion(region);
        if (result.isPresent()) return result;

        result = tryDetectExFatFromRegion(region);
        if (result.isPresent()) return result;

        result = tryDetectFatFromRegion(region);
        if (result.isPresent()) return result;

        result = tryDetectBtrfsFromRegion(region);
        if (result.isPresent()) return result;

        result = tryDetectHfsPlusFromRegion(region);
        if (result.isPresent()) return result;

        result = tryDetectApfsFromRegion(region);
        if (result.isPresent()) return result;

        return Optional.empty();
    }

    /**
     * Tries to detect an ext2/3/4 filesystem.
     */
    private static Optional<FilesystemInfo> tryDetectExt(VirtualDisk disk, long offset)
            throws IOException {
        // ext superblock is at offset 1024 from partition start
        if (disk.virtualSize() < offset + 2048) {
            return Optional.empty();
        }

        ByteBuffer sb = disk.read(offset + 1024, 1024);
        sb.order(ByteOrder.LITTLE_ENDIAN);

        // Check magic at offset 0x38 (56)
        short magic = sb.getShort(56);
        if (magic != (short) 0xEF53) {
            return Optional.empty();
        }

        // Parse superblock fields
        int inodeCount = sb.getInt(0);
        int blockCount = sb.getInt(4);
        int freeBlockCount = sb.getInt(12);
        int freeInodeCount = sb.getInt(16);
        int blockSizeShift = sb.getInt(24);
        int blockSize = 1024 << blockSizeShift;

        // Feature flags at offset 0x5C (92)
        int compatFeatures = sb.getInt(92);
        int incompatFeatures = sb.getInt(96);
        int roCompatFeatures = sb.getInt(100);

        // UUID at offset 0x68 (104)
        byte[] uuidBytes = new byte[16];
        sb.position(104);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        // Volume label at offset 0x78 (120)
        byte[] labelBytes = new byte[16];
        sb.position(120);
        sb.get(labelBytes);
        String label = parseNullTerminatedString(labelBytes);

        // Determine ext version
        String version = "ext2";
        if ((incompatFeatures & 0x0040) != 0) { // Has extents
            version = "ext4";
        } else if ((compatFeatures & 0x0004) != 0) { // Has journal
            version = "ext3";
        }

        long totalSize = (long) blockCount * blockSize;
        long freeSize = (long) freeBlockCount * blockSize;

        return Optional.of(new FilesystemInfo(
                FileSystemType.EXT4,
                version,
                Optional.ofNullable(label),
                Optional.of(uuid),
                totalSize,
                totalSize - freeSize,
                freeSize,
                blockSize,
                inodeCount
        ));
    }

    /**
     * Tries to detect an NTFS filesystem.
     */
    private static Optional<FilesystemInfo> tryDetectNtfs(VirtualDisk disk, long offset)
            throws IOException {
        if (disk.virtualSize() < offset + 512) {
            return Optional.empty();
        }

        ByteBuffer boot = disk.read(offset, 512);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        // Check OEM ID "NTFS    " at offset 3
        byte[] oemId = new byte[8];
        boot.position(3);
        boot.get(oemId);
        String oem = new String(oemId).trim();
        if (!oem.equals("NTFS")) {
            return Optional.empty();
        }

        // Parse BPB (BIOS Parameter Block)
        int bytesPerSector = boot.getShort(11) & 0xFFFF;
        int sectorsPerCluster = boot.get(13) & 0xFF;
        long totalSectors = boot.getLong(40);
        long mftCluster = boot.getLong(48);

        // Volume serial number at offset 72
        long serialNumber = boot.getLong(72);
        String uuid = String.format("%016X", serialNumber);

        int clusterSize = bytesPerSector * sectorsPerCluster;
        long totalSize = totalSectors * bytesPerSector;

        // NTFS version is in $Volume file, default to 3.1
        String version = "3.1";

        return Optional.of(new FilesystemInfo(
                FileSystemType.NTFS,
                version,
                Optional.empty(), // Label is in $Volume
                Optional.of(uuid),
                totalSize,
                0, // Used size requires MFT parsing
                0, // Free size requires bitmap parsing
                clusterSize,
                0
        ));
    }

    /**
     * Tries to detect an XFS filesystem.
     */
    private static Optional<FilesystemInfo> tryDetectXfs(VirtualDisk disk, long offset)
            throws IOException {
        if (disk.virtualSize() < offset + 512) {
            return Optional.empty();
        }

        ByteBuffer sb = disk.read(offset, 512);
        sb.order(ByteOrder.BIG_ENDIAN); // XFS is big-endian

        // Check magic "XFSB" (0x58465342) at offset 0
        int magic = sb.getInt(0);
        if (magic != 0x58465342) {
            return Optional.empty();
        }

        // Block size at offset 4
        int blockSize = sb.getInt(4);

        // Total blocks at offset 8
        long totalBlocks = sb.getLong(8);

        // Free blocks at offset 0x70 (112) - fdblocks
        // Actually at different offset in newer XFS
        // For simplicity, we'll skip free block calculation

        // UUID at offset 32
        byte[] uuidBytes = new byte[16];
        sb.position(32);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        // Label at offset 108
        byte[] labelBytes = new byte[12];
        sb.position(108);
        sb.get(labelBytes);
        String label = parseNullTerminatedString(labelBytes);

        // Version at offset 52 (sb_versionnum)
        short versionNum = sb.getShort(52);
        String version = "v" + (versionNum & 0x0F);

        // Inode count at offset 128
        long inodeCount = sb.getLong(128);

        long totalSize = totalBlocks * blockSize;

        return Optional.of(new FilesystemInfo(
                FileSystemType.XFS,
                version,
                Optional.ofNullable(label),
                Optional.of(uuid),
                totalSize,
                0,
                0,
                blockSize,
                inodeCount
        ));
    }

    /**
     * Tries to detect an exFAT filesystem.
     */
    private static Optional<FilesystemInfo> tryDetectExFat(VirtualDisk disk, long offset)
            throws IOException {
        if (disk.virtualSize() < offset + 512) {
            return Optional.empty();
        }

        ByteBuffer boot = disk.read(offset, 512);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        // Check jump instruction
        byte jump = boot.get(0);
        if (jump != (byte) 0xEB && jump != (byte) 0xE9) {
            return Optional.empty();
        }

        // Check file system name "EXFAT   " at offset 3
        byte[] fsNameBytes = new byte[8];
        boot.position(3);
        boot.get(fsNameBytes);
        String fsName = new String(fsNameBytes, java.nio.charset.StandardCharsets.US_ASCII);
        if (!fsName.equals("EXFAT   ")) {
            return Optional.empty();
        }

        // Check that bytes 11-63 are zero (exFAT requirement)
        for (int i = 11; i < 64; i++) {
            if (boot.get(i) != 0) {
                return Optional.empty();
            }
        }

        // Check boot signature
        int bootSig = boot.getShort(510) & 0xFFFF;
        if (bootSig != 0xAA55) {
            return Optional.empty();
        }

        // Parse exFAT-specific fields
        long volumeLength = boot.getLong(72);
        int clusterHeapOffset = boot.getInt(88);
        int clusterCount = boot.getInt(92);
        long volumeSerial = boot.getInt(100) & 0xFFFFFFFFL;
        int fsRevision = boot.getShort(104) & 0xFFFF;
        int bytesPerSectorShift = boot.get(108) & 0xFF;
        int sectorsPerClusterShift = boot.get(109) & 0xFF;

        int bytesPerSector = 1 << bytesPerSectorShift;
        int clusterSize = bytesPerSector * (1 << sectorsPerClusterShift);
        long totalSize = volumeLength * bytesPerSector;

        String uuid = String.format("%08X", volumeSerial);
        int major = (fsRevision >> 8) & 0xFF;
        int minor = fsRevision & 0xFF;
        String version = major + "." + minor;

        return Optional.of(new FilesystemInfo(
                FileSystemType.EXFAT,
                version,
                Optional.empty(), // Label is in root directory
                Optional.of(uuid),
                totalSize,
                0,
                0,
                clusterSize,
                0
        ));
    }

    /**
     * Tries to detect a FAT filesystem.
     */
    private static Optional<FilesystemInfo> tryDetectFat(VirtualDisk disk, long offset)
            throws IOException {
        if (disk.virtualSize() < offset + 512) {
            return Optional.empty();
        }

        ByteBuffer boot = disk.read(offset, 512);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        // Check jump instruction (0xEB or 0xE9)
        byte jump = boot.get(0);
        if (jump != (byte) 0xEB && jump != (byte) 0xE9) {
            return Optional.empty();
        }

        // Check boot signature at 510
        int bootSig = boot.getShort(510) & 0xFFFF;
        if (bootSig != 0xAA55) {
            return Optional.empty();
        }

        // Parse BPB
        int bytesPerSector = boot.getShort(11) & 0xFFFF;
        if (bytesPerSector == 0 || bytesPerSector > 4096) {
            return Optional.empty();
        }

        int sectorsPerCluster = boot.get(13) & 0xFF;
        int reservedSectors = boot.getShort(14) & 0xFFFF;
        int numFats = boot.get(16) & 0xFF;
        int rootEntryCount = boot.getShort(17) & 0xFFFF;
        int totalSectors16 = boot.getShort(19) & 0xFFFF;
        int sectorsPerFat16 = boot.getShort(22) & 0xFFFF;
        int totalSectors32 = boot.getInt(32);
        int sectorsPerFat32 = boot.getInt(36);

        // Determine FAT type
        long totalSectors = totalSectors16 != 0 ? totalSectors16 : totalSectors32;
        int sectorsPerFat = sectorsPerFat16 != 0 ? sectorsPerFat16 : sectorsPerFat32;

        int rootDirSectors = ((rootEntryCount * 32) + (bytesPerSector - 1)) / bytesPerSector;
        long dataSectors = totalSectors - (reservedSectors + (numFats * sectorsPerFat) + rootDirSectors);
        long countOfClusters = dataSectors / sectorsPerCluster;

        String fatType;
        String label = null;
        String uuid = null;

        if (countOfClusters < 4085) {
            fatType = "FAT12";
            // FAT12/16 volume label at offset 43, serial at 39
            byte[] labelBytes = new byte[11];
            boot.position(43);
            boot.get(labelBytes);
            label = new String(labelBytes).trim();
            int serial = boot.getInt(39);
            uuid = String.format("%08X", serial);
        } else if (countOfClusters < 65525) {
            fatType = "FAT16";
            byte[] labelBytes = new byte[11];
            boot.position(43);
            boot.get(labelBytes);
            label = new String(labelBytes).trim();
            int serial = boot.getInt(39);
            uuid = String.format("%08X", serial);
        } else {
            fatType = "FAT32";
            // FAT32 volume label at offset 71, serial at 67
            byte[] labelBytes = new byte[11];
            boot.position(71);
            boot.get(labelBytes);
            label = new String(labelBytes).trim();
            int serial = boot.getInt(67);
            uuid = String.format("%08X", serial);
        }

        // Clean up label
        if (label != null && label.equals("NO NAME")) {
            label = null;
        }

        int clusterSize = bytesPerSector * sectorsPerCluster;
        long totalSize = totalSectors * bytesPerSector;

        return Optional.of(new FilesystemInfo(
                FileSystemType.FAT32, // Use FAT32 type for all FAT variants
                fatType,
                Optional.ofNullable(label),
                Optional.ofNullable(uuid),
                totalSize,
                0,
                0,
                clusterSize,
                0
        ));
    }

    /**
     * Formats a UUID from bytes.
     */
    private static String formatUuid(byte[] bytes) {
        if (bytes.length < 16) {
            return "";
        }
        return String.format("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF,
                bytes[4] & 0xFF, bytes[5] & 0xFF,
                bytes[6] & 0xFF, bytes[7] & 0xFF,
                bytes[8] & 0xFF, bytes[9] & 0xFF,
                bytes[10] & 0xFF, bytes[11] & 0xFF, bytes[12] & 0xFF, bytes[13] & 0xFF,
                bytes[14] & 0xFF, bytes[15] & 0xFF);
    }

    /**
     * Parses a null-terminated string from bytes.
     */
    private static String parseNullTerminatedString(byte[] bytes) {
        int length = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) break;
            length++;
        }
        if (length == 0) return null;
        return new String(bytes, 0, length).trim();
    }

    // ========================================================================
    // DiskRegion-based detection methods (for LVM support)
    // ========================================================================

    private static Optional<FilesystemInfo> tryDetectExtFromRegion(DiskRegion region) throws IOException {
        if (region.size() < 2048) {
            return Optional.empty();
        }

        ByteBuffer sb = region.read(1024, 1024);
        sb.order(ByteOrder.LITTLE_ENDIAN);

        short magic = sb.getShort(56);
        if (magic != (short) 0xEF53) {
            return Optional.empty();
        }

        int inodeCount = sb.getInt(0);
        int blockCount = sb.getInt(4);
        int freeBlockCount = sb.getInt(12);
        int blockSizeShift = sb.getInt(24);
        int blockSize = 1024 << blockSizeShift;

        int compatFeatures = sb.getInt(92);
        int incompatFeatures = sb.getInt(96);

        byte[] uuidBytes = new byte[16];
        sb.position(104);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        byte[] labelBytes = new byte[16];
        sb.position(120);
        sb.get(labelBytes);
        String label = parseNullTerminatedString(labelBytes);

        String version = "ext2";
        if ((incompatFeatures & 0x0040) != 0) {
            version = "ext4";
        } else if ((compatFeatures & 0x0004) != 0) {
            version = "ext3";
        }

        long totalSize = (long) blockCount * blockSize;
        long freeSize = (long) freeBlockCount * blockSize;

        return Optional.of(new FilesystemInfo(
                FileSystemType.EXT4, version, Optional.ofNullable(label), Optional.of(uuid),
                totalSize, totalSize - freeSize, freeSize, blockSize, inodeCount));
    }

    private static Optional<FilesystemInfo> tryDetectNtfsFromRegion(DiskRegion region) throws IOException {
        if (region.size() < 512) {
            return Optional.empty();
        }

        ByteBuffer boot = region.read(0, 512);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        byte[] oemId = new byte[8];
        boot.position(3);
        boot.get(oemId);
        String oem = new String(oemId).trim();
        if (!oem.equals("NTFS")) {
            return Optional.empty();
        }

        int bytesPerSector = boot.getShort(11) & 0xFFFF;
        int sectorsPerCluster = boot.get(13) & 0xFF;
        long totalSectors = boot.getLong(40);
        long serialNumber = boot.getLong(72);
        String uuid = String.format("%016X", serialNumber);

        int clusterSize = bytesPerSector * sectorsPerCluster;
        long totalSize = totalSectors * bytesPerSector;

        return Optional.of(new FilesystemInfo(
                FileSystemType.NTFS, "3.1", Optional.empty(), Optional.of(uuid),
                totalSize, 0, 0, clusterSize, 0));
    }

    private static Optional<FilesystemInfo> tryDetectXfsFromRegion(DiskRegion region) throws IOException {
        if (region.size() < 512) {
            return Optional.empty();
        }

        ByteBuffer sb = region.read(0, 512);
        sb.order(ByteOrder.BIG_ENDIAN);

        int magic = sb.getInt(0);
        if (magic != 0x58465342) {
            return Optional.empty();
        }

        int blockSize = sb.getInt(4);
        long totalBlocks = sb.getLong(8);

        byte[] uuidBytes = new byte[16];
        sb.position(32);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        byte[] labelBytes = new byte[12];
        sb.position(108);
        sb.get(labelBytes);
        String label = parseNullTerminatedString(labelBytes);

        short versionNum = sb.getShort(52);
        String version = "v" + (versionNum & 0x0F);

        long inodeCount = sb.getLong(128);
        long totalSize = totalBlocks * blockSize;

        return Optional.of(new FilesystemInfo(
                FileSystemType.XFS, version, Optional.ofNullable(label), Optional.of(uuid),
                totalSize, 0, 0, blockSize, inodeCount));
    }

    private static Optional<FilesystemInfo> tryDetectExFatFromRegion(DiskRegion region) throws IOException {
        if (region.size() < 512) {
            return Optional.empty();
        }

        ByteBuffer boot = region.read(0, 512);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        byte jump = boot.get(0);
        if (jump != (byte) 0xEB && jump != (byte) 0xE9) {
            return Optional.empty();
        }

        byte[] fsNameBytes = new byte[8];
        boot.position(3);
        boot.get(fsNameBytes);
        String fsName = new String(fsNameBytes, java.nio.charset.StandardCharsets.US_ASCII);
        if (!fsName.equals("EXFAT   ")) {
            return Optional.empty();
        }

        for (int i = 11; i < 64; i++) {
            if (boot.get(i) != 0) {
                return Optional.empty();
            }
        }

        int bootSig = boot.getShort(510) & 0xFFFF;
        if (bootSig != 0xAA55) {
            return Optional.empty();
        }

        long volumeLength = boot.getLong(72);
        long volumeSerial = boot.getInt(100) & 0xFFFFFFFFL;
        int fsRevision = boot.getShort(104) & 0xFFFF;
        int bytesPerSectorShift = boot.get(108) & 0xFF;
        int sectorsPerClusterShift = boot.get(109) & 0xFF;

        int bytesPerSector = 1 << bytesPerSectorShift;
        int clusterSize = bytesPerSector * (1 << sectorsPerClusterShift);
        long totalSize = volumeLength * bytesPerSector;

        String uuid = String.format("%08X", volumeSerial);
        int major = (fsRevision >> 8) & 0xFF;
        int minor = fsRevision & 0xFF;
        String version = major + "." + minor;

        return Optional.of(new FilesystemInfo(
                FileSystemType.EXFAT, version, Optional.empty(), Optional.of(uuid),
                totalSize, 0, 0, clusterSize, 0));
    }

    private static Optional<FilesystemInfo> tryDetectFatFromRegion(DiskRegion region) throws IOException {
        if (region.size() < 512) {
            return Optional.empty();
        }

        ByteBuffer boot = region.read(0, 512);
        boot.order(ByteOrder.LITTLE_ENDIAN);

        byte jump = boot.get(0);
        if (jump != (byte) 0xEB && jump != (byte) 0xE9) {
            return Optional.empty();
        }

        int bootSig = boot.getShort(510) & 0xFFFF;
        if (bootSig != 0xAA55) {
            return Optional.empty();
        }

        int bytesPerSector = boot.getShort(11) & 0xFFFF;
        if (bytesPerSector == 0 || bytesPerSector > 4096) {
            return Optional.empty();
        }

        int sectorsPerCluster = boot.get(13) & 0xFF;
        int reservedSectors = boot.getShort(14) & 0xFFFF;
        int numFats = boot.get(16) & 0xFF;
        int rootEntryCount = boot.getShort(17) & 0xFFFF;
        int totalSectors16 = boot.getShort(19) & 0xFFFF;
        int sectorsPerFat16 = boot.getShort(22) & 0xFFFF;
        int totalSectors32 = boot.getInt(32);
        int sectorsPerFat32 = boot.getInt(36);

        long totalSectors = totalSectors16 != 0 ? totalSectors16 : totalSectors32;
        int sectorsPerFat = sectorsPerFat16 != 0 ? sectorsPerFat16 : sectorsPerFat32;

        int rootDirSectors = ((rootEntryCount * 32) + (bytesPerSector - 1)) / bytesPerSector;
        long dataSectors = totalSectors - (reservedSectors + (numFats * sectorsPerFat) + rootDirSectors);
        long countOfClusters = dataSectors / sectorsPerCluster;

        String fatType;
        String label = null;
        String uuid = null;

        if (countOfClusters < 4085) {
            fatType = "FAT12";
            byte[] labelBytes = new byte[11];
            boot.position(43);
            boot.get(labelBytes);
            label = new String(labelBytes).trim();
            int serial = boot.getInt(39);
            uuid = String.format("%08X", serial);
        } else if (countOfClusters < 65525) {
            fatType = "FAT16";
            byte[] labelBytes = new byte[11];
            boot.position(43);
            boot.get(labelBytes);
            label = new String(labelBytes).trim();
            int serial = boot.getInt(39);
            uuid = String.format("%08X", serial);
        } else {
            fatType = "FAT32";
            byte[] labelBytes = new byte[11];
            boot.position(71);
            boot.get(labelBytes);
            label = new String(labelBytes).trim();
            int serial = boot.getInt(67);
            uuid = String.format("%08X", serial);
        }

        if (label != null && label.equals("NO NAME")) {
            label = null;
        }

        int clusterSize = bytesPerSector * sectorsPerCluster;
        long totalSize = totalSectors * bytesPerSector;

        return Optional.of(new FilesystemInfo(
                FileSystemType.FAT32, fatType, Optional.ofNullable(label), Optional.ofNullable(uuid),
                totalSize, 0, 0, clusterSize, 0));
    }

    // ========================================================================
    // Btrfs detection
    // ========================================================================

    /**
     * Tries to detect a Btrfs filesystem.
     */
    private static Optional<FilesystemInfo> tryDetectBtrfs(VirtualDisk disk, long offset)
            throws IOException {
        // Btrfs superblock is at offset 64KB (65536)
        long sbOffset = offset + BtrfsSuperblock.SUPERBLOCK_OFFSET;
        if (disk.virtualSize() < sbOffset + BtrfsSuperblock.SUPERBLOCK_SIZE) {
            return Optional.empty();
        }

        // Check magic at offset 64 within superblock
        ByteBuffer buf = disk.read(sbOffset + BtrfsSuperblock.MAGIC_OFFSET, 8);
        byte[] magic = new byte[8];
        buf.get(magic);
        if (!Arrays.equals(magic, BtrfsSuperblock.MAGIC)) {
            return Optional.empty();
        }

        // Read superblock (need at least 0x12B + 256 = 555 bytes for label field)
        ByteBuffer sb = disk.read(sbOffset, 576);
        sb.order(ByteOrder.LITTLE_ENDIAN);

        return parseBtrfsSuperblock(sb);
    }

    private static Optional<FilesystemInfo> tryDetectBtrfsFromRegion(DiskRegion region) throws IOException {
        if (region.size() < BtrfsSuperblock.SUPERBLOCK_OFFSET + BtrfsSuperblock.SUPERBLOCK_SIZE) {
            return Optional.empty();
        }

        // Check magic
        ByteBuffer magicBuf = region.read(BtrfsSuperblock.SUPERBLOCK_OFFSET + BtrfsSuperblock.MAGIC_OFFSET, 8);
        byte[] magic = new byte[8];
        magicBuf.get(magic);
        if (!Arrays.equals(magic, BtrfsSuperblock.MAGIC)) {
            return Optional.empty();
        }

        // Read superblock (need at least 0x12B + 256 = 555 bytes for label field)
        ByteBuffer sb = region.read(BtrfsSuperblock.SUPERBLOCK_OFFSET, 576);
        sb.order(ByteOrder.LITTLE_ENDIAN);

        return parseBtrfsSuperblock(sb);
    }

    // ========================================================================
    // HFS+ detection
    // ========================================================================

    private static Optional<FilesystemInfo> tryDetectHfsPlus(VirtualDisk disk, long offset)
            throws IOException {
        // HFS+ volume header is at offset 1024 from partition start
        if (disk.virtualSize() < offset + 1024 + 512) {
            return Optional.empty();
        }

        ByteBuffer vh = disk.read(offset + 1024, 512);
        vh.order(ByteOrder.BIG_ENDIAN); // HFS+ is big-endian

        return parseHfsPlusVolumeHeader(vh);
    }

    private static Optional<FilesystemInfo> tryDetectHfsPlusFromRegion(DiskRegion region) throws IOException {
        if (region.size() < 1024 + 512) {
            return Optional.empty();
        }

        ByteBuffer vh = region.read(1024, 512);
        vh.order(ByteOrder.BIG_ENDIAN);

        return parseHfsPlusVolumeHeader(vh);
    }

    private static Optional<FilesystemInfo> parseHfsPlusVolumeHeader(ByteBuffer vh) {
        // Signature at offset 0: 0x482B = "H+" (HFS+), 0x4858 = "HX" (HFSX)
        short signature = vh.getShort(0);
        if (signature != 0x482B && signature != 0x4858) {
            return Optional.empty();
        }

        // Version at offset 2
        short version = vh.getShort(2);
        String versionStr = signature == 0x4858 ? "HFSX" : "HFS+";

        // Block size at offset 40
        int blockSize = vh.getInt(40);
        if (blockSize <= 0 || blockSize > 64 * 1024 * 1024) {
            return Optional.empty();
        }

        // Total blocks at offset 44 (uint32)
        long totalBlocks = vh.getInt(44) & 0xFFFFFFFFL;

        // Free blocks at offset 48 (uint32)
        long freeBlocks = vh.getInt(48) & 0xFFFFFFFFL;

        long totalSize = totalBlocks * blockSize;
        long freeSize = freeBlocks * blockSize;

        return Optional.of(new FilesystemInfo(
                FileSystemType.HFS_PLUS,
                versionStr,
                Optional.empty(), // Label requires catalog B-tree parsing
                Optional.empty(), // UUID requires reading from volume header finderInfo
                totalSize,
                totalSize - freeSize,
                freeSize,
                blockSize,
                0
        ));
    }

    // ========================================================================
    // APFS detection
    // ========================================================================

    private static Optional<FilesystemInfo> tryDetectApfs(VirtualDisk disk, long offset)
            throws IOException {
        // APFS container superblock is at block 0; magic "NXSB" at offset 32 (after obj_phys_t header)
        if (disk.virtualSize() < offset + 128) {
            return Optional.empty();
        }

        ByteBuffer sb = disk.read(offset, 128);
        sb.order(ByteOrder.LITTLE_ENDIAN); // APFS is little-endian

        return parseApfsSuperblock(sb);
    }

    private static Optional<FilesystemInfo> tryDetectApfsFromRegion(DiskRegion region) throws IOException {
        if (region.size() < 128) {
            return Optional.empty();
        }

        ByteBuffer sb = region.read(0, 128);
        sb.order(ByteOrder.LITTLE_ENDIAN);

        return parseApfsSuperblock(sb);
    }

    private static Optional<FilesystemInfo> parseApfsSuperblock(ByteBuffer sb) {
        // obj_phys_t header: checksum (8), oid (8), xid (8), type (4), subtype (4) = 32 bytes
        // Magic "NXSB" at offset 32
        int magic = sb.getInt(32);
        if (magic != 0x4253584E) { // "NXSB" in little-endian
            return Optional.empty();
        }

        // Block size at offset 36
        int blockSize = sb.getInt(36);
        if (blockSize <= 0 || blockSize > 64 * 1024 * 1024) {
            return Optional.empty();
        }

        // Block count at offset 40
        long blockCount = sb.getLong(40);

        long totalSize = blockCount * blockSize;

        // UUID at offset 48
        byte[] uuidBytes = new byte[16];
        sb.position(48);
        sb.get(uuidBytes);
        String uuid = formatUuid(uuidBytes);

        return Optional.of(new FilesystemInfo(
                FileSystemType.APFS,
                "apfs",
                Optional.empty(), // Label requires volume superblock parsing
                Optional.of(uuid),
                totalSize,
                0,
                0,
                blockSize,
                0
        ));
    }

    private static Optional<FilesystemInfo> parseBtrfsSuperblock(ByteBuffer sb) {
        // Parse key fields
        // Skip csum (32) and fsid (16)
        sb.position(32);
        byte[] fsid = new byte[16];
        sb.get(fsid);

        // Skip bytenr (8), flags (8), magic (8)
        sb.position(sb.position() + 8 + 8 + 8);

        long generation = sb.getLong();

        // Skip rootTreeRoot, chunkTreeRoot, logTreeRoot, logRootTransid
        sb.position(sb.position() + 8 + 8 + 8 + 8);

        long totalBytes = sb.getLong();
        long bytesUsed = sb.getLong();

        // Skip rootDirObjectId, numDevices
        sb.position(sb.position() + 8 + 8);

        int sectorSize = sb.getInt();
        int nodeSize = sb.getInt();

        // Format UUID
        String uuid = String.format(
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                fsid[0] & 0xFF, fsid[1] & 0xFF, fsid[2] & 0xFF, fsid[3] & 0xFF,
                fsid[4] & 0xFF, fsid[5] & 0xFF,
                fsid[6] & 0xFF, fsid[7] & 0xFF,
                fsid[8] & 0xFF, fsid[9] & 0xFF,
                fsid[10] & 0xFF, fsid[11] & 0xFF, fsid[12] & 0xFF,
                fsid[13] & 0xFF, fsid[14] & 0xFF, fsid[15] & 0xFF
        );

        // Label is at offset 0x12B (299)
        sb.position(0x12B);
        byte[] labelBytes = new byte[256];
        sb.get(labelBytes);
        int labelLen = 0;
        while (labelLen < labelBytes.length && labelBytes[labelLen] != 0) {
            labelLen++;
        }
        String label = labelLen > 0 ? new String(labelBytes, 0, labelLen, StandardCharsets.UTF_8) : null;

        return Optional.of(new FilesystemInfo(
                FileSystemType.BTRFS,
                "btrfs",
                Optional.ofNullable(label),
                Optional.of(uuid),
                totalBytes,
                bytesUsed,
                totalBytes - bytesUsed,
                nodeSize,
                0  // Inode count not tracked in Btrfs superblock
        ));
    }
}
