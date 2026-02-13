/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron;

import io.spicelabs.saffron.common.ByteUtils;
import io.spicelabs.saffron.common.PathSecurity;
import io.spicelabs.saffron.exception.*;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Smoke test to verify the project compiles and basic functionality works.
 *
 * <p>This test is part of Phase 0 to ensure the project scaffolding is correct.
 */
class SmokeTest {

    @Test
    void diskFormatEnumExists() {
        assertThat(DiskFormat.values()).hasSize(8);
        assertThat(DiskFormat.QCOW2.mimeType()).isEqualTo("application/x-qcow2");
        assertThat(DiskFormat.VMDK.extension()).isEqualTo(".vmdk");
        assertThat(DiskFormat.VHD.magic()).isEqualTo("conectix".getBytes());
        assertThat(DiskFormat.VHDX.family()).isEqualTo(DiskFormat.Family.MICROSOFT);
        assertThat(DiskFormat.VDI.family()).isEqualTo(DiskFormat.Family.ORACLE);
    }

    @Test
    void diskFormatDetectionByMagic() {
        // QCOW2 magic
        byte[] qcow2Magic = {0x51, 0x46, 0x49, (byte) 0xfb};
        assertThat(DiskFormat.detect(qcow2Magic)).contains(DiskFormat.QCOW2);

        // VMDK magic
        byte[] vmdkMagic = {0x4b, 0x44, 0x4d, 0x56};
        assertThat(DiskFormat.detect(vmdkMagic)).contains(DiskFormat.VMDK);

        // VHDX magic
        byte[] vhdxMagic = "vhdxfile".getBytes();
        assertThat(DiskFormat.detect(vhdxMagic)).contains(DiskFormat.VHDX);

        // Unknown magic
        byte[] unknown = {0x00, 0x00, 0x00, 0x00};
        assertThat(DiskFormat.detect(unknown)).isEmpty();
    }

    @Test
    void diskFormatDetectionByExtension() {
        assertThat(DiskFormat.detectByExtension("image.qcow2")).contains(DiskFormat.QCOW2);
        assertThat(DiskFormat.detectByExtension("disk.vmdk")).contains(DiskFormat.VMDK);
        assertThat(DiskFormat.detectByExtension("volume.vhd")).contains(DiskFormat.VHD);
        assertThat(DiskFormat.detectByExtension("disk.vhdx")).contains(DiskFormat.VHDX);
        assertThat(DiskFormat.detectByExtension("image.vdi")).contains(DiskFormat.VDI);
        assertThat(DiskFormat.detectByExtension("file.txt")).isEmpty();
    }

    @Test
    void securityPolicyDefaults() {
        SecurityPolicy policy = SecurityPolicy.defaults();
        assertThat(policy.maxDecompressedSize()).isEqualTo(SecurityPolicy.DEFAULT_MAX_DECOMPRESSED_SIZE);
        assertThat(policy.maxAllocationSize()).isEqualTo(SecurityPolicy.DEFAULT_MAX_ALLOCATION_SIZE);
        assertThat(policy.validateChecksums()).isTrue();
        assertThat(policy.rejectBidiChars()).isTrue();
    }

    @Test
    void securityPolicyBuilder() {
        SecurityPolicy policy = SecurityPolicy.builder()
                .maxDecompressedSize(1024 * 1024)
                .maxAllocationSize(512 * 1024)
                .validateChecksums(false)
                .build();

        assertThat(policy.maxDecompressedSize()).isEqualTo(1024 * 1024);
        assertThat(policy.maxAllocationSize()).isEqualTo(512 * 1024);
        assertThat(policy.validateChecksums()).isFalse();
    }

    @Test
    void byteUtilsBigEndian() {
        byte[] data = {0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xDE, (byte) 0xF0};

        assertThat(ByteUtils.readU16BE(data, 0)).isEqualTo(0x1234);
        assertThat(ByteUtils.readU32BE(data, 0)).isEqualTo(0x12345678L);
        assertThat(ByteUtils.readI64BE(data, 0)).isEqualTo(0x123456789ABCDEF0L);
    }

    @Test
    void byteUtilsLittleEndian() {
        byte[] data = {0x78, 0x56, 0x34, 0x12, (byte) 0xF0, (byte) 0xDE, (byte) 0xBC, (byte) 0x9A};

        assertThat(ByteUtils.readU16LE(data, 0)).isEqualTo(0x5678);
        assertThat(ByteUtils.readU32LE(data, 0)).isEqualTo(0x12345678L);
        assertThat(ByteUtils.readI64LE(data, 0)).isEqualTo(0x9ABCDEF012345678L);
    }

    @Test
    void byteUtilsHexString() {
        byte[] data = {0x12, 0x34, (byte) 0xAB, (byte) 0xCD};
        assertThat(ByteUtils.toHexString(data)).isEqualTo("1234abcd");
        assertThat(ByteUtils.toHexString(data, 2)).isEqualTo("1234...");
    }

    @Test
    void pathSecurityTraversalDetection() {
        assertThat(PathSecurity.containsPathTraversal("/etc/passwd")).isFalse();
        assertThat(PathSecurity.containsPathTraversal("/home/user/file.txt")).isFalse();
        assertThat(PathSecurity.containsPathTraversal("../etc/passwd")).isTrue();
        assertThat(PathSecurity.containsPathTraversal("/etc/../passwd")).isTrue();
        assertThat(PathSecurity.containsPathTraversal("..")).isTrue();
    }

    @Test
    void pathSecurityBidiDetection() {
        assertThat(PathSecurity.containsBidiChars("normal.txt")).isFalse();
        assertThat(PathSecurity.containsBidiChars("file\u202Ename.txt")).isTrue(); // RLO
        assertThat(PathSecurity.containsBidiChars("doc\u200Fname.pdf")).isTrue();  // RLM
    }

    @Test
    void pathSecurityZeroWidthDetection() {
        assertThat(PathSecurity.containsZeroWidthChars("normal.txt")).isFalse();
        assertThat(PathSecurity.containsZeroWidthChars("file\u200Bname.txt")).isTrue(); // ZWSP
        assertThat(PathSecurity.containsZeroWidthChars("doc\uFEFFname.pdf")).isTrue();  // BOM
    }

    @Test
    void pathSecurityNormalization() {
        assertThat(PathSecurity.normalizePath("/a//b///c")).isEqualTo("/a/b/c");
        assertThat(PathSecurity.normalizePath("/a/./b")).isEqualTo("/a/b");
        assertThat(PathSecurity.normalizePath("a\\b\\c")).isEqualTo("a/b/c");
        assertThat(PathSecurity.normalizePath("/path/")).isEqualTo("/path");
    }

    @Test
    void exceptionsHaveCorrectHierarchy() {
        // SaffronException base
        assertThat(new SaffronException("test")).isInstanceOf(RuntimeException.class);

        // InvalidDiskException
        assertThat(new SaffronException.InvalidDiskException("test"))
                .isInstanceOf(SaffronException.class);

        // UnsupportedDiskException
        assertThat(new SaffronException.UnsupportedDiskException("test"))
                .isInstanceOf(SaffronException.class);

        // InvalidMagicException
        assertThat(new InvalidMagicException("test", null, null, 0, null))
                .isInstanceOf(SaffronException.InvalidDiskException.class);

        // CorruptedDiskException
        assertThat(new CorruptedDiskException("test", null))
                .isInstanceOf(SaffronException.InvalidDiskException.class);

        // ChecksumException
        assertThat(new ChecksumException("test", 0, 0, null, 0, null, null))
                .isInstanceOf(CorruptedDiskException.class);

        // UnsupportedVersionException
        assertThat(new UnsupportedVersionException("test", 1, 2, 3, null))
                .isInstanceOf(SaffronException.UnsupportedDiskException.class);

        // EncryptedDiskException
        assertThat(new EncryptedDiskException("test", null))
                .isInstanceOf(SaffronException.UnsupportedDiskException.class);

        // ResourceLimitException
        assertThat(ResourceLimitException.decompressionBomb(100, 1000))
                .isInstanceOf(SaffronException.class);
    }

    @Test
    void diskReaderIsNotSupportedForNonExistentFile() {
        assertThat(DiskReader.isSupported(Path.of("/non/existent/file.qcow2"))).isFalse();
    }

    @Test
    void diskReaderThrowsForUnknownFormat() throws IOException {
        // Create a temp file with unknown content
        Path tempFile = Files.createTempFile("test", ".unknown");
        try {
            Files.write(tempFile, new byte[]{0x00, 0x00, 0x00, 0x00});

            assertThatThrownBy(() -> DiskReader.open(tempFile))
                    .isInstanceOf(SaffronException.UnsupportedDiskException.class)
                    .hasMessageContaining("Unable to detect");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void fileSystemTypeEnumExists() {
        assertThat(FileSystem.FileSystemType.values()).hasSize(9);
        assertThat(FileSystem.FileSystemType.EXT4.getName()).isEqualTo("ext4");
        assertThat(FileSystem.FileSystemType.NTFS.getDescription()).contains("NTFS");
    }

    @Test
    void fileSystemEntryTypeEnumExists() {
        assertThat(FileSystemEntry.EntryType.values()).hasSize(8);
        assertThat(FileSystemEntry.EntryType.REGULAR_FILE).isNotNull();
        assertThat(FileSystemEntry.EntryType.DIRECTORY).isNotNull();
        assertThat(FileSystemEntry.EntryType.SYMBOLIC_LINK).isNotNull();
    }

    @Test
    void posixPermissionsWork() {
        FileSystemEntry.PosixPermissions perms =
                new FileSystemEntry.PosixPermissions(0755, 1000, 1000, "user", "group");

        assertThat(perms.ownerRead()).isTrue();
        assertThat(perms.ownerWrite()).isTrue();
        assertThat(perms.ownerExecute()).isTrue();
        assertThat(perms.groupRead()).isTrue();
        assertThat(perms.groupWrite()).isFalse();
        assertThat(perms.groupExecute()).isTrue();
        assertThat(perms.othersRead()).isTrue();
        assertThat(perms.othersWrite()).isFalse();
        assertThat(perms.othersExecute()).isTrue();
    }

    @Test
    void virtualDiskSnapshotRecordWorks() {
        VirtualDisk.Snapshot snapshot = new VirtualDisk.Snapshot(
                "snap1", "Test Snapshot", 1024, 1704067200L, 500000);

        assertThat(snapshot.id()).isEqualTo("snap1");
        assertThat(snapshot.name()).isEqualTo("Test Snapshot");
        assertThat(snapshot.vmStateSize()).isEqualTo(1024);
        assertThat(snapshot.dateSeconds()).isEqualTo(1704067200L);
        assertThat(snapshot.dateNanos()).isEqualTo(500000);
    }
}
