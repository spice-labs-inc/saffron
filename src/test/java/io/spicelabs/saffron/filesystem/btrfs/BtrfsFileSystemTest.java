/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.btrfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for Btrfs filesystem reading.
 *
 * <p>These tests require Btrfs disk images. Fedora cloud images use Btrfs by default.
 * Download from: https://fedoraproject.org/cloud/download
 *
 * <p>Place images in test-corpus/qcow2/modern/ or set BTRFS_TEST_IMAGE environment variable.
 */
class BtrfsFileSystemTest {

    // Fedora cloud images use Btrfs by default
    private static final Path FEDORA_IMAGE = Path.of("test-corpus/qcow2/modern/fedora-cloud-41.qcow2");
    private static final Path FEDORA_IMAGE_ALT = Path.of("test-corpus/qcow2/modern/Fedora-Cloud-Base-Generic-41.qcow2");

    // Allow custom image via environment variable
    private static final String BTRFS_TEST_IMAGE_ENV = System.getenv("BTRFS_TEST_IMAGE");

    static boolean hasBtrfsTestImage() {
        if (BTRFS_TEST_IMAGE_ENV != null && Files.exists(Path.of(BTRFS_TEST_IMAGE_ENV))) {
            return true;
        }
        return Files.exists(FEDORA_IMAGE) || Files.exists(FEDORA_IMAGE_ALT);
    }

    private static Path getBtrfsTestImage() {
        if (BTRFS_TEST_IMAGE_ENV != null && Files.exists(Path.of(BTRFS_TEST_IMAGE_ENV))) {
            return Path.of(BTRFS_TEST_IMAGE_ENV);
        }
        if (Files.exists(FEDORA_IMAGE)) return FEDORA_IMAGE;
        return FEDORA_IMAGE_ALT;
    }

    @Test
    @EnabledIf("hasBtrfsTestImage")
    void readBtrfsFilesystem() throws Exception {
        Path imagePath = getBtrfsTestImage();
        System.out.println("Testing Btrfs filesystem with: " + imagePath);

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Find Btrfs partition
            long btrfsOffset = findBtrfsPartitionOffset(disk);

            if (btrfsOffset < 0) {
                System.out.println("No Btrfs partition found in " + imagePath + ", skipping filesystem test");
                return;
            }

            Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, btrfsOffset);
            assertThat(fsInfo).isPresent();
            assertThat(fsInfo.get().type()).isEqualTo(FileSystem.FileSystemType.BTRFS);

            try (FileSystem fs = FileSystemMount.mount(disk, btrfsOffset, fsInfo.get())) {
                assertThat(fs).isInstanceOf(FileSystem.BtrfsFileSystem.class);

                FileSystem.BtrfsFileSystem btrfs = (FileSystem.BtrfsFileSystem) fs;
                assertThat(btrfs.nodeSize()).isGreaterThan(0);
                assertThat(btrfs.sectorSize()).isGreaterThan(0);
                assertThat(btrfs.totalSize()).isGreaterThan(0);
                assertThat(btrfs.generation()).isGreaterThan(0);

                System.out.println("Btrfs filesystem:");
                System.out.println("  UUID: " + fs.uuid().orElse("<none>"));
                System.out.println("  Label: " + fs.label().orElse("<none>"));
                System.out.println("  Node size: " + btrfs.nodeSize());
                System.out.println("  Generation: " + btrfs.generation());
                System.out.println("  Total: " + btrfs.totalSize() / 1024 / 1024 + " MB");

                FileSystemEntry.Directory root = fs.root();
                assertThat(root.name()).isEqualTo("/");

                // List root directory
                try (Stream<FileSystemEntry> entries = root.list()) {
                    var entryNames = entries.map(FileSystemEntry::name).toList();
                    System.out.println("  Root entries: " + entryNames);
                    assertThat(entryNames).isNotEmpty();
                }
            }
        }
    }

    @Test
    @EnabledIf("hasBtrfsTestImage")
    void testBtrfsMetadata() throws Exception {
        Path imagePath = getBtrfsTestImage();

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            long btrfsOffset = findBtrfsPartitionOffset(disk);
            if (btrfsOffset < 0) {
                System.out.println("No Btrfs partition found, skipping metadata test");
                return;
            }

            try (FileSystem fs = BtrfsFileSystemImpl.mount(disk, btrfsOffset)) {
                // Test metadata map
                var metadata = fs.metadata();
                assertThat(metadata).containsKey("fsType");
                assertThat(metadata.get("fsType")).isEqualTo("btrfs");
                assertThat(metadata).containsKey("nodeSize");
                assertThat(metadata).containsKey("generation");

                // Test sizes
                assertThat(fs.totalSize()).isGreaterThan(0);
                assertThat(fs.freeSize()).isGreaterThanOrEqualTo(0);
                assertThat(fs.usedSize()).isGreaterThanOrEqualTo(0);
                assertThat(fs.usedSize() + fs.freeSize()).isEqualTo(fs.totalSize());
            }
        }
    }

    @Test
    @EnabledIf("hasBtrfsTestImage")
    void testBtrfsDirectoryListing() throws Exception {
        Path imagePath = getBtrfsTestImage();

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            long btrfsOffset = findBtrfsPartitionOffset(disk);
            if (btrfsOffset < 0) {
                System.out.println("No Btrfs partition found, skipping directory test");
                return;
            }

            try (FileSystem fs = BtrfsFileSystemImpl.mount(disk, btrfsOffset)) {
                FileSystemEntry.Directory root = fs.root();

                // Root directory should have attributes
                var attrs = root.attributes();
                System.out.println("Root attributes: " + attrs);

                // May have POSIX attributes
                if (attrs.containsKey("mode")) {
                    assertThat(attrs.get("mode")).isInstanceOf(Integer.class);
                }
                if (attrs.containsKey("uid")) {
                    assertThat(attrs.get("uid")).isInstanceOf(Integer.class);
                }

                // Try to resolve common paths
                Optional<FileSystemEntry> etc = fs.resolve("/etc");
                if (etc.isPresent()) {
                    System.out.println("/etc exists: " + etc.get().type());
                    assertThat(etc.get()).isInstanceOf(FileSystemEntry.Directory.class);
                }
            }
        }
    }

    @Test
    @EnabledIf("hasBtrfsTestImage")
    void testBtrfsFileReading() throws Exception {
        Path imagePath = getBtrfsTestImage();

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            long btrfsOffset = findBtrfsPartitionOffset(disk);
            if (btrfsOffset < 0) {
                System.out.println("No Btrfs partition found, skipping file reading test");
                return;
            }

            try (FileSystem fs = BtrfsFileSystemImpl.mount(disk, btrfsOffset)) {
                // Try to find and read /etc/os-release
                Optional<FileSystemEntry> osRelease = fs.resolve("/etc/os-release");
                if (osRelease.isPresent() && osRelease.get() instanceof FileSystemEntry.RegularFile file) {
                    String content = new String(file.readAllBytes());
                    System.out.println("/etc/os-release content:");
                    System.out.println(content.substring(0, Math.min(500, content.length())));
                    assertThat(content).isNotEmpty();
                } else {
                    System.out.println("/etc/os-release not found or not a regular file");
                }
            }
        }
    }

    @Test
    @EnabledIf("hasBtrfsTestImage")
    void testWalkFilesystem() throws Exception {
        Path imagePath = getBtrfsTestImage();

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            long btrfsOffset = findBtrfsPartitionOffset(disk);
            if (btrfsOffset < 0) {
                System.out.println("No Btrfs partition found, skipping walk test");
                return;
            }

            try (FileSystem fs = BtrfsFileSystemImpl.mount(disk, btrfsOffset)) {
                // Walk with depth limit
                try (Stream<FileSystemEntry> walked = fs.walk("/", 2)) {
                    long count = walked.count();
                    System.out.println("Entries in first 2 levels: " + count);
                    assertThat(count).isGreaterThan(0);
                }
            }
        }
    }

    private long findBtrfsPartitionOffset(VirtualDisk disk) throws IOException {
        Optional<PartitionTable> table = PartitionTable.detect(disk);
        if (table.isPresent()) {
            for (Partition p : table.get().partitions()) {
                if (p.sizeInSectors() < 10000) continue;

                long offset = p.startLba() * 512;
                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);

                if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.BTRFS) {
                    return offset;
                }
            }
        }

        // Check at offset 0
        Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, 0);
        if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.BTRFS) {
            return 0;
        }

        return -1;
    }
}
