/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.exception;

import io.spicelabs.saffron.DiskFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the exception hierarchy.
 */
class ExceptionHierarchyTest {

    @Test
    void saffronException_includesFormatContext() {
        SaffronException ex = new SaffronException("test", DiskFormat.QCOW2);

        assertThat(ex.getFormat()).isPresent().contains(DiskFormat.QCOW2);
    }

    @Test
    void saffronException_withoutFormat_returnsEmptyOptional() {
        SaffronException ex = new SaffronException("test");

        assertThat(ex.getFormat()).isEmpty();
    }

    @Test
    void saffronException_preservesMessage() {
        SaffronException ex = new SaffronException("test message", DiskFormat.VMDK);

        assertThat(ex.getMessage()).isEqualTo("test message");
    }

    @Test
    void saffronException_preservesCause() {
        Exception cause = new RuntimeException("original");
        SaffronException ex = new SaffronException("wrapped", cause);

        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void invalidMagicException_includesExpectedAndActual() {
        byte[] expected = {0x51, 0x46, 0x49};
        byte[] actual = {0x00, 0x00, 0x00};

        InvalidMagicException ex = new InvalidMagicException(
                "Bad magic", expected, actual, 0L, DiskFormat.QCOW2);

        assertThat(ex.getExpectedMagic()).isEqualTo(expected);
        assertThat(ex.getActualMagic()).isEqualTo(actual);
        assertThat(ex.getOffset()).isEqualTo(0L);
    }

    @Test
    void invalidMagicException_defensivelyCopiesArrays() {
        byte[] expected = {0x51, 0x46, 0x49};
        byte[] actual = {0x00, 0x00, 0x00};

        InvalidMagicException ex = new InvalidMagicException(
                "Bad magic", expected, actual, 0L, DiskFormat.QCOW2);

        // Modify original arrays
        expected[0] = 0x00;
        actual[0] = (byte) 0xFF;

        // Exception should have copies
        assertThat(ex.getExpectedMagic()[0]).isEqualTo((byte) 0x51);
        assertThat(ex.getActualMagic()[0]).isEqualTo((byte) 0x00);
    }

    @Test
    void unsupportedVersionException_includesVersionInfo() {
        UnsupportedVersionException ex = new UnsupportedVersionException(
                "Version 99 not supported", 99, 2, 3, DiskFormat.QCOW2);

        assertThat(ex.getVersion()).isEqualTo(99);
        assertThat(ex.getMinSupported()).isEqualTo(2);
        assertThat(ex.getMaxSupported()).isEqualTo(3);
    }

    @Test
    void corruptedDiskException_includesOffset() {
        CorruptedDiskException ex = new CorruptedDiskException(
                "Bad offset", 0x1000L, "L1 table", DiskFormat.QCOW2);

        assertThat(ex.getOffset()).isEqualTo(0x1000L);
        assertThat(ex.getStructureName()).isEqualTo("L1 table");
    }

    @Test
    void corruptedDiskException_withoutOffset_returnsNegativeOne() {
        CorruptedDiskException ex = new CorruptedDiskException(
                "Bad data", DiskFormat.VDI);

        assertThat(ex.getOffset()).isEqualTo(-1L);
    }

    @Test
    void checksumException_includesChecksumInfo() {
        ChecksumException ex = new ChecksumException(
                "Checksum mismatch", 0x12345678L, 0xABCDEF00L, "CRC32",
                0x2000L, "header", DiskFormat.VHDX);

        assertThat(ex.getExpectedChecksum()).isEqualTo(0x12345678L);
        assertThat(ex.getActualChecksum()).isEqualTo(0xABCDEF00L);
        assertThat(ex.getChecksumType()).isEqualTo("CRC32");
    }

    @Test
    void encryptedDiskException_includesEncryptionMethod() {
        EncryptedDiskException ex = new EncryptedDiskException(
                "Encrypted disk", "AES-256", DiskFormat.QCOW2);

        assertThat(ex.getEncryptionMethod()).isEqualTo("AES-256");
    }

    @Test
    void resourceLimitException_includesLimitInfo() {
        ResourceLimitException ex = new ResourceLimitException(
                "Size exceeded", "allocation_size", 1024L, 2048L);

        assertThat(ex.getResourceType()).isEqualTo("allocation_size");
        assertThat(ex.getLimit()).isEqualTo(1024L);
        assertThat(ex.getAttempted()).isEqualTo(2048L);
    }

    @Test
    void resourceLimitException_decompressionBomb_factoryMethod() {
        ResourceLimitException ex = ResourceLimitException.decompressionBomb(1024L, 10240L);

        assertThat(ex.getResourceType()).isEqualTo("decompressed_size");
        assertThat(ex.getLimit()).isEqualTo(1024L);
        assertThat(ex.getAttempted()).isEqualTo(10240L);
        assertThat(ex.getMessage()).contains("decompression bomb");
    }

    @Test
    void resourceLimitException_allocationTooLarge_factoryMethod() {
        ResourceLimitException ex = ResourceLimitException.allocationTooLarge(1024L, 2048L);

        assertThat(ex.getResourceType()).isEqualTo("allocation_size");
        assertThat(ex.getLimit()).isEqualTo(1024L);
        assertThat(ex.getAttempted()).isEqualTo(2048L);
    }

    // Hierarchy tests
    @Test
    void invalidDiskException_extendsSaffronException() {
        assertThat(SaffronException.InvalidDiskException.class.getSuperclass())
                .isEqualTo(SaffronException.class);
    }

    @Test
    void unsupportedDiskException_extendsSaffronException() {
        assertThat(SaffronException.UnsupportedDiskException.class.getSuperclass())
                .isEqualTo(SaffronException.class);
    }

    @Test
    void invalidMagicException_extendsInvalidDiskException() {
        assertThat(InvalidMagicException.class.getSuperclass())
                .isEqualTo(SaffronException.InvalidDiskException.class);
    }

    @Test
    void corruptedDiskException_extendsInvalidDiskException() {
        assertThat(CorruptedDiskException.class.getSuperclass())
                .isEqualTo(SaffronException.InvalidDiskException.class);
    }

    @Test
    void checksumException_extendsCorruptedDiskException() {
        assertThat(ChecksumException.class.getSuperclass())
                .isEqualTo(CorruptedDiskException.class);
    }

    @Test
    void unsupportedVersionException_extendsUnsupportedDiskException() {
        assertThat(UnsupportedVersionException.class.getSuperclass())
                .isEqualTo(SaffronException.UnsupportedDiskException.class);
    }

    @Test
    void encryptedDiskException_extendsUnsupportedDiskException() {
        assertThat(EncryptedDiskException.class.getSuperclass())
                .isEqualTo(SaffronException.UnsupportedDiskException.class);
    }

    @Test
    void resourceLimitException_extendsSaffronException() {
        assertThat(ResourceLimitException.class.getSuperclass())
                .isEqualTo(SaffronException.class);
    }

    @Test
    void saffronException_extendsRuntimeException() {
        assertThat(SaffronException.class.getSuperclass())
                .isEqualTo(RuntimeException.class);
    }
}
