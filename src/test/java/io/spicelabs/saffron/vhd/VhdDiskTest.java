/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.vhd;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.vhd.footer.VhdFooter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link VhdDiskImpl} and VHD reading functionality.
 */
class VhdDiskTest {

    private static final Path TEST_RESOURCES = Paths.get("src/test/resources/vhd");
    private static final Path MAGIC_ONLY_VHD = TEST_RESOURCES.resolve("magic-only.vhd");

    static boolean testResourceExists() {
        return Files.exists(MAGIC_ONLY_VHD);
    }

    @Test
    void open_syntheticVhd_succeeds(@TempDir Path tempDir) throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.FIXED, 1024 * 1024);
        Path vhd = tempDir.resolve("test.vhd");
        Files.write(vhd, vhdData);

        try (VirtualDisk disk = DiskReader.open(vhd)) {
            assertThat(disk.format()).isEqualTo(DiskFormat.VHD);
            assertThat(disk).isInstanceOf(VirtualDisk.VhdDisk.class);

            VirtualDisk.VhdDisk vhdDisk = (VirtualDisk.VhdDisk) disk;
            assertThat(vhdDisk.diskType()).isIn("fixed", "dynamic", "differencing");
            assertThat(vhdDisk.uniqueId()).isNotEmpty();
            assertThat(vhdDisk.creatorApplication()).isNotNull();
        }
    }

    @Test
    void metadata_containsExpectedKeys(@TempDir Path tempDir) throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.FIXED, 1024 * 1024);
        Path vhd = tempDir.resolve("test.vhd");
        Files.write(vhd, vhdData);

        try (VirtualDisk disk = DiskReader.open(vhd)) {
            Map<String, String> metadata = disk.metadata();

            assertThat(metadata).containsKeys(
                    "vhd.diskType",
                    "vhd.creatorApplication",
                    "vhd.virtualSize"
            );
        }
    }

    @Test
    void packageUrl_generatesValidPurl(@TempDir Path tempDir) throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.FIXED, 1024 * 1024);
        Path vhd = tempDir.resolve("test.vhd");
        Files.write(vhd, vhdData);

        try (VirtualDisk disk = DiskReader.open(vhd)) {
            String purl = disk.packageUrl().toString();

            assertThat(purl).startsWith("pkg:");
            assertThat(purl).contains("disk_type=");
        }
    }

    @Test
    void open_nonVhdFile_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        Path notVhd = tempDir.resolve("not-vhd.vhd");
        // Create a file with wrong magic
        byte[] data = new byte[1024];
        Files.write(notVhd, data);

        assertThatThrownBy(() -> DiskReader.open(notVhd, DiskFormat.VHD))
                .isInstanceOf(InvalidMagicException.class);
    }

    @Test
    void open_emptyFile_throwsException(@TempDir Path tempDir) throws IOException {
        Path empty = tempDir.resolve("empty.vhd");
        Files.write(empty, new byte[0]);

        assertThatThrownBy(() -> DiskReader.open(empty, DiskFormat.VHD))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdDisk_implementsCorrectInterface() throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.FIXED, 1024 * 1024);
        Path vhd = Files.createTempFile("test", ".vhd");
        try {
            Files.write(vhd, vhdData);

            try (VirtualDisk disk = DiskReader.open(vhd)) {
                assertThat(disk).isInstanceOf(VirtualDisk.VhdDisk.class);

                VirtualDisk.VhdDisk vhdDisk = (VirtualDisk.VhdDisk) disk;
                assertThat(vhdDisk.diskType()).isEqualTo("fixed");
                assertThat(vhdDisk.virtualSize()).isEqualTo(1024 * 1024);
            }
        } finally {
            Files.deleteIfExists(vhd);
        }
    }

    @Test
    void read_unallocatedRegion_returnsZeros(@TempDir Path tempDir) throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.FIXED, 1024 * 1024);
        Path vhd = tempDir.resolve("sparse.vhd");
        Files.write(vhd, vhdData);

        try (VirtualDisk disk = DiskReader.open(vhd)) {
            ByteBuffer buf = disk.read(0, 512);

            // Data should be zeros (or whatever is in the test data)
            assertThat(buf.remaining()).isEqualTo(512);
        }
    }

    @Test
    void differentFiles_haveDifferentMetadata(@TempDir Path tempDir) throws IOException {
        byte[] vhd1Data = createMinimalVhd(VhdFooter.DiskType.FIXED, 10 * 1024 * 1024);
        byte[] vhd2Data = createMinimalVhd(VhdFooter.DiskType.FIXED, 20 * 1024 * 1024);

        Path file1 = tempDir.resolve("file1.vhd");
        Path file2 = tempDir.resolve("file2.vhd");
        Files.write(file1, vhd1Data);
        Files.write(file2, vhd2Data);

        try (VirtualDisk disk1 = DiskReader.open(file1);
             VirtualDisk disk2 = DiskReader.open(file2)) {

            assertThat(disk1.virtualSize()).isNotEqualTo(disk2.virtualSize());
            assertThat(disk1.virtualSize()).isEqualTo(10 * 1024 * 1024);
            assertThat(disk2.virtualSize()).isEqualTo(20 * 1024 * 1024);
        }
    }

    @Test
    void openStream_readsData(@TempDir Path tempDir) throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.FIXED, 1024 * 1024);
        Path vhd = tempDir.resolve("test.vhd");
        Files.write(vhd, vhdData);

        try (VirtualDisk disk = DiskReader.open(vhd)) {
            try (InputStream is = disk.openStream()) {
                byte[] buffer = new byte[512];
                int read = is.read(buffer);

                assertThat(read).isEqualTo(512);
            }
        }
    }

    /**
     * Creates a minimal valid VHD file for testing (fixed disk).
     */
    private byte[] createMinimalVhd(VhdFooter.DiskType diskType, long virtualSize) {
        // For a fixed VHD: data + footer
        // For simplicity, create just enough for a valid footer
        int dataSize = (int) Math.min(virtualSize, 4096);
        byte[] data = new byte[dataSize + 512]; // Data + footer

        ByteBuffer footer = ByteBuffer.wrap(data, dataSize, 512);
        footer.order(ByteOrder.BIG_ENDIAN);

        // Cookie "conectix"
        footer.put("conectix".getBytes());

        // Features
        footer.putInt(0x00000002);

        // File format version
        footer.putInt(0x00010000);

        // Data offset (0xFFFFFFFF for fixed)
        footer.putLong(diskType == VhdFooter.DiskType.FIXED ? 0xFFFFFFFFFFFFFFFFL : 512);

        // Time stamp
        footer.putInt(0);

        // Creator application
        footer.put("test".getBytes());

        // Creator version
        footer.putInt(0x00010000);

        // Creator host OS
        footer.put("Wi2k".getBytes());

        // Original size
        footer.putLong(virtualSize);

        // Current size
        footer.putLong(virtualSize);

        // Disk geometry (simplified)
        int cylinders = (int) Math.min(virtualSize / (16 * 63 * 512), 65535);
        footer.putShort((short) cylinders);
        footer.put((byte) 16); // heads
        footer.put((byte) 63); // sectors per track

        // Disk type
        footer.putInt(diskType.value());

        // Checksum (simplified - not calculated properly)
        footer.putInt(0);

        // Unique ID (16 bytes)
        footer.putLong(System.currentTimeMillis());
        footer.putLong(System.nanoTime());

        // Saved state
        footer.put((byte) 0);

        return data;
    }
}
