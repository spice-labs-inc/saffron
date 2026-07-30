/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.adapter;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VirtualDiskInputStreamSource}.
 */
class VirtualDiskInputStreamSourceTest {

    @Test
    void readsFromOffset() throws IOException {
        byte[] data = "Hello, VirtualDisk!".getBytes();
        VirtualDisk disk = new ByteArrayVirtualDisk(data);
        InputStreamSource source = new VirtualDiskInputStreamSource(disk, data.length, "test");

        try (InputStream is = source.openStream(7)) {
            byte[] read = is.readAllBytes();
            assertThat(new String(read)).isEqualTo("VirtualDisk!");
        }
    }

    @Test
    void readsWholeDisk() throws IOException {
        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        VirtualDisk disk = new ByteArrayVirtualDisk(data);
        InputStreamSource source = new VirtualDiskInputStreamSource(disk, data.length, "test");

        try (InputStream is = source.openStream()) {
            assertThat(is.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void sizeMatchesVirtualSize() throws IOException {
        byte[] data = new byte[1234];
        VirtualDisk disk = new ByteArrayVirtualDisk(data);
        InputStreamSource source = new VirtualDiskInputStreamSource(disk, data.length, "test");

        assertThat(source.size()).isEqualTo(data.length);
        assertThat(source.supportsRandomAccess()).isTrue();
    }

    private static final class ByteArrayVirtualDisk implements VirtualDisk.RawDisk {
        private final byte[] data;

        ByteArrayVirtualDisk(byte[] data) {
            this.data = data.clone();
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            if (offset < 0 || offset >= data.length) {
                return ByteBuffer.allocate(0);
            }
            int available = (int) Math.min(length, data.length - offset);
            byte[] result = new byte[available];
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
            return Collections.emptyMap();
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
            return new java.io.ByteArrayInputStream(data);
        }

        @Override
        public com.github.packageurl.PackageURL packageUrl() {
            try {
                return new com.github.packageurl.PackageURL("pkg:vmdisk/raw/test@1.0");
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
        public void close() {
        }
    }
}
