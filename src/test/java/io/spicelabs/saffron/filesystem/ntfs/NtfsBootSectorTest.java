/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ntfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link NtfsBootSector}.
 */
class NtfsBootSectorTest {

    @Test
    void read_validBootSector_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createNtfsBootSector(100 * 1024 * 1024L, 512, 8);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector boot = NtfsBootSector.read(disk, 0);

            assertThat(boot.bytesPerSector()).isEqualTo(512);
            assertThat(boot.sectorsPerCluster()).isEqualTo(8);
            assertThat(boot.clusterSize()).isEqualTo(4096);
        }
    }

    @Test
    void read_invalidOemId_throwsException(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[4 * 1024 * 1024];
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            assertThatThrownBy(() -> NtfsBootSector.read(disk, 0))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid NTFS OEM ID");
        }
    }

    @Test
    void totalSizeBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        long size = 100 * 1024 * 1024L;
        byte[] diskData = createNtfsBootSector(size, 512, 8);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector boot = NtfsBootSector.read(disk, 0);

            assertThat(boot.totalSizeBytes()).isEqualTo(size);
        }
    }

    @Test
    void mftRecordSize_withPositiveValue_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createNtfsBootSectorWithMftSize(100 * 1024 * 1024L, 2);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector boot = NtfsBootSector.read(disk, 0);

            assertThat(boot.mftRecordSize()).isEqualTo(2 * 4096); // 2 clusters * 4096
        }
    }

    @Test
    void mftRecordSize_withNegativeValue_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createNtfsBootSectorWithMftSize(100 * 1024 * 1024L, -10);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector boot = NtfsBootSector.read(disk, 0);

            assertThat(boot.mftRecordSize()).isEqualTo(1024); // 2^10 = 1024
        }
    }

    @Test
    void uuid_formattedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createNtfsBootSector(50 * 1024 * 1024L, 512, 8);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector boot = NtfsBootSector.read(disk, 0);

            assertThat(boot.uuid()).matches("[0-9A-F]{16}");
        }
    }

    @Test
    void serialNumberString_formattedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createNtfsBootSectorWithSerial(50 * 1024 * 1024L, 0x12345678L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector boot = NtfsBootSector.read(disk, 0);

            assertThat(boot.serialNumberString()).isEqualTo("1234-5678");
        }
    }

    @Test
    void mftOffsetBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createNtfsBootSector(100 * 1024 * 1024L, 512, 8);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector boot = NtfsBootSector.read(disk, 0);

            // MFT cluster is 786432, cluster size is 4096
            assertThat(boot.mftOffsetBytes()).isEqualTo(786432L * 4096);
        }
    }

    // Helper methods

    private byte[] createNtfsBootSector(long size, int bytesPerSector, int sectorsPerCluster) {
        return createNtfsBootSectorInternal(size, bytesPerSector, sectorsPerCluster, -10, 0x1234567890ABCDEFL);
    }

    private byte[] createNtfsBootSectorWithMftSize(long size, int clustersPerMftRecord) {
        return createNtfsBootSectorInternal(size, 512, 8, clustersPerMftRecord, 0x1234567890ABCDEFL);
    }

    private byte[] createNtfsBootSectorWithSerial(long size, long serial) {
        return createNtfsBootSectorInternal(size, 512, 8, -10, serial);
    }

    private byte[] createNtfsBootSectorInternal(long size, int bytesPerSector, int sectorsPerCluster,
                                                 int clustersPerMftRecord, long serial) {
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
        buffer.putShort(11, (short) bytesPerSector);
        buffer.put(13, (byte) sectorsPerCluster);
        buffer.putLong(40, size / bytesPerSector);
        buffer.putLong(48, 786432);  // MFT cluster
        buffer.putLong(56, 2);       // MFT Mirror cluster
        buffer.putInt(64, clustersPerMftRecord);
        buffer.putInt(68, -10);      // Clusters per index record
        buffer.putLong(72, serial);

        // Boot signature
        buffer.putShort(510, (short) 0xAA55);

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
