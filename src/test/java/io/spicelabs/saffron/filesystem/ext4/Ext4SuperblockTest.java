/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ext4;

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
 * Tests for {@link Ext4Superblock}.
 */
class Ext4SuperblockTest {

    @Test
    void read_validSuperblock_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4Superblock(100 * 1024 * 1024L, 4096, "testvol");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.blockSize()).isEqualTo(4096);
            assertThat(sb.volumeName()).isEqualTo("testvol");
            assertThat(sb.uuid()).isNotEmpty();
        }
    }

    @Test
    void read_invalidMagic_throwsException(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[4 * 1024 * 1024];
        // No ext magic
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            assertThatThrownBy(() -> Ext4Superblock.read(disk, 0))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid ext superblock magic");
        }
    }

    @Test
    void extVersion_withExtents_returnsExt4(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4SuperblockWithFeatures(100 * 1024 * 1024L, false, true);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.extVersion()).isEqualTo("ext4");
            assertThat(sb.hasExtents()).isTrue();
        }
    }

    @Test
    void extVersion_withJournalOnly_returnsExt3(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4SuperblockWithFeatures(50 * 1024 * 1024L, true, false);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.extVersion()).isEqualTo("ext3");
            assertThat(sb.hasJournal()).isTrue();
            assertThat(sb.hasExtents()).isFalse();
        }
    }

    @Test
    void extVersion_noFeatures_returnsExt2(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4SuperblockWithFeatures(20 * 1024 * 1024L, false, false);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.extVersion()).isEqualTo("ext2");
            assertThat(sb.hasJournal()).isFalse();
            assertThat(sb.hasExtents()).isFalse();
        }
    }

    @Test
    void totalSizeBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        long expectedSize = 100 * 1024 * 1024L;
        byte[] diskData = createExt4Superblock(expectedSize, 4096, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.totalSizeBytes()).isEqualTo((expectedSize / 4096) * 4096);
        }
    }

    @Test
    void uuid_formattedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4Superblock(50 * 1024 * 1024L, 4096, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.uuid()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }

    @Test
    void compatFeatureNames_returnsExpectedFeatures(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4SuperblockWithFeatures(50 * 1024 * 1024L, true, true);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.compatFeatureNames()).contains("has_journal");
            assertThat(sb.incompatFeatureNames()).contains("extents");
        }
    }

    @Test
    void blockSize_1024_parsedCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createExt4Superblock(10 * 1024 * 1024L, 1024, null);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Ext4Superblock sb = Ext4Superblock.read(disk, 0);

            assertThat(sb.blockSize()).isEqualTo(1024);
        }
    }

    // Helper methods

    private byte[] createExt4Superblock(long size, int blockSize, String label) {
        return createExt4SuperblockInternal(size, blockSize, label, true, true);
    }

    private byte[] createExt4SuperblockWithFeatures(long size, boolean hasJournal, boolean hasExtents) {
        return createExt4SuperblockInternal(size, 4096, null, hasJournal, hasExtents);
    }

    private byte[] createExt4SuperblockInternal(long size, int blockSize, String label,
                                                  boolean hasJournal, boolean hasExtents) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int sbOffset = 1024;
        int blockSizeShift = Integer.numberOfTrailingZeros(blockSize / 1024);

        // s_inodes_count
        buffer.putInt(sbOffset + 0, 65536);

        // s_blocks_count_lo
        buffer.putInt(sbOffset + 4, (int) (size / blockSize));

        // s_free_blocks_count_lo
        buffer.putInt(sbOffset + 12, (int) (size / blockSize / 2));

        // s_free_inodes_count
        buffer.putInt(sbOffset + 16, 60000);

        // s_log_block_size
        buffer.putInt(sbOffset + 24, blockSizeShift);

        // s_magic
        buffer.putShort(sbOffset + 56, (short) 0xEF53);

        // Feature flags
        int compatFeatures = 0;
        int incompatFeatures = 0;

        if (hasJournal) {
            compatFeatures |= Ext4Superblock.COMPAT_HAS_JOURNAL;
        }
        if (hasExtents) {
            incompatFeatures |= Ext4Superblock.INCOMPAT_EXTENTS;
        }

        buffer.putInt(sbOffset + 92, compatFeatures);
        buffer.putInt(sbOffset + 96, incompatFeatures);
        buffer.putInt(sbOffset + 100, 0);

        // UUID at offset 104
        byte[] uuid = {0x12, 0x34, 0x56, 0x78, (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
                       0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        buffer.position(sbOffset + 104);
        buffer.put(uuid);

        // Volume label
        if (label != null) {
            byte[] labelBytes = new byte[16];
            byte[] src = label.getBytes();
            System.arraycopy(src, 0, labelBytes, 0, Math.min(src.length, 16));
            buffer.position(sbOffset + 120);
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
