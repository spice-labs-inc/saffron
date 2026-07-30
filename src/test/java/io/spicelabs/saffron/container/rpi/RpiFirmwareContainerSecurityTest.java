/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.rpi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.spicelabs.saffron.container.BinaryContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-oriented tests for the Raspberry Pi firmware parser.
 */
class RpiFirmwareContainerSecurityTest {

    @Test
    void rejectsIntegerOverflow() {
        // A small buffer with a huge declared size should not cause overflow or
        // drive allocation.
        byte[] data = new byte[513];
        data[512] = 0x42;
        ByteBuffer buffer = ByteBuffer.wrap(data);

        Optional<BinaryContainer> container = RpiFirmwareContainer.open(buffer, Integer.MAX_VALUE);

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsFileSmallerThanPadding() throws IOException {
        Path tmp = Files.createTempFile("tiny-bootcode", ".bin");
        tmp.toFile().deleteOnExit();
        Files.write(tmp, new byte[511]); // 511 bytes, all zero

        Optional<BinaryContainer> container = RpiFirmwareContainer.open(tmp);

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsFileWithOnlyPadding() throws IOException {
        Path tmp = Files.createTempFile("empty-bootcode", ".bin");
        tmp.toFile().deleteOnExit();
        Files.write(tmp, new byte[512]); // 512 bytes, all zero

        Optional<BinaryContainer> container = RpiFirmwareContainer.open(tmp);

        assertThat(container).isEmpty();
    }
}
