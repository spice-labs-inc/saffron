/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.partition.PartitionTable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Fuzz test for GPT/MBR partition table parsing.
 *
 * <p>Feeds random bytes to partition table detection to ensure no uncaught exceptions.
 */
class GptPartitionTableFuzzTest {

    @FuzzTest(maxDuration = "60s")
    void fuzzPartitionDetection(FuzzedDataProvider data) {
        byte[] bytes = data.consumeBytes(65536);
        if (bytes.length < 1024) return;

        try {
            FuzzVirtualDisk disk = new FuzzVirtualDisk(bytes);
            PartitionTable.detect(disk);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            // Expected
        }
    }

    /**
     * Minimal VirtualDisk for fuzz testing.
     */
    static class FuzzVirtualDisk implements VirtualDisk.RawDisk {
        private final byte[] data;

        FuzzVirtualDisk(byte[] data) {
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
        public long virtualSize() {
            return data.length;
        }

        @Override
        public long allocatedSize() {
            return data.length;
        }

        @Override
        public DiskFormat format() {
            return DiskFormat.RAW;
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of();
        }

        @Override
        public Stream<Snapshot> snapshots() {
            return Stream.empty();
        }

        @Override
        public int sectorSize() {
            return 512;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(data);
        }

        @Override
        public PackageURL packageUrl() {
            try {
                return new PackageURL("pkg:vmdisk/raw/fuzz@1.0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Optional<String> backingFile() {
            return Optional.empty();
        }

        @Override
        public boolean isEncrypted() {
            return false;
        }

        @Override
        public boolean isCompressed() {
            return false;
        }

        @Override
        public void close() {}
    }
}
