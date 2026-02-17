/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.exfat;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for exFAT filesystem implementation.
 */
class ExFatFileSystemTest {

    @Test
    void exFatBootSector_detectsValidSignature(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createMinimalExFatVolume();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            ExFatBootSector bootSector = ExFatBootSector.read(disk, 0);

            assertThat(bootSector.bytesPerSector()).isEqualTo(512);
            assertThat(bootSector.sectorsPerCluster()).isEqualTo(8);
            assertThat(bootSector.clusterSize()).isEqualTo(4096);
        }
    }

    @Test
    void exFatBootSector_rejectsInvalidSignature(@TempDir Path tempDir) throws Exception {
        byte[] diskData = new byte[1024 * 1024];
        diskData[0] = (byte) 0xEB; // Jump
        // No "EXFAT   " signature

        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            assertThatThrownBy(() -> ExFatBootSector.read(disk, 0))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid exFAT");
        }
    }

    @Test
    void exFatFileSystem_canMount(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createExFatVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = ExFatFileSystemImpl.mount(disk, 0)) {
            assertThat(fs).isInstanceOf(FileSystem.ExFatFileSystem.class);
            assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.EXFAT);
        }
    }

    @Test
    void exFatFileSystem_canReadRootDirectory(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createExFatVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = ExFatFileSystemImpl.mount(disk, 0)) {
            FileSystemEntry.Directory root = fs.root();
            assertThat(root.path()).isEqualTo("/");

            List<FileSystemEntry> entries = root.list().collect(Collectors.toList());
            // Should have our test file
            assertThat(entries).isNotEmpty();
        }
    }

    @Test
    void exFatFileSystem_canWalkFiles(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createExFatVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = ExFatFileSystemImpl.mount(disk, 0)) {
            List<FileSystemEntry> allEntries = fs.walk().collect(Collectors.toList());

            long fileCount = allEntries.stream()
                    .filter(e -> e.type() == FileSystemEntry.EntryType.REGULAR_FILE)
                    .count();
            long dirCount = allEntries.stream()
                    .filter(e -> e.type() == FileSystemEntry.EntryType.DIRECTORY)
                    .count();

            System.out.println("exFAT: " + fileCount + " files, " + dirCount + " directories");
            assertThat(dirCount).isGreaterThanOrEqualTo(1); // At least root
        }
    }

    @Test
    void exFatFileSystem_providesMetadata(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createExFatVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = ExFatFileSystemImpl.mount(disk, 0)) {
            FileSystem.ExFatFileSystem exfatFs = (FileSystem.ExFatFileSystem) fs;

            assertThat(exfatFs.clusterSize()).isEqualTo(4096);
            assertThat(exfatFs.revision()).isEqualTo("1.0");

            var metadata = fs.metadata();
            assertThat(metadata).containsKey("fsType");
            assertThat(metadata.get("fsType")).isEqualTo("exFAT");

            System.out.println("Metadata: " + metadata);
            System.out.println("Total size: " + fs.totalSize());
            System.out.println("UUID: " + fs.uuid().orElse("(none)"));
        }
    }

    @Test
    void exFatDirectoryEntry_parsesFileEntry() {
        // Create a minimal file entry set (file + stream extension + file name)
        byte[] data = new byte[96]; // 3 entries
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // File Directory Entry (0x85)
        buf.put(0, (byte) 0x85);          // Entry type
        buf.put(1, (byte) 2);             // Secondary count (stream + name)
        buf.putShort(4, (short) 0x20);    // Attributes (archive)

        // Stream Extension Entry (0xC0) at offset 32
        buf.put(32, (byte) 0xC0);         // Entry type
        buf.put(33, (byte) 0x00);         // General flags
        buf.put(35, (byte) 8);            // Name length
        buf.putLong(40, 100L);            // Valid data length
        buf.putInt(52, 3);                // First cluster
        buf.putLong(56, 100L);            // Data length

        // File Name Entry (0xC1) at offset 64
        buf.put(64, (byte) 0xC1);         // Entry type
        // Write "TEST.TXT" in UTF-16LE at offset 66
        byte[] nameBytes = "TEST.TXT".getBytes(StandardCharsets.UTF_16LE);
        buf.position(66);
        buf.put(nameBytes);

        buf.position(0);
        List<ExFatDirectoryEntry> entries = ExFatDirectoryEntry.parseDirectory(buf);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo("TEST.TXT");
        assertThat(entries.get(0).dataLength()).isEqualTo(100L);
        assertThat(entries.get(0).firstCluster()).isEqualTo(3);
    }

    @Test
    void exFatDirectoryEntry_parsesVolumeLabel() {
        byte[] data = new byte[64];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Volume Label Entry (0x83)
        buf.put(0, (byte) 0x83);          // Entry type
        buf.put(1, (byte) 7);             // Character count
        // Write "TESTDSK" in UTF-16LE at offset 2
        byte[] labelBytes = "TESTDSK".getBytes(StandardCharsets.UTF_16LE);
        buf.position(2);
        buf.put(labelBytes);

        buf.position(0);
        var label = ExFatDirectoryEntry.parseVolumeLabel(buf);

        assertThat(label).isPresent();
        assertThat(label.get()).isEqualTo("TESTDSK");
    }

    @Test
    void exFatFileSystem_canReadFileContent(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createExFatVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = ExFatFileSystemImpl.mount(disk, 0)) {
            // Find our test file
            var files = fs.walk()
                    .filter(e -> e.type() == FileSystemEntry.EntryType.REGULAR_FILE)
                    .map(e -> (FileSystemEntry.RegularFile) e)
                    .collect(Collectors.toList());

            assertThat(files).isNotEmpty();

            FileSystemEntry.RegularFile testFile = files.get(0);
            System.out.println("Found file: " + testFile.path() + " (" + testFile.size() + " bytes)");

            byte[] content = testFile.readAllBytes();
            String contentStr = new String(content, StandardCharsets.UTF_8);
            System.out.println("File content: " + contentStr);
            assertThat(contentStr).isEqualTo("Hello, exFAT!");
        }
    }

    // ========================================================================
    // Helper methods to create test exFAT volumes
    // ========================================================================

    private byte[] createMinimalExFatVolume() {
        // Create a minimal 8MB exFAT volume
        int volumeSize = 8 * 1024 * 1024;
        byte[] data = new byte[volumeSize];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Boot sector
        buf.put(0, (byte) 0xEB);          // Jump instruction
        buf.put(1, (byte) 0x76);
        buf.put(2, (byte) 0x90);

        // "EXFAT   " at offset 3
        byte[] signature = "EXFAT   ".getBytes(StandardCharsets.US_ASCII);
        buf.position(3);
        buf.put(signature);

        // Bytes 11-63 must be zero (already zero)

        // Partition offset (sectors)
        buf.putLong(64, 0L);

        // Volume length (sectors) = 8MB / 512 = 16384
        buf.putLong(72, volumeSize / 512);

        // FAT offset (sectors) - sector 32
        buf.putInt(80, 32);

        // FAT length (sectors)
        buf.putInt(84, 64);

        // Cluster heap offset (sectors) - sector 96
        buf.putInt(88, 96);

        // Cluster count
        int clusterSize = 4096;
        int dataStartSector = 96;
        int dataSectors = (volumeSize / 512) - dataStartSector;
        int clusterCount = dataSectors / (clusterSize / 512);
        buf.putInt(92, clusterCount);

        // Root directory cluster
        buf.putInt(96, 2);

        // Volume serial number
        buf.putInt(100, 0x12345678);

        // File system revision (1.0)
        buf.putShort(104, (short) 0x0100);

        // Volume flags
        buf.putShort(106, (short) 0);

        // Bytes per sector shift (9 = 512 bytes)
        buf.put(108, (byte) 9);

        // Sectors per cluster shift (3 = 8 sectors = 4096 bytes)
        buf.put(109, (byte) 3);

        // Number of FATs
        buf.put(110, (byte) 1);

        // Boot signature at 510
        buf.putShort(510, (short) 0xAA55);

        // Initialize FAT
        int fatOffset = 32 * 512;
        buf.position(fatOffset);
        buf.putInt(0xFFFFFFF8);  // Media type
        buf.putInt(0xFFFFFFFF);  // Reserved
        buf.putInt(0xFFFFFFFF);  // Root directory EOC

        // Initialize root directory (at cluster 2)
        int rootDirOffset = 96 * 512;
        // End of directory marker
        buf.put(rootDirOffset, (byte) 0x00);

        return data;
    }

    private byte[] createExFatVolumeWithFiles() {
        byte[] data = createMinimalExFatVolume();
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Add a file to the root directory
        int rootDirOffset = 96 * 512;

        // File Directory Entry (0x85)
        buf.put(rootDirOffset, (byte) 0x85);
        buf.put(rootDirOffset + 1, (byte) 2);        // Secondary count
        buf.putShort(rootDirOffset + 4, (short) 0x20); // Attributes (archive)

        // Stream Extension Entry (0xC0)
        buf.put(rootDirOffset + 32, (byte) 0xC0);
        buf.put(rootDirOffset + 33, (byte) 0x03);    // NoFatChain + AllocationPossible
        buf.put(rootDirOffset + 35, (byte) 8);       // Name length
        buf.putLong(rootDirOffset + 40, 13L);        // Valid data length
        buf.putInt(rootDirOffset + 52, 3);           // First cluster
        buf.putLong(rootDirOffset + 56, 13L);        // Data length

        // File Name Entry (0xC1)
        buf.put(rootDirOffset + 64, (byte) 0xC1);
        byte[] nameBytes = "TEST.TXT".getBytes(StandardCharsets.UTF_16LE);
        buf.position(rootDirOffset + 66);
        buf.put(nameBytes);

        // End of directory
        buf.put(rootDirOffset + 96, (byte) 0x00);

        // Update FAT for cluster 3 (file data)
        int fatOffset = 32 * 512;
        buf.putInt(fatOffset + 12, 0xFFFFFFFF);  // Cluster 3 EOC

        // Write file content at cluster 3
        int cluster3Offset = 96 * 512 + 4096;  // Root dir is cluster 2, so cluster 3 is next
        byte[] content = "Hello, exFAT!".getBytes(StandardCharsets.UTF_8);
        buf.position(cluster3Offset);
        buf.put(content);

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

        header.putInt(0x514649fb);
        header.putInt(3);
        header.putLong(0);
        header.putInt(0);
        header.putInt(16);
        header.putLong(content.length);
        header.putInt(0);
        header.putInt(1);
        header.putLong(l1Offset);
        header.putLong(refcountTableOffset);
        header.putInt(1);
        header.putInt(0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.putInt(4);
        header.putInt(104);

        header.position(l1Offset);
        header.putLong(l2Offset | 0x8000000000000000L);

        header.position(l2Offset);
        header.putLong(dataOffset | 0x8000000000000000L);

        header.position(refcountTableOffset);
        header.putLong(refcountBlockOffset);

        System.arraycopy(content, 0, qcow2, dataOffset, content.length);

        Files.write(path, qcow2);
    }
}
