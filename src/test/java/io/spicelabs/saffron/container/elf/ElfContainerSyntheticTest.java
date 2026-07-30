/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.container.BinaryContainer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parameterized synthetic ELF tests that exercise all class/endian combinations
 * and a matrix of out-of-bounds conditions.
 */
class ElfContainerSyntheticTest {

    static Stream<Arguments> validElfCombinations() {
        return Stream.of(
                Arguments.of(ElfTestFixtures.ELFCLASS32, true),
                Arguments.of(ElfTestFixtures.ELFCLASS32, false),
                Arguments.of(ElfTestFixtures.ELFCLASS64, true),
                Arguments.of(ElfTestFixtures.ELFCLASS64, false)
        );
    }

    @ParameterizedTest
    @MethodSource("validElfCombinations")
    void validElfCombinations(int elfClass, boolean littleEndian) {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(elfClass, littleEndian);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).isPresent();
        assertThat(container.get().findEntry("/sections/.data")).isPresent();
        assertThat(container.get().findEntry("/segments/0")).isPresent();
    }

    static Stream<Arguments> outOfBoundsVariants() {
        return Stream.of(
                Arguments.of(createOverrides(o -> o.ePhoff = 0xffff), "program header offset beyond file"),
                Arguments.of(createOverrides(o -> o.eShoff = 0xffff), "section header offset beyond file"),
                Arguments.of(createOverrides(o -> o.shOffset1 = 0xffff), "section data offset beyond file"),
                Arguments.of(createOverrides(o -> o.pOffset = 0xffff), "segment data offset beyond file"),
                Arguments.of(createOverrides(o -> o.shName1 = 0xffff), "section name offset beyond string table")
        );
    }

    @ParameterizedTest
    @MethodSource("outOfBoundsVariants")
    void outOfBoundsVariants(ElfTestFixtures.ElfOverrides overrides, String description) {
        ByteBuffer elf = ElfTestFixtures.buildElf(ElfTestFixtures.ELFCLASS32, true,
                ElfTestFixtures.DEFAULT_CONTENT, ".data", overrides);

        Optional<BinaryContainer> container = ElfContainer.open(elf, elf.remaining());

        assertThat(container).as("Expected rejection for: %s", description).isEmpty();
    }

    private static ElfTestFixtures.ElfOverrides createOverrides(java.util.function.Consumer<ElfTestFixtures.ElfOverrides> mutator) {
        ElfTestFixtures.ElfOverrides overrides = new ElfTestFixtures.ElfOverrides();
        mutator.accept(overrides);
        return overrides;
    }
}
