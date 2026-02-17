/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.vdi;

import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.vdi.header.VdiHeader;
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
 * Tests for {@link VdiHeader}.
 */
class VdiHeaderTest {

    @Test
    void read_validHeader_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdiHeader(VdiHeader.ImageType.DYNAMIC, 100 * 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (SeekableByteChannel channel = Files.newByteChannel(vdi, StandardOpenOption.READ)) {
            VdiHeader header = VdiHeader.read(channel);

            assertThat(header.versionMajor()).isEqualTo(1);
            assertThat(header.versionMinor()).isEqualTo(1);
            assertThat(header.imageType()).isEqualTo(VdiHeader.ImageType.DYNAMIC);
            assertThat(header.diskSize()).isEqualTo(100 * 1024 * 1024);
            assertThat(header.blockSize()).isEqualTo(VdiHeader.DEFAULT_BLOCK_SIZE);
        }
    }

    @Test
    void read_fixedDisk_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdiHeader(VdiHeader.ImageType.FIXED, 50 * 1024 * 1024);
        Path vdi = tempDir.resolve("fixed.vdi");
        Files.write(vdi, vdiData);

        try (SeekableByteChannel channel = Files.newByteChannel(vdi, StandardOpenOption.READ)) {
            VdiHeader header = VdiHeader.read(channel);

            assertThat(header.imageType()).isEqualTo(VdiHeader.ImageType.FIXED);
            assertThat(header.isFixed()).isTrue();
            assertThat(header.isDynamic()).isFalse();
        }
    }

    @Test
    void read_invalidMagic_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        byte[] data = new byte[VdiHeader.MIN_HEADER_SIZE];
        // Write wrong magic at offset 0x40
        data[0x40] = 0x00;
        data[0x41] = 0x00;
        data[0x42] = 0x00;
        data[0x43] = 0x00;
        Path vdi = tempDir.resolve("invalid.vdi");
        Files.write(vdi, data);

        try (SeekableByteChannel channel = Files.newByteChannel(vdi, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> VdiHeader.read(channel))
                    .isInstanceOf(InvalidMagicException.class);
        }
    }

    @Test
    void read_truncatedFile_throwsIOException(@TempDir Path tempDir) throws IOException {
        byte[] data = new byte[100]; // Too short
        Path vdi = tempDir.resolve("truncated.vdi");
        Files.write(vdi, data);

        try (SeekableByteChannel channel = Files.newByteChannel(vdi, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> VdiHeader.read(channel))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void versionString_formatsCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] vdiData = createMinimalVdiHeader(VdiHeader.ImageType.DYNAMIC, 1024 * 1024);
        Path vdi = tempDir.resolve("test.vdi");
        Files.write(vdi, vdiData);

        try (SeekableByteChannel channel = Files.newByteChannel(vdi, StandardOpenOption.READ)) {
            VdiHeader header = VdiHeader.read(channel);
            assertThat(header.versionString()).isEqualTo("1.1");
        }
    }

    @Test
    void imageType_fromValue_mapsCorrectly() {
        assertThat(VdiHeader.ImageType.fromValue(1)).isEqualTo(VdiHeader.ImageType.DYNAMIC);
        assertThat(VdiHeader.ImageType.fromValue(2)).isEqualTo(VdiHeader.ImageType.FIXED);
        assertThat(VdiHeader.ImageType.fromValue(3)).isEqualTo(VdiHeader.ImageType.UNDO);
        assertThat(VdiHeader.ImageType.fromValue(4)).isEqualTo(VdiHeader.ImageType.DIFFERENCING);
    }

    @Test
    void imageType_fromValue_invalidValue_throwsException() {
        assertThatThrownBy(() -> VdiHeader.ImageType.fromValue(99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constants_haveExpectedValues() {
        assertThat(VdiHeader.MAGIC).isEqualTo(0xBEDA107F);
        assertThat(VdiHeader.MAGIC_OFFSET).isEqualTo(0x40);
        assertThat(VdiHeader.DEFAULT_BLOCK_SIZE).isEqualTo(1024 * 1024);
        assertThat(VdiHeader.BLOCK_FREE).isEqualTo(0xFFFFFFFF);
        assertThat(VdiHeader.BLOCK_ZERO).isEqualTo(0xFFFFFFFE);
    }

    /**
     * Creates a minimal valid VDI header for testing.
     */
    private byte[] createMinimalVdiHeader(VdiHeader.ImageType imageType, long virtualSize) {
        byte[] data = new byte[VdiHeader.MIN_HEADER_SIZE + 4096]; // Header + some space for BAM

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

        // Comment (256 bytes)
        buffer.position(buffer.position() + 256);

        // Offset of blocks (BAM)
        buffer.putInt(VdiHeader.MIN_HEADER_SIZE);

        // Offset of data
        int blockSize = VdiHeader.DEFAULT_BLOCK_SIZE;
        int numBlocks = (int) ((virtualSize + blockSize - 1) / blockSize);
        int bamSize = numBlocks * 4;
        int dataOffset = VdiHeader.MIN_HEADER_SIZE + bamSize;
        // Align to block size
        dataOffset = ((dataOffset + blockSize - 1) / blockSize) * blockSize;
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
        for (int i = 0; i < numBlocks && buffer.remaining() >= 4; i++) {
            buffer.putInt(VdiHeader.BLOCK_FREE);
        }

        return data;
    }
}
