/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.ext4;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

class Ext4PartitionDebugTest {

    private static final String CORPUS_BASE = "/home/dpp/tmp/vmreader/saffron/test-corpus";

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    @Test
    @EnabledIf("corpusExists")
    void debugCirrosPartitions() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/cirros-0.6.2-x86_64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            System.out.println("Disk virtual size: " + disk.virtualSize() + " bytes");

            Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);
            if (tableOpt.isEmpty()) {
                System.out.println("No partition table found");
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
                System.out.println("  Size: " + p.sizeInSectors() + " sectors (" + sizeBytes + " bytes, " + (sizeBytes / 1024 / 1024) + " MB)");
                System.out.println("  Offset: " + offset);

                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);
                if (fsInfo.isPresent()) {
                    System.out.println("  Filesystem: " + fsInfo.get().type() + " " + fsInfo.get().version());
                    System.out.println("  FS Total Size: " + fsInfo.get().totalSize() + " bytes");
                    System.out.println("  FS Label: " + fsInfo.get().label().orElse("(none)"));

                    // Try to mount and list root
                    if (fsInfo.get().type() == FileSystem.FileSystemType.EXT4) {
                        try (Ext4FileSystemImpl fs = Ext4FileSystemImpl.mount(disk, offset)) {
                            System.out.println("  Root entries:");
                            var root = fs.root();
                            try (var stream = root.list()) {
                                stream.limit(10).forEach(e ->
                                    System.out.println("    " + e.type() + " " + e.name())
                                );
                            }
                        }
                    }
                } else {
                    System.out.println("  Filesystem: unknown/none");
                }
            }
        }
    }
}
