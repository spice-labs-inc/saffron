/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem;

import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link FilesystemInfo}.
 */
class FilesystemInfoTest {

    @Test
    void typeName_returnsCorrectName() {
        FilesystemInfo info = createFilesystemInfo(FileSystemType.EXT4, "ext4");
        assertThat(info.typeName()).isEqualTo("ext4");
    }

    @Test
    void description_returnsCorrectDescription() {
        FilesystemInfo info = createFilesystemInfo(FileSystemType.NTFS, "3.1");
        assertThat(info.description()).isEqualTo("Windows NTFS filesystem");
    }

    @Test
    void usedPercentage_calculatesCorrectly() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                1000,
                600,
                400,
                4096,
                0
        );

        assertThat(info.usedPercentage()).isEqualTo(60.0);
    }

    @Test
    void usedPercentage_zeroTotalSize_returnsZero() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                0,
                4096,
                0
        );

        assertThat(info.usedPercentage()).isEqualTo(0.0);
    }

    @Test
    void freePercentage_calculatesCorrectly() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                1000,
                600,
                400,
                4096,
                0
        );

        assertThat(info.freePercentage()).isEqualTo(40.0);
    }

    @Test
    void formattedTotalSize_bytes_returnsCorrectFormat() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                512,
                0,
                0,
                4096,
                0
        );

        assertThat(info.formattedTotalSize()).isEqualTo("512 B");
    }

    @Test
    void formattedTotalSize_kilobytes_returnsCorrectFormat() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                10240,
                0,
                0,
                4096,
                0
        );

        assertThat(info.formattedTotalSize()).isEqualTo("10.0 KB");
    }

    @Test
    void formattedTotalSize_megabytes_returnsCorrectFormat() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                10 * 1024 * 1024L,
                0,
                0,
                4096,
                0
        );

        assertThat(info.formattedTotalSize()).isEqualTo("10.0 MB");
    }

    @Test
    void formattedTotalSize_gigabytes_returnsCorrectFormat() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                10L * 1024 * 1024 * 1024,
                0,
                0,
                4096,
                0
        );

        assertThat(info.formattedTotalSize()).isEqualTo("10.0 GB");
    }

    @Test
    void formattedTotalSize_terabytes_returnsCorrectFormat() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.empty(),
                Optional.empty(),
                2L * 1024 * 1024 * 1024 * 1024,
                0,
                0,
                4096,
                0
        );

        assertThat(info.formattedTotalSize()).isEqualTo("2.0 TB");
    }

    @Test
    void toMetadata_includesAllFields() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.of("myvol"),
                Optional.of("12345678-abcd-ef01-2345-67890abcdef0"),
                1000000,
                600000,
                400000,
                4096,
                65536
        );

        Map<String, String> metadata = info.toMetadata();

        assertThat(metadata).containsEntry("fs.type", "ext4");
        assertThat(metadata).containsEntry("fs.version", "ext4");
        assertThat(metadata).containsEntry("fs.label", "myvol");
        assertThat(metadata).containsEntry("fs.uuid", "12345678-abcd-ef01-2345-67890abcdef0");
        assertThat(metadata).containsEntry("fs.totalSize", "1000000");
        assertThat(metadata).containsEntry("fs.usedSize", "600000");
        assertThat(metadata).containsEntry("fs.freeSize", "400000");
        assertThat(metadata).containsEntry("fs.blockSize", "4096");
        assertThat(metadata).containsEntry("fs.inodeCount", "65536");
    }

    @Test
    void toMetadata_omitsEmptyOptionals() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.NTFS,
                "3.1",
                Optional.empty(),
                Optional.empty(),
                1000,
                0,
                0,
                4096,
                0
        );

        Map<String, String> metadata = info.toMetadata();

        assertThat(metadata).doesNotContainKey("fs.label");
        assertThat(metadata).doesNotContainKey("fs.uuid");
        assertThat(metadata).doesNotContainKey("fs.usedSize");
        assertThat(metadata).doesNotContainKey("fs.freeSize");
        assertThat(metadata).doesNotContainKey("fs.inodeCount");
    }

    @Test
    void toString_includesTypeAndVersion() {
        FilesystemInfo info = new FilesystemInfo(
                FileSystemType.EXT4,
                "ext4",
                Optional.of("testvol"),
                Optional.empty(),
                1024 * 1024 * 1024L,
                0,
                0,
                4096,
                0
        );

        String str = info.toString();

        assertThat(str).contains("ext4");
        assertThat(str).contains("testvol");
        assertThat(str).contains("1.0 GB");
    }

    private FilesystemInfo createFilesystemInfo(FileSystemType type, String version) {
        return new FilesystemInfo(
                type,
                version,
                Optional.empty(),
                Optional.empty(),
                1000,
                0,
                0,
                4096,
                0
        );
    }
}
