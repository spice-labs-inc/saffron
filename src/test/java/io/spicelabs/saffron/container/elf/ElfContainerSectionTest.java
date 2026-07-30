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
 * Tests for ELF section exposure.
 */
class ElfContainerSectionTest {

    private static final String START_ELF = "src/test/resources/elf/start.elf";

    private BinaryContainer open() throws IOException {
        return ElfContainer.open(Path.of(START_ELF))
                .orElseThrow(() -> new NoSuchElementException("Failed to open start.elf"));
    }

    @Test
    void exposesTextSection() throws IOException {
        BinaryContainer container = open();
        Optional<ContainerEntry> entry = container.findEntry("/sections/.text");
        assertThat(entry).isPresent();
        assertThat(entry.get().size()).isPositive();
    }

    @Test
    void exposesDataSection() throws IOException {
        BinaryContainer container = open();
        Optional<ContainerEntry> entry = container.findEntry("/sections/.data");
        assertThat(entry).isPresent();
        assertThat(entry.get().size()).isPositive();
    }

    @Test
    void exposesRdataSection() throws IOException {
        BinaryContainer container = open();
        Optional<ContainerEntry> entry = container.findEntry("/sections/.rdata");
        assertThat(entry).isPresent();
        assertThat(entry.get().size()).isPositive();
    }

    @Test
    void sectionSizeMatchesHeader() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/sections/.text").orElseThrow();
        // Expected size from readelf -S: .text size = 0x240098 = 2355352
        assertThat(entry.size()).isEqualTo(0x240098L);
    }

    @Test
    void sectionMetadataContainsTypeAndFlags() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/sections/.text").orElseThrow();
        assertThat(entry.metadata())
                .containsEntry("type", "PROGBITS")
                .containsEntry("flags", "0x6")
                .containsEntry("flags_human", "AX");
    }

    @Test
    void handlesDuplicateSectionNames() throws IOException {
        BinaryContainer container = open();
        List<String> names = container.entries().stream().map(ContainerEntry::name).toList();

        assertThat(names).contains(
                "/sections/.rsdata",
                "/sections/.rsdata_1",
                "/sections/.rsdata_2"
        );
    }

    @Test
    void handlesEmptySectionName() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.shName1 = 0; // points to the first null byte in the string table
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        // The empty-named section should be skipped or exposed as a safe fallback.
        assertThat(container).isPresent();
        assertThat(container.get().findEntry("/sections/")).isEmpty();
    }

    @Test
    void handlesNodataSection() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.shType1 = ElfTestFixtures.SHT_NOBITS;
        overrides.shSize1 = 1024;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isPresent();
        ContainerEntry entry = container.get().findEntry("/sections/.data").orElseThrow();
        assertThat(entry.size()).isZero();
    }

    @Test
    void rejectsPathTraversalSectionName() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, "../../../etc/passwd", overrides);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isPresent();
        assertThat(container.get().findEntry("/sections/../../../etc/passwd")).isEmpty();
    }

    @Test
    void sectionNameAtStringTableBoundary() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        // String table: \0.data\0.shstrtab\0. The last valid name starts at the last
        // null terminator, which is an empty string. Use shName1 = 0 to exercise the boundary.
        overrides.shName1 = 0;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isPresent();
    }

    @Test
    void sectionNameMissingNullTerminator() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        // Point the name beyond the last null terminator in the string table.
        overrides.shName1 = 100;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }

    private static String readString(ContainerEntry entry) throws IOException {
        try (InputStream is = entry.openStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
