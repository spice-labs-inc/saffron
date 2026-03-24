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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for LVM support.
 */
class LvmTest {

    private static final Path VDI_TEST_FILE = Path.of("test-corpus/vdi/modern/ubuntu-22.04-vbox.vdi");
    private static final Path DEBIAN_TEST_FILE = Path.of("test-corpus/vdi/modern/debian-12-vbox.vdi");
    private static final Path TEST_RAW = Paths.get("src/test/resources/raw/minimal.raw");

    /** Metadata with small extents (1 sector = 512 bytes) so reads map safely into minimal.raw */
    private static final String UNIT_TEST_METADATA = """
            vg_unit_test {
                id = "unit-test-vg-uuid-1234"
                extent_size = 1

                physical_volumes {
                    pv0 {
                        id = "unit-test-pv-uuid-1234"
                        dev_size = 2048
                        pe_start = 0
                        pe_count = 2048
                    }
                }

                logical_volumes {
                    root {
                        id = "unit-test-lv-uuid-1234"
                        segment1 {
                            start_extent = 0
                            extent_count = 1
                            type = "striped"
                            stripes = [
                                "pv0", 0
                            ]
                        }
                    }
                }
            }
            """;

    private static final String SAMPLE_METADATA = """
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
                    home {
                        id = "home-1234-5678-90ab"
                        segment1 {
                            start_extent = 0
                            extent_count = 50
                            type = "striped"
                            stripes = [
                                "pv0", 100
                            ]
                        }
                    }
                }
            }
            """;

    static boolean testFileExists() {
        return Files.exists(VDI_TEST_FILE) || Files.exists(DEBIAN_TEST_FILE);
    }

    static boolean testRawExists() {
        return Files.exists(TEST_RAW);
    }

    // -------------------------------------------------------------------------
    // LvmMetadata parsing tests (no corpus files required)
    // -------------------------------------------------------------------------

    @Test
    void testLvmMetadataParsing() {
        Optional<LvmMetadata> metadataOpt = LvmMetadata.parseMetadataText(SAMPLE_METADATA);

        assertThat(metadataOpt).isPresent();

        LvmMetadata metadata = metadataOpt.get();
        assertThat(metadata.vgName()).isEqualTo("vg_test");
        assertThat(metadata.vgUuid()).isEqualTo("abcd1234-5678-90ab-cdef-1234567890ab");
        assertThat(metadata.extentSize()).isEqualTo(8192L);
        assertThat(metadata.physicalVolumes()).hasSize(1);
        assertThat(metadata.logicalVolumes()).hasSize(2);
    }

    @Test
    void testLvmMetadataExtentSizeBytes() {
        Optional<LvmMetadata> metadataOpt = LvmMetadata.parseMetadataText(SAMPLE_METADATA);
        assertThat(metadataOpt).isPresent();

        // extentSizeBytes = extentSize (sectors) * 512
        assertThat(metadataOpt.get().extentSizeBytes()).isEqualTo(8192L * 512L);
    }

    @Test
    void testLvmMetadataFindLogicalVolume() {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(SAMPLE_METADATA).orElseThrow();

        assertThat(metadata.findLogicalVolume("root")).isPresent();
        assertThat(metadata.findLogicalVolume("home")).isPresent();
        assertThat(metadata.findLogicalVolume("nonexistent")).isEmpty();
    }

    @Test
    void testLvmMetadataFindPhysicalVolume() {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(SAMPLE_METADATA).orElseThrow();

        assertThat(metadata.findPhysicalVolume("pv0")).isPresent();
        assertThat(metadata.findPhysicalVolume("nonexistent")).isEmpty();
    }

    @Test
    void testLvmMetadataPhysicalVolumeFields() {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(SAMPLE_METADATA).orElseThrow();
        LvmMetadata.PhysicalVolume pv = metadata.findPhysicalVolume("pv0").orElseThrow();

        assertThat(pv.name()).isEqualTo("pv0");
        assertThat(pv.uuid()).isEqualTo("pv00-1234-5678-90ab");
        assertThat(pv.deviceSize()).isEqualTo(2097152L);
        assertThat(pv.peStart()).isEqualTo(2048L);
        assertThat(pv.peCount()).isEqualTo(255L);
    }

    @Test
    void testLvmMetadataLogicalVolumeFields() {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(SAMPLE_METADATA).orElseThrow();
        LvmMetadata.LogicalVolume rootLv = metadata.findLogicalVolume("root").orElseThrow();

        assertThat(rootLv.name()).isEqualTo("root");
        assertThat(rootLv.uuid()).isEqualTo("root-1234-5678-90ab");
        assertThat(rootLv.segments()).hasSize(1);
    }

    @Test
    void testLvmMetadataLogicalVolumeSizeInExtents() {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(SAMPLE_METADATA).orElseThrow();

        LvmMetadata.LogicalVolume rootLv = metadata.findLogicalVolume("root").orElseThrow();
        assertThat(rootLv.sizeInExtents()).isEqualTo(100L);

        LvmMetadata.LogicalVolume homeLv = metadata.findLogicalVolume("home").orElseThrow();
        assertThat(homeLv.sizeInExtents()).isEqualTo(50L);
    }

    @Test
    void testLvmMetadataSegmentFields() {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(SAMPLE_METADATA).orElseThrow();
        LvmMetadata.LogicalVolume rootLv = metadata.findLogicalVolume("root").orElseThrow();
        LvmMetadata.Segment segment = rootLv.segments().get(0);

        assertThat(segment.startExtent()).isEqualTo(0L);
        assertThat(segment.extentCount()).isEqualTo(100L);
        assertThat(segment.type()).isEqualTo("striped");
        assertThat(segment.stripes()).hasSize(1);
    }

    @Test
    void testLvmMetadataStripeFields() {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(SAMPLE_METADATA).orElseThrow();
        LvmMetadata.LogicalVolume rootLv = metadata.findLogicalVolume("root").orElseThrow();
        LvmMetadata.Stripe stripe = rootLv.segments().get(0).stripes().get(0);

        assertThat(stripe.pvName()).isEqualTo("pv0");
        assertThat(stripe.startExtent()).isEqualTo(0L);
    }

    @Test
    void testLvmMetadataParsingInvalidText() {
        assertThat(LvmMetadata.parseMetadataText("not valid metadata {}")).isEmpty();
        assertThat(LvmMetadata.parseMetadataText("")).isEmpty();
        assertThat(LvmMetadata.parseMetadataText("{ no vg name }")).isEmpty();
    }

    @Test
    void testLvmMetadataDefaultExtentSize() {
        String metadataWithoutExtentSize = """
                vg_default {
                    id = "0000-0000-0000-0000"
                    physical_volumes {
                        pv0 {
                            id = "pvid-0000"
                            dev_size = 1048576
                            pe_start = 2048
                            pe_count = 100
                        }
                    }
                    logical_volumes {
                    }
                }
                """;

        LvmMetadata metadata = LvmMetadata.parseMetadataText(metadataWithoutExtentSize).orElseThrow();
        assertThat(metadata.extentSize()).isEqualTo(8192L);
        assertThat(metadata.extentSizeBytes()).isEqualTo(8192L * 512L);
    }

    @Test
    void testLvmMetadataMdaMagicConstant() {
        assertThat(LvmMetadata.MDA_MAGIC).isEqualTo(" LVM2 x[5A%r0N*>");
    }

    // -------------------------------------------------------------------------
    // LvmLabel constant and record tests (no disk required)
    // -------------------------------------------------------------------------

    @Test
    void testLvmLabelConstants() {
        assertThat(LvmLabel.LABEL_SIGNATURE).isEqualTo("LABELONE");
        assertThat(LvmLabel.LVM2_TYPE).isEqualTo("LVM2 001");
        assertThat(LvmLabel.SECTOR_SIZE).isEqualTo(512);
    }

    @Test
    void testLvmLabelRecordConstruction() {
        LvmLabel label = new LvmLabel(1L, "test-pv-uuid", 10737418240L, 4096L, 1048576L, 1052672L, 0L);

        assertThat(label.sectorNumber()).isEqualTo(1L);
        assertThat(label.pvUuid()).isEqualTo("test-pv-uuid");
        assertThat(label.deviceSize()).isEqualTo(10737418240L);
        assertThat(label.metadataOffset()).isEqualTo(4096L);
        assertThat(label.metadataSize()).isEqualTo(1048576L);
        assertThat(label.dataOffset()).isEqualTo(1052672L);
        assertThat(label.dataSize()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // LvmLabel disk tests (uses minimal.raw from test resources)
    // -------------------------------------------------------------------------

    @Test
    @EnabledIf("testRawExists")
    void testLvmLabelTryParseOnNonLvmDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            Optional<LvmLabel> label = LvmLabel.tryParse(disk, 0);
            assertThat(label).isEmpty();
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void testLvmLabelIsLvmPartitionOnNonLvmDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            assertThat(LvmLabel.isLvmPartition(disk, 0)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // LvmVolumeGroup detection tests (uses minimal.raw from test resources)
    // -------------------------------------------------------------------------

    @Test
    @EnabledIf("testRawExists")
    void testLvmVolumeGroupDetectOnNonLvmDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            Optional<LvmVolumeGroup> vg = LvmVolumeGroup.detect(disk);
            assertThat(vg).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // DiskRegion tests (uses minimal.raw from test resources)
    // -------------------------------------------------------------------------

    @Test
    @EnabledIf("testRawExists")
    void testDiskRegionFromDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            DiskRegion region = DiskRegion.fromDisk(disk);

            assertThat(region.size()).isEqualTo(disk.virtualSize());

            ByteBuffer buf = region.read(0, 4);
            assertThat(buf).isNotNull();
            assertThat(buf.capacity()).isEqualTo(4);
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void testDiskRegionFromPartition() throws IOException {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            DiskRegion region = DiskRegion.fromPartition(disk, 0, 512);

            assertThat(region.size()).isEqualTo(512);

            ByteBuffer buf = region.read(0, 4);
            assertThat(buf).isNotNull();
            assertThat(buf.capacity()).isEqualTo(4);
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void testDiskRegionFromPartitionZeroSizeUsesRemaining() throws IOException {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            // partitionSize=0 means "use remaining disk space"
            DiskRegion region = DiskRegion.fromPartition(disk, 0, 0);
            assertThat(region.size()).isEqualTo(disk.virtualSize());
        }
    }

    // -------------------------------------------------------------------------
    // LogicalVolumeDisk tests (uses minimal.raw + synthetic unit-test metadata)
    // -------------------------------------------------------------------------

    @Test
    @EnabledIf("testRawExists")
    void testLogicalVolumeDiskBasicProperties() throws IOException {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(UNIT_TEST_METADATA).orElseThrow();
        LvmMetadata.LogicalVolume lv = metadata.findLogicalVolume("root").orElseThrow();

        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            LogicalVolumeDisk lvDisk = new LogicalVolumeDisk(disk, 0, metadata, lv);

            assertThat(lvDisk.name()).isEqualTo("root");
            assertThat(lvDisk.uuid()).isEqualTo("unit-test-lv-uuid-1234");
            assertThat(lvDisk.volumeGroupName()).isEqualTo("vg_unit_test");
            assertThat(lvDisk.extentSizeBytes()).isEqualTo(512L); // extent_size=1 sector
            assertThat(lvDisk.segmentCount()).isEqualTo(1);
            assertThat(lvDisk.size()).isEqualTo(512L); // 1 extent * 512 bytes
            assertThat(lvDisk.toString()).contains("vg_unit_test").contains("root");
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void testLogicalVolumeDiskRead() throws IOException {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(UNIT_TEST_METADATA).orElseThrow();
        LvmMetadata.LogicalVolume lv = metadata.findLogicalVolume("root").orElseThrow();

        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            LogicalVolumeDisk lvDisk = new LogicalVolumeDisk(disk, 0, metadata, lv);

            // pe_start=0, stripe startExtent=0 → maps to physical offset 0 in minimal.raw
            ByteBuffer buf = lvDisk.read(0, 4);
            assertThat(buf).isNotNull();
            assertThat(buf.capacity()).isEqualTo(4);
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void testLogicalVolumeDiskReadNegativeOffsetThrows() throws IOException {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(UNIT_TEST_METADATA).orElseThrow();
        LvmMetadata.LogicalVolume lv = metadata.findLogicalVolume("root").orElseThrow();

        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            LogicalVolumeDisk lvDisk = new LogicalVolumeDisk(disk, 0, metadata, lv);

            assertThatThrownBy(() -> lvDisk.read(-1, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void testLogicalVolumeDiskReadBeyondSizeThrows() throws IOException {
        LvmMetadata metadata = LvmMetadata.parseMetadataText(UNIT_TEST_METADATA).orElseThrow();
        LvmMetadata.LogicalVolume lv = metadata.findLogicalVolume("root").orElseThrow();

        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            LogicalVolumeDisk lvDisk = new LogicalVolumeDisk(disk, 0, metadata, lv);

            // LV is 512 bytes; reading 513 bytes from offset 0 exceeds size
            assertThatThrownBy(() -> lvDisk.read(0, 513))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Corpus-dependent tests (skipped when test files are absent)
    // -------------------------------------------------------------------------

    @Test
    @EnabledIf("testFileExists")
    void testLvmDetectionOnVdiImage() throws IOException {
        Path testFile = Files.exists(VDI_TEST_FILE) ? VDI_TEST_FILE : DEBIAN_TEST_FILE;

        try (VirtualDisk disk = DiskReader.open(testFile)) {
            System.out.println("Testing LVM detection on: " + testFile.getFileName());
            System.out.println("Disk virtual size: " + disk.virtualSize());

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

                for (LogicalVolumeDisk lv : vg.logicalVolumes()) {
                    System.out.println("  LV: " + lv.name());
                    System.out.println("    Size: " + lv.size() + " bytes");
                    System.out.println("    Segments: " + lv.segmentCount());

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
            Optional<LogicalVolumeDisk> lvOpt = vg.findLogicalVolume("root");

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

                FileSystemEntry.Directory root = fs.root();
                System.out.println("Root directory contents:");
                try (Stream<FileSystemEntry> entries = root.list()) {
                    entries.limit(20).forEach(entry -> {
                        String type = entry instanceof FileSystemEntry.Directory ? "dir" :
                                entry instanceof FileSystemEntry.SymbolicLink ? "link" : "file";
                        System.out.println("  " + type + " " + entry.name() + " (" + entry.size() + " bytes)");
                    });
                }

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
                FileSystemMount.LvmFilesystemLocation location = lvmFilesystems.get(0);
                FileSystem fs = FileSystemMount.mount(location);
                System.out.println("Successfully mounted filesystem from LVM!");
                System.out.println("  Type: " + (fs instanceof FileSystem.Ext4FileSystem ? "ext4" : "other"));
            }
        }
    }
}
