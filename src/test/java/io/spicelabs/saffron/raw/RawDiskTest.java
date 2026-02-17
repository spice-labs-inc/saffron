/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.raw;

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
 * Tests for RAW disk image support.
 */
class RawDiskTest {

    private static final Path TEST_RAW = Paths.get("src/test/resources/raw/minimal.raw");
    private static final Path CORPUS_RAW = Paths.get("test-corpus/raw/cloud/debian/debian-12-genericcloud-amd64.raw");

    static boolean testRawExists() {
        return Files.exists(TEST_RAW);
    }

    static boolean corpusRawExists() {
        return Files.exists(CORPUS_RAW);
    }

    @Test
    @EnabledIf("testRawExists")
    void openMinimalRaw() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            assertThat(disk).isInstanceOf(VirtualDisk.RawDisk.class);
            assertThat(disk.format()).isEqualTo(DiskFormat.RAW);
            assertThat(disk.virtualSize()).isEqualTo(1048576); // 1MB
            assertThat(disk.allocatedSize()).isEqualTo(1048576);

            VirtualDisk.RawDisk rawDisk = (VirtualDisk.RawDisk) disk;
            assertThat(rawDisk.sectorSize()).isEqualTo(512);

            // Verify MBR signature at offset 510
            ByteBuffer mbr = disk.read(510, 2);
            assertThat(mbr.get(0) & 0xFF).isEqualTo(0x55);
            assertThat(mbr.get(1) & 0xFF).isEqualTo(0xAA);
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void rawDiskMetadata() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            var meta = disk.metadata();
            assertThat(meta).containsKey("raw.size");
            assertThat(meta).containsKey("raw.sectorSize");
            assertThat(meta).containsKey("raw.sectors");
            assertThat(meta.get("raw.sectors")).isEqualTo("2048"); // 1MB / 512
        }
    }

    @Test
    @EnabledIf("testRawExists")
    void rawDiskPackageUrl() throws Exception {
        try (VirtualDisk disk = DiskReader.open(TEST_RAW)) {
            var purl = disk.packageUrl();
            assertThat(purl.getType()).isEqualTo("generic");
            assertThat(purl.getNamespace()).isEqualTo("vmdisk");
            assertThat(purl.getName()).isEqualTo("minimal");
        }
    }

    @Test
    @EnabledIf("corpusRawExists")
    void openDebianRaw() throws Exception {
        try (VirtualDisk disk = DiskReader.open(CORPUS_RAW)) {
            assertThat(disk).isInstanceOf(VirtualDisk.RawDisk.class);
            assertThat(disk.format()).isEqualTo(DiskFormat.RAW);

            // Debian cloud image is about 3GB
            assertThat(disk.virtualSize()).isGreaterThan(2L * 1024 * 1024 * 1024);

            // Check MBR signature
            ByteBuffer mbr = disk.read(510, 2);
            assertThat(mbr.get(0) & 0xFF).isEqualTo(0x55);
            assertThat(mbr.get(1) & 0xFF).isEqualTo(0xAA);
        }
    }

    @Test
    void formatDetection() throws Exception {
        // RAW should be detected by extension
        assertThat(DiskFormat.detectByExtension("test.raw")).contains(DiskFormat.RAW);
        assertThat(DiskFormat.detectByExtension("test.img")).contains(DiskFormat.RAW);
        assertThat(DiskFormat.detectByExtension("test.qcow2")).contains(DiskFormat.QCOW2);
    }
}
