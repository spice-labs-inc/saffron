/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.wim;

import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.fs.FileSystem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture-based tests for WIM binary container support.
 */
class WimContainerFixtureTest {

    @ParameterizedTest(name = "detects {0}")
    @ValueSource(strings = {"valid.wim", "two-images.wim"})
    void detectsPositiveFixtures(String name) throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/wim", name));
        assertThat(format).contains(ContainerFormat.WIM);
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
        "truncated-magic.wim",
        "wrong-magic.wim",
        "truncated-header.wim",
        "header-size-mismatch.wim",
        "source-smaller-than-header.wim"
    })
    void rejectsNegativeFixtures(String name) throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of("src/test/resources/wim", name));
        assertThat(format).isEmpty();
    }

    @ParameterizedTest(name = "mounts {0}")
    @ValueSource(strings = {"valid.wim", "two-images.wim"})
    void mountsPositiveFixtures(String name) throws IOException {
        Path fixture = Path.of("src/test/resources/wim", name);
        long fileSize = Files.size(fixture);

        Optional<FileSystem> filesystem = BinaryContainerMount.mount(fixture);
        assertThat(filesystem).isPresent();
        try (FileSystem fs = filesystem.get()) {
            assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
            assertThat(fs.root().find("raw")).isPresent();
            assertThat(fs.root().find("raw").get().size()).isEqualTo(fileSize);
        }
    }
}
