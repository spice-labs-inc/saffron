/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostic test for cloud images.
 */
class CloudImageDiagnosticTest {

    private static final String CORPUS_BASE = "/home/dpp/tmp/vmreader/saffron/test-corpus";

    static boolean ubuntuCloudImageExists() {
        return Files.exists(Paths.get(CORPUS_BASE, "qcow2/cloud/ubuntu/ubuntu-24.04-server-cloudimg-amd64.qcow2"));
    }

    @Test
    @EnabledIf("ubuntuCloudImageExists")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void diagnoseUbuntuCloudImage() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/cloud/ubuntu/ubuntu-24.04-server-cloudimg-amd64.qcow2");

        System.out.println("=== Ubuntu 24.04 Cloud Image Diagnostic ===");
        System.out.println("Path: " + imagePath);
        System.out.println("Size: " + Files.size(imagePath) + " bytes");

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            System.out.println("\nDisk opened successfully!");
            System.out.println("Virtual size: " + disk.virtualSize() + " bytes");
            System.out.println("Virtual size: " + (disk.virtualSize() / (1024L * 1024L * 1024L)) + " GB");

            // Try to detect partitions
            System.out.println("\n--- Partition Detection ---");
            var partitionTable = PartitionTable.detect(disk);

            if (partitionTable.isPresent()) {
                System.out.println("Partition table type: " + partitionTable.get().type());
                System.out.println("Number of partitions: " + partitionTable.get().partitions().size());

                for (var partition : partitionTable.get().partitions()) {
                    long sizeBytes = partition.sizeInBytes(512);
                    System.out.println("\nPartition " + partition.index() + ":");
                    System.out.println("  Name: " + partition.name().orElse("(unnamed)"));
                    System.out.println("  Start LBA: " + partition.startLba());
                    System.out.println("  Start offset: " + (partition.startLba() * 512) + " bytes");
                    System.out.println("  Size: " + sizeBytes + " bytes (" + (sizeBytes / (1024L * 1024L)) + " MB)");
                    System.out.println("  Type: " + partition.typeName());
                }
            } else {
                System.out.println("No partition table detected");
            }
        }
    }
}
