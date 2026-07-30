/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.linuxkernel;

import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safety tests for {@link LinuxKernelContainerFactory}.
 */
class LinuxKernelContainerFactoryTest {

    /**
     * Verifies that a source larger than {@link Integer#MAX_VALUE} is rejected,
     * because it cannot be addressed as a single byte array.
     */
    @Test
    void rejectsSourceBeyondIntegerMaxValue() throws IOException {
        byte[] header = bzImageHeader();
        HugeKernelDisk disk = new HugeKernelDisk(header, (long) Integer.MAX_VALUE + 1L);

        Optional<BinaryContainer> container = LinuxKernelContainerFactory.open(disk);

        assertThat(container).isEmpty();
    }

    /**
     * Verifies that a normal-sized kernel fixture still opens successfully.
     */
    @Test
    void opensNormalSizedSource() throws IOException {
        byte[] header = bzImageHeader();
        byte[] image = new byte[8192];
        System.arraycopy(header, 0, image, 0, header.length);
        HugeKernelDisk disk = new HugeKernelDisk(image, image.length);

        Optional<BinaryContainer> container = LinuxKernelContainerFactory.open(disk);

        assertThat(container).isPresent();
    }

    private static byte[] bzImageHeader() {
        byte[] header = new byte[512];
        header[0] = 'M';
        header[1] = 'Z';
        // setup_sects = 0 => payload starts at 512 bytes
        header[0x1f1] = 0;
        header[510] = (byte) 0x55;
        header[511] = (byte) 0xAA;
        return header;
    }

    /**
     * VirtualDisk that reports a configurable huge size while still returning a
     * valid kernel header for the first read.
     */
    static class HugeKernelDisk implements VirtualDisk.RawDisk {
        private final byte[] header;
        private final long size;

        HugeKernelDisk(byte[] header, long size) {
            this.header = header.clone();
            this.size = size;
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            if (offset < 0 || offset >= size) {
                return ByteBuffer.allocate(length);
            }
            int available = (int) Math.min(length, size - offset);
            byte[] result = new byte[length];
            int fromHeader = (int) Math.min(available, Math.max(0, header.length - offset));
            if (fromHeader > 0) {
                System.arraycopy(header, (int) offset, result, 0, fromHeader);
            }
            return ByteBuffer.wrap(result);
        }

        @Override
        public long virtualSize() {
            return size;
        }

        @Override
        public long allocatedSize() {
            return size;
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
            return new ByteArrayInputStream(header);
        }

        @Override
        public PackageURL packageUrl() {
            try {
                return new PackageURL("pkg:vmdisk/raw/huge@1.0");
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
