/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dmg;

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
 * Security-focused boundary tests for DMG detection and opening.
 */
class DmgContainerSecurityTest {

    private static final String VALID_DMG = "src/test/resources/dmg/valid.dmg";

    @Test
    void rejectsZeroLengthBuffer() {
        ByteBuffer empty = ByteBuffer.wrap(new byte[0]);
        Optional<ContainerFormat> format = ContainerDetector.detect(empty);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsDataForkLengthOverflow() throws Exception {
        byte[] valid = Files.readAllBytes(Path.of(VALID_DMG));
        ByteBuffer buffer = ByteBuffer.wrap(valid);
        buffer.order(ByteOrder.BIG_ENDIAN);
        int footerStart = buffer.capacity() - 512;
        buffer.putLong(footerStart + 24, Long.MAX_VALUE);
        buffer.putLong(footerStart + 32, 1);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsXmlBeyondSource() throws Exception {
        byte[] valid = Files.readAllBytes(Path.of(VALID_DMG));
        ByteBuffer buffer = ByteBuffer.wrap(valid);
        buffer.order(ByteOrder.BIG_ENDIAN);
        int footerStart = buffer.capacity() - 512;
        buffer.putLong(footerStart + 216, 0);
        buffer.putLong(footerStart + 224, Long.MAX_VALUE);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsResourceForkBeyondSource() throws Exception {
        byte[] valid = Files.readAllBytes(Path.of(VALID_DMG));
        ByteBuffer buffer = ByteBuffer.wrap(valid);
        buffer.order(ByteOrder.BIG_ENDIAN);
        int footerStart = buffer.capacity() - 512;
        buffer.putLong(footerStart + 40, 0);
        buffer.putLong(footerStart + 48, Long.MAX_VALUE);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsDataForkOverlappingFooter() throws Exception {
        byte[] valid = Files.readAllBytes(Path.of(VALID_DMG));
        ByteBuffer buffer = ByteBuffer.wrap(valid);
        buffer.order(ByteOrder.BIG_ENDIAN);
        int footerStart = buffer.capacity() - 512;
        buffer.putLong(footerStart + 24, 0);
        buffer.putLong(footerStart + 32, valid.length - 100);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        assertThat(format).isEmpty();
    }
}
