/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

class AlpineDiagnosticTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void diagnoseAlpineImage() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/alpine-3.19-cloud-amd64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            System.out.println("=== Alpine 3.19 Diagnostic ===");
            System.out.println("Virtual size: " + disk.virtualSize() + " bytes");

            // Read and dump raw MBR
            System.out.println("\n--- Raw MBR Data ---");
            ByteBuffer mbr = disk.read(0, 512);
            mbr.order(ByteOrder.LITTLE_ENDIAN);

            System.out.println("Boot signature at 510: 0x" + Integer.toHexString(mbr.getShort(510) & 0xFFFF));

            // Check for ext4 superblock at offset 1024 (no partition table - direct filesystem)
            System.out.println("\n--- Checking for direct ext filesystem (no partitions) ---");
            ByteBuffer superblock = disk.read(1024, 256);
            superblock.order(ByteOrder.LITTLE_ENDIAN);
            short extMagic = superblock.getShort(56);
            System.out.println("Ext magic at offset 1024+56: 0x" + Integer.toHexString(extMagic & 0xFFFF) + " (expected 0xef53)");
            if (extMagic == (short) 0xef53) {
                System.out.println("*** This disk has ext filesystem directly without partitions ***");
                int inodeCount = superblock.getInt(0);
                int blockCount = superblock.getInt(4);
                int blockSizeShift = superblock.getInt(24);
                int blockSize = 1024 << blockSizeShift;
                System.out.println("  Inodes: " + inodeCount);
                System.out.println("  Blocks: " + blockCount);
                System.out.println("  Block size: " + blockSize);
            }

            // Check for GPT at offset 512
            ByteBuffer gptHeader = disk.read(512, 92);
            gptHeader.order(ByteOrder.LITTLE_ENDIAN);
            long gptSig = gptHeader.getLong(0);
            System.out.println("\nGPT signature at 512: 0x" + Long.toHexString(gptSig) + " (expected 0x5452415020494645 for GPT)");
            boolean hasGpt = (gptSig == 0x5452415020494645L);
            System.out.println("Has GPT: " + hasGpt);

            // Partition table entries at 446-509
            System.out.println("\nPartition table entries (offset 446):");
            for (int p = 0; p < 4; p++) {
                int offset = 446 + (p * 16);
                System.out.println("  Entry " + p + ":");
                System.out.print("    Raw bytes: ");
                for (int i = 0; i < 16; i++) {
                    System.out.printf("%02x ", mbr.get(offset + i) & 0xFF);
                }
                System.out.println();

                int bootInd = mbr.get(offset) & 0xFF;
                int partType = mbr.get(offset + 4) & 0xFF;
                long startLba = mbr.getInt(offset + 8) & 0xFFFFFFFFL;
                long sizeSectors = mbr.getInt(offset + 12) & 0xFFFFFFFFL;

                System.out.println("    Boot: 0x" + Integer.toHexString(bootInd) +
                                   ", Type: 0x" + Integer.toHexString(partType) +
                                   ", Start LBA: " + startLba +
                                   ", Size: " + sizeSectors);
            }

            Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);
            if (tableOpt.isEmpty()) {
                System.out.println("No partition table detected!");
                return;
            }

            PartitionTable table = tableOpt.get();
            System.out.println("Partition table type: " + table.type());
            System.out.println("Number of partitions: " + table.partitions().size());

            for (Partition p : table.partitions()) {
                System.out.println("\nPartition " + p.index() + ":");
                System.out.println("  Type: " + p.typeName());
                System.out.println("  Start LBA: " + p.startLba());
                System.out.println("  End LBA: " + p.endLba());
                System.out.println("  Size: " + p.sizeInSectors() + " sectors (" + (p.sizeInSectors() * 512 / 1024 / 1024) + " MB)");

                // Try to detect filesystem
                long offset = p.startLba() * 512;
                System.out.println("  Checking filesystem at offset: " + offset);

                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);
                if (fsInfo.isPresent()) {
                    System.out.println("  Filesystem detected: " + fsInfo.get().type() + " " + fsInfo.get().version());
                } else {
                    System.out.println("  No filesystem detected");

                    // Read first 2KB from partition to see what's there
                    System.out.println("  Reading first 2KB from partition:");
                    ByteBuffer data = disk.read(offset, 2048);
                    data.order(ByteOrder.LITTLE_ENDIAN);

                    // Check for ext superblock at offset 1024
                    System.out.println("  Checking for ext superblock at partition offset 1024:");
                    if (offset + 1024 < disk.virtualSize()) {
                        ByteBuffer partSuperblock = disk.read(offset + 1024, 256);
                        partSuperblock.order(ByteOrder.LITTLE_ENDIAN);

                        // ext magic at offset 56 (0x38) relative to superblock start
                        short magic = partSuperblock.getShort(56);
                        System.out.println("    Magic at offset 56: 0x" + Integer.toHexString(magic & 0xFFFF) + " (expected 0xef53 for ext)");

                        if (magic == (short) 0xef53) {
                            System.out.println("    This IS an ext filesystem - detection should work");
                            // Dump more superblock info
                            int inodeCount = partSuperblock.getInt(0);
                            int blockCount = partSuperblock.getInt(4);
                            int blockSizeShift = partSuperblock.getInt(24);
                            int blockSize = 1024 << blockSizeShift;
                            System.out.println("    Inodes: " + inodeCount);
                            System.out.println("    Blocks: " + blockCount);
                            System.out.println("    Block size: " + blockSize);
                        }
                    }

                    // Print raw bytes
                    System.out.println("  First 64 bytes of partition:");
                    for (int i = 0; i < 64; i++) {
                        if (i % 16 == 0) System.out.print("    ");
                        System.out.printf("%02x ", data.get(i) & 0xFF);
                        if (i % 16 == 15) System.out.println();
                    }
                }
            }
        }
    }

    @Test
    @EnabledIf("corpusExists")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void dumpAlpineQcow2Structure() throws Exception {
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/alpine-3.19-cloud-amd64.qcow2");
        if (!Files.exists(imagePath)) {
            return;
        }

        byte[] rawBytes = Files.readAllBytes(imagePath);
        System.out.println("=== Alpine QCOW2 Structure ===");
        System.out.println("File size: " + rawBytes.length + " bytes");

        ByteBuffer header = ByteBuffer.wrap(rawBytes, 0, 104);
        header.order(ByteOrder.BIG_ENDIAN);

        int magic = header.getInt();
        int version = header.getInt();
        long backingFileOffset = header.getLong();
        int backingFileSize = header.getInt();
        int clusterBits = header.getInt();
        long virtualSize = header.getLong();
        header.getInt(); // crypt
        int l1Size = header.getInt();
        long l1TableOffset = header.getLong();

        System.out.println("Magic: 0x" + Integer.toHexString(magic));
        System.out.println("Version: " + version);
        System.out.println("Cluster bits: " + clusterBits + " (size: " + (1 << clusterBits) + ")");
        System.out.println("Virtual size: " + virtualSize);
        System.out.println("L1 size: " + l1Size);
        System.out.println("L1 offset: " + l1TableOffset);

        // Read L1 table
        int clusterSize = 1 << clusterBits;
        System.out.println("\n--- L1 Table ---");
        ByteBuffer l1Table = ByteBuffer.wrap(rawBytes, (int) l1TableOffset, l1Size * 8);
        l1Table.order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < Math.min(4, l1Size); i++) {
            long l1Entry = l1Table.getLong();
            System.out.println("L1[" + i + "]: 0x" + Long.toHexString(l1Entry));

            if (l1Entry != 0) {
                long l2Offset = l1Entry & 0x00fffffffffffe00L;
                System.out.println("  L2 table at: " + l2Offset);

                if (l2Offset > 0 && l2Offset + 64 < rawBytes.length) {
                    ByteBuffer l2Table = ByteBuffer.wrap(rawBytes, (int) l2Offset, 64);
                    l2Table.order(ByteOrder.BIG_ENDIAN);

                    for (int j = 0; j < 8; j++) {
                        long l2Entry = l2Table.getLong();
                        if (l2Entry != 0) {
                            boolean compressed = (l2Entry & (1L << 62)) != 0;
                            System.out.println("    L2[" + j + "]: 0x" + Long.toHexString(l2Entry) +
                                               " (compressed=" + compressed + ")");

                            if (compressed) {
                                // Decode compressed cluster info
                                long descriptor = l2Entry & ~(1L << 62);
                                int x = 70 - clusterBits;
                                int csizeMask = (1 << (clusterBits - 8)) - 1;
                                long offsetMask = (1L << x) - 1;
                                long coffset = descriptor & offsetMask;
                                int nbCsectors = (int) (((descriptor >> x) & csizeMask) + 1);
                                int compressedSize = nbCsectors * 512 - (int) (coffset & 511);

                                System.out.println("      coffset=" + coffset + ", nbCsectors=" + nbCsectors +
                                                   ", compressedSize=" + compressedSize);

                                // Show first few bytes of compressed data
                                if (coffset > 0 && coffset + 32 < rawBytes.length) {
                                    System.out.print("      compressed data: ");
                                    for (int b = 0; b < 32; b++) {
                                        System.out.printf("%02x ", rawBytes[(int) coffset + b] & 0xFF);
                                    }
                                    System.out.println();
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
