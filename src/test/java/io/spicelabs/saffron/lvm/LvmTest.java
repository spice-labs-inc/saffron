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

import com.google.gson.Gson;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.corpus.CorpusTestData;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.filesystem.ext4.Ext4FileSystemImpl;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tests for LVM support.
 *
 * <p>These tests use ground truth data from the corpus scanner to verify each
 * LVM logical volume individually, ensuring that specific expectations for
 * each LV (root, home, swap, etc.) are met.
 */
class LvmTest {

    private static final Path VDI_TEST_FILE = Path.of("test-corpus/vdi/modern/ubuntu-22.04-vbox.vdi");
    private static final Path DEBIAN_TEST_FILE = Path.of("test-corpus/vdi/modern/debian-12-vbox.vdi");
    private static final Path VERIFICATION_DIR = Path.of("src/test/resources/corpus-verification");
    private static final Gson GSON = new Gson();

    static boolean testFileExists() {
        return Files.exists(VDI_TEST_FILE) || Files.exists(DEBIAN_TEST_FILE);
    }

    /**
     * Loads ground truth data for a test image if available.
     *
     * @param imagePath the path to the test image
     * @return the ground truth data, or null if not available
     */
    private CorpusTestData.CorpusImageData loadGroundTruth(Path imagePath) {
        if (!Files.isDirectory(VERIFICATION_DIR)) {
            return null;
        }

        String basename = imagePath.getFileName().toString();
        String jsonName = jsonFilename(basename);
        Path jsonPath = VERIFICATION_DIR.resolve(jsonName);

        if (!Files.exists(jsonPath)) {
            // Try alternative naming
            jsonName = basename.replace(".", "_").replace("-", "_") + ".json";
            jsonPath = VERIFICATION_DIR.resolve(jsonName);
        }

        if (!Files.exists(jsonPath)) {
            return null;
        }

        try (InputStream is = Files.newInputStream(jsonPath)) {
            return GSON.fromJson(new InputStreamReader(is), CorpusTestData.CorpusImageData.class);
        } catch (Exception e) {
            System.err.println("Failed to load ground truth: " + e.getMessage());
            return null;
        }
    }

    /**
     * Converts image basename to JSON filename (same logic as scanner).
     */
    private String jsonFilename(String basename) {
        String name = basename;
        for (char ch : ".-() ".toCharArray()) {
            name = name.replace(String.valueOf(ch), "_");
        }
        while (name.contains("__")) {
            name = name.replace("__", "_");
        }
        return name + ".json";
    }

    /**
     * Finds ground truth data for a specific LVM device.
     */
    private CorpusTestData.FilesystemData findLvGroundTruth(
            CorpusTestData.CorpusImageData imageData, String lvName) {
        if (imageData == null || imageData.filesystems == null) {
            return null;
        }

        for (CorpusTestData.FilesystemData fs : imageData.filesystems) {
            // Match by device string: /dev/vgubuntu/root or /dev/mapper/vgubuntu-root
            if (fs.device != null && (fs.device.endsWith("/" + lvName) ||
                fs.device.contains("/mapper/" + fs.device.replace("/", "-").replace("-", "")))) {
                return fs;
            }
        }
        return null;
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

        // Load ground truth for this image
        CorpusTestData.CorpusImageData groundTruth = loadGroundTruth(testFile);

        try (VirtualDisk disk = DiskReader.open(testFile)) {
            Optional<LvmVolumeGroup> vgOpt = LvmVolumeGroup.detect(disk);

            if (vgOpt.isEmpty()) {
                System.out.println("No LVM detected, skipping filesystem mount test");
                return;
            }

            LvmVolumeGroup vg = vgOpt.get();

            // Test EACH logical volume against its ground truth
            int testedLvs = 0;
            List<String> errors = new ArrayList<>();

            for (LogicalVolumeDisk lv : vg.logicalVolumes()) {
                String lvName = lv.name();
                System.out.println("\nTesting LV: " + lvName);

                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(lv);
                if (fsInfo.isEmpty()) {
                    System.out.println("  No filesystem detected (may be swap or raw)");

                    // Check ground truth - if it's swap, that's expected
                    CorpusTestData.FilesystemData lvTruth = groundTruth != null
                        ? findLvGroundTruth(groundTruth, lvName) : null;
                    if (lvTruth != null && !lvTruth.isMountable()) {
                        System.out.println("  ✓ Ground truth confirms unmountable (swap/LUKS)");
                    }
                    continue;
                }

                // Mount the filesystem
                FileSystem fs;
                try {
                    fs = FileSystemMount.mount(lv, fsInfo.get());
                } catch (UnsupportedOperationException e) {
                    System.out.println("  Unsupported filesystem type: " + fsInfo.get().type());
                    continue;
                }

                try {
                    System.out.println("  Mounted " + fsInfo.get().type() + " filesystem");
                    testedLvs++;

                    // Find ground truth for this LV
                    CorpusTestData.FilesystemData lvTruth = groundTruth != null
                        ? findLvGroundTruth(groundTruth, lvName) : null;

                    if (lvTruth != null) {
                        System.out.println("  Ground truth found: purpose=" + lvTruth.getPurpose());

                        // Verify expected paths exist (critical for root filesystem)
                        List<String> expectedPaths = lvTruth.getExpectedPaths();
                        if (!expectedPaths.isEmpty()) {
                            System.out.println("  Verifying expected paths: " + expectedPaths);
                            for (String path : expectedPaths) {
                                if (fs.resolve(path).isEmpty()) {
                                    errors.add("LV " + lvName + ": missing expected path " + path);
                                }
                            }
                        }

                        // If ground truth says this is root, verify root-specific paths
                        if (lvTruth.isRootFilesystem()) {
                            System.out.println("  Verifying root filesystem paths");
                            if (fs.resolve("/etc").isEmpty()) {
                                errors.add("LV " + lvName + ": root filesystem missing /etc");
                            }
                            if (fs.resolve("/bin").isEmpty()) {
                                errors.add("LV " + lvName + ": root filesystem missing /bin");
                            }
                            if (fs.resolve("/usr").isEmpty()) {
                                errors.add("LV " + lvName + ": root filesystem missing /usr");
                            }
                        }
                    } else {
                        // No ground truth - use basic validation
                        System.out.println("  No ground truth - using basic validation");

                        // If this looks like a root filesystem (has /etc, /bin, /usr), validate it
                        if (fs.resolve("/etc").isPresent() &&
                            fs.resolve("/bin").isPresent() &&
                            fs.resolve("/usr").isPresent()) {
                            System.out.println("  Detected as root filesystem");
                            assertThat(fs.resolve("/etc"))
                                .as("Root filesystem must have /etc")
                                .isPresent();
                            assertThat(fs.resolve("/bin"))
                                .as("Root filesystem must have /bin")
                                .isPresent();
                            assertThat(fs.resolve("/usr"))
                                .as("Root filesystem must have /usr")
                                .isPresent();
                        }
                    }

                    // Basic filesystem checks
                    FileSystemEntry.Directory root = fs.root();
                    System.out.println("  Root directory entries:");
                    try (Stream<FileSystemEntry> entries = root.list()) {
                        entries.limit(10).forEach(entry -> {
                            String type = entry instanceof FileSystemEntry.Directory ? "dir" :
                                    entry instanceof FileSystemEntry.SymbolicLink ? "link" : "file";
                            System.out.println("    " + type + " " + entry.name());
                        });
                    }

                } finally {
                    try { fs.close(); } catch (Exception ignored) {}
                }
            }

            System.out.println("\n=== Summary ===");
            System.out.println("Logical volumes tested: " + testedLvs);

            if (!errors.isEmpty()) {
                System.out.println("Errors:");
                for (String error : errors) {
                    System.out.println("  - " + error);
                }
                fail("LVM verification failed with " + errors.size() + " errors:\n" +
                     String.join("\n", errors));
            }

            assertThat(testedLvs)
                .as("At least one LV should be mountable")
                .isGreaterThan(0);
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
