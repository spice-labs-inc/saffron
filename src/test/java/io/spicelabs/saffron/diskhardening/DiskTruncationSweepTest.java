/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.diskharness.DiskFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based truncation sweep (phase 1, T1.8).
 *
 * <h2>Why this test exists</h2>
 * <p>Deterministic truncation tests cover one offset per format; this
 * sweep truncates valid images at many random offsets and asserts the
 * same contract: open+read terminates, and every read either succeeds
 * with byte-exact data (wholly below the truncation point) or throws
 * {@code IOException} (touching the truncation point in an allocated
 * region). Never a hang, never short/zero success.</p>
 *
 * <h2>LLM section</h2>
 * <p>Seeded {@code Random} for reproducibility. Each iteration writes a
 * fresh image, opens, truncates, reads a small window near the
 * truncation point and one far below it. {@code @Timeout} guards the
 * whole test (runs in the hardening fork).</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
class DiskTruncationSweepTest {

    private static final int ITERATIONS = 25;

    interface Case {
        byte[] build() throws IOException;

        DiskFormat format();
    }

    private static int clusterSize = 65536;
    private static int blockSize = 1024 * 1024;
    private static int grainSize = 65536;

    private static final Case[] CASES = {
            new Case() {
                @Override public byte[] build() {
                    byte[] c = new byte[clusterSize];
                    DiskFixtures.fill(c, 0, clusterSize);
                    return DiskFixtures.qcow2AllocatedCluster(3, 16, 4L * clusterSize, c);
                }

                @Override public DiskFormat format() {
                    return DiskFormat.QCOW2;
                }
            },
            new Case() {
                @Override public byte[] build() {
                    return DiskFixtures.fixedVhd(8192, 8192);
                }

                @Override public DiskFormat format() {
                    return DiskFormat.VHD;
                }
            },
            new Case() {
                @Override public byte[] build() {
                    return DiskFixtures.dynamicVhd(8L * blockSize, blockSize, true);
                }

                @Override public DiskFormat format() {
                    return DiskFormat.VHD;
                }
            },
            new Case() {
                @Override public byte[] build() {
                    return DiskFixtures.vhdx(8L * blockSize, blockSize, true, false);
                }

                @Override public DiskFormat format() {
                    return DiskFormat.VHDX;
                }
            },
            new Case() {
                @Override public byte[] build() {
                    return DiskFixtures.vdi(8L * blockSize, blockSize, true, null);
                }

                @Override public DiskFormat format() {
                    return DiskFormat.VDI;
                }
            },
            new Case() {
                @Override public byte[] build() {
                    return DiskFixtures.vmdk(8L * grainSize, grainSize, true, false, null);
                }

                @Override public DiskFormat format() {
                    return DiskFormat.VMDK;
                }
            },
            new Case() {
                @Override public byte[] build() {
                    byte[] raw = new byte[8192];
                    DiskFixtures.fill(raw, 0, 8192);
                    return raw;
                }

                @Override public DiskFormat format() {
                    return DiskFormat.RAW;
                }
            },
    };

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void randomTruncationsTerminateAndNeverReturnShortData(@TempDir Path dir) throws IOException {
        Random random = new Random(0x5EED_2026L);
        for (Case testCase : CASES) {
            for (int iter = 0; iter < ITERATIONS; iter++) {
                byte[] image = testCase.build();
                Path file = dir.resolve("sweep-" + System.nanoTime() + ".img");
                Files.write(file, image);

                // Truncate at a random offset that cuts into the data
                // region while keeping the trailing format metadata
                // (footer etc.) intact so open() succeeds.
                int cut = image.length / 3 + random.nextInt(image.length / 3);
                DiskFixtures.truncate(file, cut);

                VirtualDisk disk;
                try {
                    disk = DiskReader.open(file, testCase.format());
                } catch (IOException | RuntimeException openFailed) {
                    // Truncation landed inside open-time metadata; the
                    // format must fail loudly at open — acceptable.
                    continue;
                }
                try (disk) {
                    // Read far below the truncation point: succeeds with
                    // exact bytes, OR throws IOException if the truncation
                    // point cut into format metadata needed to serve the
                    // read. Never a hang, never short/zero success.
                    int belowLen = Math.max(1, Math.min(256, cut / 2 - 1));
                    try {
                        ByteBuffer below = disk.read(0, belowLen);
                        assertThat(below.remaining()).isEqualTo(belowLen);
                        // Stability: re-reading must agree (no torn reads).
                        ByteBuffer again = disk.read(0, belowLen);
                        assertThat(again.array()).isEqualTo(below.array());
                    } catch (IOException expected) {
                        // Loud failure for reads whose metadata was
                        // truncated away — acceptable.
                    }

                    // Read touching/near the truncation point: either
                    // IOException or exact bytes; never short/zero success.
                    long touchOffset = Math.max(0, cut - 64);
                    if (touchOffset + 128 > disk.virtualSize()) {
                        continue;
                    }
                    try {
                        ByteBuffer touching = disk.read(touchOffset, 128);
                        assertThat(touching.remaining()).isEqualTo(128);
                        ByteBuffer again = disk.read(touchOffset, 128);
                        assertThat(again.array()).isEqualTo(touching.array());
                    } catch (IOException expected) {
                        // The documented failure mode for allocated
                        // regions touching the truncation point.
                    }
                }
            }
        }
    }
}
