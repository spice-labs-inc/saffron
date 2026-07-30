/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.wim;

import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-focused boundary tests for WIM detection and opening.
 */
class WimContainerSecurityTest {

    private static final String VALID_WIM = "src/test/resources/wim/valid.wim";

    @Test
    void rejectsZeroLengthBuffer() {
        ByteBuffer empty = ByteBuffer.wrap(new byte[0]);
        Optional<ContainerFormat> format = ContainerDetector.detect(empty);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsHeaderSizeExceedingSource() throws Exception {
        byte[] valid = Files.readAllBytes(Path.of(VALID_WIM));
        ByteBuffer buffer = ByteBuffer.wrap(valid);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(8, valid.length + 1000);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsMaximumHeaderSize() throws Exception {
        byte[] valid = Files.readAllBytes(Path.of(VALID_WIM));
        ByteBuffer buffer = ByteBuffer.wrap(valid);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(8, Integer.MAX_VALUE);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).isEmpty();
    }

    @Test
    void acceptsOnlyKnownMagicPrefix() throws Exception {
        byte[] valid = Files.readAllBytes(Path.of(VALID_WIM));
        // Flip the last byte of the magic.
        valid[7] = (byte) 0x01;
        ByteBuffer buffer = ByteBuffer.wrap(valid);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).isEmpty();
    }
}
