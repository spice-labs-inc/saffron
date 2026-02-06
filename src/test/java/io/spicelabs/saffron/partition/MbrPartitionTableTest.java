/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.partition;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
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
 * Tests for {@link MbrPartitionTable}.
 */
class MbrPartitionTableTest {

    @Test
    void tryParse_validMbr_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithMbr(100 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);

            assertThat(mbr).isPresent();
            assertThat(mbr.get().type()).isEqualTo(PartitionTable.Type.MBR);
            assertThat(mbr.get().partitions()).hasSize(1);
        }
    }

    @Test
    void tryParse_invalidSignature_returnsEmpty(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[1024 * 1024];
        // No boot signature
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);
            assertThat(mbr).isEmpty();
        }
    }

    @Test
    void partition_hasCorrectType(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithMbrPartition(100 * 1024 * 1024L,
                MbrPartition.TYPE_LINUX, 2048, 100000);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);

            assertThat(mbr).isPresent();
            Partition partition = mbr.get().partitions().get(0);
            assertThat(partition.typeName()).isEqualTo("Linux");
        }
    }

    @Test
    void partition_hasCorrectBoundaries(@TempDir Path tempDir) throws IOException {
        long startLba = 2048;
        long sizeSectors = 100000;
        byte[] diskData = createDiskWithMbrPartition(100 * 1024 * 1024L,
                MbrPartition.TYPE_LINUX, startLba, sizeSectors);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);

            assertThat(mbr).isPresent();
            Partition partition = mbr.get().partitions().get(0);
            assertThat(partition.startLba()).isEqualTo(startLba);
            assertThat(partition.sizeInSectors()).isEqualTo(sizeSectors);
            assertThat(partition.endLba()).isEqualTo(startLba + sizeSectors - 1);
        }
    }

    @Test
    void multiplePartitions_allParsed(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithMultiplePartitions(200 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);

            assertThat(mbr).isPresent();
            assertThat(mbr.get().partitions()).hasSize(2);
        }
    }

    @Test
    void bootablePartition_isMarkedBootable(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithBootablePartition(100 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);

            assertThat(mbr).isPresent();
            Partition partition = mbr.get().partitions().get(0);
            assertThat(partition.isBootable()).isTrue();
        }
    }

    @Test
    void diskSignature_isParsed(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithMbrAndSignature(100 * 1024 * 1024L, 0x12345678);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);

            assertThat(mbr).isPresent();
            assertThat(mbr.get().diskSignature()).isEqualTo("12345678");
        }
    }

    @Test
    void mbrPartition_typeNameMapping() {
        assertThat(MbrPartition.getTypeName(MbrPartition.TYPE_LINUX)).isEqualTo("Linux");
        assertThat(MbrPartition.getTypeName(MbrPartition.TYPE_NTFS)).isEqualTo("NTFS/exFAT");
        assertThat(MbrPartition.getTypeName(MbrPartition.TYPE_FAT32_LBA)).isEqualTo("FAT32 (LBA)");
        assertThat(MbrPartition.getTypeName(MbrPartition.TYPE_LINUX_SWAP)).isEqualTo("Linux swap");
        assertThat(MbrPartition.getTypeName(MbrPartition.TYPE_GPT_PROTECTIVE)).isEqualTo("GPT Protective");
    }

    @Test
    void partitionTableDetect_findsMbr(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithMbr(100 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<PartitionTable> table = PartitionTable.detect(disk);

            assertThat(table).isPresent();
            assertThat(table.get().type()).isEqualTo(PartitionTable.Type.MBR);
        }
    }

    // Helper methods

    private byte[] createDiskWithMbr(long size) {
        return createDiskWithMbrPartition(size, MbrPartition.TYPE_LINUX, 2048, 100000);
    }

    private byte[] createDiskWithMbrPartition(long size, int partType, long startLba, long sizeSectors) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Disk signature
        buffer.putInt(MbrPartitionTable.DISK_SIGNATURE_OFFSET, 0xABCDEF01);

        // First partition entry
        int offset = MbrPartitionTable.PARTITION_TABLE_OFFSET;
        buffer.put(offset, (byte) 0x00); // Not bootable
        buffer.put(offset + 4, (byte) partType);
        buffer.putInt(offset + 8, (int) startLba);
        buffer.putInt(offset + 12, (int) sizeSectors);

        // Boot signature
        buffer.putShort(510, (short) MbrPartitionTable.BOOT_SIGNATURE);

        return data;
    }

    private byte[] createDiskWithMultiplePartitions(long size) {
        byte[] data = new byte[(int) Math.min(size, 4 * 1024 * 1024)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Disk signature
        buffer.putInt(MbrPartitionTable.DISK_SIGNATURE_OFFSET, 0x12345678);

        // First partition
        int offset = MbrPartitionTable.PARTITION_TABLE_OFFSET;
        buffer.put(offset + 4, (byte) MbrPartition.TYPE_LINUX);
        buffer.putInt(offset + 8, 2048);
        buffer.putInt(offset + 12, 100000);

        // Second partition
        offset += MbrPartitionTable.PARTITION_ENTRY_SIZE;
        buffer.put(offset + 4, (byte) MbrPartition.TYPE_LINUX_SWAP);
        buffer.putInt(offset + 8, 102048);
        buffer.putInt(offset + 12, 50000);

        // Boot signature
        buffer.putShort(510, (short) MbrPartitionTable.BOOT_SIGNATURE);

        return data;
    }

    private byte[] createDiskWithBootablePartition(long size) {
        byte[] data = createDiskWithMbrPartition(size, MbrPartition.TYPE_LINUX, 2048, 100000);
        data[MbrPartitionTable.PARTITION_TABLE_OFFSET] = (byte) 0x80; // Bootable flag
        return data;
    }

    private byte[] createDiskWithMbrAndSignature(long size, int signature) {
        byte[] data = createDiskWithMbr(size);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MbrPartitionTable.DISK_SIGNATURE_OFFSET, signature);
        return data;
    }

    private void createQcow2(Path path, byte[] content) throws IOException {
        // Create a minimal QCOW2 v3 file with the given content as virtual data
        int clusterSize = 65536;

        // Calculate sizes
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

        // Cluster bits (16 = 64KB clusters)
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
        // Incompatible features
        header.putLong(0);

        // Compatible features
        header.putLong(0);

        // Autoclear features
        header.putLong(0);

        // Refcount order (4 = 16-bit refcounts)
        header.putInt(4);

        // Header length (must be >= 104 for v3)
        header.putInt(104);

        // Write L1 table entry pointing to L2
        header.position(l1Offset);
        header.putLong(l2Offset | 0x8000000000000000L); // Standard cluster

        // Write L2 table entry pointing to data
        header.position(l2Offset);
        header.putLong(dataOffset | 0x8000000000000000L);

        // Write refcount table entry pointing to refcount block
        header.position(refcountTableOffset);
        header.putLong(refcountBlockOffset);

        // Copy content to data area
        System.arraycopy(content, 0, qcow2, dataOffset, content.length);

        Files.write(path, qcow2);
    }
}
