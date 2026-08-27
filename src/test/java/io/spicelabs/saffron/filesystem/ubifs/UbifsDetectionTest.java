/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ubifs;

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
 * Tests for UBIFS and UBI detection.
 */
class UbifsDetectionTest {

    private static final String WILD = "src/test/resources/ubi/wild";
    private static final String FIX = "src/test/resources/ubifs/fixtures";

    /**
     * Real-world UBIFS volumes must be detected.
     */
    @ParameterizedTest(name = "sample={0}")
    @ValueSource(strings = {
            "banana-lzo.ubifs", "banana-zlib.ubifs", "banana-zstd.ubifs",
            "factext-test.ubifs", "gouchaoer-ubifs.img"})
    void detectsWildUbifsVolumes(String name) throws IOException {
        byte[] image = Files.readAllBytes(Path.of(WILD, name));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).as("detected %s", name).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.UBIFS);
    }

    /**
     * Real-world UBI containers must be detected (as UBIFS when they carry
     * a UBIFS volume, as UBI otherwise).
     */
    @ParameterizedTest(name = "sample={0}")
    @ValueSource(strings = {
            "fruits.ubi", "orange-truncated.ubi", "ofrak-bcm53xx-carved.ubi",
            "quectel-usrdata.ubi", "histb-data.ubifs"})
    void detectsWildUbiContainers(String name) throws IOException {
        byte[] image = Files.readAllBytes(Path.of(WILD, name));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).as("detected %s", name).isPresent();
        assertThat(info.get().type()).isIn(FileSystemType.UBI, FileSystemType.UBIFS);
    }

    /**
     * Synthetic mkfs.ubifs images (all compressors) must be detected.
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-zlib.ubifs", "tree-lzo.ubifs", "tree-zstd.ubifs",
            "tree-none.ubifs"})
    void detectsSyntheticVolumes(String name) throws IOException {
        byte[] image = Files.readAllBytes(Path.of(FIX, name));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).as("detected %s", name).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.UBIFS);
    }

    /**
     * A synthetic two-volume UBI container must be detected.
     */
    @Test
    void detectsSyntheticUbiContainer() throws IOException {
        byte[] image = Files.readAllBytes(Path.of(FIX, "two-vol.ubi"));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.UBIFS);
    }

    /**
     * Random bytes must not be detected.
     */
    @Test
    void rejectsRandomData() throws IOException {
        byte[] image = new byte[128 * 1024];
        new Random(42).nextBytes(image);

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
