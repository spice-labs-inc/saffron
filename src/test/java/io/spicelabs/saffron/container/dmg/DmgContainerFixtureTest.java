/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dmg;

import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.fs.FileSystem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture-based tests for DMG binary container support.
 */
class DmgContainerFixtureTest {

    @ParameterizedTest(name = "detects {0}")
    @ValueSource(strings = {"valid.dmg", "empty.dmg"})
    void detectsPositiveFixtures(String name) throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/dmg", name));
        assertThat(format).contains(ContainerFormat.DMG);
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
        "truncated-footer.dmg",
        "missing-koly.dmg",
        "footer-not-at-end.dmg",
        "invalid-header-size.dmg",
        "data-fork-beyond-source.dmg"
    })
    void rejectsNegativeFixtures(String name) throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/dmg", name));
        assertThat(format).isEmpty();
    }

    @ParameterizedTest(name = "mounts {0}")
    @ValueSource(strings = {"valid.dmg", "empty.dmg"})
    void mountsPositiveFixtures(String name) throws IOException {
        Optional<FileSystem> filesystem = BinaryContainerMount.mount(Path.of("src/test/resources/dmg", name));
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
            assertThat(fs.root().find("raw")).isPresent();
        }
    }
}
