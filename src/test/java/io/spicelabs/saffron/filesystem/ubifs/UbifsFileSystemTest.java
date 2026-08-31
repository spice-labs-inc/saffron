/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ubifs;

import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.fs.FileSystemMount.FilesystemLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for the UBIFS mount implementation against committed
 * mkfs.ubifs fixtures (all four compressors).
 */
class UbifsFileSystemTest {

    private static final String FIX = "src/test/resources/ubifs/fixtures";

    /**
     * The fixture tree: root.txt, dir1/{a.bin,sub/deep.txt}, dir2/b.txt,
     * empty.txt, compress.txt (160000 bytes), sparse.bin (8K A + hole + 4K Z),
     * lnk.txt (symlink), hlink.txt (hardlink).
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-zlib.ubifs", "tree-lzo.ubifs", "tree-zstd.ubifs",
            "tree-none.ubifs"})
    void roundTripsAllFiles(String name) throws IOException {
        try (VirtualDiskHolder holder = open(fixture(name));
             FileSystem fs = holder.fs) {
            assertContent(fs, "/root.txt", "root".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/empty.txt", new byte[0]);
            assertContent(fs, "/dir1/a.bin", "aaa".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/dir1/sub/deep.txt", "deep".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/dir2/b.txt", "bb".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/hlink.txt", "root".getBytes(StandardCharsets.UTF_8));

            assertSha256(fs, "/compress.txt",
                    "173e80e4732d43327314371fe9ccaab78f59d0ca6cabdcc356318add25912775");
            assertSha256(fs, "/sparse.bin",
                    "e1a87f54d66d457dfcc2b10aa03f51ce0ed19ef2cf49d5a091caa328c1222616");

            // symlink via the parent's find (resolve follows links)
            Optional<FileSystemEntry> link = fs.root().find("lnk.txt");
            assertThat(link).isPresent();
            assertThat(link.get().type()).isEqualTo(FileSystemEntry.EntryType.SYMBOLIC_LINK);
            assertThat(((FileSystemEntry.SymbolicLink) link.get()).target()).isEqualTo("dir1/a.bin");
        }
    }

    /**
     * walk() visits the full tree with correct absolute paths.
     */
    @Test
    void walkVisitsAllEntries() throws IOException {
        try (VirtualDiskHolder holder = open(fixture("tree-zlib.ubifs"));
             FileSystem fs = holder.fs) {
            List<FileSystemEntry> entries;
            try (Stream<FileSystemEntry> walk = fs.walk()) {
                entries = walk.toList();
            }
            List<String> paths = entries.stream().map(FileSystemEntry::path).toList();
            assertThat(paths).containsExactlyInAnyOrder(
                    "/", "/root.txt", "/lnk.txt", "/hlink.txt", "/empty.txt",
                    "/compress.txt", "/sparse.bin",
                    "/dir1", "/dir1/a.bin", "/dir1/sub", "/dir1/sub/deep.txt",
                    "/dir2", "/dir2/b.txt");
        }
    }

    /**
     * Metadata reports the UBIFS geometry and compression.
     */
    @Test
    void metadataReportsGeometry() throws IOException {
        try (VirtualDiskHolder holder = open(fixture("tree-zstd.ubifs"));
             FileSystem fs = holder.fs) {
            assertThat(fs.metadata()).containsEntry("version", "ubifs");
            assertThat(fs.metadata()).containsEntry("compression", "zstd");
            assertThat(Long.parseLong(fs.metadata().get("lebSize"))).isGreaterThan(0);
        }
    }

    /** Opens a bare UBIFS fixture and mounts it. */
    private static VirtualDiskHolder open(Path path) throws IOException {
        io.spicelabs.saffron.VirtualDisk disk =
                io.spicelabs.saffron.DiskReader.open(path,
                        io.spicelabs.saffron.DiskFormat.RAW);
        Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);
        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystem.FileSystemType.UBIFS);
        FileSystem fs = FileSystemMount.mount(disk,
                new FilesystemLocation(0, info.get(), Optional.empty()));
        return new VirtualDiskHolder(disk, fs);
    }

    private record VirtualDiskHolder(io.spicelabs.saffron.VirtualDisk disk,
                                     FileSystem fs) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            fs.close();
            disk.close();
        }
    }

    private static void assertContent(FileSystem fs, String path, byte[] expected)
            throws IOException {
        Optional<FileSystemEntry> found = fs.resolve(path);
        assertThat(found).as("resolving %s", path).isPresent();
        assertThat(found.get().type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
        byte[] content = ((FileSystemEntry.RegularFile) found.get()).readAllBytes();
        assertThat(content).as("content of %s", path).containsExactly(expected);
    }

    private static void assertSha256(FileSystem fs, String path, String expected)
            throws IOException {
        Optional<FileSystemEntry> found = fs.resolve(path);
        assertThat(found).as("resolving %s", path).isPresent();
        byte[] content = ((FileSystemEntry.RegularFile) found.get()).readAllBytes();
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        assertThat(sha).as("sha256 of %s", path).isEqualTo(expected);
    }

    private static Path fixture(String name) {
        return Path.of(FIX, name);
    }
}
