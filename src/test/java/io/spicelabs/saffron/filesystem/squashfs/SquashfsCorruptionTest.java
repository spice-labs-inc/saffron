/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.fs.FileSystemMount.FilesystemLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that corrupted or truncated squashfs images fail gracefully.
 */
class SquashfsCorruptionTest {

    private static final Path FIXTURES = Paths.get("src/test/resources/squashfs");

    /**
     * Opening a truncated image should not throw an unchecked exception; it
     * should either fail to detect the filesystem or fail to mount with a
     * checked exception.
     */
    @Test
    void handlesTruncatedImage(@TempDir Path tempDir) throws IOException {
        Path original = FIXTURES.resolve("alpine-minimal.squashfs");
        Path truncated = tempDir.resolve("truncated.squashfs");
        byte[] bytes = Files.readAllBytes(original);
        Files.write(truncated, Arrays.copyOf(bytes, 50000));

        try (VirtualDisk disk = DiskReader.open(truncated)) {
            Optional<FilesystemLocation> location = FileSystemMount.findLargestFilesystem(disk);
            if (location.isPresent()) {
                assertThatThrownBy(() -> {
                    try (FileSystem fs = FileSystemMount.mount(disk, location.get())) {
                        fs.root().list();
                    }
                }).isInstanceOf(IOException.class);
            }
        }
    }

    /**
     * Corrupting compressed metadata near the end of the image should cause
     * mounting or reading the root directory to fail with a checked exception,
     * not an unchecked exception.
     */
    @Test
    void handlesBadCompression(@TempDir Path tempDir) throws IOException {
        Path original = FIXTURES.resolve("alpine-minimal.squashfs");
        Path corrupted = tempDir.resolve("corrupted.squashfs");
        byte[] bytes = Files.readAllBytes(original);
        long pos = bytes.length - 4096;
        if (pos > 0) {
            bytes[(int) pos] = (byte) ~bytes[(int) pos];
        }
        Files.write(corrupted, bytes);

        try (VirtualDisk disk = DiskReader.open(corrupted)) {
            Optional<FilesystemLocation> location = FileSystemMount.findLargestFilesystem(disk);
            assertThat(location).isPresent();
            assertThatThrownBy(() -> {
                try (FileSystem fs = FileSystemMount.mount(disk, location.get())) {
                    fs.root().list();
                }
            }).isInstanceOf(IOException.class);
        }
    }
}
