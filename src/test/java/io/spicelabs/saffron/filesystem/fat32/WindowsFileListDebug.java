/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.fat32;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Debug tool to list all files found by Saffron in Windows images.
 * This helps identify discrepancies with libguestfs ground truth.
 */
public class WindowsFileListDebug {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        // Win95 image
        String win95Path = CORPUS_BASE + "/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd";

        System.out.println("=== Analyzing Windows 95 Image ===");
        analyzeImage(win95Path, "Win95");

        // Win98 image
        String win98Path = CORPUS_BASE + "/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 98 Plus! Hard Disk.vhd";

        System.out.println("\n=== Analyzing Windows 98 Image ===");
        analyzeImage(win98Path, "Win98");

        // WinME image
        String winMePath = CORPUS_BASE + "/vmdk/legacy/windows-me.vmdk";

        System.out.println("\n=== Analyzing Windows ME Image ===");
        analyzeImage(winMePath, "WinME");
    }

    private static void analyzeImage(String imagePath, String name) throws IOException {
        Path path = Path.of(imagePath);
        if (!java.nio.file.Files.exists(path)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(path)) {
            FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);
            List<FileSystem> allFs = mountResult.mounted();

            System.out.println("Mounted " + allFs.size() + " filesystem(s)");

            List<String> allFiles = new ArrayList<>();
            List<String> hiddenFiles = new ArrayList<>();
            List<String> systemFiles = new ArrayList<>();

            for (FileSystem fs : allFs) {
                System.out.println("Filesystem type: " + fs.type());
                if (fs instanceof FileSystem.Fat32FileSystem fat) {
                    System.out.println("FAT type: " + fat.fatType());
                }

                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile rf) {
                            String pathStr = entry.path();
                            allFiles.add(pathStr);

                            Map<String, Object> attrs = entry.attributes();
                            boolean isHidden = Boolean.TRUE.equals(attrs.get("hidden"));
                            boolean isSystem = Boolean.TRUE.equals(attrs.get("system"));

                            if (isHidden) hiddenFiles.add(pathStr);
                            if (isSystem) systemFiles.add(pathStr);
                        }
                    });
                }
            }

            // Sort and output
            Collections.sort(allFiles);

            System.out.println("Total files found: " + allFiles.size());
            System.out.println("Hidden files: " + hiddenFiles.size());
            System.out.println("System files: " + systemFiles.size());

            // Output all files to a file for comparison
            java.nio.file.Files.write(Path.of("/tmp/" + name + "_saffron_files.txt"), allFiles);
            System.out.println("File list written to: /tmp/" + name + "_saffron_files.txt");

            // Show first 20 and last 20 files
            System.out.println("\nFirst 20 files:");
            for (int i = 0; i < Math.min(20, allFiles.size()); i++) {
                System.out.println("  " + allFiles.get(i));
            }

            System.out.println("\nLast 20 files:");
            for (int i = Math.max(0, allFiles.size() - 20); i < allFiles.size(); i++) {
                System.out.println("  " + allFiles.get(i));
            }

            // Close all filesystems
            for (FileSystem fs : allFs) {
                try { fs.close(); } catch (Exception ignored) {}
            }
        }
    }
}
