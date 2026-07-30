/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.linuxkernel;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.fs.FileSystem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

class LinuxKernelContainerFuzzTest {

    private static final int MUTATION_SIZE = 512;
    private static final String FIXTURE = "src/test/resources/linux-kernel/iotgoat-x86-vmlinuz";

    /**
     * Mutates the first 512 bytes of the x86 bzImage fixture and verifies that
     * detection and mounting never throw an unchecked exception.
     */
    @FuzzTest(maxDuration = "30s")
    void headerFuzz(FuzzedDataProvider data) throws IOException {
        byte[] original = Files.readAllBytes(Paths.get(FIXTURE));
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
            FuzzVirtualDisk disk = new FuzzVirtualDisk(image);
            Optional<FileSystem> fs = BinaryContainerMount.mount(disk);
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

    /**
     * Minimal VirtualDisk for fuzz testing.
     */
    static class FuzzVirtualDisk implements VirtualDisk.RawDisk {
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
