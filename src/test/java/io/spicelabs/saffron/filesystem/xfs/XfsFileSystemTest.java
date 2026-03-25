/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.xfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.corpus.RequiresImage;
import io.spicelabs.saffron.corpus.TestCorpusUtils;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for XFS filesystem reading.
 *
 * <p>These tests use filesystem-aware discovery to find any available XFS image
 * rather than requiring specific images. This ensures tests work with CI sampling.
 */
class XfsFileSystemTest {

    /**
     * Condition method for @EnabledIf - used by other tests that need XFS.
     */
    static boolean hasXfsImage() {
        return TestCorpusUtils.hasFilesystem("xfs");
    }

    @Test
    @RequiresImage(filesystem = "xfs")
    void readXfsFilesystem() throws Exception {
        Path imagePath = TestCorpusUtils.findBestTestImage("xfs")
                .orElseThrow(() -> new AssertionError("No XFS image found"));

        System.out.println("Testing XFS with image: " + imagePath);

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            Optional<PartitionTable> table = PartitionTable.detect(disk);
            assertThat(table).isPresent();

            // Find the root partition (usually the largest)
            Partition rootPartition = table.get().partitions().stream()
                    .filter(p -> p.sizeInSectors() > 1000000)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No root partition found"));

            long partitionOffset = rootPartition.startLba() * 512;
            Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, partitionOffset);

            assertThat(fsInfo).isPresent();
            assertThat(fsInfo.get().type()).isEqualTo(FileSystem.FileSystemType.XFS);

            try (FileSystem fs = FileSystemMount.mount(disk, partitionOffset, fsInfo.get())) {
                assertThat(fs).isInstanceOf(FileSystem.XfsFileSystem.class);

                FileSystem.XfsFileSystem xfs = (FileSystem.XfsFileSystem) fs;
                assertThat(xfs.blockSize()).isGreaterThan(0);
                assertThat(xfs.agCount()).isGreaterThan(0);
                assertThat(xfs.totalSize()).isGreaterThan(0);

                FileSystemEntry.Directory root = fs.root();
                assertThat(root.name()).isEqualTo("/");

                // List root directory entries
                var entryNames = root.list().map(FileSystemEntry::name).toList();
                System.out.println("XFS root entries: " + entryNames);

                // Check if this is a boot partition (has kernel files) or root partition
                boolean isBootPartition = entryNames.stream().anyMatch(name ->
                        name.startsWith("vmlinuz") || name.startsWith("initramfs") || name.equals("grub2"));
                boolean isRootPartition = entryNames.contains("etc") || entryNames.contains("usr");

                if (isRootPartition) {
                    // Standard Linux root partition - should have standard directories
                    assertThat(entryNames)
                            .as("Root partition should have standard Linux directories")
                            .contains("etc", "usr", "var");

                    // Should be able to resolve paths
                    Optional<FileSystemEntry> etc = fs.resolve("/etc");
                    assertThat(etc).isPresent();
                    assertThat(etc.get()).isInstanceOf(FileSystemEntry.Directory.class);

                    // Check a file (/etc/os-release is typically a symlink)
                    Optional<FileSystemEntry> osRelease = fs.resolve("/etc/os-release");
                    if (osRelease.isPresent()) {
                        String content;
                        if (osRelease.get() instanceof FileSystemEntry.RegularFile file) {
                            content = new String(file.readAllBytes());
                        } else if (osRelease.get() instanceof FileSystemEntry.SymbolicLink link) {
                            Optional<FileSystemEntry> resolved = link.resolve();
                            assertThat(resolved).isPresent();
                            assertThat(resolved.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
                            content = new String(((FileSystemEntry.RegularFile) resolved.get()).readAllBytes());
                        } else {
                            throw new AssertionError("Unexpected entry type: " + osRelease.get().getClass());
                        }
                        // Content should contain OS identification
                        assertThat(content).isNotEmpty();
                        System.out.println("OS Release content:\n" + content.substring(0, Math.min(200, content.length())));
                    }
                } else if (isBootPartition) {
                    // This is a boot/EFI partition - verify it has expected boot files
                    System.out.println("Detected boot partition with kernel files");
                    assertThat(entryNames)
                            .as("Boot partition should have kernel or boot files")
                            .anyMatch(name -> name.startsWith("vmlinuz") || name.startsWith("initramfs")
                                    || name.equals("grub2") || name.equals("efi"));
                } else {
                    // Generic XFS - just verify we can list entries
                    assertThat(entryNames).as("XFS partition should have entries").isNotEmpty();
                }
            }
        }
    }

    @Test
    @RequiresImage(filesystem = "xfs")
    void readXfsWithFormatQcow2() throws Exception {
        // Specifically test QCOW2 format with XFS (common in cloud images)
        Path imagePath = TestCorpusUtils.findImageWithFilesystemAndFormat("xfs", "qcow2")
                .orElseGet(() -> TestCorpusUtils.findBestTestImage("xfs")
                        .orElseThrow(() -> new AssertionError("No XFS image found")));

        System.out.println("Testing XFS on QCOW2 with image: " + imagePath);

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            FileSystem.XfsFileSystem fs = null;

            // Try to find XFS partition
            Optional<PartitionTable> table = PartitionTable.detect(disk);
            if (table.isPresent()) {
                for (Partition p : table.get().partitions()) {
                    if (p.sizeInSectors() < 1000000) continue;

                    long offset = p.startLba() * 512;
                    Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);

                    if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.XFS) {
                        fs = XfsFileSystemImpl.mount(disk, offset);
                        break;
                    }
                }
            }

            if (fs == null) {
                System.out.println("Image doesn't have XFS partition, skipping");
                return;
            }

            try {
                assertThat(fs.blockSize()).isGreaterThan(0);

                FileSystemEntry.Directory root = fs.root();
                try (Stream<FileSystemEntry> entries = root.list()) {
                    var entryNames = entries.map(FileSystemEntry::name).toList();
                    assertThat(entryNames).isNotEmpty();
                }
            } finally {
                fs.close();
            }
        }
    }

    @Test
    @RequiresImage(filesystem = "xfs")
    void xfsMetadataAndSizes() throws Exception {
        Path imagePath = TestCorpusUtils.findBestTestImage("xfs")
                .orElseThrow(() -> new AssertionError("No XFS image found"));

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            FileSystem.XfsFileSystem fs = null;

            Optional<PartitionTable> table = PartitionTable.detect(disk);
            if (table.isPresent()) {
                for (Partition p : table.get().partitions()) {
                    if (p.sizeInSectors() < 1000000) continue;

                    long offset = p.startLba() * 512;
                    Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);

                    if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.XFS) {
                        fs = XfsFileSystemImpl.mount(disk, offset);
                        break;
                    }
                }
            }

            if (fs == null) {
                System.out.println("No XFS partition found, skipping");
                return;
            }

            try {
                // Verify metadata
                var metadata = fs.metadata();
                assertThat(metadata).containsKey("blockSize");
                assertThat(metadata).containsKey("agCount");

                // Verify sizes
                assertThat(fs.totalSize()).isGreaterThan(0);

                // List root entries to determine partition type
                var entryNames = fs.root().list().map(FileSystemEntry::name).toList();
                System.out.println("XFS metadata test - root entries: " + entryNames);

                // Check if this is a root partition (has /etc) or boot partition
                boolean isRootPartition = entryNames.contains("etc") || entryNames.contains("usr");

                if (isRootPartition) {
                    Optional<FileSystemEntry> etcEntry = fs.resolve("/etc");
                    assertThat(etcEntry).isPresent();
                } else {
                    // Boot partition - just verify we can list entries
                    System.out.println("Boot partition detected - skipping /etc check");
                }
            } finally {
                fs.close();
            }
        }
    }
}
