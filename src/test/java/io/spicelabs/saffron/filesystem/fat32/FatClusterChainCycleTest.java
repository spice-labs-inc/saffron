/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.fat32;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hostile FAT cluster-chain tests (phase 4, T4.1/R4.3).
 *
 * <h2>LLM section</h2>
 * <p>A cyclic FAT chain must fail checked (corruption, not duplicated
 * data); a valid chain walks to end-of-chain. Pre-fix, cycles were only
 * bounded by table length and produced duplicated clusters.</p>
 */
class FatClusterChainCycleTest {

    private static final int EOC = 0x0FFFFFF8;

    @Test
    void cyclicChainFailsChecked() {
        // 2 -> 3 -> 4 -> 3 (cycle)
        int[] fat = {0, 0, 3, 4, 3};
        assertThatThrownBy(() -> Fat32FileSystemImpl.walkClusterChain(fat, 2, EOC, "FAT"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void validChainWalksToEoc() throws IOException {
        int[] fat = {0, 0, 3, 4, EOC};
        List<Integer> chain = Fat32FileSystemImpl.walkClusterChain(fat, 2, EOC, "FAT");
        assertThat(chain).containsExactly(2, 3, 4);
    }
}
