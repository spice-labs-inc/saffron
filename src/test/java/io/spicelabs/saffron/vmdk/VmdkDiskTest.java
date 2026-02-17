/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.vmdk;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.vmdk.sparse.SparseExtentHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link VmdkDiskImpl} and VMDK reading functionality.
 */
class VmdkDiskTest {

    @Test
    void open_syntheticVmdk_succeeds(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdk(1024 * 1024);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            assertThat(disk.format()).isEqualTo(DiskFormat.VMDK);
            assertThat(disk).isInstanceOf(VirtualDisk.VmdkDisk.class);

            VirtualDisk.VmdkDisk vmdkDisk = (VirtualDisk.VmdkDisk) disk;
            assertThat(vmdkDisk.descriptorType()).isNotEmpty();
        }
    }

    @Test
    void metadata_containsExpectedKeys(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdk(1024 * 1024);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            Map<String, String> metadata = disk.metadata();

            assertThat(metadata).containsKeys(
                    "vmdk.version",
                    "vmdk.createType",
                    "vmdk.virtualSize"
            );
        }
    }

    @Test
    void packageUrl_generatesValidPurl(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdk(1024 * 1024);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            String purl = disk.packageUrl().toString();

            assertThat(purl).startsWith("pkg:");
            assertThat(purl).contains("create_type=");
        }
    }

    @Test
    void open_nonVmdkFile_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        Path notVmdk = tempDir.resolve("not-vmdk.vmdk");
        byte[] data = new byte[1024];
        Files.write(notVmdk, data);

        assertThatThrownBy(() -> DiskReader.open(notVmdk, DiskFormat.VMDK))
                .isInstanceOf(InvalidMagicException.class);
    }

    @Test
    void open_emptyFile_throwsException(@TempDir Path tempDir) throws IOException {
        Path empty = tempDir.resolve("empty.vmdk");
        Files.write(empty, new byte[0]);

        assertThatThrownBy(() -> DiskReader.open(empty, DiskFormat.VMDK))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vmdkDisk_implementsCorrectInterface(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdk(1024 * 1024);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            assertThat(disk).isInstanceOf(VirtualDisk.VmdkDisk.class);

            VirtualDisk.VmdkDisk vmdkDisk = (VirtualDisk.VmdkDisk) disk;
            assertThat(vmdkDisk.virtualSize()).isEqualTo(1024 * 1024);
        }
    }

    @Test
    void read_unallocatedRegion_returnsZeros(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdk(1024 * 1024);
        Path vmdk = tempDir.resolve("sparse.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            ByteBuffer buf = disk.read(0, 512);

            assertThat(buf.remaining()).isEqualTo(512);
            while (buf.hasRemaining()) {
                assertThat(buf.get()).isEqualTo((byte) 0);
            }
        }
    }

    @Test
    void differentFiles_haveDifferentMetadata(@TempDir Path tempDir) throws IOException {
        byte[] vmdk1Data = createMinimalVmdk(10 * 1024 * 1024);
        byte[] vmdk2Data = createMinimalVmdk(20 * 1024 * 1024);

        Path file1 = tempDir.resolve("file1.vmdk");
        Path file2 = tempDir.resolve("file2.vmdk");
        Files.write(file1, vmdk1Data);
        Files.write(file2, vmdk2Data);

        try (VirtualDisk disk1 = DiskReader.open(file1);
             VirtualDisk disk2 = DiskReader.open(file2)) {

            assertThat(disk1.virtualSize()).isNotEqualTo(disk2.virtualSize());
            assertThat(disk1.virtualSize()).isEqualTo(10 * 1024 * 1024);
            assertThat(disk2.virtualSize()).isEqualTo(20 * 1024 * 1024);
        }
    }

    @Test
    void openStream_readsData(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdk(1024 * 1024);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            try (InputStream is = disk.openStream()) {
                byte[] buffer = new byte[512];
                int read = is.read(buffer);

                assertThat(read).isEqualTo(512);
            }
        }
    }

    @Test
    void isCompressed_withoutCompression_returnsFalse(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdk(1024 * 1024);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            assertThat(disk.isCompressed()).isFalse();
        }
    }

    @Test
    void descriptorType_returnsExpectedValue(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalVmdkWithDescriptor(1024 * 1024, "monolithicSparse");
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (VirtualDisk disk = DiskReader.open(vmdk)) {
            VirtualDisk.VmdkDisk vmdkDisk = (VirtualDisk.VmdkDisk) disk;
            assertThat(vmdkDisk.descriptorType()).isEqualTo("monolithicSparse");
        }
    }

    /**
     * Creates a minimal valid sparse VMDK file for testing.
     */
    private byte[] createMinimalVmdk(long virtualSize) {
        int headerSize = SparseExtentHeader.HEADER_SIZE;
        int grainDirSize = 4096; // Space for grain directory
        byte[] data = new byte[headerSize + grainDirSize];

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Magic
        buffer.putInt(SparseExtentHeader.MAGIC);

        // Version
        buffer.putInt(1);

        // Flags
        buffer.putInt(0);

        // Capacity (in sectors)
        buffer.putLong(virtualSize / 512);

        // Grain size (in sectors) - 128 sectors = 64KB
        buffer.putLong(128);

        // Descriptor offset (0 = none)
        buffer.putLong(0);

        // Descriptor size
        buffer.putLong(0);

        // Number of GTEs per GT
        buffer.putInt(512);

        // RGDE offset
        buffer.putLong(0);

        // GDE offset (right after header)
        buffer.putLong(1); // Sector 1

        // Overhead
        buffer.putLong(headerSize / 512 + grainDirSize / 512);

        // Unclean shutdown
        buffer.put((byte) 0);

        return data;
    }

    /**
     * Creates a sparse VMDK with an embedded descriptor.
     */
    private byte[] createMinimalVmdkWithDescriptor(long virtualSize, String createType) {
        int headerSize = SparseExtentHeader.HEADER_SIZE;
        int descriptorSize = 1024;
        int grainDirSize = 4096;
        byte[] data = new byte[headerSize + descriptorSize + grainDirSize];

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Magic
        buffer.putInt(SparseExtentHeader.MAGIC);

        // Version
        buffer.putInt(1);

        // Flags
        buffer.putInt(0);

        // Capacity (in sectors)
        buffer.putLong(virtualSize / 512);

        // Grain size (in sectors)
        buffer.putLong(128);

        // Descriptor offset (in sectors) - right after header
        buffer.putLong(1);

        // Descriptor size (in sectors)
        buffer.putLong(2);

        // Number of GTEs per GT
        buffer.putInt(512);

        // RGDE offset
        buffer.putLong(0);

        // GDE offset
        buffer.putLong(3);

        // Overhead
        buffer.putLong(10);

        // Unclean shutdown
        buffer.put((byte) 0);

        // Write descriptor at offset 512
        String descriptor = "# Disk DescriptorFile\n" +
                "version=1\n" +
                "CID=fffffffe\n" +
                "parentCID=ffffffff\n" +
                "createType=\"" + createType + "\"\n" +
                "\n" +
                "# Extent description\n" +
                "RW " + (virtualSize / 512) + " SPARSE \"test.vmdk\"\n";
        byte[] descBytes = descriptor.getBytes();
        System.arraycopy(descBytes, 0, data, headerSize, Math.min(descBytes.length, descriptorSize));

        return data;
    }
}
