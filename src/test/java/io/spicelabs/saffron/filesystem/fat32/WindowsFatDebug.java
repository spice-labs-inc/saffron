/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.fat32;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Debug tool to analyze raw FAT directory entries in Windows images.
 */
public class WindowsFatDebug {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        // Win95 image
        String win95Path = CORPUS_BASE + "/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd";

        System.out.println("=== Analyzing Windows 95 FAT Root Directory ===");
        analyzeRootDirectory(win95Path);
    }

    private static void analyzeRootDirectory(String imagePath) throws IOException {
        Path path = Path.of(imagePath);
        if (!java.nio.file.Files.exists(path)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(path)) {
            // Read the boot sector (first 512 bytes)
            ByteBuffer bootSector = disk.read(0, 512);
            bootSector.order(ByteOrder.LITTLE_ENDIAN);

            // Parse FAT boot sector
            int bytesPerSector = bootSector.getShort(11) & 0xFFFF;
            int sectorsPerCluster = bootSector.get(13) & 0xFF;
            int reservedSectors = bootSector.getShort(14) & 0xFFFF;
            int numFats = bootSector.get(16) & 0xFF;
            int rootDirEntries = bootSector.getShort(17) & 0xFFFF;
            int totalSectors16 = bootSector.getShort(19) & 0xFFFF;
            int sectorsPerFat16 = bootSector.getShort(22) & 0xFFFF;

            // Check if this is FAT32
            int sectorsPerFat32 = bootSector.getInt(36);
            int rootCluster32 = bootSector.getInt(44);

            // Determine FAT type
            boolean isFat32 = (sectorsPerFat16 == 0);
            int fatType = isFat32 ? 32 : (totalSectors16 == 0 ? 32 : (rootDirEntries == 0 ? 32 : 0));

            if (fatType == 0) {
                // Determine from total sectors
                int totalSectors = (totalSectors16 == 0) ? bootSector.getInt(32) : totalSectors16;
                int dataSectors = totalSectors - (reservedSectors + (numFats * sectorsPerFat16));
                int totalClusters = dataSectors / sectorsPerCluster;
                fatType = (totalClusters < 4085) ? 12 : (totalClusters < 65525) ? 16 : 32;
            }

            System.out.println("Bytes per sector: " + bytesPerSector);
            System.out.println("Sectors per cluster: " + sectorsPerCluster);
            System.out.println("Reserved sectors: " + reservedSectors);
            System.out.println("Number of FATs: " + numFats);
            System.out.println("Root dir entries (FAT12/16): " + rootDirEntries);
            System.out.println("Sectors per FAT (16-bit): " + sectorsPerFat16);
            System.out.println("Sectors per FAT (32-bit): " + sectorsPerFat32);
            System.out.println("Root cluster (FAT32): " + rootCluster32);
            System.out.println("FAT type: FAT" + fatType);
            System.out.println("");

            // Calculate root directory location
            long rootDirOffset;
            int rootDirSize;

            if (fatType == 32) {
                // FAT32: root directory is in data clusters
                int sectorsPerFat = (sectorsPerFat16 == 0) ? sectorsPerFat32 : sectorsPerFat16;
                int dataStartSector = reservedSectors + (numFats * sectorsPerFat);
                rootDirOffset = (dataStartSector + (rootCluster32 - 2) * sectorsPerCluster) * bytesPerSector;
                rootDirSize = 0; // Variable size, need to follow cluster chain
            } else {
                // FAT12/16: root directory is at fixed location
                int sectorsPerFat = sectorsPerFat16;
                int rootDirSectors = (rootDirEntries * 32 + bytesPerSector - 1) / bytesPerSector;
                int rootDirStartSector = reservedSectors + (numFats * sectorsPerFat);
                rootDirOffset = rootDirStartSector * bytesPerSector;
                rootDirSize = rootDirSectors * bytesPerSector;
            }

            System.out.println("Root directory offset: " + rootDirOffset);
            System.out.println("Root directory size: " + rootDirSize + " bytes");
            System.out.println("");

            // Read root directory
            ByteBuffer rootDir;
            if (fatType == 32) {
                // For FAT32, read first cluster
                rootDir = disk.read(rootDirOffset, sectorsPerCluster * bytesPerSector);
            } else {
                rootDir = disk.read(rootDirOffset, rootDirSize);
            }

            // Parse directory entries
            List<DirEntry> entries = parseDirectoryEntries(rootDir);

            System.out.println("Root directory entries: " + entries.size());
            System.out.println("");
            System.out.println("Entries:");
            System.out.println(String.format("%-15s %-10s %-5s %-5s %-5s %-10s %-8s", "Name", "Ext", "Attr", "Hid", "Sys", "Size", "Cluster"));
            System.out.println("-".repeat(70));

            for (DirEntry entry : entries) {
                String name = entry.name;
                String ext = entry.ext;
                String attrStr = String.format("0x%02X", entry.attributes);
                String hidden = (entry.attributes & 0x02) != 0 ? "H" : "";
                String system = (entry.attributes & 0x04) != 0 ? "S" : "";
                String volume = (entry.attributes & 0x08) != 0 ? "V" : "";
                String dir = (entry.attributes & 0x10) != 0 ? "D" : "";
                String archive = (entry.attributes & 0x20) != 0 ? "A" : "";
                String attrFlags = hidden + system + volume + dir + archive;

                System.out.println(String.format("%-15s %-10s %-5s %-5s %-5s %-10d %-8d",
                    name, ext, attrStr, hidden, system, entry.fileSize, entry.firstCluster));
            }

            // Look for duplicate names
            System.out.println("\n=== Checking for duplicates ===");
            for (int i = 0; i < entries.size(); i++) {
                for (int j = i + 1; j < entries.size(); j++) {
                    DirEntry e1 = entries.get(i);
                    DirEntry e2 = entries.get(j);
                    String full1 = e1.name + (e1.ext.isEmpty() ? "" : "." + e1.ext);
                    String full2 = e2.name + (e2.ext.isEmpty() ? "" : "." + e2.ext);
                    if (full1.equalsIgnoreCase(full2)) {
                        System.out.println("Duplicate found: " + full1);
                        System.out.println("  Entry 1: attr=0x" + Integer.toHexString(e1.attributes) + ", size=" + e1.fileSize);
                        System.out.println("  Entry 2: attr=0x" + Integer.toHexString(e2.attributes) + ", size=" + e2.fileSize);
                    }
                }
            }

            // Look for entries with unusual attributes
            System.out.println("\n=== Entries with VOLUME_ID attribute ===");
            for (DirEntry entry : entries) {
                if ((entry.attributes & 0x08) != 0) {
                    String full = entry.name + (entry.ext.isEmpty() ? "" : "." + entry.ext);
                    System.out.println("  " + full + " (attr=0x" + Integer.toHexString(entry.attributes) + ")");
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
