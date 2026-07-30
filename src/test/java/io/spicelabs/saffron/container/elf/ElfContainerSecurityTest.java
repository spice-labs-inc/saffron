/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security and boundary tests for the ELF parser.
 */
class ElfContainerSecurityTest {

    @Test
    void rejectsProgramHeaderTableBeyondFile() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.ePhoff = 1000;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsSectionHeaderTableBeyondFile() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eShoff = 1000;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsSectionDataBeyondFile() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.shSize1 = 0x7fffffff;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<io.spicelabs.saffron.container.BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsSegmentDataBeyondFile() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.pFilesz = 0x7fffffff;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<io.spicelabs.saffron.container.BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsOverflowingProgramHeaderCount() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.ePhnum = 0xffff;
        overrides.ePhentsize = 0xffff;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsOverflowingSectionHeaderCount() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eShnum = 0xffff;
        overrides.eShentsize = 0xffff;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsInvalidSectionStringIndex() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eShstrndx = 10;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<io.spicelabs.saffron.container.BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsShstrndxUndef() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eShstrndx = ElfTestFixtures.SHN_UNDEF;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<io.spicelabs.saffron.container.BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsShstrndxXindex() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eShstrndx = ElfTestFixtures.SHN_XINDEX;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<io.spicelabs.saffron.container.BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsNonZeroProgramHeaderCountWithZeroOffset() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.ePhoff = 0;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsNonZeroSectionHeaderCountWithZeroOffset() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eShoff = 0;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsHeaderSizeMismatch() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eEhsize = 64; // 32-bit ELF must have e_ehsize=52
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsProgramHeaderEntrySizeMismatch() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.ePhentsize = 64; // 32-bit phentsize must be 32
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsSectionHeaderEntrySizeMismatch() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.eShentsize = 64; // 32-bit shentsize must be 40
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsInvalidSectionStringTableType() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.shTypeStrtab = ElfTestFixtures.SHT_PROGBITS; // not a string table
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<io.spicelabs.saffron.container.BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }

    @Test
    void rejectsOverflowingOffsetPlusSize() {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        overrides.shOffset1 = Long.MAX_VALUE - 10;
        overrides.shSize1 = 100;
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<io.spicelabs.saffron.container.BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isEmpty();
    }
}
