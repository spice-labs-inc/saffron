/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.rpi;

import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Raspberry Pi firmware container content exposure.
 */
class RpiFirmwareContainerTest {

    private static final String BOOTCODE = "src/test/resources/rpi-firmware/bootcode.bin";
    private static final String FIXUP = "src/test/resources/rpi-firmware/fixup.dat";

    @Test
    void exposesBootcodeSections() throws IOException {
        BinaryContainer container = RpiFirmwareContainer.open(Path.of(BOOTCODE))
                .orElseThrow(() -> new AssertionError("Failed to open bootcode.bin"));

        assertThat(container.findEntry("/raw")).isPresent();
        assertThat(container.findEntry("/raw").get().size()).isEqualTo(52_116L);
        assertThat(container.findEntry("/bootcode")).isPresent();
        assertThat(container.findEntry("/bootcode").get().size()).isEqualTo(52_116L - 512);
    }

    @Test
    void exposesFixupSections() throws IOException {
        BinaryContainer container = RpiFirmwareContainer.open(Path.of(FIXUP))
                .orElseThrow(() -> new AssertionError("Failed to open fixup.dat"));

        assertThat(container.findEntry("/raw")).isPresent();
        assertThat(container.findEntry("/raw").get().size()).isEqualTo(6_695L);
        assertThat(container.findEntry("/fixup")).isPresent();
        assertThat(container.findEntry("/fixup").get().size()).isEqualTo(6_695L);
    }

    @Test
    void rawEntryEqualsInput() throws IOException {
        byte[] original = Files.readAllBytes(Path.of(BOOTCODE));
        BinaryContainer container = RpiFirmwareContainer.open(Path.of(BOOTCODE))
                .orElseThrow(() -> new AssertionError("Failed to open bootcode.bin"));

        ContainerEntry raw = container.findEntry("/raw").orElseThrow();
        try (InputStream is = raw.openStream()) {
            byte[] read = is.readAllBytes();
            assertThat(read).isEqualTo(original);
        }
    }

    @Test
    void bootcodeEntryStartsAt0x200() throws IOException {
        byte[] original = Files.readAllBytes(Path.of(BOOTCODE));
        BinaryContainer container = RpiFirmwareContainer.open(Path.of(BOOTCODE))
                .orElseThrow(() -> new AssertionError("Failed to open bootcode.bin"));

        ContainerEntry bootcode = container.findEntry("/bootcode").orElseThrow();
        try (InputStream is = bootcode.openStream()) {
            byte[] read = is.readAllBytes();
            assertThat(read.length).isEqualTo(original.length - 512);
            for (int i = 0; i < read.length; i++) {
                assertThat(read[i]).isEqualTo(original[i + 512]);
            }
        }
    }

    @Test
    void independentStreams() throws IOException {
        BinaryContainer container = RpiFirmwareContainer.open(Path.of(BOOTCODE))
                .orElseThrow(() -> new AssertionError("Failed to open bootcode.bin"));

        ContainerEntry raw = container.findEntry("/raw").orElseThrow();
        try (InputStream first = raw.openStream();
             InputStream second = raw.openStream()) {
            assertThat(first.read()).isEqualTo(0); // bootcode starts with zero
            assertThat(second.skip(512)).isEqualTo(512);
            assertThat(second.read()).isNotEqualTo(-1);
            // First stream was not affected by second stream skip.
            assertThat(first.read()).isEqualTo(0);
        }
    }

    @Test
    void noNullEntries() throws IOException {
        BinaryContainer container = RpiFirmwareContainer.open(Path.of(BOOTCODE))
                .orElseThrow(() -> new AssertionError("Failed to open bootcode.bin"));

        List<ContainerEntry> entries = container.entries();
        assertThat(entries).doesNotContainNull();
        for (ContainerEntry entry : entries) {
            assertThat(entry.name()).isNotNull();
            assertThat(entry.size()).isGreaterThanOrEqualTo(0);
            assertThat(entry.metadata()).isNotNull();
            assertThat(entry.openStream()).isNotNull();
        }
    }

    @Test
    void largeFileDoesNotLoadWholeFile() throws IOException {
        Path dir = Files.createTempDirectory("rpi-test");
        Path tmp = dir.resolve("bootcode.bin");
        long size = 1024L * 1024L * 1024L; // 1 GB
        // Create a sparse file with the correct bootcode structure: first 512 bytes zero,
        // byte at 0x200 non-zero. Use seek/write to keep creation fast.
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tmp.toFile(), "rw")) {
            raf.setLength(size);
            raf.seek(0x200);
            raf.write(0x42);
        }
        tmp.toFile().deleteOnExit();

        BinaryContainer container = RpiFirmwareContainer.open(tmp)
                .orElseThrow(() -> new AssertionError("Failed to open large bootcode.bin"));

        ContainerEntry raw = container.findEntry("/raw").orElseThrow();
        assertThat(raw.size()).isEqualTo(size);
        try (InputStream is = raw.openStream()) {
            byte[] probe = is.readNBytes(1);
            assertThat(probe[0]).isEqualTo((byte) 0);
            is.skip(0x1FF);
            assertThat(is.read()).isEqualTo(0x42);
        }
    }
}
