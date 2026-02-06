/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link FilesystemDetector}.
 */
class FilesystemDetectorTest {

    @Test
    void detect_ext4Filesystem_returnsExt4Info(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4Filesystem(100 * 1024 * 1024L, "test-vol", false, true);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isPresent();
            assertThat(info.get().type()).isEqualTo(FileSystemType.EXT4);
            assertThat(info.get().version()).isEqualTo("ext4");
            assertThat(info.get().label()).contains("test-vol");
        }
    }

    @Test
    void detect_ext3Filesystem_returnsExt3Version(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4Filesystem(50 * 1024 * 1024L, "ext3vol", true, false);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isPresent();
            assertThat(info.get().version()).isEqualTo("ext3");
        }
    }

    @Test
    void detect_ext2Filesystem_returnsExt2Version(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4Filesystem(20 * 1024 * 1024L, null, false, false);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isPresent();
            assertThat(info.get().version()).isEqualTo("ext2");
        }
    }

    @Test
    void detect_ntfsFilesystem_returnsNtfsInfo(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createNtfsFilesystem(100 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isPresent();
            assertThat(info.get().type()).isEqualTo(FileSystemType.NTFS);
            assertThat(info.get().uuid()).isPresent();
        }
    }

    @Test
    void detect_fat32Filesystem_returnsFatInfo(@TempDir Path tempDir) throws IOException {
        // Need 500MB+ for cluster count to be >= 65525 for FAT32 detection
        byte[] diskData = createFat32Filesystem(500 * 1024 * 1024L, "MYVOL");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isPresent();
            assertThat(info.get().type()).isEqualTo(FileSystemType.FAT32);
            assertThat(info.get().version()).isEqualTo("FAT32");
            assertThat(info.get().label()).contains("MYVOL");
        }
    }

    @Test
    void detect_fat16Filesystem_returnsFat16Version(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat16Filesystem(16 * 1024 * 1024L, "FAT16VOL");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isPresent();
            assertThat(info.get().version()).isEqualTo("FAT16");
        }
    }

    @Test
    void detect_xfsFilesystem_returnsXfsInfo(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsFilesystem(200 * 1024 * 1024L, "xfs-vol");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isPresent();
            assertThat(info.get().type()).isEqualTo(FileSystemType.XFS);
            assertThat(info.get().label()).contains("xfs-vol");
        }
    }

    @Test
    void detect_noFilesystem_returnsEmpty(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[1024 * 1024];
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);

            assertThat(info).isEmpty();
        }
    }

    @Test
    void detect_atOffset_worksCorrectly(@TempDir Path tempDir) throws IOException {
        // Create disk with ext4 at offset 32KB (fits within first QCOW2 cluster)
        int partitionOffset = 32 * 1024;
        byte[] diskData = new byte[64 * 1024]; // One cluster size
        byte[] ext4Data = createExt4Filesystem(30 * 1024L, "offset-vol", false, true);
        System.arraycopy(ext4Data, 0, diskData, partitionOffset, Math.min(ext4Data.length, 30 * 1024));

        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, partitionOffset);

            assertThat(info).isPresent();
            assertThat(info.get().type()).isEqualTo(FileSystemType.EXT4);
            assertThat(info.get().label()).contains("offset-vol");
        }
    }

    // Helper methods

    private byte[] createExt4Filesystem(long size, String label, boolean hasJournal, boolean hasExtents) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Superblock at offset 1024
        int sbOffset = 1024;

        // s_inodes_count
        buffer.putInt(sbOffset + 0, 65536);

        // s_blocks_count_lo
        buffer.putInt(sbOffset + 4, (int) (size / 4096));

        // s_free_blocks_count_lo
        buffer.putInt(sbOffset + 12, (int) (size / 4096 / 2));

        // s_free_inodes_count
        buffer.putInt(sbOffset + 16, 60000);

        // s_log_block_size (2 = 4096 bytes)
        buffer.putInt(sbOffset + 24, 2);

        // s_magic at offset 56
        buffer.putShort(sbOffset + 56, (short) 0xEF53);

        // Feature flags
        int compatFeatures = 0;
        int incompatFeatures = 0;

        if (hasJournal) {
            compatFeatures |= 0x0004; // COMPAT_HAS_JOURNAL
        }
        if (hasExtents) {
            incompatFeatures |= 0x0040; // INCOMPAT_EXTENTS
        }

        buffer.putInt(sbOffset + 92, compatFeatures);
        buffer.putInt(sbOffset + 96, incompatFeatures);
        buffer.putInt(sbOffset + 100, 0); // ro_compat

        // UUID at offset 104
        byte[] uuid = {0x12, 0x34, 0x56, 0x78, (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
                       0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        buffer.position(sbOffset + 104);
        buffer.put(uuid);

        // Volume label at offset 120
        if (label != null) {
            byte[] labelBytes = new byte[16];
            byte[] src = label.getBytes();
            System.arraycopy(src, 0, labelBytes, 0, Math.min(src.length, 16));
            buffer.position(sbOffset + 120);
            buffer.put(labelBytes);
        }

        return data;
    }

    private byte[] createNtfsFilesystem(long size) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Jump instruction
        buffer.put(0, (byte) 0xEB);
        buffer.put(1, (byte) 0x52);
        buffer.put(2, (byte) 0x90);

        // OEM ID "NTFS    "
        byte[] oemId = "NTFS    ".getBytes();
        buffer.position(3);
        buffer.put(oemId);

        // BPB
        buffer.putShort(11, (short) 512);  // Bytes per sector
        buffer.put(13, (byte) 8);          // Sectors per cluster
        buffer.putLong(40, size / 512);    // Total sectors
        buffer.putLong(48, 786432);        // MFT cluster
        buffer.putLong(56, 2);             // MFT Mirror cluster
        buffer.putInt(64, -10);            // Clusters per MFT record (1024 bytes)
        buffer.putInt(68, -10);            // Clusters per index record
        buffer.putLong(72, 0x1234567890ABCDEFL); // Volume serial

        // Boot signature
        buffer.putShort(510, (short) 0xAA55);

        return data;
    }

    private byte[] createFat32Filesystem(long size, String label) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Jump instruction
        buffer.put(0, (byte) 0xEB);
        buffer.put(1, (byte) 0x58);
        buffer.put(2, (byte) 0x90);

        // OEM ID
        byte[] oemId = "MSDOS5.0".getBytes();
        buffer.position(3);
        buffer.put(oemId);

        // BPB
        buffer.putShort(11, (short) 512);  // Bytes per sector
        buffer.put(13, (byte) 8);          // Sectors per cluster
        buffer.putShort(14, (short) 32);   // Reserved sectors
        buffer.put(16, (byte) 2);          // Number of FATs
        buffer.putShort(17, (short) 0);    // Root entries (0 for FAT32)
        buffer.putShort(19, (short) 0);    // Total sectors 16
        buffer.put(21, (byte) 0xF8);       // Media type
        buffer.putShort(22, (short) 0);    // Sectors per FAT (16-bit, 0 for FAT32)
        buffer.putShort(24, (short) 63);   // Sectors per track
        buffer.putShort(26, (short) 255);  // Number of heads
        buffer.putInt(28, 0);              // Hidden sectors
        buffer.putInt(32, (int) (size / 512)); // Total sectors 32

        // FAT32 specific
        buffer.putInt(36, 8192);           // Sectors per FAT
        buffer.putShort(40, (short) 0);    // Extended flags
        buffer.putShort(42, (short) 0);    // FS version
        buffer.putInt(44, 2);              // Root directory cluster
        buffer.putShort(48, (short) 1);    // FSInfo sector
        buffer.putShort(50, (short) 6);    // Backup boot sector
        buffer.put(64, (byte) 0x80);       // Drive number
        buffer.put(66, (byte) 0x29);       // Boot signature
        buffer.putInt(67, 0xDEADBEEF);     // Volume serial

        // Volume label at offset 71
        byte[] labelBytes = new byte[11];
        if (label != null) {
            byte[] src = label.getBytes();
            System.arraycopy(src, 0, labelBytes, 0, Math.min(src.length, 11));
            // Pad with spaces
            for (int i = src.length; i < 11; i++) {
                labelBytes[i] = ' ';
            }
        } else {
            System.arraycopy("NO NAME    ".getBytes(), 0, labelBytes, 0, 11);
        }
        buffer.position(71);
        buffer.put(labelBytes);

        // FS type string
        buffer.position(82);
        buffer.put("FAT32   ".getBytes());

        // Boot signature
        buffer.putShort(510, (short) 0xAA55);

        return data;
    }

    private byte[] createFat16Filesystem(long size, String label) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Jump instruction
        buffer.put(0, (byte) 0xEB);
        buffer.put(1, (byte) 0x3C);
        buffer.put(2, (byte) 0x90);

        // OEM ID
        byte[] oemId = "MSDOS5.0".getBytes();
        buffer.position(3);
        buffer.put(oemId);

        // BPB - FAT16 configuration
        buffer.putShort(11, (short) 512);  // Bytes per sector
        buffer.put(13, (byte) 4);          // Sectors per cluster
        buffer.putShort(14, (short) 1);    // Reserved sectors
        buffer.put(16, (byte) 2);          // Number of FATs
        buffer.putShort(17, (short) 512);  // Root entries (non-zero for FAT12/16)
        buffer.putShort(19, (short) (size / 512)); // Total sectors 16
        buffer.put(21, (byte) 0xF8);       // Media type
        buffer.putShort(22, (short) 128);  // Sectors per FAT (non-zero for FAT12/16)
        buffer.putShort(24, (short) 63);   // Sectors per track
        buffer.putShort(26, (short) 255);  // Number of heads
        buffer.putInt(28, 0);              // Hidden sectors
        buffer.putInt(32, 0);              // Total sectors 32

        // FAT12/16 fields
        buffer.put(36, (byte) 0x80);       // Drive number
        buffer.put(38, (byte) 0x29);       // Boot signature
        buffer.putInt(39, 0xCAFEBABE);     // Volume serial

        // Volume label at offset 43
        byte[] labelBytes = new byte[11];
        if (label != null) {
            byte[] src = label.getBytes();
            System.arraycopy(src, 0, labelBytes, 0, Math.min(src.length, 11));
            for (int i = src.length; i < 11; i++) {
                labelBytes[i] = ' ';
            }
        } else {
            System.arraycopy("NO NAME    ".getBytes(), 0, labelBytes, 0, 11);
        }
        buffer.position(43);
        buffer.put(labelBytes);

        // FS type string
        buffer.position(54);
        buffer.put("FAT16   ".getBytes());

        // Boot signature
        buffer.putShort(510, (short) 0xAA55);

        return data;
    }

    private byte[] createXfsFilesystem(long size, String label) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN); // XFS is big-endian

        // Magic "XFSB"
        buffer.putInt(0, 0x58465342);

        // Block size
        buffer.putInt(4, 4096);

        // Total blocks
        buffer.putLong(8, size / 4096);

        // Realtime blocks
        buffer.putLong(16, 0);

        // Realtime extents
        buffer.putLong(24, 0);

        // UUID at offset 32
        byte[] uuid = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
                       (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
                       (byte) 0xdd, (byte) 0xee, (byte) 0xff, 0x00};
        buffer.position(32);
        buffer.put(uuid);

        // Log start
        buffer.putLong(48, 2);

        // Root inode
        buffer.putLong(56, 128);

        // Version number at offset 52
        buffer.putShort(52, (short) 0x0004); // Version 4

        // AG info
        buffer.putInt(84, (int) (size / 4096)); // Blocks per AG
        buffer.putInt(88, 1);                    // AG count

        // Sector size
        buffer.putInt(100, 512);

        // Inode size
        buffer.putInt(104, 256);

        // Volume label at offset 108
        if (label != null) {
            byte[] labelBytes = new byte[12];
            byte[] src = label.getBytes();
            System.arraycopy(src, 0, labelBytes, 0, Math.min(src.length, 12));
            buffer.position(108);
            buffer.put(labelBytes);
        }

        return data;
    }

    private void createQcow2(Path path, byte[] content) throws IOException {
        int clusterSize = 65536;

        int l1Offset = clusterSize;
        int l2Offset = clusterSize * 2;
        int refcountTableOffset = clusterSize * 3;
        int refcountBlockOffset = clusterSize * 4;
        int dataOffset = clusterSize * 5;

        byte[] qcow2 = new byte[dataOffset + content.length];
        ByteBuffer header = ByteBuffer.wrap(qcow2);
        header.order(ByteOrder.BIG_ENDIAN);

        // Magic
        header.putInt(0x514649fb);

        // Version
        header.putInt(3);

        // Backing file offset
        header.putLong(0);

        // Backing file size
        header.putInt(0);

        // Cluster bits
        header.putInt(16);

        // Virtual size
        header.putLong(content.length);

        // Encryption method
        header.putInt(0);

        // L1 size
        header.putInt(1);

        // L1 table offset
        header.putLong(l1Offset);

        // Refcount table offset
        header.putLong(refcountTableOffset);

        // Refcount table clusters
        header.putInt(1);

        // Snapshots count
        header.putInt(0);

        // Snapshots offset
        header.putLong(0);

        // V3 fields
        header.putLong(0); // Incompatible features
        header.putLong(0); // Compatible features
        header.putLong(0); // Autoclear features
        header.putInt(4);  // Refcount order
        header.putInt(104); // Header length

        // L1 table entry
        header.position(l1Offset);
        header.putLong(l2Offset | 0x8000000000000000L);

        // L2 table entry
        header.position(l2Offset);
        header.putLong(dataOffset | 0x8000000000000000L);

        // Refcount table entry
        header.position(refcountTableOffset);
        header.putLong(refcountBlockOffset);

        // Copy content
        System.arraycopy(content, 0, qcow2, dataOffset, content.length);

        Files.write(path, qcow2);
    }
}
