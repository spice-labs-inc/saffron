/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.filesystem.ext4.Ext4FileSystemImpl;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

class VdiDebugTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    @Test
    @EnabledIf("corpusExists")
    void debugDebianVdiPartitions() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "vdi/modern/debian-12-vbox.vdi");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            System.out.println("=== Debian 12 VDI Debug ===");
            System.out.println("Disk virtual size: " + disk.virtualSize() + " bytes (" +
                               (disk.virtualSize() / 1024 / 1024 / 1024) + " GB)");

            Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);
            if (tableOpt.isEmpty()) {
                System.out.println("No partition table found");
                // Try filesystem at offset 0
                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, 0);
                if (fsInfo.isPresent()) {
                    System.out.println("Filesystem at offset 0: " + fsInfo.get().type());
                }
                return;
            }

            PartitionTable table = tableOpt.get();
            System.out.println("Partition table type: " + table.type());
            System.out.println("Number of partitions: " + table.partitions().size());

            for (Partition p : table.partitions()) {
                long offset = p.startLba() * 512;
                long sizeBytes = p.sizeInSectors() * 512;

                System.out.println("\nPartition " + p.index() + ":");
                System.out.println("  Type: " + p.typeName());
                System.out.println("  Start LBA: " + p.startLba());
                System.out.println("  Size: " + p.sizeInSectors() + " sectors (" +
                                   sizeBytes + " bytes, " + (sizeBytes / 1024 / 1024) + " MB)");
                System.out.println("  Offset: " + offset);

                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);
                if (fsInfo.isPresent()) {
                    System.out.println("  Filesystem: " + fsInfo.get().type() + " " + fsInfo.get().version());
                    System.out.println("  FS Total Size: " + fsInfo.get().totalSize() + " bytes");
                    System.out.println("  FS Label: " + fsInfo.get().label().orElse("(none)"));

                    // Try to mount and count files if ext
                    if (fsInfo.get().type() == FileSystem.FileSystemType.EXT4) {
                        try (Ext4FileSystemImpl fs = Ext4FileSystemImpl.mount(disk, offset)) {
                            System.out.println("  Mounted successfully");
                            System.out.println("  Root entries:");
                            var root = fs.root();
                            try (var stream = root.list()) {
                                stream.limit(20).forEach(e ->
                                    System.out.println("    " + e.type() + " " + e.name())
                                );
                            }

                            // Count all files
                            AtomicLong fileCount = new AtomicLong(0);
                            AtomicLong dirCount = new AtomicLong(0);
                            try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                                walkStream.forEach(entry -> {
                                    if (entry instanceof FileSystemEntry.RegularFile) {
                                        fileCount.incrementAndGet();
                                    } else if (entry instanceof FileSystemEntry.Directory) {
                                        dirCount.incrementAndGet();
                                    }
                                });
                            }
                            System.out.println("  Total files: " + fileCount.get());
                            System.out.println("  Total directories: " + dirCount.get());
                        } catch (Exception e) {
                            System.out.println("  Mount error: " + e.getMessage());
                        }
                    }
                } else {
                    System.out.println("  Filesystem: unknown/none");
                }
            }
        }
    }
}
