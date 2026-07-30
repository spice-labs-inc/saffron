/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ELF entry stream behavior.
 */
class ElfContainerStreamTest {

    @Test
    void openStreamReturnsIndependentStreams() throws IOException {
        BinaryContainer container = ElfContainer.open(Path.of("src/test/resources/elf/start.elf"))
                .orElseThrow();
        ContainerEntry entry = container.findEntry("/sections/.text").orElseThrow();

        byte[] a;
        byte[] b;
        try (InputStream left = entry.openStream(); InputStream right = entry.openStream()) {
            left.skip(10);
            a = left.readAllBytes();
            b = right.readAllBytes();
        }

        assertThat(a.length).isEqualTo(entry.size() - 10);
        assertThat(b.length).isEqualTo(entry.size());
    }

    @Test
    void readAllBytesMatchesSize() throws IOException {
        BinaryContainer container = ElfContainer.open(Path.of("src/test/resources/elf/libmbedx509.so"))
                .orElseThrow();
        ContainerEntry entry = container.findEntry("/segments/0").orElseThrow();

        try (InputStream is = entry.openStream()) {
            byte[] bytes = is.readAllBytes();
            assertThat(bytes.length).isEqualTo(entry.size());
        }
    }
}
