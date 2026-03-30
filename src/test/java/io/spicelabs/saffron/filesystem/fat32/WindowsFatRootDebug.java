/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.fat32;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Debug tool to analyze raw FAT root directory entries in Windows images.
 * This bypasses the VHD handling in DiskReader and reads from the partition directly.
 */
public class WindowsFatRootDebug {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        // Win95 image
        String win95Path = CORPUS_BASE + "/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd";

        System.out.println("=== Analyzing Windows 95 FAT Root Directory ===");
        analyzeImage(win95Path);
    }

    private static void analyzeImage(String imagePath) throws IOException {
        Path path = Path.of(imagePath);
        if (!java.nio.file.Files.exists(path)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(path)) {
            // Find partition offset
            Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);
            if (tableOpt.isEmpty()) {
                System.out.println("No partition table found");
                return;
            }

            PartitionTable table = tableOpt.get();
            List<Partition> partitions = table.partitions();
            System.out.println("Found " + partitions.size() + " partition(s)");

            if (partitions.isEmpty()) {
                System.out.println("No partitions found");
                return;
            }

            // Use first partition
            Partition p = partitions.get(0);
            long partitionOffset = p.startLba() * 512;
            System.out.println("Partition 1 offset: " + partitionOffset + " bytes (LBA " + p.startLba() + ")");
            System.out.println("Partition 1 size: " + p.sizeInSectors() + " sectors");

            // Read boot sector from partition
            ByteBuffer bootSector = disk.read(partitionOffset, 512);
            bootSector.order(ByteOrder.LITTLE_ENDIAN);

            // Parse FAT boot sector
            byte[] oemNameBytes = new byte[8];
            bootSector.position(3);
            bootSector.get(oemNameBytes);
            String oemName = new String(oemNameBytes, StandardCharsets.US_ASCII);

            bootSector.position(11);
            int bytesPerSector = bootSector.getShort() & 0xFFFF;
            int sectorsPerCluster = bootSector.get() & 0xFF;
            int reservedSectors = bootSector.getShort() & 0xFFFF;
            int numFats = bootSector.get() & 0xFF;
            int rootDirEntries = bootSector.getShort() & 0xFFFF;
            int totalSectors16 = bootSector.getShort() & 0xFFFF;
            bootSector.get(); // media descriptor
            int sectorsPerFat16 = bootSector.getShort() & 0xFFFF;

            // Determine FAT type
            int fatType;
            if (sectorsPerFat16 == 0) {
                fatType = 32;
            } else {
                int totalSectors = (totalSectors16 == 0) ? bootSector.getInt(32) : totalSectors16;
                int dataSectors = totalSectors - (reservedSectors + (numFats * sectorsPerFat16));
                int totalClusters = dataSectors / sectorsPerCluster;
                fatType = (totalClusters < 4085) ? 12 : (totalClusters < 65525) ? 16 : 32;
            }

            System.out.println("\n=== Boot Sector Info ===");
            System.out.println("OEM Name: " + oemName);
            System.out.println("Bytes per sector: " + bytesPerSector);
            System.out.println("Sectors per cluster: " + sectorsPerCluster);
            System.out.println("Reserved sectors: " + reservedSectors);
            System.out.println("Number of FATs: " + numFats);
            System.out.println("Root dir entries (FAT12/16): " + rootDirEntries);
            System.out.println("Sectors per FAT (16-bit): " + sectorsPerFat16);
            System.out.println("FAT type: FAT" + fatType);

            // Calculate root directory location
            long rootDirOffset;
            int rootDirSize;

            if (fatType == 32) {
                // FAT32: root directory is in data clusters
                bootSector.position(36);
                int sectorsPerFat32 = bootSector.getInt();
                int sectorsPerFat = sectorsPerFat32;
                int dataStartSector = reservedSectors + (numFats * sectorsPerFat);
                bootSector.position(44);
                int rootCluster32 = bootSector.getInt();
                rootDirOffset = partitionOffset + (dataStartSector + (rootCluster32 - 2) * sectorsPerCluster) * bytesPerSector;
                rootDirSize = sectorsPerCluster * bytesPerSector;
                System.out.println("Root cluster (FAT32): " + rootCluster32);
            } else {
                // FAT12/16: root directory is at fixed location
                int sectorsPerFat = sectorsPerFat16;
                int rootDirSectors = (rootDirEntries * 32 + bytesPerSector - 1) / bytesPerSector;
                int rootDirStartSector = reservedSectors + (numFats * sectorsPerFat);
                rootDirOffset = partitionOffset + (rootDirStartSector * bytesPerSector);
                rootDirSize = rootDirSectors * bytesPerSector;
            }

            System.out.println("\n=== Root Directory ===");
            System.out.println("Root directory offset: " + rootDirOffset);
            System.out.println("Root directory size: " + rootDirSize + " bytes");

            // Read root directory
            ByteBuffer rootDir = disk.read(rootDirOffset, rootDirSize);

            // Parse directory entries
            List<DirEntry> entries = parseDirectoryEntries(rootDir);

            System.out.println("\n=== Directory Entries ===");
            System.out.println(String.format("%-20s %-8s %-15s %-10s %-8s", "Name", "Attr", "Type", "Size", "Cluster"));
            System.out.println("-".repeat(75));

            int hiddenCount = 0;
            int systemCount = 0;
            int volumeCount = 0;

            for (DirEntry entry : entries) {
                String fullName = entry.name + (entry.ext.isEmpty() ? "" : "." + entry.ext);
                String attrStr = String.format("0x%02X", entry.attributes);

                List<String> types = new ArrayList<>();
                if ((entry.attributes & 0x01) != 0) types.add("RO");
                if ((entry.attributes & 0x02) != 0) { types.add("HID"); hiddenCount++; }
                if ((entry.attributes & 0x04) != 0) { types.add("SYS"); systemCount++; }
                if ((entry.attributes & 0x08) != 0) { types.add("VOL"); volumeCount++; }
                if ((entry.attributes & 0x10) != 0) types.add("DIR");
                if ((entry.attributes & 0x20) != 0) types.add("ARC");

                String typeStr = String.join(",", types);

                System.out.println(String.format("%-20s %-8s %-15s %-10d %-8d",
                    fullName, attrStr, typeStr, entry.fileSize, entry.firstCluster));
            }

            System.out.println("\n=== Summary ===");
            System.out.println("Total entries: " + entries.size());
            System.out.println("Hidden files: " + hiddenCount);
            System.out.println("System files: " + systemCount);
            System.out.println("Volume labels: " + volumeCount);
            System.out.println("Hidden OR System: " + (entries.stream().filter(e -> (e.attributes & 0x06) != 0).count()));

            // List files that are both hidden and system
            System.out.println("\n=== Hidden AND System files (typical for IO.SYS, MSDOS.SYS) ===");
            for (DirEntry entry : entries) {
                if ((entry.attributes & 0x06) == 0x06) {
                    String fullName = entry.name + (entry.ext.isEmpty() ? "" : "." + entry.ext);
                    System.out.println("  " + fullName);
                }
            }
        }
    }

    private static List<DirEntry> parseDirectoryEntries(ByteBuffer buffer) {
        List<DirEntry> entries = new ArrayList<>();
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.remaining() >= 32) {
            int pos = buffer.position();
            byte firstByte = buffer.get(pos);

            // Skip free entries
            if (firstByte == (byte) 0xE5) {
                buffer.position(pos + 32);
                continue;
            }

            // End of directory
            if (firstByte == 0x00) {
                break;
            }

            // Check for LFN (Long File Name) entry
            byte attr = buffer.get(pos + 11);
            if ((attr & 0x0F) == 0x0F) {
                // Skip LFN entry
                buffer.position(pos + 32);
                continue;
            }

            // Parse short filename entry
            byte[] nameBytes = new byte[8];
            byte[] extBytes = new byte[3];
            buffer.position(pos);
            buffer.get(nameBytes);
            buffer.get(extBytes);

            // Handle special first byte
            if (nameBytes[0] == 0x05) {
                nameBytes[0] = (byte) 0xE5;
            }

            String name = new String(nameBytes, StandardCharsets.US_ASCII).trim();
            String ext = new String(extBytes, StandardCharsets.US_ASCII).trim();

            int clusterLow = buffer.getShort(pos + 26) & 0xFFFF;
            int clusterHigh = buffer.getShort(pos + 20) & 0xFFFF;
            int firstCluster = (clusterHigh << 16) | clusterLow;
            int fileSize = buffer.getInt(pos + 28);

            entries.add(new DirEntry(name, ext, attr, firstCluster, fileSize));

            buffer.position(pos + 32);
        }

        return entries;
    }

    private record DirEntry(String name, String ext, int attributes, int firstCluster, int fileSize) {}
}
