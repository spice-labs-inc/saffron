/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.fs.FileSystemMount.FilesystemLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for squashfs images.
 *
 * <p>The fixtures are pre-built squashfs images checked in under
 * {@code src/test/resources/squashfs/fixtures/} (generated once with the
 * {@code mksquashfs} tool during fixture setup). Tests never invoke any external
 * process.
 */
class SquashfsPropertyTest {

    private static final String FIXTURE_DIR = "src/test/resources/squashfs/fixtures";

    /**
     * Mounts a pre-built squashfs image containing a small, fixed directory tree
     * and verifies that every file and directory round-trips with the same name,
     * type, and content.
     */
    @Test
    void roundTrip() throws IOException {
        Map<String, byte[]> expectedFiles = Map.of(
                "root.txt", "root".getBytes(StandardCharsets.UTF_8),
                "dir1/a.bin", "aaa".getBytes(StandardCharsets.UTF_8),
                "dir1/sub/deep.txt", "deep".getBytes(StandardCharsets.UTF_8),
                "dir2/b.txt", "bb".getBytes(StandardCharsets.UTF_8));

        try (VirtualDisk disk = DiskReader.open(fixture("tree.squashfs"));
             FileSystem fs = mountLargest(disk)) {
            List<FileSystemEntry> entries;
            try (Stream<FileSystemEntry> walk = fs.walk()) {
                entries = walk.toList();
            }
            List<String> paths = entries.stream().map(FileSystemEntry::path).sorted().toList();
            assertThat(paths).containsAll(expectedFiles.keySet().stream().map(k -> "/" + k).toList());

            for (Map.Entry<String, byte[]> expected : expectedFiles.entrySet()) {
                String path = "/" + expected.getKey();
                Optional<FileSystemEntry> found = fs.resolve(path);
                assertThat(found).as("expected %s", path).isPresent();
                FileSystemEntry entry = found.get();
                assertThat(entry.type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
                byte[] content = ((FileSystemEntry.RegularFile) entry).readAllBytes();
                assertThat(content).as("content of %s", path).containsExactly(expected.getValue());
            }
        }
    }

    /**
     * Verifies that squashfs images created with each supported compression
     * algorithm can be mounted and read back.
     */
    @ParameterizedTest(name = "compression={0}")
    @ValueSource(strings = {"xz", "gzip", "lzo", "lz4", "zstd"})
    void roundTripWithCompression(String compression) throws IOException {
        byte[] content = "Hello, squashfs!".getBytes(StandardCharsets.UTF_8);

        try (VirtualDisk disk = DiskReader.open(fixture("comp-" + compression + ".squashfs"));
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> found = fs.resolve("/hello.txt");
            assertThat(found).isPresent();
            FileSystemEntry entry = found.get();
            assertThat(entry.type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
            byte[] read = ((FileSystemEntry.RegularFile) entry).readAllBytes();
            assertThat(read).containsExactly(content);
        }
    }

    /**
     * Verifies that an uncompressed squashfs image can be mounted and read back.
     */
    @Test
    void roundTripUncompressed() throws IOException {
        byte[] content = "Hello, squashfs!".getBytes(StandardCharsets.UTF_8);

        try (VirtualDisk disk = DiskReader.open(fixture("uncompressed.squashfs"));
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> found = fs.resolve("/hello.txt");
            assertThat(found).isPresent();
            FileSystemEntry entry = found.get();
            assertThat(entry.type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
            byte[] read = ((FileSystemEntry.RegularFile) entry).readAllBytes();
            assertThat(read).containsExactly(content);
        }
    }

    private static Path fixture(String name) {
        return Path.of(FIXTURE_DIR, name);
    }

    private static FileSystem mountLargest(VirtualDisk disk) throws IOException {
        Optional<FilesystemLocation> location = FileSystemMount.findLargestFilesystem(disk);
        assertThat(location).isPresent();
        return FileSystemMount.mount(disk, location.get());
    }
}