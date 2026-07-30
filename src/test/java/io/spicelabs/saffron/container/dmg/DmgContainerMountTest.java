/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dmg;

import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests that DMG files are mounted as binary containers.
 */
class DmgContainerMountTest {

    private static final String VALID_DMG = "src/test/resources/dmg/valid.dmg";

    @Test
    void mountReturnsBinaryContainer() throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of(VALID_DMG));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
            assertThat(fs.metadata().get("format")).isEqualTo("dmg");
        }
    }

    @Test
    void rawEntryExistsAndHasCorrectSize() throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of(VALID_DMG));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            Optional<FileSystemEntry> raw = fs.root().find("raw");
            assertThat(raw).isPresent();
            assertThat(raw.get().size()).isEqualTo(4096);
        }
    }

    @Test
    void rawEntryContainsDataFork() throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of(VALID_DMG));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            Optional<FileSystemEntry> raw = fs.root().find("raw");
            assertThat(raw).isPresent();
            assertThat(raw.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
            FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) raw.get();
            try (InputStream is = file.openStream()) {
                byte[] bytes = is.readAllBytes();
                assertThat(bytes).hasSize(4096);
                assertThat(bytes).containsOnly(0);
            }
        }
    }

    @Test
    void rawEntryStreamsAreIndependent() throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of(VALID_DMG));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            Optional<FileSystemEntry> raw = fs.root().find("raw");
            assertThat(raw).isPresent();
            assertThat(raw.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
            FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) raw.get();

            try (InputStream first = file.openStream();
                 InputStream second = file.openStream()) {
                byte[] firstBytes = first.readNBytes(10);
                byte[] secondBytes = second.readAllBytes();

                assertThat(firstBytes).hasSize(10);
                assertThat(firstBytes).containsOnly(0);
                assertThat(secondBytes).hasSize(4096);
                assertThat(secondBytes).containsOnly(0);

                byte[] rest = first.readAllBytes();
                assertThat(rest).hasSize(4086);
                assertThat(rest).containsOnly(0);
            }
        }
    }

    @Test
    void metadataContainsExpectedKeys() throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of(VALID_DMG));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            assertThat(fs.metadata()).containsKey("dmg.version");
            assertThat(fs.metadata()).containsKey("dmg.data_fork_length");
            assertThat(fs.metadata().get("dmg.data_fork_length")).isEqualTo("4096");
        }
    }
}
