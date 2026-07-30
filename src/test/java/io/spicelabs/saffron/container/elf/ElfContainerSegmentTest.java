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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ELF program segment exposure.
 */
class ElfContainerSegmentTest {

    private static final String LIB_ELF = "src/test/resources/elf/libmbedx509.so";

    private BinaryContainer open() throws IOException {
        return ElfContainer.open(Path.of(LIB_ELF))
                .orElseThrow(() -> new NoSuchElementException("Failed to open libmbedx509.so"));
    }

    @Test
    void exposesLoadSegments() throws IOException {
        BinaryContainer container = open();
        List<ContainerEntry> entries = container.entries();

        assertThat(entries)
                .anyMatch(e -> e.name().startsWith("/segments/") && "PT_LOAD".equals(e.metadata().get("type")));
    }

    @Test
    void segmentCountMatchesProgramHeaderCount() throws IOException {
        BinaryContainer container = open();
        long segmentCount = container.entries().stream()
                .map(ContainerEntry::name)
                .filter(name -> name.startsWith("/segments/"))
                .count();
        assertThat(segmentCount).isEqualTo(5);
    }

    @Test
    void segmentSizeMatchesHeader() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/segments/0").orElseThrow();
        // From readelf -l: first LOAD segment has FileSiz 0x96b8 = 38600
        assertThat(entry.size()).isEqualTo(0x96b8L);
    }

    @Test
    void segmentContentsContainCertificate() throws IOException {
        BinaryContainer container = open();
        boolean found = false;
        for (ContainerEntry entry : container.entries()) {
            if (!entry.name().startsWith("/segments/")) {
                continue;
            }
            try (InputStream is = entry.openStream()) {
                String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                if (text.contains("BEGIN CERTIFICATE")) {
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void segmentMetadataContainsTypeAndFlags() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/segments/0").orElseThrow();
        assertThat(entry.metadata())
                .containsEntry("type", "PT_LOAD")
                .containsKey("flags")
                .containsKey("flags_human");
    }

    @Test
    void handlesZeroFileSizeSegment() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.pFilesz = 0;
        overrides.pMemsz = 0;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isPresent();
        ContainerEntry entry = container.get().findEntry("/segments/0").orElseThrow();
        assertThat(entry.size()).isZero();
    }
}
