/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.qcow2;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.exception.UnsupportedVersionException;
import io.spicelabs.saffron.qcow2.header.Qcow2Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link Qcow2Header}.
 */
class Qcow2HeaderTest {

    @Test
    void readHeader_validMagic_succeeds() throws IOException {
        byte[] header = createMinimalQcow2Header(3, 16, 10 * 1024 * 1024);

        Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));

        assertThat(parsed.version()).isEqualTo(3);
        assertThat(parsed.clusterBits()).isEqualTo(16);
        assertThat(parsed.clusterSize()).isEqualTo(65536);
        assertThat(parsed.virtualSize()).isEqualTo(10 * 1024 * 1024);
    }

    @Test
    void readHeader_invalidMagic_throwsInvalidMagicException() {
        byte[] badMagic = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03};

        assertThatThrownBy(() -> Qcow2Header.read(new ByteArrayInputStream(badMagic)))
                .isInstanceOf(InvalidMagicException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void readHeader_version2_parsesCorrectly() throws IOException {
        byte[] header = createMinimalQcow2Header(2, 16, 10 * 1024 * 1024);

        Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));

        assertThat(parsed.version()).isEqualTo(2);
        assertThat(parsed.refcountOrder()).isEqualTo(Qcow2Header.DEFAULT_REFCOUNT_ORDER);
    }

    @Test
    void readHeader_version3_parsesExtendedFields() throws IOException {
        byte[] header = createMinimalQcow2Header(3, 16, 10 * 1024 * 1024);

        Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));

        assertThat(parsed.version()).isEqualTo(3);
        assertThat(parsed.headerLength()).isGreaterThanOrEqualTo(Qcow2Header.MIN_V3_HEADER_LENGTH);
    }

    @Test
    void readHeader_unsupportedVersion_throwsUnsupportedVersionException() {
        byte[] header = createMinimalQcow2Header(99, 16, 10 * 1024 * 1024);

        assertThatThrownBy(() -> Qcow2Header.read(new ByteArrayInputStream(header)))
                .isInstanceOf(UnsupportedVersionException.class)
                .satisfies(ex -> {
                    UnsupportedVersionException uve = (UnsupportedVersionException) ex;
                    assertThat(uve.getVersion()).isEqualTo(99);
                    assertThat(uve.getMinSupported()).isEqualTo(2);
                    assertThat(uve.getMaxSupported()).isEqualTo(3);
                });
    }

    @Test
    void readHeader_clusterSize_parsedFromClusterBits() throws IOException {
        byte[] header = createMinimalQcow2Header(3, 12, 10 * 1024 * 1024); // 4KB clusters

        Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));

        assertThat(parsed.clusterBits()).isEqualTo(12);
        assertThat(parsed.clusterSize()).isEqualTo(4096);
    }

    @Test
    void readHeader_defaultClusterSize_is64KB() throws IOException {
        byte[] header = createMinimalQcow2Header(3, 16, 10 * 1024 * 1024);

        Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));

        assertThat(parsed.clusterSize()).isEqualTo(65536);
    }

    @Test
    void clusterSize_powersOfTwo() throws IOException {
        for (int bits = 9; bits <= 21; bits++) {
            byte[] header = createMinimalQcow2Header(3, bits, 10 * 1024 * 1024);
            Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));
            assertThat(parsed.clusterSize()).isEqualTo(1 << bits);
        }
    }

    @Test
    void refcountBits_calculatedFromOrder() throws IOException {
        byte[] header = createMinimalQcow2Header(3, 16, 10 * 1024 * 1024);

        Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));

        // Default refcount order is 4, meaning 2^4 = 16 bits
        assertThat(parsed.refcountBits()).isEqualTo(16);
    }

    @Test
    void isEncrypted_noEncryption_returnsFalse() throws IOException {
        byte[] header = createMinimalQcow2Header(3, 16, 10 * 1024 * 1024);

        Qcow2Header parsed = Qcow2Header.read(new ByteArrayInputStream(header));

        assertThat(parsed.isEncrypted()).isFalse();
    }

    @Test
    void readHeader_fromTestResource(@TempDir Path tempDir) throws IOException {
        // Create a minimal valid QCOW2 file
        byte[] data = createMinimalQcow2Header(3, 16, 1024 * 1024);
        Path qcow2 = tempDir.resolve("test.qcow2");
        Files.write(qcow2, data);

        Qcow2Header parsed = Qcow2Header.read(Files.newInputStream(qcow2));

        assertThat(parsed.version()).isEqualTo(3);
        assertThat(parsed.virtualSize()).isEqualTo(1024 * 1024);
    }

    /**
     * Creates a minimal valid QCOW2 header for testing.
     */
    private byte[] createMinimalQcow2Header(int version, int clusterBits, long virtualSize) {
        ByteBuffer buf = ByteBuffer.allocate(version >= 3 ? 104 : 72);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Magic
        buf.put(Qcow2Header.MAGIC);

        // Version
        buf.putInt(version);

        // Backing file offset (0 = no backing file)
        buf.putLong(0);

        // Backing file size
        buf.putInt(0);

        // Cluster bits
        buf.putInt(clusterBits);

        // Virtual size
        buf.putLong(virtualSize);

        // Crypt method (0 = no encryption)
        buf.putInt(0);

        // L1 size (number of entries)
        int clusterSize = 1 << clusterBits;
        int l2Entries = clusterSize / 8;
        int l1Size = (int) ((virtualSize + (long) clusterSize * l2Entries - 1) / ((long) clusterSize * l2Entries));
        buf.putInt(l1Size);

        // L1 table offset (after header)
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

            // Refcount order (4 = 16-bit refcounts)
            buf.putInt(4);

            // Header length
            buf.putInt(104);
        }

        return buf.array();
    }
}
