/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.fit;

import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for FIT / U-Boot image detection.
 *
 * <p>Detection must recognize a FIT image (a DTB containing an {@code /images}
 * node) as {@link ContainerFormat#FIT_IMAGE}, while a plain DTB without an
 * {@code /images} node is classified as {@link ContainerFormat#DTB}.
 * Malformed or random input must be rejected without crashing.</p>
 */
class FitContainerDetectionTest {

    private static final String FIXTURE_DIR = "src/test/resources/fit";
    private static final String FIT_FIXTURE =
            FIXTURE_DIR + "/openwrt-23.05.3-mediatek-filogic-mediatek_mt7981-rfb-initramfs.itb";
    private static final String DTB_FIXTURE = FIXTURE_DIR + "/mediatek_mt7981-rfb.dtb";
    private static final String TOO_SMALL = "src/test/resources/invalid-too-small.bin";
    private static final String RANDOM = "src/test/resources/invalid-random.bin";

    /**
     * Verifies that the OpenWrt FIT fixture is detected as a FIT image.
     *
     * <p>The fixture is a DTB container with an {@code /images} node, so it must
     * be classified as {@code FIT_IMAGE} rather than a plain DTB.</p>
     */
    @Test
    void detectsFit() throws IOException {
        Path fixture = Path.of(FIT_FIXTURE);

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).contains(ContainerFormat.FIT_IMAGE);
    }

    /**
     * Verifies that a plain DTB extracted from the FIT fixture is not
     * misclassified as a FIT image.
     */
    @Test
    void rejectsPlainDtb() throws IOException {
        Path fixture = Path.of(DTB_FIXTURE);

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).contains(ContainerFormat.DTB);
    }

    /**
     * Verifies that a file shorter than a DTB header is rejected cleanly.
     */
    @Test
    void rejectsTruncatedHeader() throws IOException {
        Path fixture = Path.of(TOO_SMALL);

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).isEmpty();
    }

    /**
     * Verifies that random bytes are not classified as a FIT image or DTB.
     */
    @Test
    void rejectsRandomData() throws IOException {
        Path fixture = Path.of(RANDOM);

        Optional<ContainerFormat> format = ContainerDetector.detect(fixture);

        assertThat(format).isEmpty();
    }
}
