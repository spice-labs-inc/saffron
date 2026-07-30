/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ELF binary container detection.
 */
class ElfContainerDetectionTest {

    private static final String LIB_ELF = "src/test/resources/elf/libmbedx509.so";
    private static final String START_ELF = "src/test/resources/elf/start.elf";
    private static final String RANDOM = "src/test/resources/invalid-random.bin";

    @Test
    void detectsLibElf() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(LIB_ELF));
        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void detectsStartElf() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(START_ELF));
        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void detectsTinyElf() {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS32, true);
        assertThat(elf.remaining()).isLessThan(512);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void detectsLibElfFromVirtualDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(Path.of(LIB_ELF))) {
            Optional<ContainerFormat> format = ContainerDetector.detect(disk);
            assertThat(format).contains(ContainerFormat.ELF);
        }
    }

    @Test
    void detectsStartElfFromVirtualDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(Path.of(START_ELF))) {
            Optional<ContainerFormat> format = ContainerDetector.detect(disk);
            assertThat(format).contains(ContainerFormat.ELF);
        }
    }

    @Test
    void rejectsRandomData() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(RANDOM));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsTruncatedHeader() throws IOException {
        Path tmp = Files.createTempFile("truncated-elf-", ".bin");
        tmp.toFile().deleteOnExit();
        Files.write(tmp, new byte[]{0x7f, 0x45, 0x4c});

        Optional<ContainerFormat> format = ContainerDetector.detect(tmp);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsBadMagic() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0x7f, 0x45, 0x4c, 0x45, 0x01, 0x01, 0x01, 0x00});

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsInvalidElfClass() {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS32, true);
        elf.put(4, (byte) 3);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsInvalidElfData() {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS32, true);
        elf.put(5, (byte) 3);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).isEmpty();
    }

    @Test
    void detects32BitLittleEndianElf() {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS32, true);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void detects32BitBigEndianElf() {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS32, false);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void detects64BitLittleEndianElf() {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS64, true);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void detects64BitBigEndianElf() {
        ByteBuffer elf = ElfTestFixtures.buildValidElf(ElfTestFixtures.ELFCLASS64, false);

        Optional<ContainerFormat> format = ContainerDetector.detect(elf);

        assertThat(format).contains(ContainerFormat.ELF);
    }
}
