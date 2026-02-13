/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

class DebugPartitionTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void debugCirrosPartitions() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/cirros-0.6.2-x86_64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            System.out.println("\n=== CirrOS 0.6.2 ===");
            System.out.println("Virtual size: " + disk.virtualSize() + " bytes");

            // Read raw GPT header for debugging
            java.nio.ByteBuffer gptHeader = disk.read(512, 512);
            gptHeader.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            System.out.println("GPT signature: " + Long.toHexString(gptHeader.getLong(0)));
            System.out.println("GPT revision: " + Integer.toHexString(gptHeader.getInt(8)));
            System.out.println("Entries LBA: " + gptHeader.getLong(72));
            System.out.println("Num entries: " + gptHeader.getInt(80));
            System.out.println("Entry size: " + gptHeader.getInt(84));

            // Read first partition entry - read entire 128 byte entry
            long entriesLba = gptHeader.getLong(72);
            long entriesOffset = entriesLba * 512;
            System.out.println("Reading partition entries at offset: " + entriesOffset);

            java.nio.ByteBuffer entry = disk.read(entriesOffset, 128);
            entry.order(java.nio.ByteOrder.LITTLE_ENDIAN);

            // Print all 128 bytes of the first entry
            System.out.println("First partition entry (128 bytes):");
            for (int row = 0; row < 8; row++) {
                System.out.printf("  %3d: ", row * 16);
                for (int col = 0; col < 16; col++) {
                    System.out.printf("%02x ", entry.get(row * 16 + col) & 0xFF);
                }
                System.out.println();
            }

            // Parse the entry fields manually
            entry.position(0);
            System.out.println("\nParsed fields:");
            System.out.print("  Type GUID (bytes 0-15): ");
            for (int i = 0; i < 16; i++) {
                System.out.printf("%02x ", entry.get(i) & 0xFF);
            }
            System.out.println();

            System.out.print("  Unique GUID (bytes 16-31): ");
            for (int i = 16; i < 32; i++) {
                System.out.printf("%02x ", entry.get(i) & 0xFF);
            }
            System.out.println();

            // Get the LBA values
            entry.position(32);
            long startLba = entry.getLong();
            long endLba = entry.getLong();
            System.out.println("  Starting LBA (bytes 32-39): " + startLba);
            System.out.println("  Ending LBA (bytes 40-47): " + endLba);

            // Read raw bytes at the LBA offsets to verify
            System.out.print("  Raw bytes 32-47: ");
            for (int i = 32; i < 48; i++) {
                System.out.printf("%02x ", entry.get(i) & 0xFF);
            }
            System.out.println();

            // Also check if the QCOW2 reader reports this region as allocated
            if (disk instanceof VirtualDisk.Qcow2Disk) {
                VirtualDisk.Qcow2Disk qcow2 = (VirtualDisk.Qcow2Disk) disk;
                System.out.println("\nQCOW2 specific info:");
                System.out.println("  Cluster size: " + qcow2.clusterSize());
                System.out.println("  Entries offset in cluster: " + (entriesOffset % qcow2.clusterSize()));
            }

            Optional<PartitionTable> table = PartitionTable.detect(disk);
            if (table.isPresent()) {
                System.out.println("\nPartition table type: " + table.get().type());
                System.out.println("Partitions: " + table.get().partitions().size());
                for (Partition p : table.get().partitions()) {
                    long sizeKB = (p.sizeInSectors() * 512) / 1024;
                    System.out.printf("  Partition %d: start=%d, end=%d, size=%d sectors (%d KB), type=%s%n",
                            p.index(), p.startLba(), p.endLba(), p.sizeInSectors(), sizeKB, p.typeName());
                }
            } else {
                System.out.println("No partition table detected");
            }
        }
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void debugUbuntuPartitions() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/ubuntu-22.04-cloudimg-amd64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            System.out.println("\n=== Ubuntu 22.04 ===");
            System.out.println("Virtual size: " + disk.virtualSize() + " bytes");

            Optional<PartitionTable> table = PartitionTable.detect(disk);
            if (table.isPresent()) {
                System.out.println("Partition type: " + table.get().type());
                System.out.println("Partitions: " + table.get().partitions().size());
                for (Partition p : table.get().partitions()) {
                    long sizeKB = (p.sizeInSectors() * 512) / 1024;
                    System.out.printf("  Partition %d: start=%d, size=%d sectors (%d KB), type=%s%n",
                            p.index(), p.startLba(), p.sizeInSectors(), sizeKB, p.typeName());
                }
            } else {
                System.out.println("No partition table detected");
            }
        }
    }
}
