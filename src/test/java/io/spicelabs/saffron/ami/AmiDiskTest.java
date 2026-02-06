/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.ami;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AMI disk image support.
 */
class AmiDiskTest {

    private static final Path TEST_AMI_MANIFEST = Paths.get("src/test/resources/ami/image.manifest.xml");

    static boolean testAmiExists() {
        return Files.exists(TEST_AMI_MANIFEST);
    }

    @Test
    @EnabledIf("testAmiExists")
    void openAmiBundle() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_AMI_MANIFEST)) {
            assertThat(disk).isInstanceOf(VirtualDisk.AmiDisk.class);
            assertThat(disk.format()).isEqualTo(DiskFormat.AMI);

            VirtualDisk.AmiDisk amiDisk = (VirtualDisk.AmiDisk) disk;
            assertThat(amiDisk.imageName()).isEqualTo("test-image");
            assertThat(amiDisk.architecture()).isEqualTo("x86_64");
            assertThat(amiDisk.partCount()).isEqualTo(100);

            // Test resource is unencrypted
            assertThat(disk.isEncrypted()).isFalse();
        }
    }

    @Test
    @EnabledIf("testAmiExists")
    void amiDiskMetadata() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_AMI_MANIFEST)) {
            var meta = disk.metadata();
            assertThat(meta).containsKey("ami.imageName");
            assertThat(meta).containsKey("ami.architecture");
            assertThat(meta).containsKey("ami.partCount");
            assertThat(meta.get("ami.imageName")).isEqualTo("test-image");
            assertThat(meta.get("ami.architecture")).isEqualTo("x86_64");
            assertThat(meta.get("ami.partCount")).isEqualTo("100");
        }
    }

    @Test
    @EnabledIf("testAmiExists")
    void amiDiskPackageUrl() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_AMI_MANIFEST)) {
            var purl = disk.packageUrl();
            assertThat(purl.getType()).isEqualTo("generic");
            assertThat(purl.getNamespace()).isEqualTo("vmdisk");
            assertThat(purl.getName()).isEqualTo("test-image");
            assertThat(purl.getQualifiers()).containsEntry("format", "ami");
            assertThat(purl.getQualifiers()).containsEntry("arch", "x86_64");
        }
    }

    @Test
    @EnabledIf("testAmiExists")
    void amiDiskSizes() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_AMI_MANIFEST)) {
            // Virtual size from manifest
            assertThat(disk.virtualSize()).isEqualTo(1048576);
            // Bundled size from manifest
            assertThat(disk.allocatedSize()).isEqualTo(1048576);
        }
    }

    @Test
    void formatDetection() {
        // AMI should be detected by extension
        assertThat(DiskFormat.detectByExtension("image.manifest.xml")).contains(DiskFormat.AMI);
        assertThat(DiskFormat.detectByExtension("test.manifest.xml")).contains(DiskFormat.AMI);
    }
}
