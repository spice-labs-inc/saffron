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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for mounting and reading real squashfs fixtures.
 */
class SquashfsMountTest {

    private static final Path FIXTURES = Paths.get("src/test/resources/squashfs");

    /**
     * Mounts the minimal Alpine fixture and verifies the root directory
     * contains the expected top-level directories.
     */
    @Test
    void mountsMinimalImage() throws IOException {
        Path image = FIXTURES.resolve("alpine-minimal.squashfs");
        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            FileSystemEntry.Directory root = fs.root();
            Set<String> names = root.list().map(FileSystemEntry::name).collect(Collectors.toSet());
            assertThat(names).contains("bin", "etc", "lib", "usr");
        }
    }

    /**
     * Reads a known file from the minimal Alpine fixture and checks that the
     * content contains the expected distribution name.
     */
    @Test
    void readsKnownFile() throws IOException {
        Path image = FIXTURES.resolve("alpine-minimal.squashfs");
        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> entry = fs.resolve("/etc/os-release");
            assertThat(entry).isPresent();
            assertThat(entry.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
            String content = new String(((FileSystemEntry.RegularFile) entry.get()).readAllBytes());
            assertThat(content).contains("Alpine");
        }
    }

    /**
     * Verifies that a nested regular file can be resolved inside the minimal
     * Alpine fixture.
     */
    @Test
    void walksNestedDirectories() throws IOException {
        Path image = FIXTURES.resolve("alpine-minimal.squashfs");
        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> entry = fs.resolve("/bin/busybox");
            assertThat(entry).isPresent();
            assertThat(entry.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
            assertThat(entry.get().size()).isGreaterThan(0);
        }
    }

    /**
     * Mounts the full Alpine rootfs fixture and checks the expected top-level
     * directories.
     */
    @Test
    void mountsAlpineImage() throws IOException {
        Path image = FIXTURES.resolve("alpine-rootfs.squashfs");
        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            FileSystemEntry.Directory root = fs.root();
            Set<String> names = root.list().map(FileSystemEntry::name).collect(Collectors.toSet());
            assertThat(names).contains("bin", "etc", "lib", "usr");
        }
    }

    /**
     * Mounts the IoTGoat root filesystem fixture and verifies that it contains
     * at least one thousand entries.
     */
    @Test
    void mountsIoTGoatRoot() throws IOException {
        Path image = FIXTURES.resolve("iotgoat-rpi-rootfs.squashfs");
        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            long count;
            try (Stream<FileSystemEntry> walk = fs.walk()) {
                count = walk.count();
            }
            assertThat(count).isGreaterThanOrEqualTo(1000);
        }
    }

    /**
     * Verifies that the IoTGoat fixture contains the opkg key directory and the
     * uhttpd init script referenced in the corpus analysis tests.
     */
    @Test
    void findsCryptoMaterial() throws IOException {
        Path image = FIXTURES.resolve("iotgoat-rpi-rootfs.squashfs");
        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> keys = fs.resolve("/etc/opkg/keys");
            assertThat(keys).isPresent();
            assertThat(keys.get().type()).isEqualTo(FileSystemEntry.EntryType.DIRECTORY);

            Optional<FileSystemEntry> uhttpd = fs.resolve("/etc/init.d/uhttpd");
            assertThat(uhttpd).isPresent();
            assertThat(uhttpd.get().type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
        }
    }

    private static FileSystem mountLargest(VirtualDisk disk) throws IOException {
        Optional<FilesystemLocation> location = FileSystemMount.findLargestFilesystem(disk);
        assertThat(location).isPresent();
        assertThat(location.get().info().type()).isEqualTo(FileSystem.FileSystemType.SQUASHFS);
        return FileSystemMount.mount(disk, location.get());
    }
}
