/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.diskharness.DiskFixtures;
import io.spicelabs.saffron.qcow2.cluster.ClusterReader;
import io.spicelabs.saffron.qcow2.header.Qcow2Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic race reproduction for the qcow2 reader (phase 2, T2.1).
 *
 * <h2>Why this test exists</h2>
 * <p>Pre-fix, {@code ClusterReader} used stateful
 * {@code channel.position(x)} + {@code channel.read(...)} without
 * synchronization, and its L2 cache fields were plain non-volatile
 * state: two concurrent reads could interleave positions and return data
 * from the wrong physical offset — silently wrong bytes, not an
 * exception. A probabilistic stress test cannot prove this reliably, so
 * this test wraps the channel with a barrier that deterministically
 * interleaves two readers between {@code position()} and
 * {@code read()}. On pre-fix code every pair produces a mismatch; on
 * fixed code (position+read atomic under the channel monitor) each read
 * is correct.</p>
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Fixture: two L2 tables with distinct per-offset patterns
 *       (seeds A/B) — a cross-table read is byte-detectable.</li>
 *   <li>The barrier channel pauses inside {@code read()} until both
 *       threads have recorded their positions, or times out (post-fix,
 *       the second thread is blocked on the monitor).</li>
 *   <li>Two threads read virtual cluster 0 (seed A) and the cluster of
 *       L2 table B (seed B) simultaneously, then results are compared.</li>
 * </ul>
 */
class ChannelRaceTest {

    /**
     * Wraps a {@link SeekableByteChannel} and blocks inside
     * {@code read()} on a 2-party barrier: both racing threads record
     * their positions, then both reads proceed against the delegate's
     * current (last-writer-wins) position. A timed await keeps the test
     * alive when the fix serializes access (the second thread cannot
     * reach its read until the first finishes).
     */
    static final class BarrierChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private volatile boolean enabled = true;

        BarrierChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        void disable() {
            enabled = false;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (enabled) {
                try {
                    barrier.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                } catch (BrokenBarrierException | TimeoutException e) {
                    // Pre-fix: both threads arrive and interleave (the bug).
                    // Post-fix: the second thread is blocked on the
                    // channel monitor; we proceed alone after the timeout.
                    barrier.reset();
                }
            }
            return delegate.read(dst);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static byte[] readRange(ClusterReader reader, long offset, int length)
            throws IOException {
        ByteBuffer buf = reader.read(offset, length);
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentReadsOfDifferentL2TablesNeverCrossRead(@TempDir Path dir) throws Exception {
        int clusterBits = 16; // 64 KiB clusters
        int clusterSize = 1 << clusterBits;
        int seedA = 7;
        int seedB = 42;
        byte[] image = DiskFixtures.qcow2TwoL2Tables(3, clusterBits, seedA, seedB);
        Path file = dir.resolve("race.qcow2");
        Files.write(file, image);

        long l2Entries = clusterSize / 8L;
        long virtualB = l2Entries * clusterSize; // first cluster of L2 table B

        try (SeekableByteChannel headerChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            Qcow2Header header = Qcow2Header.read(headerChannel);
            headerChannel.close();
            try (BarrierChannel barrier = new BarrierChannel(
                    FileChannel.open(file, StandardOpenOption.READ))) {
                ClusterReader reader = new ClusterReader(barrier, header);

                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    // Several interleavings; the pre-fix race fires on
                    // every pair, so a single pair already proves it.
                    for (int iteration = 0; iteration < 5; iteration++) {
                        Future<byte[]> readA = pool.submit(() -> readRange(reader, 0, 64));
                        Future<byte[]> readB = pool.submit(() -> readRange(reader, virtualB, 64));

                        byte[] bytesA = readA.get(10, TimeUnit.SECONDS);
                        byte[] bytesB = readB.get(10, TimeUnit.SECONDS);

                        for (int i = 0; i < 64; i++) {
                            assertThat(bytesA[i])
                                    .as("iteration %d, cluster A byte %d", iteration, i)
                                    .isEqualTo(DiskFixtures.pattern(i * 2L + seedA));
                            assertThat(bytesB[i])
                                    .as("iteration %d, cluster B byte %d", iteration, i)
                                    .isEqualTo(DiskFixtures.pattern(i * 2L + 1 + seedB));
                        }
                    }
                } finally {
                    barrier.disable();
                    pool.shutdownNow();
                }
            }
        }
    }
}
