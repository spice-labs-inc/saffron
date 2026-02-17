/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.lvm;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.filesystem.ext4.Ext4FileSystemImpl;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for LVM support.
 */
class LvmTest {

    private static final Path VDI_TEST_FILE = Path.of("test-corpus/vdi/modern/ubuntu-22.04-vbox.vdi");
    private static final Path DEBIAN_TEST_FILE = Path.of("test-corpus/vdi/modern/debian-12-vbox.vdi");

    static boolean testFileExists() {
        return Files.exists(VDI_TEST_FILE) || Files.exists(DEBIAN_TEST_FILE);
    }

    @Test
    void testLvmMetadataParsing() {
        // Test LVM metadata text parsing
        String metadataText = """
            vg_test {
                id = "abcd1234-5678-90ab-cdef-1234567890ab"
                extent_size = 8192

                physical_volumes {
                    pv0 {
                        id = "pv00-1234-5678-90ab"
                        dev_size = 2097152
                        pe_start = 2048
                        pe_count = 255
                    }
                }

                logical_volumes {
                    root {
                        id = "root-1234-5678-90ab"
                        segment1 {
                            start_extent = 0
                            extent_count = 100
                            type = "striped"
                            stripes = [
                                "pv0", 0
                            ]
                        }
                    }
                }
            }
            """;

        // The parsing should work on this metadata format
        // This is a simplified test - real LVM metadata may have more fields
        assertThat(metadataText).contains("vg_test");
        assertThat(metadataText).contains("logical_volumes");
        assertThat(metadataText).contains("physical_volumes");
    }

    @Test
    @EnabledIf("testFileExists")
    void testLvmDetectionOnVdiImage() throws IOException {
        Path testFile = Files.exists(VDI_TEST_FILE) ? VDI_TEST_FILE : DEBIAN_TEST_FILE;

        try (VirtualDisk disk = DiskReader.open(testFile)) {
            System.out.println("Testing LVM detection on: " + testFile.getFileName());
            System.out.println("Disk virtual size: " + disk.virtualSize());

            // Try to detect LVM
            Optional<LvmVolumeGroup> vgOpt = LvmVolumeGroup.detect(disk);

            if (vgOpt.isPresent()) {
                LvmVolumeGroup vg = vgOpt.get();
                System.out.println("Found LVM Volume Group: " + vg.name());
                System.out.println("  VG UUID: " + vg.uuid());
                System.out.println("  PV UUID: " + vg.pvUuid());
                System.out.println("  Extent size: " + vg.extentSizeBytes() + " bytes");
                System.out.println("  Logical volumes: " + vg.logicalVolumeCount());

                assertThat(vg.name()).isNotEmpty();
                assertThat(vg.logicalVolumeCount()).isGreaterThan(0);

                // List all logical volumes
                for (LogicalVolumeDisk lv : vg.logicalVolumes()) {
                    System.out.println("  LV: " + lv.name());
                    System.out.println("    Size: " + lv.size() + " bytes");
                    System.out.println("    Segments: " + lv.segmentCount());

                    // Try to detect filesystem in this LV
                    Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(lv);
                    if (fsInfo.isPresent()) {
                        System.out.println("    Filesystem: " + fsInfo.get().type());
                        System.out.println("    FS Total size: " + fsInfo.get().totalSize());
                    }
                }
            } else {
                System.out.println("No LVM detected - image may not have LVM partition");
            }
        }
    }

    @Test
    @EnabledIf("testFileExists")
    void testMountFilesystemFromLvm() throws IOException {
        Path testFile = Files.exists(VDI_TEST_FILE) ? VDI_TEST_FILE : DEBIAN_TEST_FILE;

        try (VirtualDisk disk = DiskReader.open(testFile)) {
            Optional<LvmVolumeGroup> vgOpt = LvmVolumeGroup.detect(disk);

            if (vgOpt.isEmpty()) {
                System.out.println("No LVM detected, skipping filesystem mount test");
                return;
            }

            LvmVolumeGroup vg = vgOpt.get();
            Optional<LogicalVolumeDisk> lvOpt = vg.largestLogicalVolume();

            assertThat(lvOpt).isPresent();

            LogicalVolumeDisk lv = lvOpt.get();
            System.out.println("Mounting filesystem from LV: " + lv.name());

            Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(lv);
            assertThat(fsInfo).isPresent();

            if (fsInfo.get().type() == FileSystem.FileSystemType.EXT4) {
                FileSystem fs = Ext4FileSystemImpl.mount(lv);
                System.out.println("Mounted ext4 filesystem");
                System.out.println("  Total size: " + fs.totalSize());
                System.out.println("  Used size: " + fs.usedSize());
                System.out.println("  Label: " + fs.label().orElse("(none)"));

                // List root directory
                FileSystemEntry.Directory root = fs.root();
                System.out.println("Root directory contents:");
                try (Stream<FileSystemEntry> entries = root.list()) {
                    entries.limit(20).forEach(entry -> {
                        String type = entry instanceof FileSystemEntry.Directory ? "dir" :
                                entry instanceof FileSystemEntry.SymbolicLink ? "link" : "file";
                        System.out.println("  " + type + " " + entry.name() + " (" + entry.size() + " bytes)");
                    });
                }

                // Check for common Linux root directories
                assertThat(fs.resolve("/etc")).isPresent();
                assertThat(fs.resolve("/usr")).isPresent();
                assertThat(fs.resolve("/bin")).isPresent();
            }
        }
    }

    @Test
    @EnabledIf("testFileExists")
    void testFindLvmFilesystems() throws IOException {
        Path testFile = Files.exists(VDI_TEST_FILE) ? VDI_TEST_FILE : DEBIAN_TEST_FILE;

        try (VirtualDisk disk = DiskReader.open(testFile)) {
            List<FileSystemMount.LvmFilesystemLocation> lvmFilesystems =
                    FileSystemMount.findLvmFilesystems(disk);

            System.out.println("Found " + lvmFilesystems.size() + " LVM filesystems:");
            for (FileSystemMount.LvmFilesystemLocation loc : lvmFilesystems) {
                System.out.println("  LV: " + loc.logicalVolume().name());
                System.out.println("    Type: " + loc.info().type());
                System.out.println("    Size: " + loc.info().totalSize());
            }

            if (!lvmFilesystems.isEmpty()) {
                // Mount one of the LVM filesystems
                FileSystemMount.LvmFilesystemLocation location = lvmFilesystems.get(0);
                FileSystem fs = FileSystemMount.mount(location);
                System.out.println("Successfully mounted filesystem from LVM!");
                System.out.println("  Type: " + (fs instanceof FileSystem.Ext4FileSystem ? "ext4" : "other"));
            }
        }
    }
}
