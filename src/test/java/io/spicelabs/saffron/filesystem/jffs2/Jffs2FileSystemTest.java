/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.jffs2;

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
 * Round-trip tests for the JFFS2 mount implementation.
 *
 * <p>Fixtures are committed images generated once by the reference
 * {@code mkfs.jffs2} tool (see
 * {@code src/test/resources/jffs2/generate-fixtures.sh}). Tests never invoke
 * an external process.
 */
class Jffs2FileSystemTest {

    private static final String FIXTURE_DIR = "src/test/resources/jffs2/fixtures";

    /**
     * The fixture tree contains:
     * <pre>
     * /root.txt        "root"
     * /hlink.txt       hard link to root.txt
     * /lnk.txt         symlink -> dir1/a.bin
     * /empty.txt       empty
     * /compress.txt    82000 bytes of repeating text
     * /zeros.bin       16384 zero bytes
     * /big.bin         40960 random bytes
     * /dir1/a.bin      "aaa"
     * /dir1/sub/deep.txt "deep"
     * /dir2/b.txt      "bb"
     * </pre>
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-none.jffs2", "tree-zlib.jffs2", "tree-lzo.jffs2",
            "tree-rtime.jffs2", "tree-none-noclean.jffs2"})
    void roundTripsAllFiles(String name) throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture(name));
             FileSystem fs = mountLargest(disk)) {
            assertContent(fs, "/root.txt", "root".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/hlink.txt", "root".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/empty.txt", new byte[0]);
            assertContent(fs, "/dir1/a.bin", "aaa".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/dir1/sub/deep.txt", "deep".getBytes(StandardCharsets.UTF_8));
            assertContent(fs, "/dir2/b.txt", "bb".getBytes(StandardCharsets.UTF_8));

            // Deterministic bulk content is verified by SHA-256 (computed from
            // the source files at fixture generation time).
            assertSha256(fs, "/compress.txt",
                    "240b3415320dcb235c0702aa97a416d59f42ab46ab86a3b65a2fd43482267df3");
            assertSha256(fs, "/zeros.bin",
                    "4fe7b59af6de3b665b67788cc2f99892ab827efae3a467342b3bb4e3bc8e5bfe");
            assertSha256(fs, "/big.bin",
                    "5b8a0931393094bcc456db79e2ab6433d0f5af073bfbee7489853be5774060c2");
        }
    }

    /**
     * The symlink /lnk.txt resolves to the content of /dir1/a.bin. Note that
     * {@code fs.resolve()} follows symlinks (returning the target); the link
     * entry itself is reached via the parent directory's {@code find()}.
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-zlib.jffs2"})
    void resolvesSymlink(String name) throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture(name));
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
     * The hard link /hlink.txt and /root.txt share the same inode and content.
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-zlib.jffs2"})
    void hardLinksShareContent(String name) throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture(name));
             FileSystem fs = mountLargest(disk)) {
            byte[] viaRoot = ((FileSystemEntry.RegularFile) fs.resolve("/root.txt").orElseThrow()).readAllBytes();
            byte[] viaLink = ((FileSystemEntry.RegularFile) fs.resolve("/hlink.txt").orElseThrow()).readAllBytes();
            assertThat(viaLink).containsExactly(viaRoot);
        }
    }

    /**
     * walk() must visit every file and directory exactly once, with correct
     * absolute paths and entry types.
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-zlib.jffs2"})
    void walkVisitsAllEntries(String name) throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture(name));
             FileSystem fs = mountLargest(disk)) {
            List<FileSystemEntry> entries;
            try (Stream<FileSystemEntry> walk = fs.walk()) {
                entries = walk.toList();
            }
            List<String> paths = entries.stream().map(FileSystemEntry::path).toList();
            assertThat(paths).containsExactlyInAnyOrder(
                    "/", "/root.txt", "/hlink.txt", "/lnk.txt", "/empty.txt",
                    "/compress.txt", "/zeros.bin", "/big.bin",
                    "/dir1", "/dir1/a.bin", "/dir1/sub", "/dir1/sub/deep.txt",
                    "/dir2", "/dir2/b.txt");
        }
    }

    /**
     * The root directory lists the expected entries.
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-zlib.jffs2"})
    void rootDirectoryListsEntries(String name) throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture(name));
             FileSystem fs = mountLargest(disk)) {
            List<String> names;
            try (Stream<FileSystemEntry> list = fs.root().list()) {
                names = list.map(FileSystemEntry::name).toList();
            }
            assertThat(names).contains("root.txt", "dir1", "dir2", "lnk.txt", "hlink.txt");
        }
    }

    /**
     * Resolving a non-existent path returns empty rather than throwing.
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-zlib.jffs2"})
    void resolveMissingReturnsEmpty(String name) throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture(name));
             FileSystem fs = mountLargest(disk)) {
            assertThat(fs.resolve("/does-not-exist")).isEmpty();
        }
    }

    /**
     * Filesystem metadata reports the format version and a positive inode
     * count.
     */
    @Test
    void metadataReportsVersionAndInodeCount() throws IOException {
        try (VirtualDisk disk = DiskReader.open(fixture("tree-zlib.jffs2"));
             FileSystem fs = mountLargest(disk)) {
            assertThat(fs.metadata()).containsEntry("version", "jffs2");
            long inodeCount = Long.parseLong(fs.metadata().get("inodeCount"));
            assertThat(inodeCount).isGreaterThan(0);
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
        FileSystemEntry entry = found.get();
        assertThat(entry.type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
        byte[] content = ((FileSystemEntry.RegularFile) entry).readAllBytes();
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
        assertThat(location.get().info().type()).isEqualTo(FileSystem.FileSystemType.JFFS2);
        return FileSystemMount.mount(disk, location.get());
    }
}
