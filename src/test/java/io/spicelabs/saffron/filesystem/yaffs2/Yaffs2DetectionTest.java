/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.yaffs2;

import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for YAFFS2 filesystem detection.
 *
 * <p>YAFFS2 has no magic number: detection infers the chunk geometry
 * (page + spare sizes) and tag/data endianness and requires a valid
 * object-header population.
 */
class Yaffs2DetectionTest {

    private static final String WILD = "src/test/resources/yaffs2/wild";

    /**
     * Real-world samples covering the geometry matrix must all be detected.
     */
    @ParameterizedTest(name = "sample={0}")
    @ValueSource(strings = {
            "binwalk-yaffs2.bin",
            "unblob-sample.2048.16.le.yaffs2",
            "unblob-sample.2048.64.le.yaffs2",
            "unblob-sample.2048.512.le.yaffs2",
            "unblob-sample.4096.128.le.yaffs2",
            "unblob-sample.8192.256.le.yaffs2",
            "unblob-sample.16384.64.le.yaffs2",
            "ofrak-yaffs2-2k64-le.img",
            "ofrak-yaffs2-2k64-be.img",
            "ofrak-yaffs2-4k128-le.img",
            "factext-yaffs2-le.img",
            "factext-yaffs2-be.img",
            "ba-yaffs2-2048-64-be-empty-dir.img",
            "ba-yaffs2-2048-64-be-empty-file.img",
            "ba-yaffs2-2048-64-be-dir-with-file.img",
            "ba-yaffs2-2048-64-be-links.img",
            "ba-yaffs2-1024-32-le-empty-dir.img",
            "ba-yaffs2-1024-32-be-dir-with-file.img",
            "ba-yaffs2-4096-64-be-empty-dir.img",
            "ba-yaffs2-8192-256-be-empty-dir.img",
            "ustc-lab-rootfs.yaffs2"})
    void detectsWildSamples(String name) throws IOException {
        byte[] image = Files.readAllBytes(Path.of(WILD, name));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).as("detected %s", name).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.YAFFS2);
        assertThat(info.get().version()).isEqualTo("yaffs2");
    }

    /**
     * Random bytes must not be detected.
     */
    @Test
    void rejectsRandomData() throws IOException {
        byte[] image = new byte[2112 * 8];
        new Random(42).nextBytes(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A region whose size is not a multiple of any candidate chunk size
     * must not be detected.
     */
    @Test
    void rejectsUnalignedSize() throws IOException {
        byte[] image = new byte[2112 * 4 + 100];
        new Random(7).nextBytes(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A region too small for two chunks must not be detected.
     */
    @Test
    void rejectsTinyRegion() throws IOException {
        byte[] image = new byte[1024];

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
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
