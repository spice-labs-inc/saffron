/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.fat32;

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
 * Tests for {@link FatBootSector}.
 */
class FatBootSectorTest {

    @Test
    void read_validFat32BootSector_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat32BootSector(100 * 1024 * 1024L, "TESTVOL");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.fatType()).isEqualTo("FAT32");
            assertThat(boot.volumeLabel()).isEqualTo("TESTVOL");
            assertThat(boot.bytesPerSector()).isEqualTo(512);
        }
    }

    @Test
    void read_invalidJumpInstruction_throwsException(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[4 * 1024 * 1024];
        // Add boot signature but no jump
        ByteBuffer buffer = ByteBuffer.wrap(diskData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(510, (short) 0xAA55);

        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            assertThatThrownBy(() -> FatBootSector.read(disk, 0))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid FAT jump instruction");
        }
    }

    @Test
    void read_invalidBootSignature_throwsException(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[4 * 1024 * 1024];
        // Add jump but no boot signature
        diskData[0] = (byte) 0xEB;

        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            assertThatThrownBy(() -> FatBootSector.read(disk, 0))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid FAT boot signature");
        }
    }

    @Test
    void read_fat16Filesystem_detectsCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat16BootSector(16 * 1024 * 1024L, "FAT16VOL");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.fatType()).isEqualTo("FAT16");
            assertThat(boot.isFat32()).isFalse();
        }
    }

    @Test
    void clusterSize_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat32BootSector(100 * 1024 * 1024L, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.clusterSize()).isEqualTo(512 * 8); // bytes per sector * sectors per cluster
        }
    }

    @Test
    void totalSizeBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        long size = 100 * 1024 * 1024L;
        byte[] diskData = createFat32BootSector(size, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.totalSizeBytes()).isEqualTo(size);
        }
    }

    @Test
    void uuid_formattedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat32BootSector(50 * 1024 * 1024L, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.uuid()).matches("[0-9A-F]{8}");
        }
    }

    @Test
    void serialNumberString_formattedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat32BootSectorWithSerial(50 * 1024 * 1024L, 0xDEADBEEF);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.serialNumberString()).isEqualTo("DEAD-BEEF");
        }
    }

    @Test
    void isFat32_fat32Volume_returnsTrue(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat32BootSector(100 * 1024 * 1024L, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.isFat32()).isTrue();
        }
    }

    @Test
    void mediaTypeDescription_fixedDisk_returnsCorrectDescription(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat32BootSector(100 * 1024 * 1024L, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.mediaTypeDescription()).isEqualTo("Fixed disk");
        }
    }

    @Test
    void volumeLabel_noName_returnsNull(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createFat32BootSector(100 * 1024 * 1024L, "NO NAME");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            FatBootSector boot = FatBootSector.read(disk, 0);

            assertThat(boot.volumeLabel()).isNull();
        }
    }

    // Helper methods

    private byte[] createFat32BootSector(long size, String label) {
        return createFat32BootSectorWithSerial(size, 0xDEADBEEF, label);
    }

    private byte[] createFat32BootSectorWithSerial(long size, int serial) {
        return createFat32BootSectorWithSerial(size, serial, null);
    }

    private byte[] createFat32BootSectorWithSerial(long size, int serial, String label) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Jump instruction
        buffer.put(0, (byte) 0xEB);
        buffer.put(1, (byte) 0x58);
        buffer.put(2, (byte) 0x90);

        // OEM ID
        buffer.position(3);
        buffer.put("MSDOS5.0".getBytes());

        // BPB
        buffer.putShort(11, (short) 512);
        buffer.put(13, (byte) 8);
        buffer.putShort(14, (short) 32);
        buffer.put(16, (byte) 2);
        buffer.putShort(17, (short) 0);
        buffer.putShort(19, (short) 0);
        buffer.put(21, (byte) 0xF8);
        buffer.putShort(22, (short) 0);
        buffer.putShort(24, (short) 63);
        buffer.putShort(26, (short) 255);
        buffer.putInt(28, 0);
        buffer.putInt(32, (int) (size / 512));

        // FAT32 specific
        buffer.putInt(36, 8192);
        buffer.putShort(40, (short) 0);
        buffer.putShort(42, (short) 0);
        buffer.putInt(44, 2);
        buffer.putShort(48, (short) 1);
        buffer.putShort(50, (short) 6);
        buffer.put(64, (byte) 0x80);
        buffer.put(66, (byte) 0x29);
        buffer.putInt(67, serial);

        // Volume label
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
        buffer.position(71);
        buffer.put(labelBytes);

        buffer.position(82);
        buffer.put("FAT32   ".getBytes());

        buffer.putShort(510, (short) 0xAA55);

        return data;
    }

    private byte[] createFat16BootSector(long size, String label) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Jump instruction
        buffer.put(0, (byte) 0xEB);
        buffer.put(1, (byte) 0x3C);
        buffer.put(2, (byte) 0x90);

        // OEM ID
        buffer.position(3);
        buffer.put("MSDOS5.0".getBytes());

        // BPB
        buffer.putShort(11, (short) 512);
        buffer.put(13, (byte) 4);
        buffer.putShort(14, (short) 1);
        buffer.put(16, (byte) 2);
        buffer.putShort(17, (short) 512);
        buffer.putShort(19, (short) (size / 512));
        buffer.put(21, (byte) 0xF8);
        buffer.putShort(22, (short) 128);
        buffer.putShort(24, (short) 63);
        buffer.putShort(26, (short) 255);
        buffer.putInt(28, 0);
        buffer.putInt(32, 0);

        // FAT12/16 fields
        buffer.put(36, (byte) 0x80);
        buffer.put(38, (byte) 0x29);
        buffer.putInt(39, 0xCAFEBABE);

        // Volume label
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

        buffer.position(54);
        buffer.put("FAT16   ".getBytes());

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
