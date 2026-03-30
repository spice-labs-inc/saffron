/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Debug tool to analyze Btrfs subvolume discovery.
 */
public class BtrfsSubvolumeDebug {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        // Test with Fedora image which has Btrfs subvolumes
        String fedoraPath = CORPUS_BASE + "/qcow2/modern/Fedora-Cloud-Base-Generic-42-1.1.x86_64.qcow2";

        System.out.println("=== Analyzing Fedora Btrfs Subvolumes ===");
        analyzeImage(fedoraPath);
    }

    private static void analyzeImage(String imagePath) throws IOException {
        Path path = Path.of(imagePath);
        if (!java.nio.file.Files.exists(path)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(path)) {
            // Find partition table
            Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);
            if (tableOpt.isEmpty()) {
                System.out.println("No partition table found");
                return;
            }

            PartitionTable table = tableOpt.get();
            List<Partition> partitions = table.partitions();
            System.out.println("Found " + partitions.size() + " partition(s)");

            // Find Btrfs partitions
            for (int i = 0; i < partitions.size(); i++) {
                Partition p = partitions.get(i);
                long offset = p.startLba() * 512;

                // Try to detect filesystem
                var fsInfoOpt = io.spicelabs.saffron.filesystem.FilesystemDetector.detect(disk, offset);
                if (fsInfoOpt.isPresent() && fsInfoOpt.get().type() == FileSystem.FileSystemType.BTRFS) {
                    System.out.println("\n=== Partition " + (i+1) + " (index " + i + ") - Btrfs ===");
                    System.out.println("  Offset: " + offset);
                    System.out.println("  Size: " + p.sizeInSectors() + " sectors");

                    // Mount Btrfs with subvolumes
                    DiskRegion region = DiskRegion.fromPartition(disk, offset, 0);
                    List<BtrfsFileSystemImpl> subvolumes = BtrfsFileSystemImpl.mountWithSubvolumes(region, 0);

                    System.out.println("  Found " + subvolumes.size() + " subvolume(s):");
                    for (BtrfsFileSystemImpl fs : subvolumes) {
                        System.out.println("    - Object ID: " + fs.subvolumeObjectId());
                        System.out.println("      Name: " + fs.subvolumeName().orElse("(unknown)"));
                    }

                    // Close all subvolumes
                    for (BtrfsFileSystemImpl fs : subvolumes) {
                        fs.close();
                    }
                }
            }
        }
    }
}
