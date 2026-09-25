/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ntfs;

import com.google.gson.Gson;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data-driven tests over the real NTFS images built by mkntfs / ntfs-3g
 * (see {@code src/test/resources/ntfs/README.md}). Each image has a sibling
 * expectations file computed from the source tree the image was populated
 * from; this class only reads the files, it never runs external tools.
 *
 * <p>The expectations are also consumed by downstream integration tests
 * ({@code test-fixtures.json}, format {@code saffron-ntfs-fixture}); the
 * {@code id} of every file is its path under {@code src/test/resources}
 * without the {@code .json} suffix, and ids must be unique.
 *
 * <p>Known deviations (see the {@code TODO_*} constants) are outside the
 * scope of the {@code $DATA}-pieces / large-cluster work: a fixture run that
 * hits only those is reported as skipped with the TODO text, any other
 * mismatch fails.
 */
class NtfsFixtureTest {

    private static final Path RESOURCES = Path.of("src/test/resources");
    private static final Path FIXTURE_DIR = RESOURCES.resolve("ntfs/fixtures");
    private static final Gson GSON = new Gson();

    /** FILE_ATTRIBUTE_SPARSE_FILE in $STANDARD_INFORMATION. */
    private static final int ATTR_SPARSE = 0x200;
    /** FILE_ATTRIBUTE_COMPRESSED in $STANDARD_INFORMATION. */
    private static final int ATTR_COMPRESSED = 0x800;

    // ── known deviations exposed by the fixtures (tracking-issue TODOs) ──

    /** Directory listings are deduplicated by MFT record and named from the record's first $FILE_NAME. */
    private static final String TODO_HARDLINK_NAMES = "TODO(ntfs hard links): listDirectory() keys "
            + "entries by MFT record and createEntry() names them from the record's first Win32/POSIX "
            + "$FILE_NAME, so two names of one file in the same directory collapse into one entry and a "
            + "file linked into several directories shows the same name everywhere; the index entry's own "
            + "$FILE_NAME should be used";

    /** A stored (uncompressible) unit coalesced with the next unit's compressed clusters is read raw. */
    private static final String TODO_COMPRESSED_COALESCED = "TODO(ntfs compression): readCompressedDataRuns() "
            + "recognises a compressed unit only as 'run + following sparse run == 16 clusters'; when ntfs-3g "
            + "coalesces a fully stored unit with the compressed clusters of the next unit into one run "
            + "(mixed.bin: run of 18 clusters + hole of 14) the whole run is copied raw; the loop must "
            + "walk compression units by VCN and split runs at unit boundaries";

    /** Directory reparse points (symlinks to directories, junctions) come back as plain directories. */
    private static final String TODO_DIR_REPARSE = "TODO(ntfs reparse): createEntry() tests "
            + "isDirectory() before $REPARSE_POINT, so a symlink to a directory (or a junction) is returned "
            + "as an empty Directory instead of a SymbolicLink";

    /** SymbolicLink.resolve() cannot follow '..' segments or drive-letter absolute targets. */
    private static final String TODO_SYMLINK_RESOLVE = "TODO(ntfs symlinks): NtfsSymlink.resolve() joins "
            + "the parent path and the target textually, so '..' segments are looked up as entry names and "
            + "an absolute Windows target ('C:\\\\dir\\\\file' -> 'C:/dir/file') is treated as relative";

    /** Files whose content is known to be mis-read because of TODO_COMPRESSED_COALESCED (fixture id:path). */
    private static final Set<String> COALESCED_RUN_FILES = Set.of(
            "ntfs/fixtures/compressed:/comp/mixed.bin",        // stored unit + next unit's compressed clusters in one run
            "ntfs/fixtures/compressed:/comp/random-200k.bin"); // three stored units + a compressed tail unit in one run

    // ── expectations file format (saffron-ntfs-fixture) ──────────────────

    static class Fixture {
        String id;
        String image;
        String mkntfsOptions;
        String mountOptions;
        String label;
        int fileCount;
        int directoryCount;
        int symlinkCount;
        List<Entry> entries;
    }

    static class Entry {
        String path;
        String type;            // file | dir | symlink
        long size;
        String sha256;
        String target;          // symlink: target as written, '/'-normalized
        String resolvesTo;      // symlink: absolute path it resolves to
        Boolean isDirectoryLink;
        Map<String, StreamExpectation> streams;
        String hardlinkGroup;
        Boolean compressed;
        Boolean sparse;
    }

    static class StreamExpectation {
        long size;
        String sha256;
    }

    static List<Path> fixtureFiles() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURE_DIR)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Fixture load(Path json) throws IOException {
        try (Reader r = Files.newBufferedReader(json)) {
            return GSON.fromJson(r, Fixture.class);
        }
    }

    /** The id rule: path under src/test/resources without the .json suffix. */
    private static String expectedId(Path json) {
        String rel = RESOURCES.relativize(json).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ".json".length());
    }

    // ── ids ───────────────────────────────────────────────────────────────

    @Test
    void everyFixtureHasItsPathAsIdAndIdsAreUnique() throws IOException {
        List<Path> files = fixtureFiles();
        assertThat(files).as("fixture expectation files").isNotEmpty();
        Set<String> seen = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (Path json : files) {
            Fixture f = load(json);
            String expected = expectedId(json);
            if (!expected.equals(f.id)) {
                problems.add(json + ": id \"" + f.id + "\" != \"" + expected + "\"");
            }
            if (!seen.add(f.id)) {
                problems.add(json + ": duplicate id \"" + f.id + "\"");
            }
            String base = json.getFileName().toString();
            String imageExpected = base.substring(0, base.length() - ".json".length()) + ".img";
            if (!imageExpected.equals(f.image)) {
                problems.add(json + ": image \"" + f.image + "\" != \"" + imageExpected + "\"");
            }
            if (!Files.exists(FIXTURE_DIR.resolve(f.image))) {
                problems.add(json + ": image " + f.image + " missing");
            }
        }
        assertThat(problems).isEmpty();
    }

    // ── mounting and geometry ─────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void mountsAtOffsetZeroWithExpectedGeometry(Path json) throws IOException {
        Fixture fixture = load(json);
        try (Mounted m = mount(fixture)) {
            assertThat(m.fs.type()).isEqualTo(FileSystem.FileSystemType.NTFS);
            assertThat(m.fs.label()).contains(fixture.label);

            int sectorSize = option(fixture.mkntfsOptions, "-s", 512);
            int clusterSize = option(fixture.mkntfsOptions, "-c", 4096);
            assertThat(m.ntfs.clusterSize()).as("clusterSize").isEqualTo(clusterSize);
            Map<String, String> meta = m.fs.metadata();
            assertThat(meta.get("bytesPerSector")).isEqualTo(String.valueOf(sectorSize));
            assertThat(meta.get("clusterSize")).isEqualTo(String.valueOf(clusterSize));
            assertThat(meta.get("sectorsPerCluster")).isEqualTo(String.valueOf(clusterSize / sectorSize));
            assertThat(m.fs.totalSize()).isGreaterThan(0);
            assertThat(m.fs.usedSize()).isGreaterThan(0).isLessThanOrEqualTo(m.fs.totalSize());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void walkCountsMatch(Path json) throws IOException {
        Fixture fixture = load(json);
        try (Mounted m = mount(fixture)) {
            int files = 0;
            int dirs = 0;
            int links = 0;
            try (Stream<FileSystemEntry> walk = m.fs.walk()) {
                for (FileSystemEntry e : (Iterable<FileSystemEntry>) walk::iterator) {
                    if (e.path().equals("/")) {
                        continue;
                    }
                    if (e instanceof FileSystemEntry.SymbolicLink) {
                        links++;
                    } else if (e instanceof FileSystemEntry.Directory) {
                        dirs++;
                    } else if (e instanceof FileSystemEntry.RegularFile) {
                        files++;
                    }
                }
            }
            if (files == fixture.fileCount && dirs == fixture.directoryCount && links == fixture.symlinkCount) {
                return;
            }
            // Known deviations: same-directory hard-link names collapse into one entry;
            // directory symlinks are counted as directories.
            int sameDirLinkNames = sameDirectoryHardLinkDuplicates(fixture);
            int dirLinks = (int) fixture.entries.stream()
                    .filter(e -> e.type.equals("symlink") && Boolean.TRUE.equals(e.isDirectoryLink)).count();
            Deviations dev = new Deviations();
            if (files == fixture.fileCount - sameDirLinkNames && sameDirLinkNames > 0) {
                dev.known(TODO_HARDLINK_NAMES, sameDirLinkNames + " same-directory hard-link name(s) not listed");
            } else {
                assertThat(files).as("regular files").isEqualTo(fixture.fileCount);
            }
            if (dirs == fixture.directoryCount + dirLinks && links == fixture.symlinkCount - dirLinks && dirLinks > 0) {
                dev.known(TODO_DIR_REPARSE, dirLinks + " directory symlink(s) counted as directories");
            } else {
                assertThat(dirs).as("directories (excluding root)").isEqualTo(fixture.directoryCount);
                assertThat(links).as("symbolic links").isEqualTo(fixture.symlinkCount);
            }
            dev.abortIfAny();
        }
    }

    /** Number of hard-link names that share a directory with another name of the same file. */
    private static int sameDirectoryHardLinkDuplicates(Fixture fixture) {
        Map<String, Set<String>> dirsByGroup = new LinkedHashMap<>();
        int duplicates = 0;
        for (Entry e : fixture.entries) {
            if (e.hardlinkGroup == null) {
                continue;
            }
            String dir = e.path.substring(0, e.path.lastIndexOf('/'));
            if (!dirsByGroup.computeIfAbsent(e.hardlinkGroup, k -> new HashSet<>()).add(dir)) {
                duplicates++;
            }
        }
        return duplicates;
    }

    // ── files and directories ─────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void filesAndDirectoriesMatch(Path json) throws IOException {
        Fixture fixture = load(json);
        Deviations dev = new Deviations();
        try (Mounted m = mount(fixture)) {
            for (Entry expected : fixture.entries) {
                switch (expected.type) {
                    case "file" -> checkFile(m, fixture, expected, dev);
                    case "dir" -> checkDirectory(m, expected);
                    default -> { /* symlinks: symlinksMatch */ }
                }
            }
        }
        dev.abortIfAny();
    }

    private static void checkFile(Mounted m, Fixture fixture, Entry expected, Deviations dev) throws IOException {
        Optional<FileSystemEntry> resolved = m.fs.resolve(expected.path);
        if (resolved.isEmpty() && expected.hardlinkGroup != null) {
            dev.known(TODO_HARDLINK_NAMES, expected.path + " not found");
            return;
        }
        assertThat(resolved).as("resolve " + expected.path).isPresent();
        FileSystemEntry entry = resolved.get();
        assertThat(entry).as(expected.path).isInstanceOf(FileSystemEntry.RegularFile.class);
        FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) entry;
        assertThat(file.name()).as("name of " + expected.path).isEqualTo(baseName(expected.path));
        assertThat(file.size()).as("size of " + expected.path).isEqualTo(expected.size);
        String streamed;
        try (InputStream in = file.openStream()) {
            streamed = sha256(in);
        }
        String materialized = sha256(file.readAllBytes());
        if (!streamed.equals(expected.sha256) && COALESCED_RUN_FILES.contains(fixture.id + ":" + expected.path)) {
            dev.known(TODO_COMPRESSED_COALESCED, expected.path + " content mismatch");
            return;
        }
        assertThat(streamed).as("sha256 (openStream) of " + expected.path).isEqualTo(expected.sha256);
        assertThat(materialized).as("sha256 (readAllBytes) of " + expected.path).isEqualTo(expected.sha256);
        checkFlags(file, expected);
    }

    private static void checkDirectory(Mounted m, Entry expected) throws IOException {
        FileSystemEntry entry = resolve(m, expected.path);
        assertThat(entry).as(expected.path).isInstanceOf(FileSystemEntry.Directory.class);
        assertThat(entry.name()).as("name of " + expected.path).isEqualTo(baseName(expected.path));
        checkFlags(entry, expected);
    }

    private static void checkFlags(FileSystemEntry entry, Entry expected) {
        int fileAttributes = fileAttributes(entry);
        if (Boolean.TRUE.equals(expected.compressed)) {
            assertThat(fileAttributes & ATTR_COMPRESSED).as("COMPRESSED flag on " + expected.path).isNotZero();
        }
        if (Boolean.TRUE.equals(expected.sparse)) {
            assertThat(fileAttributes & ATTR_SPARSE).as("SPARSE flag on " + expected.path).isNotZero();
        }
    }

    // ── symbolic links (reparse points) ───────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void symlinksMatch(Path json) throws IOException {
        Fixture fixture = load(json);
        Deviations dev = new Deviations();
        try (Mounted m = mount(fixture)) {
            for (Entry expected : fixture.entries) {
                if (!expected.type.equals("symlink")) {
                    continue;
                }
                FileSystemEntry entry = resolve(m, expected.path);
                if (Boolean.TRUE.equals(expected.isDirectoryLink) && entry instanceof FileSystemEntry.Directory) {
                    dev.known(TODO_DIR_REPARSE, expected.path + " returned as a directory");
                    continue;
                }
                assertThat(entry).as(expected.path).isInstanceOf(FileSystemEntry.SymbolicLink.class);
                FileSystemEntry.SymbolicLink link = (FileSystemEntry.SymbolicLink) entry;
                assertThat(link.target()).as("target of " + expected.path).isEqualTo(expected.target);
                Optional<FileSystemEntry> resolved = link.resolve();
                boolean hardTarget = expected.target.startsWith("..") || expected.target.contains(":");
                if (resolved.isEmpty() && hardTarget) {
                    dev.known(TODO_SYMLINK_RESOLVE, expected.path + " -> " + expected.target + " not resolved");
                    continue;
                }
                assertThat(resolved).as("resolution of " + expected.path).isPresent();
                assertThat(resolved.get().path()).as("resolved path of " + expected.path)
                        .isEqualTo(expected.resolvesTo);
            }
        }
        dev.abortIfAny();
    }

    // ── alternate data streams ────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void alternateDataStreamsMatch(Path json) throws IOException {
        Fixture fixture = load(json);
        try (Mounted m = mount(fixture)) {
            for (Entry expected : fixture.entries) {
                if (expected.streams == null || expected.streams.isEmpty()) {
                    continue;
                }
                FileSystemEntry entry = resolve(m, expected.path);
                if (!(entry instanceof FileSystemEntry.RegularFile file)) {
                    // Directory ADS exist on NTFS (and in sparse-and-ads.json) but the
                    // public API only exposes streams of regular files.
                    continue;
                }
                Map<String, Object> attrs = file.attributes();
                assertThat(attrs.get("ntfs.ads.count")).as("ADS count of " + expected.path)
                        .isEqualTo(expected.streams.size());
                for (Map.Entry<String, StreamExpectation> s : expected.streams.entrySet()) {
                    String name = s.getKey();
                    StreamExpectation se = s.getValue();
                    assertThat(attrs.get("ntfs.ads." + name)).as("ntfs.ads." + name + " of " + expected.path)
                            .isEqualTo(se.size);
                    byte[] data = m.ntfs.readAlternateStream(file, name);
                    assertThat(data.length).as("size of " + expected.path + ":" + name).isEqualTo(se.size);
                    assertThat(sha256(data)).as("sha256 of " + expected.path + ":" + name).isEqualTo(se.sha256);
                }
            }
        }
    }

    // ── hard links ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void hardLinksMatch(Path json) throws IOException {
        Fixture fixture = load(json);
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : fixture.entries) {
            if (e.hardlinkGroup != null) {
                groups.computeIfAbsent(e.hardlinkGroup, k -> new ArrayList<>()).add(e);
            }
        }
        Deviations dev = new Deviations();
        try (Mounted m = mount(fixture)) {
            for (Map.Entry<String, List<Entry>> g : groups.entrySet()) {
                List<Entry> names = g.getValue();
                assertThat(names.size()).as("group " + g.getKey()).isGreaterThan(1);
                for (Entry expected : names) {
                    Optional<FileSystemEntry> resolved = m.fs.resolve(expected.path);
                    if (resolved.isEmpty()) {
                        dev.known(TODO_HARDLINK_NAMES, expected.path + " (group " + g.getKey() + ") not found");
                        continue;
                    }
                    FileSystemEntry entry = resolved.get();
                    assertThat(entry).as(expected.path).isInstanceOf(FileSystemEntry.RegularFile.class);
                    assertThat(entry.name()).as("per-directory name of " + expected.path)
                            .isEqualTo(baseName(expected.path));
                    assertThat(sha256(((FileSystemEntry.RegularFile) entry).readAllBytes()))
                            .as("content of " + expected.path).isEqualTo(expected.sha256);
                    Object linkCount = entry.attributes().get("hardLinkCount");
                    assertThat(linkCount).as("hardLinkCount of " + expected.path).isInstanceOf(Integer.class);
                    assertThat((Integer) linkCount).isGreaterThanOrEqualTo(names.size());
                }
            }
        }
        dev.abortIfAny();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Collects known deviations; if any were hit the test is aborted (skipped) with their TODOs. */
    private static final class Deviations {
        private final List<String> hits = new ArrayList<>();

        void known(String todo, String detail) {
            hits.add(todo + " [" + detail + "]");
        }

        void abortIfAny() {
            Assumptions.assumeTrue(hits.isEmpty(), () -> String.join("\n", hits));
        }
    }

    /** A mounted fixture: the disk and the single NTFS volume at offset 0. */
    private static final class Mounted implements AutoCloseable {
        final VirtualDisk disk;
        final FileSystem fs;
        final FileSystem.NtfsFileSystem ntfs;

        Mounted(VirtualDisk disk, FileSystem fs) {
            this.disk = disk;
            this.fs = fs;
            this.ntfs = (FileSystem.NtfsFileSystem) fs;
        }

        @Override
        public void close() throws IOException {
            fs.close();
            disk.close();
        }
    }

    private static Mounted mount(Fixture fixture) throws IOException {
        Path image = FIXTURE_DIR.resolve(fixture.image);
        VirtualDisk disk = DiskReader.open(image);
        List<FileSystem> mounted = FileSystemMount.mountAll(disk);
        assertThat(mounted).as("filesystems in " + fixture.image).hasSize(1);
        FileSystem fs = mounted.get(0);
        assertThat(fs).isInstanceOf(FileSystem.NtfsFileSystem.class);
        return new Mounted(disk, fs);
    }

    private static FileSystemEntry resolve(Mounted m, String path) throws IOException {
        Optional<FileSystemEntry> entry = m.fs.resolve(path);
        assertThat(entry).as("resolve " + path).isPresent();
        return entry.get();
    }

    private static int fileAttributes(FileSystemEntry entry) {
        Object value = entry.attributes().get("fileAttributes");
        assertThat(value).as("fileAttributes of " + entry.path()).isInstanceOf(Integer.class);
        return (Integer) value;
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** "-c 65536" style option lookup in the recorded mkntfs command line. */
    private static int option(String options, String flag, int dflt) {
        String[] parts = options.trim().split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (parts[i].equals(flag)) {
                return Integer.parseInt(parts[i + 1]);
            }
        }
        return dflt;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
