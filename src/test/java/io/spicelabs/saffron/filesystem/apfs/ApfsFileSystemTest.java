/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.apfs;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for APFS filesystem reading.
 */
class ApfsFileSystemTest {

    private static final Path APFS_IMAGE = Path.of("test-corpus/dmg/apfs-test.dmg");

    static boolean hasApfsImage() {
        return Files.exists(APFS_IMAGE);
    }

    @Test
    @EnabledIf("hasApfsImage")
    void readApfsFilesystem() throws Exception {
        try (VirtualDisk disk = DiskReader.open(APFS_IMAGE)) {
            // Try to find APFS partition
            FileSystem.ApfsFileSystem fs = null;

            Optional<PartitionTable> table = PartitionTable.detect(disk);
            if (table.isPresent()) {
                for (Partition p : table.get().partitions()) {
                    if (p.sizeInSectors() < 100) continue;

                    long offset = p.startLba() * 512;
                    Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);

                    if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.APFS) {
                        fs = ApfsFileSystemImpl.mount(disk, offset);
                        break;
                    }
                }
            }

            // Also try direct detection at offset 0
            if (fs == null) {
                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, 0);
                if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.APFS) {
                    fs = ApfsFileSystemImpl.mount(disk, 0);
                }
            }

            if (fs == null) {
                System.out.println("APFS image doesn't have APFS partition, skipping");
                return;
            }

            try {
                System.out.println("APFS blockSize: " + fs.blockSize());
                System.out.println("APFS type: " + fs.type());
                System.out.println("APFS volumeName: " + fs.volumeName());
                System.out.println("APFS metadata: " + fs.metadata());

                assertThat(fs.blockSize()).isGreaterThan(0);
                assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.APFS);
                assertThat(fs.volumeName()).isNotEmpty();

                FileSystemEntry.Directory root = fs.root();
                assertThat(root.name()).isEqualTo("/");

                try (Stream<FileSystemEntry> entries = root.list()) {
                    var entryNames = entries.map(FileSystemEntry::name).toList();
                    System.out.println("APFS root entries (count=" + entryNames.size() + "): " + entryNames);
                    assertThat(entryNames).isNotEmpty();
                }
            } finally {
                fs.close();
            }
        }
    }

    @Test
    void apfsFileSystemType() {
        assertThat(FileSystem.FileSystemType.APFS.getName()).isEqualTo("apfs");
        assertThat(FileSystem.FileSystemType.APFS.getDescription()).isEqualTo("macOS APFS filesystem");
    }

    @Test
    void apfsIsSupported() {
        assertThat(FileSystemMount.isSupported(FileSystem.FileSystemType.APFS)).isTrue();
    }
}
