/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.vdi;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.vdi.header.VdiHeader;
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
 * Tests for {@link VdiDiskImpl} and VDI reading functionality.
 */
class VdiDiskTest {

    @Test
    void open_syntheticVdi_succeeds(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            assertThat(disk.format()).isEqualTo(DiskFormat.VDI);
            assertThat(disk).isInstanceOf(VirtualDisk.VdiDisk.class);

            VirtualDisk.VdiDisk vdiDisk = (VirtualDisk.VdiDisk) disk;
            assertThat(vdiDisk.imageType()).isIn("dynamic", "fixed", "undo", "differencing");
            assertThat(vdiDisk.vdiVersion()).isEqualTo(1);
            assertThat(vdiDisk.blockSize()).isEqualTo(VdiHeader.DEFAULT_BLOCK_SIZE);
        }
    }

    @Test
    void metadata_containsExpectedKeys(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            Map<String, String> metadata = disk.metadata();

            assertThat(metadata).containsKeys(
                    "vdi.version",
                    "vdi.imageType",
                    "vdi.virtualSize",
                    "vdi.blockSize"
            );
        }
    }

    @Test
    void packageUrl_generatesValidPurl(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            String purl = disk.packageUrl().toString();

            assertThat(purl).startsWith("pkg:");
            assertThat(purl).contains("image_type=");
        }
    }

    @Test
    void open_nonVdiFile_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        Path notVdi = tempDir.resolve("not-vdi.vdi");
        // Create a file with wrong magic
        byte[] data = new byte[1024];
        Files.write(notVdi, data);

        assertThatThrownBy(() -> DiskReader.open(notVdi, DiskFormat.VDI))
                .isInstanceOf(InvalidMagicException.class);
    }

    @Test
    void open_emptyFile_throwsException(@TempDir Path tempDir) throws IOException {
        Path empty = tempDir.resolve("empty.vdi");
        Files.write(empty, new byte[0]);

        assertThatThrownBy(() -> DiskReader.open(empty, DiskFormat.VDI))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vdiDisk_implementsCorrectInterface(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.FIXED, 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            assertThat(disk).isInstanceOf(VirtualDisk.VdiDisk.class);

            VirtualDisk.VdiDisk vdiDisk = (VirtualDisk.VdiDisk) disk;
            assertThat(vdiDisk.imageType()).isEqualTo("fixed");
            assertThat(vdiDisk.virtualSize()).isEqualTo(1024 * 1024);
        }
    }

    @Test
    void read_unallocatedRegion_returnsZeros(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("sparse.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            ByteBuffer buf = disk.read(0, 512);

            // Data should be zeros (unallocated blocks return zeros)
            assertThat(buf.remaining()).isEqualTo(512);
            while (buf.hasRemaining()) {
                assertThat(buf.get()).isEqualTo((byte) 0);
            }
        }
    }

    @Test
    void differentFiles_haveDifferentMetadata(@TempDir Path tempDir) throws IOException {
        byte[] vdi1Data = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 10 * 1024 * 1024);
        byte[] vdi2Data = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 20 * 1024 * 1024);

        Path file1 = tempDir.resolve("file1.vdi");
        Path file2 = tempDir.resolve("file2.vdi");
        Files.write(file1, vdi1Data);
        Files.write(file2, vdi2Data);

        try (VirtualDisk disk1 = DiskReader.open(file1);
             VirtualDisk disk2 = DiskReader.open(file2)) {

            assertThat(disk1.virtualSize()).isNotEqualTo(disk2.virtualSize());
            assertThat(disk1.virtualSize()).isEqualTo(10 * 1024 * 1024);
            assertThat(disk2.virtualSize()).isEqualTo(20 * 1024 * 1024);
        }
    }

    @Test
    void openStream_readsData(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            try (InputStream is = disk.openStream()) {
                byte[] buffer = new byte[512];
                int read = is.read(buffer);

                assertThat(read).isEqualTo(512);
            }
        }
    }

    @Test
    void dynamicDisk_reportsCorrectType(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("dynamic.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            VirtualDisk.VdiDisk vdiDisk = (VirtualDisk.VdiDisk) disk;
            assertThat(vdiDisk.imageType()).isEqualTo("dynamic");
        }
    }

    @Test
    void isCompressed_returnsFalse(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdi(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (VirtualDisk disk = DiskReader.open(vdi)) {
            // VDI doesn't support compression
            assertThat(disk.isCompressed()).isFalse();
        }
    }

    /**
     * Creates a minimal valid VDI file for testing (dynamic disk).
     */
    private byte[] createMinimalVdi(VdiHeader.ImageType imageType, long virtualSize) {
        int blockSize = VdiHeader.DEFAULT_BLOCK_SIZE;
        int numBlocks = (int) ((virtualSize + blockSize - 1) / blockSize);
        int bamSize = numBlocks * 4;
        int dataOffset = VdiHeader.MIN_HEADER_SIZE + bamSize;
        // Align to 512 bytes
        dataOffset = ((dataOffset + 511) / 512) * 512;

        // For dynamic disk, we just need header + BAM
        byte[] data = new byte[dataOffset];

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Text preamble (64 bytes)
        String preamble = "<<< Oracle VM VirtualBox Disk Image >>>\n";
        System.arraycopy(preamble.getBytes(), 0, data, 0, preamble.length());

        buffer.position(VdiHeader.MAGIC_OFFSET);

        // Magic signature
        buffer.putInt(VdiHeader.MAGIC);

        // Version (1.1) - minor then major
        buffer.putShort((short) 1); // minor
        buffer.putShort((short) 1); // major

        // Header size (from offset 0x48)
        buffer.putInt(0x180);

        // Image type
        buffer.putInt(imageType.value());

        // Image flags
        buffer.putInt(0);

        // Comment (256 bytes) - skip
        buffer.position(buffer.position() + 256);

        // Offset of blocks (BAM)
        buffer.putInt(VdiHeader.MIN_HEADER_SIZE);

        // Offset of data
        buffer.putInt(dataOffset);

        // Legacy geometry
        buffer.putInt(0); // cylinders
        buffer.putInt(0); // heads
        buffer.putInt(0); // sectors per track

        // Sector size
        buffer.putInt(512);

        // Unused
        buffer.putInt(0);

        // Disk size (virtual size)
        buffer.putLong(virtualSize);

        // Block size
        buffer.putInt(blockSize);

        // Block extra data size
        buffer.putInt(0);

        // Blocks in HDD
        buffer.putInt(numBlocks);

        // Blocks allocated
        buffer.putInt(0);

        // Image UUID (16 bytes)
        buffer.putLong(System.currentTimeMillis());
        buffer.putLong(System.nanoTime());

        // Last snap UUID (16 bytes)
        buffer.putLong(0);
        buffer.putLong(0);

        // Link UUID (16 bytes)
        buffer.putLong(0);
        buffer.putLong(0);

        // Parent UUID (16 bytes)
        buffer.putLong(0);
        buffer.putLong(0);

        // Write BAM entries as unallocated
        buffer.position(VdiHeader.MIN_HEADER_SIZE);
        for (int i = 0; i < numBlocks; i++) {
            buffer.putInt(VdiHeader.BLOCK_FREE);
        }

        return data;
    }
}
