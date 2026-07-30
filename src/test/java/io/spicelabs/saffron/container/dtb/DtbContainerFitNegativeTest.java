/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dtb;

import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative tests ensuring FIT images are not treated as plain DTB.
 */
class DtbContainerFitNegativeTest {

    private static final String FIT = "src/test/resources/fit/openwrt-23.05.3-mediatek-filogic-mediatek_mt7981-rfb-initramfs.itb";

    @Test
    void fitNotDtb() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(FIT));
        assertThat(format).contains(ContainerFormat.FIT_IMAGE);
        assertThat(format).isNotEqualTo(Optional.of(ContainerFormat.DTB));
    }

    @Test
    void dtbContainerRejectsFit() throws IOException {
        Optional<?> container = DtbContainer.open(Path.of(FIT));
        assertThat(container).isEmpty();
    }
}
