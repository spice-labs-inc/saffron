/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.cramfs;

import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for cramfs filesystem detection.
 *
 * <p>cramfs is recognized by the magic {@code 0x28cd3d45} at offset 0
 * (byte-swapped {@code 0x453dcd28} for big-endian images) plus the
 * {@code "Compressed ROMFS"} signature at offset 16.
 */
class CramfsDetectionTest {

    private static final String FIXTURE_DIR = "src/test/resources/cramfs/fixtures";

    /**
     * A little-endian mkcramfs image must be detected as CRAMFS.
     */
    @Test
    void detectsLittleEndianFixture() throws IOException {
        byte[] image = Files.readAllBytes(Path.of(FIXTURE_DIR, "tree.cramfs"));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.CRAMFS);
        assertThat(info.get().version()).isEqualTo("cramfs");
    }

    /**
     * A big-endian (cramfsswap'd) image must also be detected: mkcramfs
     * writes images in host byte order, so both endians exist in the wild.
     */
    @Test
    void detectsBigEndianFixture() throws IOException {
        byte[] image = Files.readAllBytes(Path.of(FIXTURE_DIR, "tree-be.cramfs"));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.CRAMFS);
    }

    /**
     * An empty cramfs image (root offset 0, no entries) is still valid.
     */
    @Test
    void detectsEmptyFixture() throws IOException {
        byte[] image = Files.readAllBytes(Path.of(FIXTURE_DIR, "empty.cramfs"));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.CRAMFS);
    }

    /**
     * Random bytes must not be detected.
     */
    @Test
    void rejectsRandomData() throws IOException {
        byte[] image = new byte[4096];
        new Random(42).nextBytes(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A buffer too small to hold the superblock must not be detected.
     */
    @Test
    void rejectsTruncatedImage() throws IOException {
        byte[] image = new byte[32];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, 0x28cd3d45);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * The magic alone (without the "Compressed ROMFS" signature and without
     * the WRONG_SIGNATURE flag) is not enough evidence.
     */
    @Test
    void rejectsMagicWithoutSignature() throws IOException {
        byte[] image = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, 0x28cd3d45);
        buf.putInt(8, 0); // flags: no WRONG_SIGNATURE

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A magic with the WRONG_SIGNATURE flag set is accepted (some historical
     * mkcramfs versions wrote a different signature string).
     */
    @Test
    void acceptsWrongSignatureFlag() throws IOException {
        byte[] image = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, 0x28cd3d45);
        buf.putInt(8, 0x200); // CRAMFS_FLAG_WRONG_SIGNATURE
        // root inode: directory
        buf.putInt(64, 0x000041ed);
        buf.putInt(68, 0x00000000);
        buf.putInt(72, 0x00000000);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.CRAMFS);
    }

    /**
     * A superblock whose root inode is not a directory (and offset is
     * non-zero) is rejected.
     */
    @Test
    void rejectsNonDirectoryRoot() throws IOException {
        byte[] image = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, 0x28cd3d45);
        byte[] sig = "Compressed ROMFS".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(sig, 0, image, 16, sig.length);
        // root inode: regular file with data offset
        buf.putInt(64, 0x000081a4);
        buf.putInt(68, 0x00000004);
        buf.putInt(72, 0x00000000 | (10 << 6));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A cramfs whose superblock sits at offset 512 (boot-sector prefix) is
     * detected: the kernel explicitly supports this layout.
     */
    @Test
    void detectsSuperblockAt512() throws IOException {
        byte[] base = Files.readAllBytes(Path.of(FIXTURE_DIR, "tree.cramfs"));
        byte[] image = new byte[512 + base.length];
        System.arraycopy(base, 0, image, 512, base.length);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.CRAMFS);
    }

    static final class ByteArrayDiskRegion implements DiskRegion {
        private final byte[] data;

        ByteArrayDiskRegion(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IOException("Read out of bounds");
            }
            byte[] copy = new byte[length];
            System.arraycopy(data, (int) offset, copy, 0, length);
            return ByteBuffer.wrap(copy);
        }

        @Override
        public long size() {
            return data.length;
        }
    }
}
