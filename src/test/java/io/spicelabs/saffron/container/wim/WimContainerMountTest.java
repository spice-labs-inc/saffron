/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.wim;

import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests that WIM files are mounted as binary containers.
 */
class WimContainerMountTest {

    private static final String VALID_WIM = "src/test/resources/wim/valid.wim";

    @Test
    void mountReturnsBinaryContainer() throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of(VALID_WIM));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
            assertThat(fs.metadata().get("format")).isEqualTo("wim");
        }
    }

    @Test
    void rawEntryExistsAndHasCorrectSize() throws IOException {
        Path fixture = Path.of(VALID_WIM);
        long fileSize = Files.size(fixture);

        Optional<FileSystem> filesystem = BinaryContainerMount.mount(fixture);
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            Optional<FileSystemEntry> raw = fs.root().find("raw");
            assertThat(raw).isPresent();
            assertThat(raw.get().size()).isEqualTo(fileSize);
        }
    }

    @Test
    void rawEntryIsReadable() throws IOException {
        Path fixture = Path.of(VALID_WIM);
        byte[] expected = Files.readAllBytes(fixture);

        Optional<FileSystem> filesystem = BinaryContainerMount.mount(fixture);
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            Optional<FileSystemEntry> raw = fs.root().find("raw");
            assertThat(raw).isPresent();
            assertThat(raw.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
            FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) raw.get();
            try (InputStream is = file.openStream()) {
                byte[] actual = is.readAllBytes();
                assertThat(actual).isEqualTo(expected);
            }
        }
    }

    @Test
    void rawEntryStreamsAreIndependent() throws IOException {
        Path fixture = Path.of(VALID_WIM);
        byte[] expected = Files.readAllBytes(fixture);

        Optional<FileSystem> filesystem = BinaryContainerMount.mount(fixture);
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
                assertThat(firstBytes).isEqualTo(java.util.Arrays.copyOfRange(expected, 0, 10));
                assertThat(secondBytes).isEqualTo(expected);

                byte[] rest = first.readAllBytes();
                assertThat(rest).isEqualTo(java.util.Arrays.copyOfRange(expected, 10, expected.length));
            }
        }
    }

    @Test
    void metadataContainsExpectedKeys() throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of(VALID_WIM));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            assertThat(fs.metadata()).containsKey("wim.version");
            assertThat(fs.metadata()).containsKey("wim.image_count");
            assertThat(fs.metadata().get("wim.image_count")).isEqualTo("1");
        }
    }
}
