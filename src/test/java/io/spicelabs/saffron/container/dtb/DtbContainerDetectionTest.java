/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dtb;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DTB binary container detection.
 */
class DtbContainerDetectionTest {

    private static final String DTB = "src/test/resources/dtb/bcm2710-rpi-3-b.dtb";

    @Test
    void detectsDtb() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(DTB));
        assertThat(format).contains(ContainerFormat.DTB);
    }

    @Test
    void detectsDtbFromVirtualDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(Path.of(DTB))) {
            Optional<ContainerFormat> format = ContainerDetector.detect(disk);
            assertThat(format).contains(ContainerFormat.DTB);
        }
    }

    @Test
    void rejectsRandomData() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/invalid-random.bin"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsTruncatedHeader() throws IOException {
        Path tmp = Files.createTempFile("truncated-dtb-", ".bin");
        tmp.toFile().deleteOnExit();
        Files.write(tmp, new byte[]{0x00});

        Optional<ContainerFormat> format = ContainerDetector.detect(tmp);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsBadMagic() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0x7f, 0x45, 0x4c, 0x46, 0x00, 0x00, 0x00, 0x00});

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }

    @Test
    void detectsDtbFromBuffer() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(DTB));
        Optional<ContainerFormat> format = ContainerDetector.detect(ByteBuffer.wrap(data));
        assertThat(format).contains(ContainerFormat.DTB);
    }
}
