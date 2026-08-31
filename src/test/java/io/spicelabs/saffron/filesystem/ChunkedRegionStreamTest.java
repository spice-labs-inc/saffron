/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ChunkedRegionStream} (phase 5, T5.2/T5.4/T5.5).
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Byte equivalence against a materialized reference across
 *       multi-segment files with sparse gaps.</li>
 *   <li>A recording region asserts: no single read over 256 KiB and zero
 *       reads for sparse gaps (T5.2/T5.5).</li>
 *   <li>Independence: two streams over the same region read correctly
 *       interleaved; single-byte reads agree with bulk reads; len==0
 *       returns 0; too many segments rejected.</li>
 * </ul>
 */
class ChunkedRegionStreamTest {

    /** Region over a byte array that records the max read length. */
    static final class RecordingRegion implements DiskRegion {
        final byte[] data;
        long maxRead;
        long readCount;

        RecordingRegion(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) throws IOException {
            maxRead = Math.max(maxRead, length);
            readCount++;
            if (offset < 0 || offset + length > data.length) {
                throw new IOException("out of bounds");
            }
            byte[] out = new byte[length];
            System.arraycopy(data, (int) offset, out, 0, length);
            return ByteBuffer.wrap(out);
        }

        @Override
        public long size() {
            return data.length;
        }
    }

    private static byte[] pattern(int length, int seed) {
        byte[] out = new byte[length];
        new Random(seed).nextBytes(out);
        return out;
    }

    @Test
    void segmentsWithSparseGapsEqualReference() throws IOException {
        byte[] backing = new byte[1 << 20];
        Random rnd = new Random(7);
        rnd.nextBytes(backing);

        // Logical file: [0, 100KB) from offset 0; gap 100KB-300KB;
        // [300KB, 420KB) from offset 512KB; total size 500KB with trailing gap.
        List<ChunkedRegionStream.Segment> segments = List.of(
                new ChunkedRegionStream.Segment(0, 0, 100 * 1024),
                new ChunkedRegionStream.Segment(300 * 1024, 512 * 1024, 120 * 1024));
        long size = 500 * 1024;

        byte[] reference = new byte[(int) size];
        System.arraycopy(backing, 0, reference, 0, 100 * 1024);
        System.arraycopy(backing, 512 * 1024, reference, 300 * 1024, 120 * 1024);

        RecordingRegion region = new RecordingRegion(backing);
        try (InputStream in = new ChunkedRegionStream(region, segments, size)) {
            assertThat(in.readAllBytes()).isEqualTo(reference);
        }
        assertThat(region.maxRead).isLessThanOrEqualTo(ChunkedRegionStream.WINDOW);
    }

    @Test
    void sparseGapsNeverTouchRegion() throws IOException {
        byte[] backing = new byte[4096];
        List<ChunkedRegionStream.Segment> segments = List.of(
                new ChunkedRegionStream.Segment(0, 0, 512));
        RecordingRegion region = new RecordingRegion(backing);
        try (InputStream in = new ChunkedRegionStream(region, segments, 4096)) {
            byte[] out = in.readAllBytes();
            assertThat(out).hasSize(4096);
            for (int i = 512; i < 4096; i++) {
                assertThat(out[i]).isZero();
            }
        }
        assertThat(region.readCount).isEqualTo(1); // only the 512-byte segment
    }

    @Test
    void singleByteAndBulkReadsAgree() throws IOException {
        byte[] backing = pattern(64 * 1024, 11);
        List<ChunkedRegionStream.Segment> segments = List.of(
                new ChunkedRegionStream.Segment(0, 0, 64 * 1024));
        RecordingRegion region = new RecordingRegion(backing);
        try (InputStream in = new ChunkedRegionStream(region, segments, 64 * 1024)) {
            for (int i = 0; i < 64 * 1024; i++) {
                int b = in.read();
                assertThat(b).isEqualTo(backing[i] & 0xFF);
            }
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void streamsAreIndependent() throws IOException {
        byte[] backing = pattern(256 * 1024, 13);
        List<ChunkedRegionStream.Segment> segments = List.of(
                new ChunkedRegionStream.Segment(0, 0, 256 * 1024));
        RecordingRegion region = new RecordingRegion(backing);
        try (InputStream a = new ChunkedRegionStream(region, segments, 256 * 1024);
             InputStream b = new ChunkedRegionStream(region, segments, 256 * 1024)) {
            byte[] bufA = new byte[4096];
            byte[] bufB = new byte[4096];
            for (int round = 0; round < 8; round++) {
                assertThat(a.readNBytes(bufA, 0, 4096)).isEqualTo(4096);
                assertThat(b.readNBytes(bufB, 0, 4096)).isEqualTo(4096);
                assertThat(bufA).isEqualTo(bufB);
            }
        }
    }

    @Test
    void zeroLengthReadReturnsZero() throws IOException {
        byte[] backing = new byte[16];
        try (InputStream in = new ChunkedRegionStream(new RecordingRegion(backing),
                List.of(new ChunkedRegionStream.Segment(0, 0, 16)), 16)) {
            assertThat(in.read(new byte[8], 0, 0)).isZero();
        }
    }

    @Test
    void tooManySegmentsRejected() {
        List<ChunkedRegionStream.Segment> segments = new ArrayList<>();
        for (int i = 0; i < ChunkedRegionStream.MAX_SEGMENTS + 1; i++) {
            segments.add(new ChunkedRegionStream.Segment(i, i, 1));
        }
        assertThatThrownBy(() -> new ChunkedRegionStream(
                new RecordingRegion(new byte[16]), segments, 8))
                .isInstanceOf(IOException.class);
    }
}
