/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.xfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hostile extent-btree tests for {@link XfsFileSystemImpl} (phase 4, T4.1).
 *
 * <h2>LLM section</h2>
 * <p>An internal btree block whose pointer references its own block must
 * fail checked ("cycle"); a chain of distinct blocks longer than the cap
 * must fail checked ("too deep").</p>
 */
class XfsExtentBtreeCycleTest {

    private static final int BLOCK = 512;

    /** Internal btree block (level 1, one record) whose pointer is {@code ptr}. */
    private static byte[] internalBlock(long ptr) {
        byte[] block = new byte[BLOCK];
        ByteBuffer buf = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0, 0x424D4150);   // BMAP magic
        buf.putShort(4, (short) 1);  // level (internal)
        buf.putShort(6, (short) 1);  // numrecs
        int maxrecs = (BLOCK - 24) / 16;
        int ptrOffset = 24 + maxrecs * 8;
        buf.putLong(ptrOffset, ptr);
        return block;
    }

    @Test
    void selfReferencingBtreeFailsChecked() {
        byte[] block = internalBlock(7);
        List<XfsExtent> extents = new ArrayList<>();
        assertThatThrownBy(() -> XfsFileSystemImpl.walkExtentBtree(
                List.of(7L), 1, extents, 0, new HashSet<>(), n -> block, BLOCK, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void overDeepBtreeFailsChecked() {
        byte[] block = internalBlock(7);
        List<XfsExtent> extents = new ArrayList<>();
        assertThatThrownBy(() -> XfsFileSystemImpl.walkExtentBtree(
                List.of(7L), 1, extents, 0, new HashSet<>(),
                n -> internalBlock(n + 1), BLOCK, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too deep");
    }
}
