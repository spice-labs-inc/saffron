/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test to debug QCOW2 reading issues.
 */
class Qcow2DiagnosticTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void diagnoseQcow2Reading() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/cirros-0.6.2-x86_64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            System.out.println("=== QCOW2 Diagnostic for CirrOS ===");
            System.out.println("Virtual size: " + disk.virtualSize() + " bytes");

            if (disk instanceof VirtualDisk.Qcow2Disk qcow2) {
                System.out.println("Cluster size: " + qcow2.clusterSize() + " bytes");
            }

            // Read first 4KB to see MBR and GPT header
            System.out.println("\n--- Reading first 4KB ---");
            ByteBuffer first4k = disk.read(0, 4096);
            first4k.order(ByteOrder.LITTLE_ENDIAN);

            // Check MBR signature at offset 510
            System.out.println("MBR signature at 510: " + String.format("0x%04x", first4k.getShort(510) & 0xFFFF));

            // Check GPT signature at offset 512
            first4k.position(512);
            long gptSig = first4k.getLong();
            System.out.println("GPT signature at 512: 0x" + Long.toHexString(gptSig));
            System.out.println("Expected 'EFI PART': 0x5452415020494645");

            // Read GPT header details
            first4k.position(512 + 72); // Partition entries LBA
            long entriesLba = first4k.getLong();
            int numEntries = first4k.getInt();
            int entrySize = first4k.getInt();
            System.out.println("\nGPT Header:");
            System.out.println("  Partition entries LBA: " + entriesLba);
            System.out.println("  Number of entries: " + numEntries);
            System.out.println("  Entry size: " + entrySize);

            // Calculate entries offset
            long entriesOffset = entriesLba * 512;
            System.out.println("  Entries byte offset: " + entriesOffset);

            // Read partition entries (should start at LBA 2 = offset 1024)
            System.out.println("\n--- Reading partition entries at offset " + entriesOffset + " ---");
            ByteBuffer entries = disk.read(entriesOffset, 512);
            entries.order(ByteOrder.LITTLE_ENDIAN);

            // Print first 128 bytes (first partition entry)
            System.out.println("First partition entry raw bytes:");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 128; i++) {
                if (i % 16 == 0) {
                    if (i > 0) System.out.println(sb);
                    sb = new StringBuilder(String.format("  %3d: ", i));
                }
                sb.append(String.format("%02x ", entries.get(i) & 0xFF));
            }
            System.out.println(sb);

            // Parse the partition entry
            entries.position(0);

            // Type GUID (bytes 0-15)
            System.out.println("\nParsed partition entry 0:");
            byte[] typeGuid = new byte[16];
            entries.get(typeGuid);
            System.out.print("  Type GUID: ");
            for (byte b : typeGuid) System.out.printf("%02x", b & 0xFF);
            System.out.println();

            // Unique GUID (bytes 16-31)
            byte[] uniqueGuid = new byte[16];
            entries.get(uniqueGuid);
            System.out.print("  Unique GUID: ");
            for (byte b : uniqueGuid) System.out.printf("%02x", b & 0xFF);
            System.out.println();

            // Starting LBA (bytes 32-39)
            long startLba = entries.getLong();
            System.out.println("  Starting LBA: " + startLba);

            // Ending LBA (bytes 40-47)
            long endLba = entries.getLong();
            System.out.println("  Ending LBA: " + endLba);

            // Check if the data looks valid
            boolean typeGuidAllZeros = true;
            for (byte b : typeGuid) {
                if (b != 0) {
                    typeGuidAllZeros = false;
                    break;
                }
            }

            if (typeGuidAllZeros) {
                System.out.println("\n*** ERROR: Type GUID is all zeros - data not being read correctly ***");
            }

            if (startLba == 0 && endLba == 0) {
                System.out.println("*** ERROR: LBAs are zero - partition data not being read correctly ***");

                // Let's try reading the same offset directly with a larger buffer
                System.out.println("\n--- Attempting to read larger buffer around partition entries ---");
                ByteBuffer largeRead = disk.read(0, 8192);
                largeRead.order(ByteOrder.LITTLE_ENDIAN);

                System.out.println("Data at offset 1024-1152 (from large read):");
                sb = new StringBuilder();
                for (int i = 1024; i < 1152; i++) {
                    if ((i - 1024) % 16 == 0) {
                        if (i > 1024) System.out.println(sb);
                        sb = new StringBuilder(String.format("  %3d: ", i - 1024));
                    }
                    sb.append(String.format("%02x ", largeRead.get(i) & 0xFF));
                }
                System.out.println(sb);
            }

            // Verify we can read known data (GPT header is at 512)
            System.out.println("\n--- Verification: re-read GPT header ---");
            ByteBuffer gptHeader = disk.read(512, 92);
            gptHeader.order(ByteOrder.LITTLE_ENDIAN);
            long sig2 = gptHeader.getLong();
            System.out.println("GPT signature (re-read): 0x" + Long.toHexString(sig2));
            assertThat(sig2).as("GPT signature should be valid").isEqualTo(0x5452415020494645L);

            // Check if this is a compressed cluster
            if (disk instanceof VirtualDisk.Qcow2Disk) {
                System.out.println("\n--- Checking cluster allocation for offset 1024 ---");
                var qcow2Impl = (io.spicelabs.saffron.qcow2.Qcow2DiskImpl) disk;
                System.out.println("Is offset 0-65536 allocated? " + qcow2Impl.isAllocated(0, 65536));
            }
        }
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void dumpQcow2RawFile() throws Exception {
        // Read the raw QCOW2 file to see what's actually stored
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/cirros-0.6.2-x86_64.qcow2");
        if (!Files.exists(imagePath)) {
            return;
        }

        byte[] rawBytes = Files.readAllBytes(imagePath);
        System.out.println("=== Raw QCOW2 File Analysis ===");
        System.out.println("File size: " + rawBytes.length + " bytes");

        // QCOW2 header
        ByteBuffer header = ByteBuffer.wrap(rawBytes, 0, 104);
        header.order(ByteOrder.BIG_ENDIAN); // QCOW2 header is big-endian

        int magic = header.getInt();
        int version = header.getInt();
        long backingFileOffset = header.getLong();
        int backingFileSize = header.getInt();
        int clusterBits = header.getInt();
        long virtualSize = header.getLong();
        int cryptMethod = header.getInt();
        int l1Size = header.getInt();
        long l1TableOffset = header.getLong();
        long refcountTableOffset = header.getLong();
        int refcountTableClusters = header.getInt();
        int nbSnapshots = header.getInt();
        long snapshotsOffset = header.getLong();

        System.out.println("\nQCOW2 Header:");
        System.out.println("  Magic: 0x" + Integer.toHexString(magic) + " (expected 0x514649fb)");
        System.out.println("  Version: " + version);
        System.out.println("  Cluster bits: " + clusterBits + " (cluster size: " + (1 << clusterBits) + ")");
        System.out.println("  Virtual size: " + virtualSize);
        System.out.println("  L1 size: " + l1Size);
        System.out.println("  L1 table offset: " + l1TableOffset);

        // Read L1 table entry for cluster 0
        int clusterSize = 1 << clusterBits;
        int l2Entries = clusterSize / 8;

        System.out.println("\n--- L1 Table (first few entries) ---");
        ByteBuffer l1Table = ByteBuffer.wrap(rawBytes, (int) l1TableOffset, Math.min(l1Size * 8, rawBytes.length - (int) l1TableOffset));
        l1Table.order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < Math.min(4, l1Size); i++) {
            long l1Entry = l1Table.getLong();
            System.out.println("  L1[" + i + "]: 0x" + Long.toHexString(l1Entry));

            if (l1Entry != 0) {
                long l2Offset = l1Entry & 0x00fffffffffffe00L;
                System.out.println("    L2 table offset: " + l2Offset);

                // Read first few L2 entries
                if (l2Offset > 0 && l2Offset < rawBytes.length) {
                    System.out.println("    --- L2 Table (first entries) ---");
                    ByteBuffer l2Table = ByteBuffer.wrap(rawBytes, (int) l2Offset, Math.min(64, rawBytes.length - (int) l2Offset));
                    l2Table.order(ByteOrder.BIG_ENDIAN);

                    for (int j = 0; j < 8 && l2Table.hasRemaining(); j++) {
                        long l2Entry = l2Table.getLong();
                        boolean compressed = (l2Entry & (1L << 62)) != 0;
                        boolean zero = (l2Entry & 1L) != 0 && (l2Entry & 0x00fffffffffffe00L) == 0;
                        long offset = l2Entry & 0x00fffffffffffe00L;

                        System.out.println("      L2[" + j + "]: 0x" + Long.toHexString(l2Entry) +
                                           " (compressed=" + compressed + ", zero=" + zero +
                                           ", offset=" + offset + ")");
                    }
                }
            }
        }
    }
}
