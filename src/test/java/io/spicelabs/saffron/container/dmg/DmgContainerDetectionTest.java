/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dmg;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DMG binary container detection.
 */
class DmgContainerDetectionTest {

    private static final String VALID_DMG = "src/test/resources/dmg/valid.dmg";

    @Test
    void detectsValidDmgFromPath() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(VALID_DMG));
        assertThat(format).contains(ContainerFormat.DMG);
    }

    @Test
    void detectsValidDmgFromByteBuffer() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(Path.of(VALID_DMG)));
        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).contains(ContainerFormat.DMG);
    }

    @Test
    void detectsValidDmgFromVirtualDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(Path.of(VALID_DMG))) {
            Optional<ContainerFormat> format = ContainerDetector.detect(disk);
            assertThat(format).contains(ContainerFormat.DMG);
        }
    }

    @Test
    void rejectsEmptyFile() throws IOException {
        Path tmp = Files.createTempFile("empty-", ".dmg");
        tmp.toFile().deleteOnExit();
        Optional<ContainerFormat> format = ContainerDetector.detect(tmp);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsTruncatedFooter() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/dmg/truncated-footer.dmg"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsMissingKolySignature() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/dmg/missing-koly.dmg"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsFooterNotAtEnd() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/dmg/footer-not-at-end.dmg"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsInvalidHeaderSize() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/dmg/invalid-header-size.dmg"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsDataForkBeyondSource() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/dmg/data-fork-beyond-source.dmg"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsNegativeDataForkOffset() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(Path.of(VALID_DMG)));
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(buffer.capacity() - 512 + 24, -1L);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsDataForkOverflow() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(Path.of(VALID_DMG)));
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(buffer.capacity() - 512 + 24, Long.MAX_VALUE);
        buffer.putLong(buffer.capacity() - 512 + 32, 2);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }
}
