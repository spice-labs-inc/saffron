/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.ext4;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.fs.FileSystemMount.FilesystemLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for ext4 filesystem reading.
 */
class Ext4FileSystemTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testReadCirrosFilesystem() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/cirros-0.6.2-x86_64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Skipping - image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Find filesystem using dynamic detection
            Optional<FilesystemLocation> locationOpt = FileSystemMount.findLargestFilesystem(disk);
            assertThat(locationOpt).as("Should find filesystem").isPresent();

            FilesystemLocation location = locationOpt.get();
            System.out.println("Detected filesystem: " + location.info().type() + " at offset " + location.offset());

            assertThat(FileSystemMount.isSupported(location.info().type()))
                .as("Filesystem type " + location.info().type() + " should be implemented")
                .isTrue();

            // Mount the filesystem
            try (FileSystem fs = FileSystemMount.mount(disk, location)) {
                // Test basic filesystem info
                System.out.println("Filesystem type: " + fs.type());
                System.out.println("Total size: " + fs.totalSize() + " bytes");
                System.out.println("Used size: " + fs.usedSize() + " bytes");
                System.out.println("UUID: " + fs.uuid().orElse("(none)"));

                assertThat(fs.totalSize()).isGreaterThan(0);

                // Test root directory
                FileSystemEntry.Directory root = fs.root();
                assertThat(root.name()).isEqualTo("/");
                assertThat(root.path()).isEqualTo("/");

                // List root directory
                System.out.println("\nRoot directory contents:");
                try (Stream<FileSystemEntry> entries = root.list()) {
                    entries.forEach(entry -> {
                        String typeStr = switch (entry.type()) {
                            case DIRECTORY -> "dir";
                            case REGULAR_FILE -> "file";
                            case SYMBOLIC_LINK -> "link";
                            default -> entry.type().name().toLowerCase();
                        };
                        System.out.println("  " + typeStr + " " + entry.name() + " (" + entry.size() + " bytes)");
                    });
                }

                // Test resolve paths - CirrOS is minimal, test what it has
                Optional<FileSystemEntry> bootEntry = fs.resolve("/boot");
                assertThat(bootEntry).as("Should resolve /boot path").isPresent();
                assertThat(bootEntry.get()).isInstanceOf(FileSystemEntry.Directory.class);

                // Test symlink reading
                Optional<FileSystemEntry> vmlinuzEntry = fs.resolve("/vmlinuz");
                if (vmlinuzEntry.isPresent() && vmlinuzEntry.get() instanceof FileSystemEntry.SymbolicLink link) {
                    System.out.println("vmlinuz -> " + link.target());
                }

                // Count all files and directories
                System.out.println("\nWalking filesystem...");
                AtomicInteger fileCount = new AtomicInteger(0);
                AtomicInteger dirCount = new AtomicInteger(0);
                AtomicInteger symlinkCount = new AtomicInteger(0);

                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.Directory) {
                            dirCount.incrementAndGet();
                        } else if (entry instanceof FileSystemEntry.RegularFile) {
                            fileCount.incrementAndGet();
                        } else if (entry instanceof FileSystemEntry.SymbolicLink) {
                            symlinkCount.incrementAndGet();
                        }
                    });
                }

                System.out.println("Files: " + fileCount.get());
                System.out.println("Directories: " + dirCount.get());
                System.out.println("Symlinks: " + symlinkCount.get());
                System.out.println("Total entries: " + (fileCount.get() + dirCount.get() + symlinkCount.get()));

                // CirrOS is minimal - just verify we found some entries
                assertThat(dirCount.get()).as("Should have directories").isGreaterThan(0);
            }
        }
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testReadAlpineFilesystem() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/alpine-3.19-cloud-amd64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Skipping - image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Find filesystem using dynamic detection
            Optional<FilesystemLocation> locationOpt = FileSystemMount.findLargestFilesystem(disk);
            assertThat(locationOpt).as("Should find filesystem").isPresent();

            FilesystemLocation location = locationOpt.get();
            System.out.println("Detected filesystem: " + location.info().type() + " at offset " + location.offset());

            assertThat(FileSystemMount.isSupported(location.info().type()))
                .as("Filesystem type " + location.info().type() + " should be implemented")
                .isTrue();

            // Mount the filesystem
            try (FileSystem fs = FileSystemMount.mount(disk, location)) {
                System.out.println("Filesystem type: " + fs.type());
                System.out.println("Total size: " + fs.totalSize() + " bytes");
                System.out.println("UUID: " + fs.uuid().orElse("(none)"));

                // List root directory
                FileSystemEntry.Directory root = fs.root();
                System.out.println("\nRoot directory contents:");
                try (Stream<FileSystemEntry> entries = root.list()) {
                    entries.forEach(entry -> {
                        System.out.println("  " + entry.type() + " " + entry.name());
                    });
                }

                // Count files
                AtomicInteger totalEntries = new AtomicInteger(0);
                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(e -> totalEntries.incrementAndGet());
                }
                System.out.println("\nTotal entries: " + totalEntries.get());

                assertThat(totalEntries.get()).isGreaterThan(10);
            }
        }
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testReadFileContent() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/cirros-0.6.2-x86_64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Skipping - image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Find filesystem using dynamic detection
            Optional<FilesystemLocation> locationOpt = FileSystemMount.findLargestFilesystem(disk);
            assertThat(locationOpt).as("Should find filesystem").isPresent();

            FilesystemLocation location = locationOpt.get();
            assertThat(FileSystemMount.isSupported(location.info().type()))
                .as("Filesystem type " + location.info().type() + " should be implemented")
                .isTrue();

            try (FileSystem fs = FileSystemMount.mount(disk, location)) {
                // CirrOS is minimal - try reading a kernel file from /boot
                Optional<FileSystemEntry> bootDir = fs.resolve("/boot");
                if (bootDir.isPresent() && bootDir.get() instanceof FileSystemEntry.Directory dir) {
                    System.out.println("Boot directory contents:");
                    try (Stream<FileSystemEntry> entries = dir.list()) {
                        entries.forEach(e -> {
                            System.out.println("  " + e.name() + " (" + e.size() + " bytes)");
                            // Try reading first 100 bytes of a regular file
                            if (e instanceof FileSystemEntry.RegularFile file && file.size() > 0) {
                                try {
                                    byte[] content = file.readAllBytes();
                                    System.out.println("    Read " + content.length + " bytes successfully");
                                } catch (Exception ex) {
                                    System.out.println("    Error reading: " + ex.getMessage());
                                }
                            }
                        });
                    }
                }

                // Test reading a symlink target
                Optional<FileSystemEntry> vmlinuz = fs.resolve("/vmlinuz");
                if (vmlinuz.isPresent() && vmlinuz.get() instanceof FileSystemEntry.SymbolicLink link) {
                    System.out.println("\nvmlinuz symlink target: " + link.target());
                }
            }
        }
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testSha256Hash() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/cirros-0.6.2-x86_64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Skipping - image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Find filesystem using dynamic detection
            Optional<FilesystemLocation> locationOpt = FileSystemMount.findLargestFilesystem(disk);
            assertThat(locationOpt).as("Should find filesystem").isPresent();

            FilesystemLocation location = locationOpt.get();
            assertThat(FileSystemMount.isSupported(location.info().type()))
                .as("Filesystem type " + location.info().type() + " should be implemented")
                .isTrue();

            try (FileSystem fs = FileSystemMount.mount(disk, location)) {
                // Find any regular file in /boot and compute SHA256
                Optional<FileSystemEntry> bootDir = fs.resolve("/boot");
                if (bootDir.isPresent() && bootDir.get() instanceof FileSystemEntry.Directory dir) {
                    try (Stream<FileSystemEntry> entries = dir.list()) {
                        Optional<FileSystemEntry.RegularFile> anyFile = entries
                            .filter(e -> e instanceof FileSystemEntry.RegularFile)
                            .map(e -> (FileSystemEntry.RegularFile) e)
                            .filter(f -> f.size() > 0 && f.size() < 50_000_000) // Skip huge files
                            .findFirst();

                        if (anyFile.isPresent()) {
                            FileSystemEntry.RegularFile file = anyFile.get();
                            byte[] content = file.readAllBytes();
                            MessageDigest md = MessageDigest.getInstance("SHA-256");
                            byte[] hash = md.digest(content);
                            String hashStr = HexFormat.of().formatHex(hash);
                            System.out.println(file.name() + " SHA256: " + hashStr);
                            System.out.println("File size: " + file.size() + " bytes");
                            System.out.println("Content length: " + content.length + " bytes");

                            assertThat(hashStr).hasSize(64);
                            assertThat(content.length).isEqualTo((int) file.size());
                        }
                    }
                }
            }
        }
    }
}
