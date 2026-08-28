/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plausibility-cap boundary tests for the btrfs chunk scan (phase 6, T6.3).
 *
 * <h2>LLM section</h2>
 * <p>Both bounds (64k fixed cap, region/32 proportional cap) reject at
 * their edges and accept below them.</p>
 */
class BtrfsChunkCapTest {

    @Test
    void fixedCapBoundaries() {
        assertThatCode(() -> BtrfsChunkTree.checkChunkCount(65536, Long.MAX_VALUE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> BtrfsChunkTree.checkChunkCount(65537, Long.MAX_VALUE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("plausibility cap");
    }

    @Test
    void regionProportionalBoundBoundaries() throws IOException {
        long region = 2L * 1024 * 1024; // 64k chunks would be 32 bytes each
        BtrfsChunkTree.checkChunkCount(65536, region);
        assertThatThrownBy(() -> BtrfsChunkTree.checkChunkCount(65537, region))
                .isInstanceOf(IOException.class);
        // A small region rejects a much lower count via the proportional bound.
        assertThatThrownBy(() -> BtrfsChunkTree.checkChunkCount(1000, 30000L))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size plausibility");
    }
}
