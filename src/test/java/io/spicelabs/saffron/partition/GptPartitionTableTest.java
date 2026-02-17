/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.partition;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link GptPartitionTable}.
 */
class GptPartitionTableTest {

    @Test
    void tryParse_validGpt_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithGpt(100 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<GptPartitionTable> gpt = GptPartitionTable.tryParse(disk);

            assertThat(gpt).isPresent();
            assertThat(gpt.get().type()).isEqualTo(PartitionTable.Type.GPT);
            assertThat(gpt.get().partitions()).hasSize(1);
        }
    }

    @Test
    void tryParse_invalidSignature_returnsEmpty(@TempDir Path tempDir) throws IOException {
        byte[] diskData = new byte[2 * 1024 * 1024];
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<GptPartitionTable> gpt = GptPartitionTable.tryParse(disk);
            assertThat(gpt).isEmpty();
        }
    }

    @Test
    void partition_hasCorrectType(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithGptPartition(100 * 1024 * 1024L,
                GptPartition.TYPE_LINUX_FILESYSTEM, 2048, 100000, "Linux");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<GptPartitionTable> gpt = GptPartitionTable.tryParse(disk);

            assertThat(gpt).isPresent();
            Partition partition = gpt.get().partitions().get(0);
            assertThat(partition.typeName()).isEqualTo("Linux Filesystem");
        }
    }

    @Test
    void partition_hasCorrectBoundaries(@TempDir Path tempDir) throws IOException {
        long startLba = 2048;
        long endLba = 102047;
        byte[] diskData = createDiskWithGptPartition(100 * 1024 * 1024L,
                GptPartition.TYPE_LINUX_FILESYSTEM, startLba, endLba - startLba + 1, "Test");
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<GptPartitionTable> gpt = GptPartitionTable.tryParse(disk);

            assertThat(gpt).isPresent();
            Partition partition = gpt.get().partitions().get(0);
            assertThat(partition.startLba()).isEqualTo(startLba);
            assertThat(partition.endLba()).isEqualTo(endLba);
        }
    }

    @Test
    void partition_hasName(@TempDir Path tempDir) throws IOException {
        String partitionName = "MyPartition";
        byte[] diskData = createDiskWithGptPartition(100 * 1024 * 1024L,
                GptPartition.TYPE_LINUX_FILESYSTEM, 2048, 100000, partitionName);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<GptPartitionTable> gpt = GptPartitionTable.tryParse(disk);

            assertThat(gpt).isPresent();
            Partition partition = gpt.get().partitions().get(0);
            assertThat(partition.name()).contains(partitionName);
        }
    }

    @Test
    void diskGuid_isParsed(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithGpt(100 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<GptPartitionTable> gpt = GptPartitionTable.tryParse(disk);

            assertThat(gpt).isPresent();
            assertThat(gpt.get().diskSignature()).isNotEmpty();
        }
    }

    @Test
    void gptPartition_typeNameMapping() {
        assertThat(new GptPartition(0, GptPartition.TYPE_EFI_SYSTEM,
                UUID.randomUUID(), 0, 0, 0, Optional.empty()).typeName())
                .isEqualTo("EFI System");

        assertThat(new GptPartition(0, GptPartition.TYPE_LINUX_FILESYSTEM,
                UUID.randomUUID(), 0, 0, 0, Optional.empty()).typeName())
                .isEqualTo("Linux Filesystem");

        assertThat(new GptPartition(0, GptPartition.TYPE_MS_BASIC_DATA,
                UUID.randomUUID(), 0, 0, 0, Optional.empty()).typeName())
                .isEqualTo("Microsoft Basic Data");
    }

    @Test
    void partitionTableDetect_findsGpt(@TempDir Path tempDir) throws IOException {
        byte[] diskData = createDiskWithGpt(100 * 1024 * 1024L);
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            Optional<PartitionTable> table = PartitionTable.detect(disk);

            assertThat(table).isPresent();
            assertThat(table.get().type()).isEqualTo(PartitionTable.Type.GPT);
        }
    }

    @Test
    void gptPartition_attributes() {
        GptPartition readOnly = new GptPartition(0, GptPartition.TYPE_LINUX_FILESYSTEM,
                UUID.randomUUID(), 0, 100, GptPartition.ATTR_READ_ONLY, Optional.empty());
        assertThat(readOnly.isReadOnly()).isTrue();

        GptPartition hidden = new GptPartition(0, GptPartition.TYPE_LINUX_FILESYSTEM,
                UUID.randomUUID(), 0, 100, GptPartition.ATTR_HIDDEN, Optional.empty());
        assertThat(hidden.isHidden()).isTrue();

        GptPartition required = new GptPartition(0, GptPartition.TYPE_EFI_SYSTEM,
                UUID.randomUUID(), 0, 100, GptPartition.ATTR_PLATFORM_REQUIRED, Optional.empty());
        assertThat(required.isPlatformRequired()).isTrue();
    }

    // Helper methods

    private byte[] createDiskWithGpt(long size) {
        return createDiskWithGptPartition(size, GptPartition.TYPE_LINUX_FILESYSTEM,
                2048, 100000, "Linux");
    }

    private byte[] createDiskWithGptPartition(long size, UUID partType,
                                               long startLba, long sizeSectors, String name) {
        int minSize = 4 * 1024 * 1024;
        byte[] data = new byte[(int) Math.min(size, minSize)];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Protective MBR at sector 0
        buffer.position(MbrPartitionTable.PARTITION_TABLE_OFFSET);
        buffer.put((byte) 0x00); // Not bootable
        buffer.put((byte) 0x00); // CHS start head
        buffer.put((byte) 0x02); // CHS start sector/cylinder
        buffer.put((byte) 0x00); // CHS start cylinder
        buffer.put((byte) MbrPartition.TYPE_GPT_PROTECTIVE);
        buffer.put((byte) 0xFF); // CHS end head
        buffer.put((byte) 0xFF); // CHS end sector/cylinder
        buffer.put((byte) 0xFF); // CHS end cylinder
        buffer.putInt(1); // Start LBA
        buffer.putInt((int) (size / 512 - 1)); // Size in sectors

        // Boot signature
        buffer.putShort(510, (short) MbrPartitionTable.BOOT_SIGNATURE);

        // GPT header at sector 1 (offset 512)
        buffer.position(512);
        buffer.putLong(GptPartitionTable.GPT_SIGNATURE);
        buffer.putInt(0x00010000); // Revision 1.0
        buffer.putInt(92); // Header size
        buffer.putInt(0); // CRC32 (skip for test)
        buffer.putInt(0); // Reserved
        buffer.putLong(1); // Current LBA
        buffer.putLong((size / 512) - 1); // Backup LBA
        buffer.putLong(34); // First usable LBA
        buffer.putLong((size / 512) - 34); // Last usable LBA

        // Disk GUID (16 bytes)
        UUID diskGuid = UUID.randomUUID();
        writeGuid(buffer, diskGuid);

        buffer.putLong(2); // Partition entries LBA
        buffer.putInt(128); // Number of entries
        buffer.putInt(128); // Entry size
        buffer.putInt(0); // Partition entries CRC32

        // Partition entry at sector 2 (offset 1024)
        buffer.position(1024);

        // Partition type GUID
        writeGuid(buffer, partType);

        // Unique partition GUID
        writeGuid(buffer, UUID.randomUUID());

        // Starting LBA
        buffer.putLong(startLba);

        // Ending LBA
        buffer.putLong(startLba + sizeSectors - 1);

        // Attributes
        buffer.putLong(0);

        // Partition name (UTF-16LE)
        if (name != null) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
            buffer.put(nameBytes, 0, Math.min(nameBytes.length, 72));
        }

        return data;
    }

    private void writeGuid(ByteBuffer buffer, UUID uuid) {
        // GPT uses mixed-endian format
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        // First 3 components: little-endian
        buffer.putInt((int) (msb >> 32));
        buffer.putShort((short) (msb >> 16));
        buffer.putShort((short) msb);

        // Last 2 components: big-endian (as bytes)
        for (int i = 56; i >= 0; i -= 8) {
            buffer.put((byte) (lsb >> i));
        }
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
        header.putLong(l2Offset | 0x8000000000000000L);

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
