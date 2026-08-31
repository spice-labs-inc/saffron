/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.apfs;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Omap cache-budget and cycle tests (phase 6 T6.2 / phase 4 T4.1) over a
 * synthetic omap: block 0 = omap object, block 1 = index node, blocks
 * 2..4 = leaf nodes (16-bit TOC offsets cap each node at ~2000 entries,
 * so the 4200-oid table spans three leaves).
 *
 * <h2>LLM section</h2>
 * <p>Leaf value layout: flags(4)+size(4)+paddr(8); index value: child
 * block at offset 0. The cycle test uses a self-referential index node.</p>
 */
class ApfsObjectMapCacheTest {

    private static final int BLOCK = 256 * 1024;

    static final class OmapFixture {
        final byte[] image;

        OmapFixture(byte[] image) {
            this.image = image;
        }

        DiskRegion region() {
            return new DiskRegion() {
                @Override
                public ByteBuffer read(long offset, int length) {
                    byte[] out = new byte[length];
                    System.arraycopy(image, (int) offset, out, 0, length);
                    return ByteBuffer.wrap(out);
                }

                @Override
                public long size() {
                    return image.length;
                }
            };
        }

        ApfsObjectMap open() throws IOException {
            return ApfsObjectMap.open(region(), BLOCK, 0);
        }
    }

    /** Writes a btree node at {@code blockIndex} and returns the next block. */
    private static int writeNode(byte[] image, int blockIndex, int level, boolean leaf,
                                 int nkeys, long[] oids, long[] payloads) {
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        int nodeBase = blockIndex * BLOCK;
        int nodeSize = BLOCK;
        int flags = ApfsBTreeReader.BTNODE_FIXED_KV_SIZE;
        if (leaf) {
            flags |= ApfsBTreeReader.BTNODE_LEAF;
        }
        int keyLen = 16;
        int valLen = leaf ? 16 : 8;

        buf.putShort(nodeBase + 32, (short) flags);
        buf.putShort(nodeBase + 34, (short) level);
        buf.putInt(nodeBase + 36, nkeys);
        int tableSpaceLen = nkeys * 4;
        buf.putShort(nodeBase + 40, (short) 0);
        buf.putShort(nodeBase + 42, (short) tableSpaceLen);
        buf.putShort(nodeBase + 44, (short) 0);
        buf.putShort(nodeBase + 46, (short) 0);

        int tocStart = nodeBase + 56;
        int keysStart = tocStart + tableSpaceLen;
        for (int i = 0; i < nkeys; i++) {
            int keyOff = i * keyLen;
            int valOff = (i + 1) * valLen;
            buf.putShort(tocStart + i * 4, (short) keyOff);
            buf.putShort(tocStart + i * 4 + 2, (short) valOff);
            buf.putLong(keysStart + keyOff, oids[i]);
            buf.putLong(keysStart + keyOff + 8, 1); // xid
            int store = nodeBase + nodeSize - valOff;
            if (leaf) {
                buf.putInt(store, 0);
                buf.putInt(store + 4, 0);
                buf.putLong(store + 8, payloads[i]); // paddr
            } else {
                buf.putLong(store, payloads[i]); // child block
            }
        }
        return blockIndex + 1;
    }

    /** 4200 oids across an index node + three leaves (blocks 2..4). */
    private static OmapFixture buildLargeOmap() {
        int blocks = 5;
        byte[] image = new byte[blocks * BLOCK];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(48, 1); // treeOid = index node at block 1

        int leaves = 3;
        int perLeaf = 1400;
        // Index node at block 1: one entry per leaf (threshold oid, child block).
        long[] thresholds = new long[leaves];
        long[] children = new long[leaves];
        for (int l = 0; l < leaves; l++) {
            thresholds[l] = (long) l * perLeaf;
            children[l] = 2 + l;
        }
        writeNode(image, 1, 1, false, leaves, thresholds, children);
        int block = 2;
        for (int l = 0; l < leaves; l++) {
            long[] oids = new long[perLeaf];
            long[] paddrs = new long[perLeaf];
            for (int i = 0; i < perLeaf; i++) {
                long oid = (long) l * perLeaf + i;
                oids[i] = oid;
                paddrs[i] = 1000L + oid;
            }
            block = writeNode(image, block, 0, true, perLeaf, oids, paddrs);
        }
        return new OmapFixture(image);
    }

    /** Self-referential index node at block 1 (child block = 1). */
    private static OmapFixture buildCyclicOmap() {
        byte[] image = new byte[2 * BLOCK];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(48, 1);
        writeNode(image, 1, 1, false, 1, new long[]{0}, new long[]{1});
        return new OmapFixture(image);
    }

    static byte[] debugImage() {
        return buildLargeOmap().image;
    }

    static DiskRegion debugRegion() {
        return buildLargeOmap().region();
    }

    @Test
    void resolutionCacheIsBounded() throws IOException {
        ApfsObjectMap omap = buildLargeOmap().open();
        for (long oid = 0; oid < 4200; oid++) {
            assertThat(omap.resolve(oid, 100)).isEqualTo(1000L + oid);
        }
        assertThat(omap.cacheSize()).isLessThanOrEqualTo(4096);
        // Re-resolving a (possibly evicted) OID still works: re-read on miss.
        assertThat(omap.resolve(0, 100)).isEqualTo(1000L);
        assertThat(omap.resolve(4199, 100)).isEqualTo(5199L);
    }

    @Test
    void selfReferentialIndexNodeFailsChecked() throws IOException {
        ApfsObjectMap omap = buildCyclicOmap().open();
        assertThatThrownBy(() -> omap.resolve(0, 100))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cycle");
    }
}
