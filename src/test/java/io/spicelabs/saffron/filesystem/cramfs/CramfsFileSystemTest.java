/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.cramfs;

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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for the cramfs mount implementation.
 *
 * <p>Fixtures are committed images generated once with util-linux
 * mkfs.cramfs (see {@code src/test/resources/cramfs/generate-fixtures.sh}).
 * Tests never invoke an external process.
 */
class CramfsFileSystemTest {

    private static final String FIXTURE_DIR = "src/test/resources/cramfs/fixtures";

    /**
     * The fixture tree:
     * <pre>
     * /root.txt        "root"
     * /lnk.txt         symlink -> dir1/a.bin
     * /empty.txt       empty
     * /compress.txt    90000 bytes of repeating text (zlib-compressed blocks)
     * /sparse.bin      104096 bytes: 8192 'A', hole, 4096 'Z'
     * /dir1/a.bin      "aaa"
     * /dir1/sub/deep.txt "deep"
     * /dir2/b.txt      "bb"
     * </pre>
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree.cramfs", "tree-be.cramfs"})
    void roundTripsAllFiles(String name) throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture(name));
             FileSystem fs = mountLargest(disk)) {
            assertContent(fs, "/root.txt", "root".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/empty.txt", new byte[0]);
            assertContent(fs, "/dir1/a.bin", "aaa".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/dir1/sub/deep.txt", "deep".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/dir2/b.txt", "bb".getBytes(StandardCharsets.UTF_8));

            assertSha256(fs, "/compress.txt",
                    "14cd2aaa414efb3b3b2610eb9942f2dc739ae65f558aa17ab624c0df21157a29");
            assertSha256(fs, "/sparse.bin",
                    "dea5b66bebbeccf48a3821c2664cab6bc353e917d9c7b37ac6f298c3c2f8a061");
        }
    }

    /**
     * The symlink /lnk.txt targets dir1/a.bin. {@code fs.resolve()} follows
     * symlinks; the link entry itself is reached via the parent's find().
     */
    @Test
    void resolvesSymlink() throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture("tree.cramfs"));
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> entry = fs.root().find("lnk.txt");
            assertThat(entry).isPresent();
            assertThat(entry.get().type()).isEqualTo(FileSystemEntry.EntryType.SYMBOLIC_LINK);
            FileSystemEntry.SymbolicLink link = (FileSystemEntry.SymbolicLink) entry.get();
            assertThat(link.target()).isEqualTo("dir1/a.bin");
            Optional<FileSystemEntry> target = link.resolve();
            assertThat(target).isPresent();
            assertThat(((FileSystemEntry.RegularFile) target.get()).readAllBytes())
                    .containsExactly("aaa".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * walk() visits every entry exactly once with correct absolute paths.
     */
    @Test
    void walkVisitsAllEntries() throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture("tree.cramfs"));
             FileSystem fs = mountLargest(disk)) {
            List<FileSystemEntry> entries;
            try (Stream<FileSystemEntry> walk = fs.walk()) {
                entries = walk.toList();
            }
            List<String> paths = entries.stream().map(FileSystemEntry::path).toList();
            assertThat(paths).containsExactlyInAnyOrder(
                    "/", "/root.txt", "/lnk.txt", "/empty.txt",
                    "/compress.txt", "/sparse.bin",
                    "/dir1", "/dir1/a.bin", "/dir1/sub", "/dir1/sub/deep.txt",
                    "/dir2", "/dir2/b.txt");
        }
    }

    /**
     * An empty cramfs image mounts with an empty root directory.
     */
    @Test
    void mountsEmptyFilesystem() throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture("empty.cramfs"));
             FileSystem fs = mountLargest(disk)) {
            List<String> names;
            try (Stream<FileSystemEntry> list = fs.root().list()) {
                names = list.map(FileSystemEntry::name).toList();
            }
            assertThat(names).isEmpty();
        }
    }

    /**
     * Resolving a non-existent path returns empty rather than throwing.
     */
    @Test
    void resolveMissingReturnsEmpty() throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture("tree.cramfs"));
             FileSystem fs = mountLargest(disk)) {
            assertThat(fs.resolve("/does-not-exist")).isEmpty();
        }
    }

    /**
     * Metadata reports the filesystem name and file count from fsid.
     */
    @Test
    void metadataReportsNameAndCounts() throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture("tree.cramfs"));
             FileSystem fs = mountLargest(disk)) {
            assertThat(fs.metadata()).containsEntry("version", "cramfs");
            long files = Long.parseLong(fs.metadata().get("fileCount"));
            assertThat(files).isGreaterThan(0);
        }
    }

    private static void assertContent(FileSystem fs, String path, byte[] expected) throws IOException {
        Optional<FileSystemEntry> found = fs.resolve(path);
        assertThat(found).as("resolving %s", path).isPresent();
        FileSystemEntry entry = found.get();
        assertThat(entry.type()).as("type of %s", path).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
        byte[] content = ((FileSystemEntry.RegularFile) entry).readAllBytes();
        assertThat(content).as("content of %s", path).containsExactly(expected);
    }

    private static void assertSha256(FileSystem fs, String path, String expectedSha256) throws IOException {
        Optional<FileSystemEntry> found = fs.resolve(path);
        assertThat(found).as("resolving %s", path).isPresent();
        byte[] content = ((FileSystemEntry.RegularFile) found.get()).readAllBytes();
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        assertThat(sha).as("sha256 of %s", path).isEqualTo(expectedSha256);
    }

    private static Path fixture(String name) {
        return Path.of(FIXTURE_DIR, name);
    }

    private static FileSystem mountLargest(VirtualDisk disk) throws IOException {
        Optional<FilesystemLocation> location = FileSystemMount.findLargestFilesystem(disk);
        assertThat(location).isPresent();
        assertThat(location.get().info().type()).isEqualTo(FileSystem.FileSystemType.CRAMFS);
        return FileSystemMount.mount(disk, location.get());
    }
}
