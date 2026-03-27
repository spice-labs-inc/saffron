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
import java.util.List;
import java.util.stream.Stream;

/**
 * Debug tool to list all hidden+system files in Windows images.
 */
public class WindowsHiddenSystemFiles {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        String[] images = {
            "Win95", CORPUS_BASE + "/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 95 Hard Disk.vhd",
            "Win98", CORPUS_BASE + "/vhd/legacy/xp-mode/Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/Windows 98 Plus! Hard Disk.vhd",
            "WinME", CORPUS_BASE + "/vmdk/legacy/windows-me.vmdk"
        };

        for (int i = 0; i < images.length; i += 2) {
            String name = images[i];
            String path = images[i + 1];
            System.out.println("\n=== " + name + " Hidden+System Files ===");
            analyzeImage(path);
        }
    }

    private static void analyzeImage(String imagePath) throws IOException {
        Path path = Path.of(imagePath);
        if (!java.nio.file.Files.exists(path)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(path)) {
            FileSystemMount.MountAllResult mountResult = FileSystemMount.mountAllWithDetected(disk);

            for (FileSystem fs : mountResult.mounted()) {
                if (fs instanceof FileSystem.Fat32FileSystem) {
                    List<String> hiddenSystemFiles = new ArrayList<>();
                    long totalFiles = 0;

                    try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                        for (FileSystemEntry entry : walkStream.toList()) {
                            if (entry instanceof FileSystemEntry.RegularFile) {
                                totalFiles++;
                                var attrs = entry.attributes();
                                boolean hidden = Boolean.TRUE.equals(attrs.get("hidden"));
                                boolean system = Boolean.TRUE.equals(attrs.get("system"));
                                if (hidden && system) {
                                    hiddenSystemFiles.add(entry.path());
                                }
                            }
                        }
                    }

                    System.out.println("Total files: " + totalFiles);
                    System.out.println("Hidden+System files: " + hiddenSystemFiles.size());
                    System.out.println("Files:");
                    for (String f : hiddenSystemFiles) {
                        System.out.println("  " + f);
                    }
                }
                fs.close();
            }
        }
    }
}
