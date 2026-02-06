/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.gcp;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GCP disk image support.
 */
class GcpDiskTest {

    private static final Path TEST_GCP = Paths.get("src/test/resources/gcp/minimal.tar.gz");

    static boolean testGcpExists() {
        return Files.exists(TEST_GCP);
    }

    @Test
    @EnabledIf("testGcpExists")
    void openMinimalGcp() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_GCP)) {
            assertThat(disk).isInstanceOf(VirtualDisk.GcpDisk.class);
            assertThat(disk.format()).isEqualTo(DiskFormat.GCP);

            VirtualDisk.GcpDisk gcpDisk = (VirtualDisk.GcpDisk) disk;
            assertThat(gcpDisk.innerDisk()).isNotNull();
            assertThat(gcpDisk.innerDisk()).isInstanceOf(VirtualDisk.RawDisk.class);

            // Virtual size should match the inner disk.raw
            assertThat(disk.virtualSize()).isGreaterThan(0);

            // GCP images are compressed
            assertThat(disk.isCompressed()).isTrue();
        }
    }

    @Test
    @EnabledIf("testGcpExists")
    void gcpDiskMetadata() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_GCP)) {
            var meta = disk.metadata();
            assertThat(meta).containsKey("gcp.sourcePath");
            assertThat(meta).containsKey("gcp.extractedPath");
        }
    }

    @Test
    @EnabledIf("testGcpExists")
    void gcpDiskPackageUrl() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_GCP)) {
            var purl = disk.packageUrl();
            assertThat(purl.getType()).isEqualTo("generic");
            assertThat(purl.getNamespace()).isEqualTo("vmdisk");
            assertThat(purl.getQualifiers()).containsEntry("format", "gcp");
        }
    }

    @Test
    void formatDetection() throws Exception {
        // GCP is detected by extension when filename contains "disk"
        assertThat(DiskFormat.detectByExtension("disk.tar.gz")).contains(DiskFormat.GCP);
        assertThat(DiskFormat.detectByExtension("mydisk-image.tar.gz")).contains(DiskFormat.GCP);
        // Plain tar.gz without "disk" is not detected as GCP by extension
        assertThat(DiskFormat.detectByExtension("test.tar.gz")).isEmpty();
    }
}
