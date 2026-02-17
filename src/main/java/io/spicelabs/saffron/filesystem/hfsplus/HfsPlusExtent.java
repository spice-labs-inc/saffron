/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.hfsplus;

import java.util.List;

/**
 * Represents an HFS+ extent descriptor.
 *
 * <p>An extent maps a contiguous range of allocation blocks on disk.
 * Each extent has a start block and a block count.
 *
 * @param startBlock the first allocation block
 * @param blockCount the number of contiguous blocks
 */
public record HfsPlusExtent(long startBlock, long blockCount) {

    /**
     * Resolves a logical block offset within a fork to a physical allocation block.
     *
     * @param extents the list of extents describing the fork
     * @param logicalBlock the logical block offset to resolve
     * @return the physical allocation block number, or -1 if not found
     */
    public static long resolveLogicalBlock(List<HfsPlusExtent> extents, long logicalBlock) {
        long offset = 0;
        for (HfsPlusExtent extent : extents) {
            if (logicalBlock < offset + extent.blockCount()) {
                return extent.startBlock() + (logicalBlock - offset);
            }
            offset += extent.blockCount();
        }
        return -1;
    }

    /**
     * Returns the total number of blocks covered by the given extents.
     */
    public static long totalBlocks(List<HfsPlusExtent> extents) {
        long total = 0;
        for (HfsPlusExtent extent : extents) {
            total += extent.blockCount();
        }
        return total;
    }
}
