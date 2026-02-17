/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.vmdk;

import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.vmdk.sparse.SparseExtentHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SparseExtentHeader}.
 */
class SparseExtentHeaderTest {

    @Test
    void read_validHeader_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalSparseHeader(100 * 1024 * 1024L, 128);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (SeekableByteChannel channel = Files.newByteChannel(vmdk, StandardOpenOption.READ)) {
            SparseExtentHeader header = SparseExtentHeader.read(channel);

            assertThat(header.version()).isEqualTo(1);
            assertThat(header.virtualSizeBytes()).isEqualTo(100 * 1024 * 1024L);
            assertThat(header.grainSize()).isEqualTo(128);
        }
    }

    @Test
    void read_invalidMagic_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        byte[] data = new byte[SparseExtentHeader.HEADER_SIZE];
        // Write wrong magic
        data[0] = 0x00;
        data[1] = 0x00;
        data[2] = 0x00;
        data[3] = 0x00;
        Path vmdk = tempDir.resolve("invalid.vmdk");
        Files.write(vmdk, data);

        try (SeekableByteChannel channel = Files.newByteChannel(vmdk, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> SparseExtentHeader.read(channel))
                    .isInstanceOf(InvalidMagicException.class);
        }
    }

    @Test
    void read_truncatedFile_throwsIOException(@TempDir Path tempDir) throws IOException {
        byte[] data = new byte[100]; // Too short
        Path vmdk = tempDir.resolve("truncated.vmdk");
        Files.write(vmdk, data);

        try (SeekableByteChannel channel = Files.newByteChannel(vmdk, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> SparseExtentHeader.read(channel))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void virtualSizeBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        long expectedSize = 50 * 1024 * 1024L;
        byte[] vmdkData = createMinimalSparseHeader(expectedSize, 128);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (SeekableByteChannel channel = Files.newByteChannel(vmdk, StandardOpenOption.READ)) {
            SparseExtentHeader header = SparseExtentHeader.read(channel);
            assertThat(header.virtualSizeBytes()).isEqualTo(expectedSize);
        }
    }

    @Test
    void grainSizeBytes_calculatesCorrectly(@TempDir Path tempDir) throws IOException {
        int grainSizeSectors = 128; // 64KB
        byte[] vmdkData = createMinimalSparseHeader(1024 * 1024L, grainSizeSectors);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (SeekableByteChannel channel = Files.newByteChannel(vmdk, StandardOpenOption.READ)) {
            SparseExtentHeader header = SparseExtentHeader.read(channel);
            assertThat(header.grainSizeBytes()).isEqualTo(grainSizeSectors * 512);
        }
    }

    @Test
    void hasEmbeddedDescriptor_withDescriptor_returnsTrue(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalSparseHeaderWithDescriptor(1024 * 1024L);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (SeekableByteChannel channel = Files.newByteChannel(vmdk, StandardOpenOption.READ)) {
            SparseExtentHeader header = SparseExtentHeader.read(channel);
            assertThat(header.hasEmbeddedDescriptor()).isTrue();
        }
    }

    @Test
    void hasEmbeddedDescriptor_withoutDescriptor_returnsFalse(@TempDir Path tempDir) throws IOException {
        byte[] vmdkData = createMinimalSparseHeader(1024 * 1024L, 128);
        Path vmdk = tempDir.resolve("test.vmdk");
        Files.write(vmdk, vmdkData);

        try (SeekableByteChannel channel = Files.newByteChannel(vmdk, StandardOpenOption.READ)) {
            SparseExtentHeader header = SparseExtentHeader.read(channel);
            assertThat(header.hasEmbeddedDescriptor()).isFalse();
        }
    }

    @Test
    void constants_haveExpectedValues() {
        assertThat(SparseExtentHeader.MAGIC).isEqualTo(0x564D444B);
        assertThat(SparseExtentHeader.HEADER_SIZE).isEqualTo(512);
        assertThat(SparseExtentHeader.SECTOR_SIZE).isEqualTo(512);
    }

    /**
     * Creates a minimal valid sparse VMDK header for testing.
     */
    private byte[] createMinimalSparseHeader(long virtualSize, int grainSizeSectors) {
        byte[] data = new byte[SparseExtentHeader.HEADER_SIZE];
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
        buffer.putLong(grainSizeSectors);

        // Descriptor offset (0 = none)
        buffer.putLong(0);

        // Descriptor size
        buffer.putLong(0);

        // Number of GTEs per GT
        buffer.putInt(512);

        // RGDE offset
        buffer.putLong(0);

        // GDE offset
        buffer.putLong(0);

        // Overhead
        buffer.putLong(128);

        // Unclean shutdown
        buffer.put((byte) 0);

        return data;
    }

    /**
     * Creates a sparse header with embedded descriptor.
     */
    private byte[] createMinimalSparseHeaderWithDescriptor(long virtualSize) {
        byte[] data = new byte[SparseExtentHeader.HEADER_SIZE + 512]; // Header + descriptor space
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
        buffer.putLong(1);

        // Number of GTEs per GT
        buffer.putInt(512);

        // RGDE offset
        buffer.putLong(0);

        // GDE offset
        buffer.putLong(0);

        // Overhead
        buffer.putLong(128);

        // Unclean shutdown
        buffer.put((byte) 0);

        return data;
    }
}
