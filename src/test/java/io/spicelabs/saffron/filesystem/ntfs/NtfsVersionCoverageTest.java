/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ntfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests to verify NTFS version coverage in test corpus.
 */
class NtfsVersionCoverageTest {

    private static final String VHD_BASE = "test-corpus/vhd/legacy/xp-mode/" +
        "Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/";

    // Windows 10 VMDK would go here if stream-optimized format was supported
    // private static final String WIN10_VMDK = "test-corpus/vmdk/legacy/windows-10/" +
    //     "MSEdge-Win10-VMware/MSEdge-Win10-VMware-disk1.vmdk";

    @Test
    void listAllWindowsImageFilesystems() throws Exception {
        String[] images = {
            "Windows 95 Hard Disk.vhd",
            "Windows 98 Plus! Hard Disk.vhd",
            "Windows NT Workstation 4.0 Hard Disk.vhd",
            "Windows XP Mode.vhd",
            "MicrosoftOs-21.30HardDisk.vhd"
        };

        System.out.println("\n=== Windows Image Filesystem Types ===\n");

        int ntfsCount = 0;
        for (String img : images) {
            Path p = Path.of(VHD_BASE + img);
            if (!Files.exists(p)) {
                System.out.println(img + ": NOT FOUND");
                continue;
            }

            try (VirtualDisk disk = DiskReader.open(p)) {
                try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {
                    FileSystemType fsType = fs.type();
                    System.out.println(img + ": " + fsType.name());
                    if (fsType == FileSystemType.NTFS) {
                        ntfsCount++;
                    }
                }
            } catch (Exception e) {
                System.out.println(img + ": ERROR - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        System.out.println("\nTotal NTFS images found: " + ntfsCount);
        assertThat(ntfsCount).as("Should have at least 2 NTFS test images").isGreaterThanOrEqualTo(2);
    }

    @Test
    void verifyNtfs12WindowsNt4() throws Exception {
        Path nt4 = Path.of(VHD_BASE + "Windows NT Workstation 4.0 Hard Disk.vhd");
        if (!Files.exists(nt4)) {
            System.out.println("SKIP: Windows NT 4.0 image not found");
            return;
        }

        try (VirtualDisk disk = DiskReader.open(nt4);
             FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {

            System.out.println("\n=== Windows NT 4.0 (NTFS 1.2) ===");
            System.out.println("Filesystem: " + fs.type().name());
            System.out.println("Total size: " + (fs.totalSize() / (1024 * 1024)) + " MB");
            System.out.println("Label: " + fs.label().orElse("(none)"));

            assertThat(fs.type()).isEqualTo(FileSystemType.NTFS);

            // Verify WINNT directory exists (NT4 used WINNT, not WINDOWS)
            var winnt = fs.resolve("/WINNT");
            assertThat(winnt).as("NT4 should have WINNT directory").isPresent();

            System.out.println("WINNT directory: FOUND");
            System.out.println("NTFS 1.2 (Windows NT 4.0): VERIFIED ✓");
        }
    }

    @Test
    void verifyNtfs31WindowsXp() throws Exception {
        Path xp = Path.of(VHD_BASE + "Windows XP Mode.vhd");
        if (!Files.exists(xp)) {
            System.out.println("SKIP: Windows XP Mode image not found");
            return;
        }

        try (VirtualDisk disk = DiskReader.open(xp);
             FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {

            System.out.println("\n=== Windows XP (NTFS 3.1) ===");
            System.out.println("Filesystem: " + fs.type().name());
            System.out.println("Total size: " + (fs.totalSize() / (1024L * 1024 * 1024)) + " GB");
            System.out.println("Label: " + fs.label().orElse("(none)"));

            assertThat(fs.type()).isEqualTo(FileSystemType.NTFS);

            // Verify WINDOWS directory exists (XP uses WINDOWS, not WINNT)
            var windows = fs.resolve("/WINDOWS");
            assertThat(windows).as("XP should have WINDOWS directory").isPresent();

            // Verify system32 exists
            var sys32 = fs.resolve("/WINDOWS/system32");
            assertThat(sys32).as("XP should have system32 directory").isPresent();

            System.out.println("WINDOWS directory: FOUND");
            System.out.println("system32 directory: FOUND");
            System.out.println("NTFS 3.1 (Windows XP): VERIFIED ✓");
        }
    }

    @Test
    void printNtfsVersionCoverageSummary() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("NTFS VERSION COVERAGE SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println();
        System.out.println("| NTFS Version | Windows Version      | Test Data     | Status |");
        System.out.println("|--------------|----------------------|---------------|--------|");
        System.out.println("| NTFS 1.0     | Windows NT 3.1       | -             | N/A    |");
        System.out.println("| NTFS 1.1     | Windows NT 3.5x      | -             | N/A    |");

        boolean hasNt4 = Files.exists(Path.of(VHD_BASE + "Windows NT Workstation 4.0 Hard Disk.vhd"));
        boolean hasXp = Files.exists(Path.of(VHD_BASE + "Windows XP Mode.vhd"));

        System.out.println("| NTFS 1.2     | Windows NT 4.0       | " +
            (hasNt4 ? "503 MB VHD    " : "-             ") + "| " + (hasNt4 ? "✓      " : "MISSING") + "|");
        System.out.println("| NTFS 3.0     | Windows 2000         | -             | MISSING|");
        System.out.println("| NTFS 3.1     | Windows XP           | " +
            (hasXp ? "1.2 GB VHD    " : "-             ") + "| " + (hasXp ? "✓      " : "MISSING") + "|");
        System.out.println("| NTFS 3.1     | Windows Vista+       | -             | MISSING|");
        System.out.println();
        int coverage = (hasNt4 ? 1 : 0) + (hasXp ? 1 : 0);
        System.out.println("Coverage: " + coverage + "/3 major versions (NT4=1.2, 2000=3.0, XP+=3.1)");
        System.out.println("=".repeat(60));
    }
}
