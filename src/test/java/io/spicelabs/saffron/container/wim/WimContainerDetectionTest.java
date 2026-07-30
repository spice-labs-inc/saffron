/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.wim;

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
 * Tests for WIM binary container detection.
 */
class WimContainerDetectionTest {

    private static final String VALID_WIM = "src/test/resources/wim/valid.wim";
    private static final String TWO_IMAGES_WIM = "src/test/resources/wim/two-images.wim";

    @Test
    void detectsValidWimFromPath() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(VALID_WIM));
        assertThat(format).contains(ContainerFormat.WIM);
    }

    @Test
    void detectsTwoImagesWimFromPath() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(TWO_IMAGES_WIM));
        assertThat(format).contains(ContainerFormat.WIM);
    }

    @Test
    void detectsValidWimFromByteBuffer() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(Path.of(VALID_WIM)));
        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).contains(ContainerFormat.WIM);
    }

    @Test
    void detectsValidWimFromVirtualDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(Path.of(VALID_WIM))) {
            Optional<ContainerFormat> format = ContainerDetector.detect(disk);
            assertThat(format).contains(ContainerFormat.WIM);
        }
    }

    @Test
    void rejectsEmptyFile() throws IOException {
        Path tmp = Files.createTempFile("empty-", ".wim");
        tmp.toFile().deleteOnExit();
        Optional<ContainerFormat> format = ContainerDetector.detect(tmp);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsTruncatedMagic() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/wim/truncated-magic.wim"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsWrongMagic() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/wim/wrong-magic.wim"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsTruncatedHeader() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/wim/truncated-header.wim"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsHeaderSizeMismatch() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/wim/header-size-mismatch.wim"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsSourceSmallerThanHeader() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/wim/source-smaller-than-header.wim"));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsManipulatedVersion() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(Path.of(VALID_WIM)));
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(12, 0);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsNegativeImageCount() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(Path.of(VALID_WIM)));
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(44, -1);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }
}
