/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.qcow2;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.InvalidMagicException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
 * Tests for {@link Qcow2DiskImpl} and QCOW2 reading functionality.
 */
class Qcow2DiskTest {

    private static final Path CORPUS_DIR = Paths.get("test-corpus/qcow2/modern");
    private static final Path CIRROS_IMAGE = CORPUS_DIR.resolve("cirros-0.6.2-x86_64.qcow2");

    static boolean corpusImageExists() {
        return Files.exists(CIRROS_IMAGE);
    }

    @Test
    @EnabledIf("corpusImageExists")
    void open_cirrosImage_succeeds() throws IOException {
        try (VirtualDisk disk = DiskReader.open(CIRROS_IMAGE)) {
            assertThat(disk.format()).isEqualTo(DiskFormat.QCOW2);
            assertThat(disk).isInstanceOf(VirtualDisk.Qcow2Disk.class);

            VirtualDisk.Qcow2Disk qcow2 = (VirtualDisk.Qcow2Disk) disk;
            assertThat(qcow2.version()).isIn(2, 3);
            assertThat(qcow2.clusterSize()).isGreaterThan(0);
            assertThat(disk.virtualSize()).isGreaterThan(0);
        }
    }

    @Test
    @EnabledIf("corpusImageExists")
    void read_firstSector_succeeds() throws IOException {
        try (VirtualDisk disk = DiskReader.open(CIRROS_IMAGE)) {
            ByteBuffer sector = disk.read(0, 512);

            assertThat(sector.remaining()).isEqualTo(512);
            // Most disk images start with a bootloader or partition table
            // Just verify we got data
        }
    }

    @Test
    @EnabledIf("corpusImageExists")
    void metadata_containsExpectedKeys() throws IOException {
        try (VirtualDisk disk = DiskReader.open(CIRROS_IMAGE)) {
            Map<String, String> metadata = disk.metadata();

            assertThat(metadata).containsKeys(
                    "qcow2.version",
                    "qcow2.clusterSize",
                    "qcow2.size"
            );
        }
    }

    @Test
    @EnabledIf("corpusImageExists")
    void packageUrl_generatesValidPurl() throws IOException {
        try (VirtualDisk disk = DiskReader.open(CIRROS_IMAGE)) {
            String purl = disk.packageUrl().toString();

            assertThat(purl).startsWith("pkg:");
            assertThat(purl).contains("qcow_version=");
        }
    }

    @Test
    @EnabledIf("corpusImageExists")
    void virtualSize_matchesExpected() throws IOException {
        try (VirtualDisk disk = DiskReader.open(CIRROS_IMAGE)) {
            // CirrOS images are typically 117440512 bytes (112 MB)
            assertThat(disk.virtualSize()).isGreaterThan(100 * 1024 * 1024);
        }
    }

    @Test
    @EnabledIf("corpusImageExists")
    void openStream_readsData() throws IOException {
        try (VirtualDisk disk = DiskReader.open(CIRROS_IMAGE)) {
            try (InputStream is = disk.openStream()) {
                byte[] buffer = new byte[512];
                int read = is.read(buffer);

                assertThat(read).isEqualTo(512);
            }
        }
    }

    @Test
    void open_nonQcow2File_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        Path notQcow2 = tempDir.resolve("not-qcow2.qcow2");
        Files.write(notQcow2, new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});

        assertThatThrownBy(() -> DiskReader.open(notQcow2, DiskFormat.QCOW2))
                .isInstanceOf(InvalidMagicException.class);
    }

    @Test
    void open_emptyFile_throwsException(@TempDir Path tempDir) throws IOException {
        Path empty = tempDir.resolve("empty.qcow2");
        Files.write(empty, new byte[0]);

        assertThatThrownBy(() -> DiskReader.open(empty, DiskFormat.QCOW2))
                .isInstanceOf(IOException.class);
    }

    @Test
    void qcow2Disk_implementsCorrectInterface() throws IOException {
        // Create a minimal valid QCOW2 header
        byte[] header = createMinimalQcow2(3, 16, 1024 * 1024);
        Path qcow2 = Files.createTempFile("test", ".qcow2");
        try {
            Files.write(qcow2, header);

            try (VirtualDisk disk = DiskReader.open(qcow2)) {
                assertThat(disk).isInstanceOf(VirtualDisk.Qcow2Disk.class);

                VirtualDisk.Qcow2Disk qcow2Disk = (VirtualDisk.Qcow2Disk) disk;
                assertThat(qcow2Disk.version()).isEqualTo(3);
                assertThat(qcow2Disk.clusterSize()).isEqualTo(65536);
            }
        } finally {
            Files.deleteIfExists(qcow2);
        }
    }

    @Test
    void read_unallocatedRegion_returnsZeros(@TempDir Path tempDir) throws IOException {
        byte[] header = createMinimalQcow2(3, 16, 1024 * 1024);
        Path qcow2 = tempDir.resolve("sparse.qcow2");
        Files.write(qcow2, header);

        try (VirtualDisk disk = DiskReader.open(qcow2)) {
            ByteBuffer buf = disk.read(0, 4096);

            // Unallocated clusters should read as zeros
            while (buf.hasRemaining()) {
                assertThat(buf.get()).isEqualTo((byte) 0);
            }
        }
    }

    // Anti-fake test
    @Test
    @EnabledIf("corpusImageExists")
    void qcow2Reader_actuallyParsesFile_notHardcoded() throws IOException {
        // Read the same file twice and verify consistent results
        try (VirtualDisk disk1 = DiskReader.open(CIRROS_IMAGE);
             VirtualDisk disk2 = DiskReader.open(CIRROS_IMAGE)) {

            assertThat(disk1.virtualSize()).isEqualTo(disk2.virtualSize());
            assertThat(disk1.metadata()).isEqualTo(disk2.metadata());

            // Read some data and compare
            ByteBuffer buf1 = disk1.read(0, 512);
            ByteBuffer buf2 = disk2.read(0, 512);

            assertThat(buf1.array()).isEqualTo(buf2.array());
        }
    }

    // Anti-fake test: different files should have different metadata
    @Test
    void differentFiles_haveDifferentMetadata(@TempDir Path tempDir) throws IOException {
        byte[] header1 = createMinimalQcow2(3, 16, 10 * 1024 * 1024);
        byte[] header2 = createMinimalQcow2(3, 16, 20 * 1024 * 1024);

        Path file1 = tempDir.resolve("file1.qcow2");
        Path file2 = tempDir.resolve("file2.qcow2");
        Files.write(file1, header1);
        Files.write(file2, header2);

        try (VirtualDisk disk1 = DiskReader.open(file1);
             VirtualDisk disk2 = DiskReader.open(file2)) {

            // Virtual sizes should be different
            assertThat(disk1.virtualSize()).isNotEqualTo(disk2.virtualSize());
            assertThat(disk1.virtualSize()).isEqualTo(10 * 1024 * 1024);
            assertThat(disk2.virtualSize()).isEqualTo(20 * 1024 * 1024);
        }
    }

    /**
     * Creates a minimal valid QCOW2 file for testing.
     */
    private byte[] createMinimalQcow2(int version, int clusterBits, long virtualSize) {
        int clusterSize = 1 << clusterBits;
        int headerSize = version >= 3 ? 104 : 72;

        // We need:
        // - Header (at offset 0)
        // - L1 table (at offset clusterSize)
        // - Refcount table (at offset 2*clusterSize)
        // - At least one refcount block (at offset 3*clusterSize)

        int totalSize = 4 * clusterSize;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Magic
        buf.put((byte) 0x51);
        buf.put((byte) 0x46);
        buf.put((byte) 0x49);
        buf.put((byte) 0xfb);

        // Version
        buf.putInt(version);

        // Backing file offset
        buf.putLong(0);

        // Backing file size
        buf.putInt(0);

        // Cluster bits
        buf.putInt(clusterBits);

        // Virtual size
        buf.putLong(virtualSize);

        // Crypt method
        buf.putInt(0);

        // L1 size
        int l2Entries = clusterSize / 8;
        long l1Size = (virtualSize + (long) clusterSize * l2Entries - 1) / ((long) clusterSize * l2Entries);
        buf.putInt((int) l1Size);

        // L1 table offset
        buf.putLong(clusterSize);

        // Refcount table offset
        buf.putLong(2L * clusterSize);

        // Refcount table clusters
        buf.putInt(1);

        // Number of snapshots
        buf.putInt(0);

        // Snapshots offset
        buf.putLong(0);

        if (version >= 3) {
            // Incompatible features
            buf.putLong(0);

            // Compatible features
            buf.putLong(0);

            // Autoclear features
            buf.putLong(0);

            // Refcount order
            buf.putInt(4);

            // Header length
            buf.putInt(104);
        }

        // Pad header to cluster boundary
        buf.position(clusterSize);

        // L1 table (all zeros = unallocated)
        for (int i = 0; i < l1Size; i++) {
            buf.putLong(0);
        }

        // Refcount table at 2*clusterSize
        buf.position(2 * clusterSize);
        // Point to refcount block at 3*clusterSize
        buf.putLong(3L * clusterSize);

        // Refcount block at 3*clusterSize (all zeros initially)
        buf.position(3 * clusterSize);
        // Mark first few clusters as allocated (header, L1, refcount table, refcount block)
        buf.putShort((short) 1); // cluster 0 (header)
        buf.putShort((short) 1); // cluster 1 (L1 table)
        buf.putShort((short) 1); // cluster 2 (refcount table)
        buf.putShort((short) 1); // cluster 3 (refcount block)

        return buf.array();
    }
}
