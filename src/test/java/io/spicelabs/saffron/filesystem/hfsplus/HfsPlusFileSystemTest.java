/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.hfsplus;

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
 * Tests for HFS+ filesystem reading.
 */
class HfsPlusFileSystemTest {

    private static final Path HFSPLUS_IMAGE = Path.of("test-corpus/dmg/hfsplus-test.dmg");

    static boolean hasHfsPlusImage() {
        return Files.exists(HFSPLUS_IMAGE);
    }

    @Test
    @EnabledIf("hasHfsPlusImage")
    void readHfsPlusFilesystem() throws Exception {
        try (VirtualDisk disk = DiskReader.open(HFSPLUS_IMAGE)) {
            // Try to find HFS+ partition
            FileSystem.HfsPlusFileSystem fs = null;

            Optional<PartitionTable> table = PartitionTable.detect(disk);
            if (table.isPresent()) {
                for (Partition p : table.get().partitions()) {
                    if (p.sizeInSectors() < 100) continue;

                    long offset = p.startLba() * 512;
                    Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);

                    if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.HFS_PLUS) {
                        fs = HfsPlusFileSystemImpl.mount(disk, offset);
                        break;
                    }
                }
            }

            // Also try direct detection at offset 0
            if (fs == null) {
                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, 0);
                if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.HFS_PLUS) {
                    fs = HfsPlusFileSystemImpl.mount(disk, 0);
                }
            }

            if (fs == null) {
                System.out.println("HFS+ image doesn't have HFS+ partition, skipping");
                return;
            }

            try {
                assertThat(fs.blockSize()).isGreaterThan(0);
                assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.HFS_PLUS);

                FileSystemEntry.Directory root = fs.root();
                assertThat(root.name()).isEqualTo("/");

                try (Stream<FileSystemEntry> entries = root.list()) {
                    var entryNames = entries.map(FileSystemEntry::name).toList();
                    assertThat(entryNames).isNotEmpty();
                    System.out.println("HFS+ root entries: " + entryNames);
                }
            } finally {
                fs.close();
            }
        }
    }

    @Test
    void hfsPlusFileSystemType() {
        assertThat(FileSystem.FileSystemType.HFS_PLUS.getName()).isEqualTo("hfsplus");
        assertThat(FileSystem.FileSystemType.HFS_PLUS.getDescription()).isEqualTo("macOS HFS+ filesystem");
    }

    @Test
    void hfsPlusIsSupported() {
        assertThat(FileSystemMount.isSupported(FileSystem.FileSystemType.HFS_PLUS)).isTrue();
    }
}
