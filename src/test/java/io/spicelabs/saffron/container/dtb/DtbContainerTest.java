/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dtb;

import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DTB binary container entry exposure.
 */
class DtbContainerTest {

    private static final String DTB_3B = "src/test/resources/dtb/bcm2710-rpi-3-b.dtb";
    private static final String DTB_3B_PLUS = "src/test/resources/dtb/bcm2710-rpi-3-b-plus.dtb";

    private BinaryContainer open(String path) throws IOException {
        return DtbContainer.open(Path.of(path))
                .orElseThrow(() -> new NoSuchElementException("Failed to open " + path));
    }

    @Test
    void exposesRawDtb() throws IOException {
        BinaryContainer container = open(DTB_3B);
        Optional<ContainerEntry> entry = container.findEntry("/dtb");
        assertThat(entry).isPresent();
        assertThat(entry.get().size()).isEqualTo(Files.size(Path.of(DTB_3B)));
    }

    @Test
    void rawDtbContentMatchesSource() throws IOException {
        BinaryContainer container = open(DTB_3B);
        ContainerEntry entry = container.findEntry("/dtb").orElseThrow();
        byte[] expected = Files.readAllBytes(Path.of(DTB_3B));
        try (InputStream is = entry.openStream()) {
            assertThat(is.readAllBytes()).isEqualTo(expected);
        }
    }

    @Test
    void exposesModelProperty() throws IOException {
        BinaryContainer container = open(DTB_3B);
        Optional<ContainerEntry> entry = container.findEntry("/model");
        assertThat(entry).isPresent();
        try (InputStream is = entry.get().openStream()) {
            assertThat(new String(is.readAllBytes())).contains("Raspberry Pi 3 Model B");
        }
    }

    @Test
    void exposesCompatibleProperty() throws IOException {
        BinaryContainer container = open(DTB_3B);
        Optional<ContainerEntry> entry = container.findEntry("/compatible");
        assertThat(entry).isPresent();
        assertThat(entry.get().size()).isPositive();
    }

    @Test
    void exposesChildProperty() throws IOException {
        BinaryContainer container = open(DTB_3B);
        List<String> names = container.entries().stream().map(ContainerEntry::name).toList();
        assertThat(names).anyMatch(n -> n.startsWith("/chosen/") || n.startsWith("/soc/"));
    }

    @Test
    void findEntryNonExistent() throws IOException {
        BinaryContainer container = open(DTB_3B);
        assertThat(container.findEntry("/no-such-property")).isEmpty();
    }

    @Test
    void containerMetadataContainsFormatAndSize() throws IOException {
        BinaryContainer container = open(DTB_3B);
        assertThat(container.metadata())
                .containsEntry("format", "dtb")
                .containsEntry("source_size", Long.toString(Files.size(Path.of(DTB_3B))));
        assertThat(container.metadata()).containsKey("entry_count");
    }

    @Test
    void openStreamReturnsIndependentStreams() throws IOException {
        BinaryContainer container = open(DTB_3B);
        ContainerEntry entry = container.findEntry("/dtb").orElseThrow();
        try (InputStream a = entry.openStream(); InputStream b = entry.openStream()) {
            a.skip(10);
            assertThat(a.read()).isNotEqualTo(b.read());
        }
    }

    @Test
    void entriesIncludeDtbAndProperties() throws IOException {
        BinaryContainer container = open(DTB_3B_PLUS);
        List<String> names = container.entries().stream().map(ContainerEntry::name).toList();
        assertThat(names).contains("/dtb");
        assertThat(names).contains("/model");
    }
}
