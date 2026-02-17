/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests format detection using the generated test resources.
 */
class FormatDetectionTest {

    private static final String RESOURCES_PATH = "/";

    @Test
    void detect_qcow2Magic_returnsQcow2() throws IOException {
        Path qcow2File = getResourcePath("qcow2/magic-only.qcow2");
        if (qcow2File == null) return;

        Optional<DiskFormat> format = DiskFormat.detect(qcow2File);

        assertThat(format).isPresent().contains(DiskFormat.QCOW2);
    }

    @Test
    void detect_vmdkMagic_returnsVmdk() throws IOException {
        Path vmdkFile = getResourcePath("vmdk/magic-only.vmdk");
        if (vmdkFile == null) return;

        Optional<DiskFormat> format = DiskFormat.detect(vmdkFile);

        assertThat(format).isPresent().contains(DiskFormat.VMDK);
    }

    @Test
    void detect_vhdMagic_returnsVhd() throws IOException {
        Path vhdFile = getResourcePath("vhd/magic-only.vhd");
        if (vhdFile == null) return;

        Optional<DiskFormat> format = DiskFormat.detect(vhdFile);

        assertThat(format).isPresent().contains(DiskFormat.VHD);
    }

    @Test
    void detect_vhdxMagic_returnsVhdx() throws IOException {
        Path vhdxFile = getResourcePath("vhdx/magic-only.vhdx");
        if (vhdxFile == null) return;

        Optional<DiskFormat> format = DiskFormat.detect(vhdxFile);

        assertThat(format).isPresent().contains(DiskFormat.VHDX);
    }

    @Test
    void detect_emptyFile_returnsEmpty(@TempDir Path tempDir) throws IOException {
        Path emptyFile = tempDir.resolve("empty.bin");
        Files.createFile(emptyFile);

        Optional<DiskFormat> format = DiskFormat.detect(emptyFile);

        assertThat(format).isEmpty();
    }

    @Test
    void detect_randomBytes_returnsEmpty(@TempDir Path tempDir) throws IOException {
        Path randomFile = tempDir.resolve("random.bin");
        byte[] randomBytes = new byte[512];
        new java.util.Random(12345L).nextBytes(randomBytes);
        Files.write(randomFile, randomBytes);

        Optional<DiskFormat> format = DiskFormat.detect(randomFile);

        assertThat(format).isEmpty();
    }

    @Test
    void detect_byMagicBytes_qcow2() {
        byte[] qcow2Magic = {0x51, 0x46, 0x49, (byte) 0xfb, 0, 0, 0, 3};

        Optional<DiskFormat> format = DiskFormat.detect(qcow2Magic);

        assertThat(format).isPresent().contains(DiskFormat.QCOW2);
    }

    @Test
    void detect_byMagicBytes_vmdk() {
        byte[] vmdkMagic = {'K', 'D', 'M', 'V', 1, 0, 0, 0};

        Optional<DiskFormat> format = DiskFormat.detect(vmdkMagic);

        assertThat(format).isPresent().contains(DiskFormat.VMDK);
    }

    @Test
    void detect_byMagicBytes_vhdx() {
        byte[] vhdxMagic = "vhdxfile".getBytes();

        Optional<DiskFormat> format = DiskFormat.detect(vhdxMagic);

        assertThat(format).isPresent().contains(DiskFormat.VHDX);
    }

    @Test
    void detect_byMagicBytes_tooShort() {
        byte[] shortBytes = {0x51, 0x46};

        Optional<DiskFormat> format = DiskFormat.detect(shortBytes);

        assertThat(format).isEmpty();
    }

    @Test
    void detect_byMagicBytes_null() {
        Optional<DiskFormat> format = DiskFormat.detect((byte[]) null);

        assertThat(format).isEmpty();
    }

    @Test
    void detectByExtension_qcow2() {
        assertThat(DiskFormat.detectByExtension("image.qcow2")).contains(DiskFormat.QCOW2);
        assertThat(DiskFormat.detectByExtension("image.QCOW2")).contains(DiskFormat.QCOW2);
        assertThat(DiskFormat.detectByExtension("image.qcow")).contains(DiskFormat.QCOW2);
    }

    @Test
    void detectByExtension_vmdk() {
        assertThat(DiskFormat.detectByExtension("disk.vmdk")).contains(DiskFormat.VMDK);
        assertThat(DiskFormat.detectByExtension("disk.VMDK")).contains(DiskFormat.VMDK);
    }

    @Test
    void detectByExtension_vhd() {
        assertThat(DiskFormat.detectByExtension("disk.vhd")).contains(DiskFormat.VHD);
        assertThat(DiskFormat.detectByExtension("disk.VHD")).contains(DiskFormat.VHD);
    }

    @Test
    void detectByExtension_vhdx() {
        assertThat(DiskFormat.detectByExtension("disk.vhdx")).contains(DiskFormat.VHDX);
        assertThat(DiskFormat.detectByExtension("disk.VHDX")).contains(DiskFormat.VHDX);
    }

    @Test
    void detectByExtension_vdi() {
        assertThat(DiskFormat.detectByExtension("disk.vdi")).contains(DiskFormat.VDI);
        assertThat(DiskFormat.detectByExtension("disk.VDI")).contains(DiskFormat.VDI);
    }

    @Test
    void detectByExtension_unknown() {
        assertThat(DiskFormat.detectByExtension("file.txt")).isEmpty();
        assertThat(DiskFormat.detectByExtension("file.iso")).isEmpty();
        assertThat(DiskFormat.detectByExtension("file")).isEmpty();
    }

    @Test
    void diskFormat_family() {
        assertThat(DiskFormat.QCOW2.family()).isEqualTo(DiskFormat.Family.QEMU);
        assertThat(DiskFormat.VMDK.family()).isEqualTo(DiskFormat.Family.VMWARE);
        assertThat(DiskFormat.VHD.family()).isEqualTo(DiskFormat.Family.MICROSOFT);
        assertThat(DiskFormat.VHDX.family()).isEqualTo(DiskFormat.Family.MICROSOFT);
        assertThat(DiskFormat.VDI.family()).isEqualTo(DiskFormat.Family.ORACLE);
    }

    @Test
    void diskFormat_mimeType() {
        assertThat(DiskFormat.QCOW2.mimeType()).isEqualTo("application/x-qcow2");
        assertThat(DiskFormat.VMDK.mimeType()).isEqualTo("application/x-vmdk");
        assertThat(DiskFormat.VHD.mimeType()).isEqualTo("application/x-vhd");
        assertThat(DiskFormat.VHDX.mimeType()).isEqualTo("application/x-vhdx");
        assertThat(DiskFormat.VDI.mimeType()).isEqualTo("application/x-vdi");
    }

    @Test
    void diskFormat_extension() {
        assertThat(DiskFormat.QCOW2.extension()).isEqualTo(".qcow2");
        assertThat(DiskFormat.VMDK.extension()).isEqualTo(".vmdk");
        assertThat(DiskFormat.VHD.extension()).isEqualTo(".vhd");
        assertThat(DiskFormat.VHDX.extension()).isEqualTo(".vhdx");
        assertThat(DiskFormat.VDI.extension()).isEqualTo(".vdi");
    }

    private Path getResourcePath(String resource) {
        try {
            // Try to find in test resources
            Path resourcePath = Path.of("src/test/resources", resource);
            if (Files.exists(resourcePath)) {
                return resourcePath;
            }

            // Try classpath
            var url = getClass().getResource("/" + resource);
            if (url != null) {
                return Path.of(url.toURI());
            }
        } catch (Exception e) {
            // Resource not found
        }
        return null;
    }
}
