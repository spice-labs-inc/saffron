/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.lvm.DiskRegion;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Fuzz test for ext4 superblock parsing.
 *
 * <p>Feeds random bytes as filesystem data to ensure no uncaught exceptions
 * (only expected IOException/IllegalArgumentException should be thrown).
 */
class Ext4SuperblockFuzzTest {

    @FuzzTest(maxDuration = "60s")
    void fuzzExt4Detection(FuzzedDataProvider data) {
        byte[] bytes = data.consumeBytes(4096);
        if (bytes.length < 2048) return;

        try {
            // Create a minimal DiskRegion from the fuzzed data
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            FuzzDiskRegion region = new FuzzDiskRegion(bytes);
            FilesystemDetector.detect(region);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            // Expected — malformed data should throw these
        }
    }

    /**
     * Minimal DiskRegion for fuzz testing that reads from a byte array.
     */
    static class FuzzDiskRegion implements DiskRegion {
        private final byte[] data;

        FuzzDiskRegion(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) throws IOException {
            if (offset < 0 || offset >= data.length) {
                return ByteBuffer.allocate(length); // Return zeros for out-of-range
            }
            int available = (int) Math.min(length, data.length - offset);
            byte[] result = new byte[length];
            System.arraycopy(data, (int) offset, result, 0, available);
            return ByteBuffer.wrap(result);
        }

        @Override
        public long size() {
            return data.length;
        }
    }
}
