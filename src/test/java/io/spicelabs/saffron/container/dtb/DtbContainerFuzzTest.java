/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dtb;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.fs.FileSystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Fuzz tests for DTB detection and mounting.
 */
class DtbContainerFuzzTest {

    private static final int MUTATION_SIZE = 64;
    private static final String DTB = "src/test/resources/dtb/bcm2710-rpi-3-b.dtb";

    @FuzzTest(maxDuration = "30s")
    void headerFuzz(FuzzedDataProvider data) throws IOException {
        byte[] original = Files.readAllBytes(Paths.get(DTB));
        byte[] mutated = data.consumeBytes(MUTATION_SIZE);
        byte[] image = original.clone();
        int toCopy = Math.min(MUTATION_SIZE, mutated.length);
        System.arraycopy(mutated, 0, image, 0, toCopy);

        try {
            ContainerDetector.detect(ByteBuffer.wrap(image));
        } catch (IllegalArgumentException | ArithmeticException | UnsupportedOperationException e) {
            // Expected for malformed input
        }

        try {
            Optional<FileSystem> fs = BinaryContainerMount.mount(new FuzzVirtualDisk(image));
            fs.ifPresent(f -> {
                try {
                    f.close();
                } catch (IOException ignored) {
                }
            });
        } catch (IllegalArgumentException | ArithmeticException | UnsupportedOperationException | IOException e) {
            // Expected for malformed input
        }
    }

    static class FuzzVirtualDisk implements io.spicelabs.saffron.VirtualDisk.RawDisk {
        private final byte[] data;

        FuzzVirtualDisk(byte[] data) {
            this.data = data.clone();
        }

        @Override
        public ByteBuffer read(long offset, int length) {
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
        public io.spicelabs.saffron.DiskFormat format() {
            return io.spicelabs.saffron.DiskFormat.RAW;
        }

        @Override
        public java.util.Map<String, String> metadata() {
            return java.util.Map.of();
        }

        @Override
        public java.util.stream.Stream<Snapshot> snapshots() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public int sectorSize() {
            return 512;
        }

        @Override
        public java.io.InputStream openStream() {
            return new java.io.ByteArrayInputStream(data);
        }

        @Override
        public com.github.packageurl.PackageURL packageUrl() {
            try {
                return new com.github.packageurl.PackageURL("pkg:vmdisk/raw/fuzz@1.0");
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
