/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.container.BinaryContainer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ELF container-level metadata.
 */
class ElfContainerMetadataTest {

    @Test
    void containerMetadataContainsElfTypeAndMachine() throws IOException {
        BinaryContainer container = ElfContainer.open(Path.of("src/test/resources/elf/libmbedx509.so"))
                .orElseThrow();

        Map<String, String> metadata = container.metadata();

        assertThat(metadata)
                .containsKey("type")
                .containsKey("machine")
                .containsKey("entry")
                .containsKey("entry_count")
                .containsKey("source_size");
    }

    @Test
    void startElfMetadataMatchesHeader() throws IOException {
        BinaryContainer container = ElfContainer.open(Path.of("src/test/resources/elf/start.elf"))
                .orElseThrow();

        Map<String, String> metadata = container.metadata();

        // start.elf is ET_EXEC with entry point 0xcec00200
        assertThat(metadata.get("type")).isEqualTo("ET_EXEC");
        assertThat(metadata.get("entry")).isEqualTo("0xcec00200");
        assertThat(metadata.get("machine")).isNotEmpty();
    }
}
