/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ext4;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hostile extent-tree tests for {@link Ext4FileSystemImpl} (phase 4, T4.1).
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>An internal extent node whose index entry points back to its own
 *       block must fail checked ("cycle"), never StackOverflowError.</li>
 *   <li>A chain of distinct internal nodes longer than the 64 cap must
 *       fail checked ("too deep").</li>
 *   <li>A valid leaf terminates with an empty list.</li>
 * </ul>
 */
class Ext4ExtentCycleTest {

    private static final short EXTENT_MAGIC = (short) 0xF30A;

    /** Internal node (depth 1, one index) pointing at {@code leafBlock}. */
    private static byte[] internalNode(long leafBlock) {
        byte[] node = new byte[12 + 12];
        ByteBuffer buf = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, EXTENT_MAGIC);
        buf.putShort(2, (short) 1);  // entries
        buf.putShort(4, (short) 4);  // max
        buf.putShort(6, (short) 1);  // depth (internal)
        buf.putInt(8, 0);            // generation
        buf.putInt(12, 0);           // index logical block
        buf.putInt(16, (int) leafBlock); // leaf block lo
        buf.putShort(20, (short) 0); // leaf block hi
        return node;
    }

    /** Leaf node with no extents. */
    private static byte[] leafNode() {
        byte[] node = new byte[12];
        ByteBuffer buf = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, EXTENT_MAGIC);
        buf.putShort(2, (short) 0);  // entries
        buf.putShort(4, (short) 4);  // max
        buf.putShort(6, (short) 0);  // depth (leaf)
        buf.putInt(8, 0);
        return node;
    }

    @Test
    void selfReferencingExtentTreeFailsChecked() {
        byte[] node = internalNode(42);
        assertThatThrownBy(() -> Ext4FileSystemImpl.walkExtentTree(
                node, 0, new HashSet<>(), block -> node))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void overDeepExtentTreeFailsChecked() {
        byte[] node = internalNode(42);
        // Reader returns distinct internal nodes forever: only the depth
        // cap can stop the walk.
        assertThatThrownBy(() -> Ext4FileSystemImpl.walkExtentTree(
                node, 0, new HashSet<>(), block -> internalNode(block + 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too deep");
    }

    @Test
    void validLeafTerminates() throws IOException {
        List<Ext4Extent.Leaf> leaves = Ext4FileSystemImpl.walkExtentTree(
                leafNode(), 0, new HashSet<>(), block -> {
                    throw new AssertionError("leaf tree must not read blocks");
                });
        assertThat(leaves).isEmpty();
    }
}
