/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.xfs;

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
 * Tests for {@link XfsSuperblock}.
 */
class XfsSuperblockTest {

    @Test
    void read_validSuperblock_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsSuperblock(200 * 1024 * 1024L, 4096, "xfs-test");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.blockSize()).isEqualTo(4096);
            assertThat(sb.volumeLabel()).isEqualTo("xfs-test");
            assertThat(sb.uuid()).isNotEmpty();
        }
    }

    @Test
    void read_invalidMagic_throwsException(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[4 * 1024 * 1024];
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            assertThatThrownBy(() -> XfsSuperblock.read(disk, 0))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid XFS magic");
        }
    }

    @Test
    void totalSizeBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        long size = 200 * 1024 * 1024L;
        byte[] diskData = createXfsSuperblock(size, 4096, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.totalSizeBytes()).isEqualTo((size / 4096) * 4096);
        }
    }

    @Test
    void version_parsedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsSuperblockWithVersion(100 * 1024 * 1024L, 4);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.version()).isEqualTo("v4");
        }
    }

    @Test
    void uuid_formattedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsSuperblock(100 * 1024 * 1024L, 4096, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.uuid()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }

    @Test
    void isV5_version5_returnsTrue(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsSuperblockWithVersion(100 * 1024 * 1024L, 5);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.isV5()).isTrue();
        }
    }

    @Test
    void isV5_version4_returnsFalse(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsSuperblockWithVersion(100 * 1024 * 1024L, 4);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.isV5()).isFalse();
        }
    }

    @Test
    void agSizeBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsSuperblock(200 * 1024 * 1024L, 4096, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.agSizeBytes()).isEqualTo((long) sb.blocksPerAg() * sb.blockSize());
        }
    }

    @Test
    void features_parsedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createXfsSuperblockWithFeatures(100 * 1024 * 1024L,
                XfsSuperblock.VERSION_ATTRBIT | XfsSuperblock.VERSION_QUOTABIT);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            XfsSuperblock sb = XfsSuperblock.read(disk, 0);

            assertThat(sb.features()).contains("attr", "quota");
        }
    }

    // Helper methods

    private byte[] createXfsSuperblock(long size, int blockSize, String label) {
        return createXfsSuperblockInternal(size, blockSize, 4, label, 0);
    }

    private byte[] createXfsSuperblockWithVersion(long size, int version) {
        return createXfsSuperblockInternal(size, 4096, version, null, 0);
    }

    private byte[] createXfsSuperblockWithFeatures(long size, int featureFlags) {
        return createXfsSuperblockInternal(size, 4096, 4, null, featureFlags);
    }

    private byte[] createXfsSuperblockInternal(long size, int blockSize, int version,
                                                String label, int featureFlags) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN); // XFS is big-endian

        // Magic "XFSB"
        buffer.putInt(0, 0x58465342);

        // Block size
        buffer.putInt(4, blockSize);

        // Total blocks
        buffer.putLong(8, size / blockSize);

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

        // Version number at offset 52
        int versionNum = version | featureFlags;
        buffer.putShort(52, (short) versionNum);

        // Root inode
        buffer.putLong(56, 128);

        // AG info
        buffer.putInt(84, (int) (size / blockSize));
        buffer.putInt(88, 1);

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
