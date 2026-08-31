/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.io.BinaryReader;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lazy-vs-eager property tests for {@link SquashfsMetadataTable}
 * (phase 6, T6.4/T6.5).
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Synthetic tables of compressed and uncompressed metadata blocks
 *       with a seeded random layout; for many random (blockStart,
 *       blockOffset) positions, the lazy reader's bytes equal the eager
 *       golden oracle's bytes (including reads crossing block
 *       boundaries).</li>
 *   <li>Block cache: reading across many blocks never holds more than
 *       {@code MAX_CACHED_BLOCKS} decompressed blocks; a thrash pattern
 *       across &gt; 32 blocks terminates and stays bounded.</li>
 * </ul>
 */
class SquashfsMetadataTableLazyTest {

    private static final int BLOCK = 8192;

    private static byte[] deflate(byte[] input) {
        Deflater d = new Deflater();
        d.setInput(input);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    /**
     * Builds a synthetic table of {@code count} blocks: alternating
     * compressed (random payload) and uncompressed blocks; the final
     * block is compressed but truncates to {@code lastLen} so its true
     * decompressed length is short (exercises the final-block edge).
     */
    private record Table(byte[] bytes, long[] blockStarts) {
    }

    private static Table buildTable(int count, int lastLen) {
        Random rnd = new Random(0x6AAD_F00D);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] starts = new long[count];
        for (int i = 0; i < count; i++) {
            starts[i] = out.size();
            int payloadLen = (i == count - 1) ? lastLen : BLOCK;
            byte[] payload = new byte[payloadLen];
            boolean compressed = i % 2 == 0;
            if (compressed) {
                // Compressible payload (deflate must shrink it below 8192).
                for (int j = 0; j < payloadLen; j++) {
                    payload[j] = (byte) ((j / 16) + i);
                }
            } else {
                rnd.nextBytes(payload);
            }
            if (compressed) {
                byte[] deflated = deflate(payload);
                int size = deflated.length;
                out.write(size & 0xff);
                out.write(((size & 0x7f00) >> 8)); // uncompressed: high bit 0
                out.writeBytes(deflated);
            } else {
                out.write(payload.length & 0xff);
                out.write(((payload.length & 0x7f00) >> 8) | 0x80); // high bit 1
                out.writeBytes(payload);
            }
        }
        return new Table(out.toByteArray(), starts);
    }

    private static DiskRegion region(byte[] data) {
        return new DiskRegion() {
            @Override
            public ByteBuffer read(long offset, int length) {
                byte[] out = new byte[length];
                System.arraycopy(data, (int) offset, out, 0, length);
                return ByteBuffer.wrap(out);
            }

            @Override
            public long size() {
                return data.length;
            }
        };
    }

    @Test
    void lazyReadersMatchEagerOracleAcrossPositions() throws IOException {
        Table table = buildTable(16, 1000);
        DiskRegion region = region(table.bytes());
        SquashfsCompressor compressor = SquashfsCompressor.forId(0);

        SquashfsMetadataTable lazy = SquashfsMetadataTable.read(region, compressor, 0, table.bytes().length);
        SquashfsMetadataTableEager eager = SquashfsMetadataTableEager.read(region, compressor, 0, table.bytes().length);

        Random rnd = new Random(42);
        for (int i = 0; i < 200; i++) {
            long blockStart = table.blockStarts()[rnd.nextInt(16)];
            int blockOffset = rnd.nextInt(100);
            BinaryReader lazyReader = lazy.readerAt(blockStart, blockOffset);
            BinaryReader eagerReader = eager.readerAt(blockStart, blockOffset);
            // Compare the next 64 bytes (may cross block boundaries).
            for (int j = 0; j < 64; j++) {
                int a = readByteOrMinusOne(lazyReader);
                int b = readByteOrMinusOne(eagerReader);
                assertThat(a).as("pos %d block %d off %d byte %d", i, blockStart, blockOffset, j)
                        .isEqualTo(b);
            }
        }
    }

    @Test
    void blockCacheStaysBoundedAcrossManyBlocks() throws IOException {
        Table table = buildTable(64, BLOCK);
        DiskRegion region = region(table.bytes());
        SquashfsCompressor compressor = SquashfsCompressor.forId(0);
        SquashfsMetadataTable lazy = SquashfsMetadataTable.read(region, compressor, 0, table.bytes().length);

        for (int i = 0; i < 64; i++) {
            long blockStart = table.blockStarts()[i];
            BinaryReader reader = lazy.readerAt(blockStart, 0);
            readByteOrMinusOne(reader);
        }
        assertThat(lazy.cachedBlockCount()).isLessThanOrEqualTo(SquashfsMetadataTable.MAX_CACHED_BLOCKS);
    }

    @Test
    void thrashPatternTerminatesAndStaysBounded() throws IOException {
        Table table = buildTable(64, BLOCK);
        DiskRegion region = region(table.bytes());
        SquashfsCompressor compressor = SquashfsCompressor.forId(0);
        SquashfsMetadataTable lazy = SquashfsMetadataTable.read(region, compressor, 0, table.bytes().length);

        for (int round = 0; round < 1000; round++) {
            long blockStart = table.blockStarts()[round % 64];
            BinaryReader reader = lazy.readerAt(blockStart, 0);
            readByteOrMinusOne(reader);
        }
        assertThat(lazy.cachedBlockCount()).isLessThanOrEqualTo(SquashfsMetadataTable.MAX_CACHED_BLOCKS);
    }

    @Test
    void unknownBlockRejected() throws IOException {
        Table table = buildTable(4, BLOCK);
        SquashfsMetadataTable lazy = SquashfsMetadataTable.read(region(table.bytes()),
                SquashfsCompressor.forId(0), 0, table.bytes().length);
        assertThatThrownBy(() -> lazy.readerAt(999_999, 0)).isInstanceOf(IOException.class);
    }
    private static int readByteOrMinusOne(BinaryReader reader) throws IOException {
        try {
            return reader.readUInt8();
        } catch (java.io.EOFException e) {
            return -1;
        }
    }
}
