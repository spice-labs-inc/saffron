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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-style round-trip test for squashfs images created with mksquashfs.
 */
class SquashfsPropertyTest {

    /**
     * Generates a small random directory tree, creates a squashfs image with
     * {@code mksquashfs}, mounts the image, and verifies that every file and
     * directory round-trips with the same name, type, and content.
     */
    @Test
    void roundTrip(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Map<String, byte[]> expectedFiles = new HashMap<>();
        createRandomTree(source, source, new Random(42), 3, expectedFiles);

        Path image = tempDir.resolve("test.squashfs");
        ProcessBuilder pb = new ProcessBuilder("mksquashfs", source.toString(), image.toString(), "-noappend", "-quiet");
        Process process = pb.inheritIO().start();
        int exit = process.waitFor();
        assertThat(exit).isEqualTo(0);
        assertThat(Files.exists(image)).isTrue();

        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            List<FileSystemEntry> entries = new ArrayList<>();
            try (Stream<FileSystemEntry> walk = fs.walk()) {
                walk.forEach(entries::add);
            }
            List<String> paths = entries.stream().map(FileSystemEntry::path).sorted().collect(Collectors.toList());
            assertThat(paths).containsAll(expectedFiles.keySet().stream().map(k -> "/" + k).collect(Collectors.toSet()));

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

    private void createRandomTree(Path root, Path dir, Random random, int depth, Map<String, byte[]> files) throws IOException {
        int filesHere = random.nextInt(4) + 1;
        for (int i = 0; i < filesHere; i++) {
            String name = "file" + i + "_" + random.nextInt(1000);
            Path file = dir.resolve(name);
            byte[] content = new byte[random.nextInt(200) + 1];
            random.nextBytes(content);
            Files.write(file, content);
            Path rel = root.relativize(file);
            files.put(rel.toString().replace("\\", "/"), content);
        }
        if (depth > 0) {
            int dirs = random.nextInt(3) + 1;
            for (int i = 0; i < dirs; i++) {
                String name = "dir" + i + "_" + random.nextInt(1000);
                Path child = dir.resolve(name);
                Files.createDirectories(child);
                createRandomTree(root, child, random, depth - 1, files);
            }
        }
    }

    /**
     * Verifies that squashfs images created with each supported compression
     * algorithm can be mounted and read back.
     */
    @ParameterizedTest(name = "compression={0}")
    @ValueSource(strings = {"xz", "gzip", "lzo", "lz4", "zstd"})
    void roundTripWithCompression(String compression, @TempDir Path tempDir) throws IOException, InterruptedException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Path file = source.resolve("hello.txt");
        byte[] content = "Hello, squashfs!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, content);

        Path image = tempDir.resolve("test-" + compression + ".squashfs");
        ProcessBuilder pb = new ProcessBuilder(
                "mksquashfs", source.toString(), image.toString(),
                "-comp", compression, "-noappend", "-quiet");
        Process process = pb.inheritIO().start();
        int exit = process.waitFor();
        assertThat(exit).as("mksquashfs with %s", compression).isEqualTo(0);
        assertThat(Files.exists(image)).isTrue();

        try (VirtualDisk disk = DiskReader.open(image);
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
    void roundTripUncompressed(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Path file = source.resolve("hello.txt");
        byte[] content = "Hello, squashfs!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, content);

        Path image = tempDir.resolve("test-uncompressed.squashfs");
        ProcessBuilder pb = new ProcessBuilder(
                "mksquashfs", source.toString(), image.toString(),
                "-no-compression", "-noappend", "-quiet");
        Process process = pb.inheritIO().start();
        int exit = process.waitFor();
        assertThat(exit).as("mksquashfs uncompressed").isEqualTo(0);
        assertThat(Files.exists(image)).isTrue();

        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> found = fs.resolve("/hello.txt");
            assertThat(found).isPresent();
            FileSystemEntry entry = found.get();
            assertThat(entry.type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
            byte[] read = ((FileSystemEntry.RegularFile) entry).readAllBytes();
            assertThat(read).containsExactly(content);
        }
    }

    private static FileSystem mountLargest(VirtualDisk disk) throws IOException {
        Optional<FilesystemLocation> location = FileSystemMount.findLargestFilesystem(disk);
        assertThat(location).isPresent();
        return FileSystemMount.mount(disk, location.get());
    }
}
