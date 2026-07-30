/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.linuxkernel;

import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Linux kernel image format detection.
 *
 * <p>Each test documents the fixture it uses and the kernel subtype it validates.
 * Detection must recognize bzImage, zImage, raw ARM64 Image, and U-Boot uImage
 * as {@link ContainerFormat#LINUX_KERNEL}, while rejecting random data and
 * truncated headers cleanly.</p>
 */
class LinuxKernelDetectionTest {

    private static final String FIXTURE_DIR = "src/test/resources/linux-kernel";

    /**
     * Verifies that an x86 bzImage (IoTGoat x86 vmlinuz) is detected as a Linux
     * kernel container.
     */
    @Test
    void detectsBzImage() throws IOException {
        Path fixture = Path.of(FIXTURE_DIR, "iotgoat-x86-vmlinuz");

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).contains(ContainerFormat.LINUX_KERNEL);
    }

    /**
     * Verifies that an ARM32 zImage (IoTGoat Raspberry Pi kernel.img) is detected
     * as a Linux kernel container.
     */
    @Test
    void detectsZImage() throws IOException {
        Path fixture = Path.of(FIXTURE_DIR, "iotgoat-rpi-kernel.img");

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).contains(ContainerFormat.LINUX_KERNEL);
    }

    /**
     * Verifies that a gzip-compressed ARM64 Image (Raspberry Pi kernel8.img) is
     * detected as a compressed single payload. Compressed-single detection must
     * take precedence over the legacy gzip-image kernel detection so that a
     * plain .gz file is not misclassified as a Linux kernel.
     */
    @Test
    void detectsImage() throws IOException {
        Path fixture = Path.of(FIXTURE_DIR, "raspberrypi-kernel8.img");

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).contains(ContainerFormat.COMPRESSED_SINGLE);
    }

    /**
     * Verifies that a U-Boot uImage (OpenWrt oxnas akitio_mycloud) is detected as
     * a Linux kernel container.
     */
    @Test
    void detectsUImage() throws IOException {
        Path fixture = Path.of(FIXTURE_DIR, "openwrt-oxnas-akitio_mycloud-uImage");

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).contains(ContainerFormat.LINUX_KERNEL);
    }

    /**
     * Verifies that random bytes are not misclassified as a Linux kernel.
     */
    @Test
    void rejectsRandomData(@TempDir Path tempDir) throws IOException {
        Path fixture = tempDir.resolve("random.bin");
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        Files.write(fixture, data);

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).isEmpty();
    }

    /**
     * Verifies that a file that only contains a kernel magic prefix but lacks the
     * full header is not detected as a mountable kernel.
     */
    @Test
    void rejectsTruncatedHeader(@TempDir Path tempDir) throws IOException {
        Path fixture = tempDir.resolve("truncated-uImage.bin");
        // U-Boot uImage magic followed by only a few bytes
        byte[] data = new byte[] {0x27, 0x05, 0x19, 0x56, 0x00, 0x00, 0x00, 0x00};
        Files.write(fixture, data);

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).isEmpty();
    }
}
