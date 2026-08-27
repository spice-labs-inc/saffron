/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.diskharness.DiskFixtures;
import io.spicelabs.saffron.qcow2.cluster.ClusterReader;
import io.spicelabs.saffron.qcow2.header.Qcow2Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency stress for the disk layers (phase 2, T2.2–T2.4).
 *
 * <h2>Why this test exists</h2>
 * <p>The deterministic {@link ChannelRaceTest} proves the failure class;
 * this suite provides confidence across all disk formats: many threads
 * read random aligned subranges of one {@code VirtualDisk} instance and
 * every byte must match the single-threaded reference. Fixtures carry a
 * per-offset pattern, so ANY wrong-offset read mismatches. It also
 * hammers the qcow2 L2 cache across two L2 tables (cache replacement
 * under concurrency) and checks the stream contract (one stream per
 * thread).</p>
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Deadline-driven: iterations run until ~20 s elapsed (not fixed
 *       counts), 8 worker threads.</li>
 *   <li>Formats: qcow2, vhd (fixed + dynamic), vhdx, vdi, vmdk, raw.</li>
 *   <li>Reference bytes captured once, single-threaded, before the
 *       storm.</li>
 * </ul>
 */
class DiskConcurrencyTest {

    private static final int THREADS = 8;

    private interface Fixture {
        byte[] build() throws IOException;

        DiskFormat format();

        /** Virtual range of the pattern data (allocated region). */
        int dataLength();
    }

    private static final int CLUSTER = 65536;
    private static final int BLOCK = 1024 * 1024;
    private static final int GRAIN = 65536;

    private static Fixture rawFixture() {
        return new Fixture() {
            @Override public byte[] build() {
                byte[] raw = new byte[256 * 1024];
                DiskFixtures.fill(raw, 0, raw.length);
                return raw;
            }

            @Override public DiskFormat format() {
                return DiskFormat.RAW;
            }

            @Override public int dataLength() {
                return 256 * 1024;
            }
        };
    }

    private static Fixture qcow2Fixture() {
        return new Fixture() {
            @Override public byte[] build() {
                byte[] cluster = new byte[CLUSTER];
                DiskFixtures.fill(cluster, 0, CLUSTER);
                return DiskFixtures.qcow2AllocatedCluster(3, 16, 4L * CLUSTER, cluster);
            }

            @Override public DiskFormat format() {
                return DiskFormat.QCOW2;
            }

            @Override public int dataLength() {
                return CLUSTER;
            }
        };
    }

    private static Fixture vhdFixedFixture() {
        return new Fixture() {
            @Override public byte[] build() {
                return DiskFixtures.fixedVhd(256 * 1024, 256 * 1024);
            }

            @Override public DiskFormat format() {
                return DiskFormat.VHD;
            }

            @Override public int dataLength() {
                return 256 * 1024;
            }
        };
    }

    private static Fixture vhdDynamicFixture() {
        return new Fixture() {
            @Override public byte[] build() {
                return DiskFixtures.dynamicVhd(8L * BLOCK, BLOCK, true);
            }

            @Override public DiskFormat format() {
                return DiskFormat.VHD;
            }

            @Override public int dataLength() {
                return BLOCK;
            }
        };
    }

    private static Fixture vhdxFixture() {
        return new Fixture() {
            @Override public byte[] build() {
                return DiskFixtures.vhdx(8L * BLOCK, BLOCK, true, false);
            }

            @Override public DiskFormat format() {
                return DiskFormat.VHDX;
            }

            @Override public int dataLength() {
                return BLOCK;
            }
        };
    }

    private static Fixture vdiFixture() {
        return new Fixture() {
            @Override public byte[] build() {
                return DiskFixtures.vdi(8L * BLOCK, BLOCK, true, null);
            }

            @Override public DiskFormat format() {
                return DiskFormat.VDI;
            }

            @Override public int dataLength() {
                return BLOCK;
            }
        };
    }

    private static Fixture vmdkFixture() {
        return new Fixture() {
            @Override public byte[] build() {
                return DiskFixtures.vmdk(8L * GRAIN, GRAIN, true, false, null);
            }

            @Override public DiskFormat format() {
                return DiskFormat.VMDK;
            }

            @Override public int dataLength() {
                return GRAIN;
            }
        };
    }

    /**
     * Requirement: concurrent random reads of one disk instance across
     * every format return byte-exact data (T2.2).
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void concurrentRandomReadsMatchReferenceAcrossFormats(@TempDir Path dir) throws Exception {
        for (Fixture fixture : List.of(rawFixture(), qcow2Fixture(), vhdFixedFixture(),
                vhdDynamicFixture(), vhdxFixture(), vdiFixture(), vmdkFixture())) {
            byte[] image = fixture.build();
            Path file = dir.resolve("stress-" + System.nanoTime() + ".img");
            Files.write(file, image);

            try (VirtualDisk disk = DiskReader.open(file, fixture.format())) {
                int dataLength = fixture.dataLength();
                byte[] reference = new byte[dataLength];
                ByteBuffer refBuf = disk.read(0, dataLength);
                refBuf.get(reference);

                ExecutorService pool = Executors.newFixedThreadPool(THREADS);
                try {
                    Random random = new Random(0xBEEF_CAFE + dataLength);
                    List<Future<Void>> futures = new ArrayList<>();
                    AtomicBoolean failed = new AtomicBoolean(false);

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    int[] rounds = {0};
                    while (System.nanoTime() < deadline && !failed.get()) {
                        for (int t = 0; t < THREADS; t++) {
                            futures.add(pool.submit(new Callable<Void>() {
                                @Override
                                public Void call() throws Exception {
                                    int length = 512 + random.nextInt(3584);
                                    int offset = random.nextInt(dataLength - length + 1);
                                    offset = (offset / 512) * 512;
                                    ByteBuffer buf = disk.read(offset, length);
                                    for (int i = 0; i < length; i++) {
                                        byte expected = reference[offset + i];
                                        if (buf.get() != expected) {
                                            failed.set(true);
                                            throw new AssertionError("Mismatch at offset "
                                                    + (offset + i));
                                        }
                                    }
                                    return null;
                                }
                            }));
                        }
                        for (Future<Void> f : futures) {
                            f.get(30, TimeUnit.SECONDS);
                        }
                        futures.clear();
                        rounds[0]++;
                    }
                    assertThat(failed.get())
                            .as("concurrent reads mismatched for %s", file.getFileName())
                            .isFalse();
                    assertThat(rounds[0]).as("rounds executed").isPositive();
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    /**
     * Requirement: L2 cache replacement under concurrency never pairs
     * index N with table M (T2.3): two threads alternate between two
     * clusters mapped by different L2 tables.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void l2CacheReplacementUnderConcurrencyIsCorrect(@TempDir Path dir) throws Exception {
        int clusterBits = 16;
        int clusterSize = 1 << clusterBits;
        int seedA = 3;
        int seedB = 99;
        byte[] image = DiskFixtures.qcow2TwoL2Tables(3, clusterBits, seedA, seedB);
        Path file = dir.resolve("l2hammer.qcow2");
        Files.write(file, image);

        long l2Entries = clusterSize / 8L;
        long virtualB = l2Entries * clusterSize;

        try (SeekableByteChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            Qcow2Header header = Qcow2Header.read(ch);
            try (SeekableByteChannel data = FileChannel.open(file, StandardOpenOption.READ)) {
                ClusterReader reader = new ClusterReader(data, header);

                ExecutorService pool = Executors.newFixedThreadPool(THREADS);
                try {
                    List<Future<Void>> futures = new ArrayList<>();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (System.nanoTime() < deadline) {
                        for (int t = 0; t < THREADS; t++) {
                            boolean clusterB = (t & 1) == 1;
                            futures.add(pool.submit(() -> {
                                long vaddr = clusterB ? virtualB : 0;
                                ByteBuffer buf = reader.read(vaddr, 256);
                                for (int i = 0; i < 256; i++) {
                                    byte expected = clusterB
                                            ? DiskFixtures.pattern(i * 2L + 1 + seedB)
                                            : DiskFixtures.pattern(i * 2L + seedA);
                                    assertThat(buf.get())
                                            .as("%s byte %d", clusterB ? "B" : "A", i)
                                            .isEqualTo(expected);
                                }
                                return null;
                            }));
                        }
                        for (Future<Void> f : futures) {
                            f.get(30, TimeUnit.SECONDS);
                        }
                        futures.clear();
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    /**
     * Requirement: streams are single-threaded per instance; one stream
     * per thread reads correctly (T2.4).
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentStreamsOnePerThreadReadCorrectly(@TempDir Path dir) throws Exception {
        byte[] image = DiskFixtures.dynamicVhd(8L * BLOCK, BLOCK, true);
        Path file = dir.resolve("streams.vhd");
        Files.write(file, image);

        try (VirtualDisk disk = DiskReader.open(file, DiskFormat.VHD)) {
            byte[] reference = new byte[BLOCK];
            ByteBuffer refBuf = disk.read(0, BLOCK);
            refBuf.get(reference);

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                List<Future<Void>> futures = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    int start = t * 4096;
                    futures.add(pool.submit(() -> {
                        try (InputStream in = disk.openStream()) {
                            in.skipNBytes(start);
                            byte[] data = in.readNBytes(4096);
                            for (int i = 0; i < 4096; i++) {
                                assertThat(data[i]).as("byte %d", start + i)
                                        .isEqualTo(reference[start + i]);
                            }
                        }
                        return null;
                    }));
                }
                for (Future<Void> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
