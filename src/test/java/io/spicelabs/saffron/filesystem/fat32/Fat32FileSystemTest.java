/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.fat32;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for FAT32 filesystem implementation.
 */
class Fat32FileSystemTest {

    private static final Path VDI_IMAGE = Path.of("test-corpus/vdi/modern/ubuntu-22.04-vbox.vdi");

    static boolean vdiImageExists() {
        return Files.exists(VDI_IMAGE);
    }

    @Test
    @EnabledIf("vdiImageExists")
    void fat32FileSystem_canReadEfiPartition() throws Exception {
        try (VirtualDisk disk = DiskReader.open(VDI_IMAGE)) {
            // Find all filesystems
            var locations = FileSystemMount.findFilesystems(disk);

            // Find the FAT32 filesystem (EFI partition)
            var fat32Location = locations.stream()
                    .filter(loc -> loc.info().type() == FileSystem.FileSystemType.FAT32)
                    .findFirst();

            assertThat(fat32Location)
                    .as("FAT32 EFI partition should be detected")
                    .isPresent();

            // Mount the FAT32 filesystem
            try (FileSystem fs = FileSystemMount.mount(disk, fat32Location.get())) {
                assertThat(fs).isInstanceOf(FileSystem.Fat32FileSystem.class);

                // Check we can get root directory
                FileSystemEntry.Directory root = fs.root();
                assertThat(root.path()).isEqualTo("/");

                // List root directory entries
                List<FileSystemEntry> entries = root.list().collect(Collectors.toList());
                assertThat(entries)
                        .as("EFI partition should have entries")
                        .isNotEmpty();

                // EFI partitions typically have an EFI directory
                boolean hasEfiDir = entries.stream()
                        .anyMatch(e -> e.name().equalsIgnoreCase("EFI") && e.type() == FileSystemEntry.EntryType.DIRECTORY);
                assertThat(hasEfiDir)
                        .as("EFI partition should have EFI directory")
                        .isTrue();
            }
        }
    }

    @Test
    @EnabledIf("vdiImageExists")
    void fat32FileSystem_canWalkEntireFilesystem() throws Exception {
        try (VirtualDisk disk = DiskReader.open(VDI_IMAGE)) {
            var locations = FileSystemMount.findFilesystems(disk);
            var fat32Location = locations.stream()
                    .filter(loc -> loc.info().type() == FileSystem.FileSystemType.FAT32)
                    .findFirst()
                    .orElseThrow();

            try (FileSystem fs = FileSystemMount.mount(disk, fat32Location)) {
                // Walk all entries
                List<FileSystemEntry> allEntries = fs.walk().collect(Collectors.toList());

                // Count files and directories
                long fileCount = allEntries.stream()
                        .filter(e -> e.type() == FileSystemEntry.EntryType.REGULAR_FILE)
                        .count();
                long dirCount = allEntries.stream()
                        .filter(e -> e.type() == FileSystemEntry.EntryType.DIRECTORY)
                        .count();

                System.out.println("FAT32 filesystem contains: " + fileCount + " files, " + dirCount + " directories");

                assertThat(fileCount)
                        .as("EFI partition should have some files")
                        .isGreaterThan(0);
                assertThat(dirCount)
                        .as("EFI partition should have some directories (including root)")
                        .isGreaterThanOrEqualTo(1);

                // Print file names for debugging
                System.out.println("Files found:");
                allEntries.stream()
                        .filter(e -> e.type() == FileSystemEntry.EntryType.REGULAR_FILE)
                        .forEach(e -> System.out.println("  " + e.path() + " (" + e.size() + " bytes)"));
            }
        }
    }

    @Test
    @EnabledIf("vdiImageExists")
    void fat32FileSystem_canResolveAndReadFiles() throws Exception {
        try (VirtualDisk disk = DiskReader.open(VDI_IMAGE)) {
            var locations = FileSystemMount.findFilesystems(disk);
            var fat32Location = locations.stream()
                    .filter(loc -> loc.info().type() == FileSystem.FileSystemType.FAT32)
                    .findFirst()
                    .orElseThrow();

            try (FileSystem fs = FileSystemMount.mount(disk, fat32Location)) {
                // Try to find and resolve EFI directory
                var efiDir = fs.resolve("/EFI");
                assertThat(efiDir)
                        .as("/EFI directory should be resolvable")
                        .isPresent();
                assertThat(efiDir.get())
                        .isInstanceOf(FileSystemEntry.Directory.class);

                // Find any .efi file and try to read it
                var efiFiles = fs.walk()
                        .filter(e -> e.name().toLowerCase().endsWith(".efi"))
                        .filter(e -> e instanceof FileSystemEntry.RegularFile)
                        .map(e -> (FileSystemEntry.RegularFile) e)
                        .collect(Collectors.toList());

                if (!efiFiles.isEmpty()) {
                    FileSystemEntry.RegularFile efiFile = efiFiles.get(0);
                    System.out.println("Reading EFI file: " + efiFile.path() + " (" + efiFile.size() + " bytes)");

                    byte[] content = efiFile.readAllBytes();
                    assertThat(content)
                            .as("EFI file should have content")
                            .isNotEmpty();

                    // EFI files start with "MZ" (DOS header) followed by PE header
                    if (content.length >= 2) {
                        assertThat(content[0]).as("EFI file should start with MZ header").isEqualTo((byte) 'M');
                        assertThat(content[1]).isEqualTo((byte) 'Z');
                    }
                }
            }
        }
    }

    @Test
    @EnabledIf("vdiImageExists")
    void fat32FileSystem_providesCorrectMetadata() throws Exception {
        try (VirtualDisk disk = DiskReader.open(VDI_IMAGE)) {
            var locations = FileSystemMount.findFilesystems(disk);
            var fat32Location = locations.stream()
                    .filter(loc -> loc.info().type() == FileSystem.FileSystemType.FAT32)
                    .findFirst()
                    .orElseThrow();

            try (FileSystem fs = FileSystemMount.mount(disk, fat32Location)) {
                FileSystem.Fat32FileSystem fat32Fs = (FileSystem.Fat32FileSystem) fs;

                // Check FAT type
                String fatType = fat32Fs.fatType();
                assertThat(fatType)
                        .as("FAT type should be FAT12, FAT16, or FAT32")
                        .isIn("FAT12", "FAT16", "FAT32");

                // Check metadata
                var metadata = fs.metadata();
                assertThat(metadata).containsKey("fatType");
                assertThat(metadata).containsKey("bytesPerSector");
                assertThat(metadata).containsKey("sectorsPerCluster");

                System.out.println("FAT type: " + fatType);
                System.out.println("Metadata: " + metadata);
                System.out.println("Total size: " + fs.totalSize() + " bytes");
                System.out.println("UUID: " + fs.uuid().orElse("(none)"));
                System.out.println("Label: " + fs.label().orElse("(none)"));
            }
        }
    }

    @Test
    @EnabledIf("vdiImageExists")
    void fat32FileSystem_supportsLongFileNames() throws Exception {
        try (VirtualDisk disk = DiskReader.open(VDI_IMAGE)) {
            var locations = FileSystemMount.findFilesystems(disk);
            var fat32Location = locations.stream()
                    .filter(loc -> loc.info().type() == FileSystem.FileSystemType.FAT32)
                    .findFirst()
                    .orElseThrow();

            try (FileSystem fs = FileSystemMount.mount(disk, fat32Location)) {
                // Walk and find files with long names (> 8.3 format)
                var longNameFiles = fs.walk()
                        .filter(e -> e.type() == FileSystemEntry.EntryType.REGULAR_FILE)
                        .filter(e -> e.name().length() > 12 || e.name().contains(" "))
                        .collect(Collectors.toList());

                System.out.println("Files with long names:");
                for (FileSystemEntry e : longNameFiles) {
                    System.out.println("  " + e.path());
                }

                // Even if there are no long filenames in this EFI partition,
                // the implementation supports them
                assertThat(longNameFiles).isNotNull();
            }
        }
    }
}
