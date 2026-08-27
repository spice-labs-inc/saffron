/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.exfat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hostile exFAT cluster-chain tests (phase 4, T4.1/R4.3).
 */
class ExFatClusterChainCycleTest {

    private static final int EOC = 0xFFFFFFF8;
    private static final int BAD = 0xFFFFFFF7;

    @Test
    void cyclicChainFailsChecked() {
        int[] fat = {0, 0, 3, 4, 3};
        assertThatThrownBy(() -> ExFatFileSystemImpl.walkClusterChain(fat, 2, EOC, BAD, "exFAT"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void unsignedChainTerminationSemantics() {
        // High-bit values: signed comparison would misclassify every one
        // of these (the pre-fix bug stopped chains after one cluster).
        assertThat(ExFatFileSystemImpl.chainTerminates(0xFFFFFFF8, 0xFFFFFFF8, 0xFFFFFFF7))
                .as("EOC marker").isTrue();
        assertThat(ExFatFileSystemImpl.chainTerminates(0xFFFFFFF7, 0xFFFFFFF8, 0xFFFFFFF7))
                .as("BAD marker").isTrue();
        assertThat(ExFatFileSystemImpl.chainTerminates(0xFFFFFFFF, 0xFFFFFFF8, 0xFFFFFFF7))
                .as("beyond EOC").isTrue();
        assertThat(ExFatFileSystemImpl.chainTerminates(0xFFFFFFF0, 0xFFFFFFF8, 0xFFFFFFF7))
                .as("valid high cluster, unsigned").isFalse();
        assertThat(ExFatFileSystemImpl.chainTerminates(0x80000000, 0xFFFFFFF8, 0xFFFFFFF7))
                .as("valid high cluster, sign bit set").isFalse();
        assertThat(ExFatFileSystemImpl.chainTerminates(3, 0xFFFFFFF8, 0xFFFFFFF7))
                .as("ordinary cluster").isFalse();
        assertThat(ExFatFileSystemImpl.chainTerminates(1, 0xFFFFFFF8, 0xFFFFFFF7))
                .as("below first cluster").isTrue();
    }

    @Test
    void validChainWalksToEoc() throws IOException {
        int[] fat = {0, 0, 3, 4, EOC};
        List<Integer> chain = ExFatFileSystemImpl.walkClusterChain(fat, 2, EOC, BAD, "exFAT");
        assertThat(chain).containsExactly(2, 3, 4);
    }
}
