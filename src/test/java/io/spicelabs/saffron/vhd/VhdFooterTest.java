/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.vhd;

import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.exception.UnsupportedVersionException;
import io.spicelabs.saffron.vhd.footer.VhdFooter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link VhdFooter}.
 */
class VhdFooterTest {

    @Test
    void readFooter_validMagic_succeeds(@TempDir Path tempDir) throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.FIXED, 10 * 1024 * 1024);
        Path vhd = tempDir.resolve("test.vhd");
        Files.write(vhd, vhdData);

        try (SeekableByteChannel channel = Files.newByteChannel(vhd)) {
            VhdFooter footer = VhdFooter.read(channel);

            assertThat(footer.diskType()).isEqualTo(VhdFooter.DiskType.FIXED);
            assertThat(footer.virtualSize()).isEqualTo(10 * 1024 * 1024);
            assertThat(footer.creatorApplication()).isEqualTo("test");
        }
    }

    @Test
    void readFooter_invalidMagic_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        byte[] badData = new byte[1024];
        Path vhd = tempDir.resolve("bad.vhd");
        Files.write(vhd, badData);

        try (SeekableByteChannel channel = Files.newByteChannel(vhd)) {
            assertThatThrownBy(() -> VhdFooter.read(channel))
                    .isInstanceOf(InvalidMagicException.class)
                    .hasMessageContaining("conectix");
        }
    }

    @Test
    void readFooter_dynamicDisk_parsesCorrectly(@TempDir Path tempDir) throws IOException {
        byte[] vhdData = createMinimalVhd(VhdFooter.DiskType.DYNAMIC, 50 * 1024 * 1024);
        Path vhd = tempDir.resolve("dynamic.vhd");
        Files.write(vhd, vhdData);

        try (SeekableByteChannel channel = Files.newByteChannel(vhd)) {
            VhdFooter footer = VhdFooter.read(channel);

            assertThat(footer.diskType()).isEqualTo(VhdFooter.DiskType.DYNAMIC);
            assertThat(footer.isDynamic()).isTrue();
            assertThat(footer.isFixed()).isFalse();
        }
    }

    @Test
    void diskType_fromValue_returnsCorrectType() {
        assertThat(VhdFooter.DiskType.fromValue(2)).isEqualTo(VhdFooter.DiskType.FIXED);
        assertThat(VhdFooter.DiskType.fromValue(3)).isEqualTo(VhdFooter.DiskType.DYNAMIC);
        assertThat(VhdFooter.DiskType.fromValue(4)).isEqualTo(VhdFooter.DiskType.DIFFERENCING);
    }

    @Test
    void diskType_fromInvalidValue_throwsException() {
        assertThatThrownBy(() -> VhdFooter.DiskType.fromValue(99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void footer_constants_areCorrect() {
        assertThat(VhdFooter.FOOTER_SIZE).isEqualTo(512);
        assertThat(VhdFooter.VERSION_1_0).isEqualTo(0x00010000);
        assertThat(new String(VhdFooter.MAGIC)).isEqualTo("conectix");
    }

    @Test
    void readFooter_fileTooSmall_throwsException(@TempDir Path tempDir) throws IOException {
        byte[] smallData = new byte[100];
        Path vhd = tempDir.resolve("small.vhd");
        Files.write(vhd, smallData);

        try (SeekableByteChannel channel = Files.newByteChannel(vhd)) {
            assertThatThrownBy(() -> VhdFooter.read(channel))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("too small");
        }
    }

    @Test
    void differentSizes_haveDifferentFooters(@TempDir Path tempDir) throws IOException {
        byte[] vhd1 = createMinimalVhd(VhdFooter.DiskType.FIXED, 10 * 1024 * 1024);
        byte[] vhd2 = createMinimalVhd(VhdFooter.DiskType.FIXED, 100 * 1024 * 1024);

        Path file1 = tempDir.resolve("small.vhd");
        Path file2 = tempDir.resolve("large.vhd");
        Files.write(file1, vhd1);
        Files.write(file2, vhd2);

        try (SeekableByteChannel ch1 = Files.newByteChannel(file1);
             SeekableByteChannel ch2 = Files.newByteChannel(file2)) {

            VhdFooter footer1 = VhdFooter.read(ch1);
            VhdFooter footer2 = VhdFooter.read(ch2);

            assertThat(footer1.virtualSize()).isNotEqualTo(footer2.virtualSize());
            assertThat(footer1.virtualSize()).isEqualTo(10 * 1024 * 1024);
            assertThat(footer2.virtualSize()).isEqualTo(100 * 1024 * 1024);
        }
    }

    /**
     * Creates a minimal valid VHD file for testing.
     */
    private byte[] createMinimalVhd(VhdFooter.DiskType diskType, long virtualSize) {
        int dataSize = 512;
        byte[] data = new byte[dataSize + 512];

        ByteBuffer footer = ByteBuffer.wrap(data, dataSize, 512);
        footer.order(ByteOrder.BIG_ENDIAN);

        // Cookie "conectix"
        footer.put("conectix".getBytes());

        // Features
        footer.putInt(0x00000002);

        // File format version
        footer.putInt(0x00010000);

        // Data offset
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

        // Disk geometry
        int cylinders = (int) Math.min(virtualSize / (16 * 63 * 512), 65535);
        footer.putShort((short) cylinders);
        footer.put((byte) 16);
        footer.put((byte) 63);

        // Disk type
        footer.putInt(diskType.value());

        // Checksum
        footer.putInt(0);

        // Unique ID
        footer.putLong(System.currentTimeMillis());
        footer.putLong(System.nanoTime());

        // Saved state
        footer.put((byte) 0);

        return data;
    }
}
