/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.spicelabs.saffron.lvm.DiskRegion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Fuzz test for the squashfs superblock and table parser.
 */
class SquashfsFuzzTest {

    private static final int MUTATION_SIZE = 128;

    /**
     * Mutates the first 128 bytes of the minimal Alpine squashfs fixture and
     * verifies that mounting never throws an unchecked exception.
     */
    @FuzzTest(maxDuration = "30s")
    void superblockFuzz(FuzzedDataProvider data) throws IOException {
        byte[] original = Files.readAllBytes(Paths.get("src/test/resources/squashfs/alpine-minimal.squashfs"));
        byte[] mutated = data.consumeBytes(MUTATION_SIZE);
        byte[] image = original.clone();
        int toCopy = Math.min(MUTATION_SIZE, mutated.length);
        System.arraycopy(mutated, 0, image, 0, toCopy);

        FuzzDiskRegion region = new FuzzDiskRegion(image);
        try {
            SquashfsFileSystemImpl.mount(region);
        } catch (IOException | IllegalArgumentException | ArithmeticException | UnsupportedOperationException e) {
            // Expected for malformed input
        }
    }

    private static final class FuzzDiskRegion implements DiskRegion {
        private final byte[] data;

        FuzzDiskRegion(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) throws IOException {
            if (offset < 0 || offset >= data.length) {
                return ByteBuffer.allocate(length);
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
