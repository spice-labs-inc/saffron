/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.yaffs2;

import io.spicelabs.saffron.fs.FileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hostile hardlink-cycle test (phase 4, T4.1/R4.4).
 *
 * <h2>LLM section</h2>
 * <p>Two hardlink objects pointing at each other must fail checked on
 * resolution ("hardlink cycle"), never StackOverflowError (pre-fix
 * behavior).</p>
 */
class Yaffs2HardlinkCycleTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void mutuallyRecursiveHardlinksFailChecked() throws IOException {
        Yaffs2ImageWriter w = new Yaffs2ImageWriter();
        w.header(2, Yaffs2ImageWriter.TYPE_HARDLINK, 1, "a", Yaffs2ImageWriter.MODE_REG, 0, null, 3);
        w.header(3, Yaffs2ImageWriter.TYPE_HARDLINK, 1, "b", Yaffs2ImageWriter.MODE_REG, 0, null, 2);

        try (FileSystem fs = Yaffs2FileSystemImpl.mount(
                new Yaffs2SecurityTest.Region(w.bytes()))) {
            assertThatThrownBy(() -> fs.resolve("/a"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("hardlink cycle");
        }
    }
}
