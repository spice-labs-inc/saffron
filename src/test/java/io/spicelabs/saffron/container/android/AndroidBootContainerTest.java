/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.android;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container tests for Android boot images.
 */
class AndroidBootContainerTest {

    private static final String FIXTURE = "src/test/resources/android-boot/boot.img";

    @Test
    void exposesKernel() throws IOException {
        BinaryContainer container = AndroidBootContainer.open(Path.of(FIXTURE)).orElseThrow();
        ContainerEntry entry = container.findEntry("/kernel").orElseThrow();
        assertThat(entry.size()).isEqualTo(2048);
        try (InputStream is = entry.openStream()) {
            byte[] bytes = is.readAllBytes();
            assertThat(bytes).hasSize(2048);
            // Kernel payload marker is at offset 1024 within the kernel page.
            assertThat(new String(bytes, 1024, 14)).isEqualTo("KERNEL-PAYLOAD");
        }
    }

    @Test
    void exposesRamdisk() throws IOException {
        BinaryContainer container = AndroidBootContainer.open(Path.of(FIXTURE)).orElseThrow();
        ContainerEntry entry = container.findEntry("/ramdisk").orElseThrow();
        assertThat(entry.size()).isEqualTo(1024);
        try (InputStream is = entry.openStream()) {
            byte[] bytes = is.readAllBytes();
            assertThat(new String(bytes, 0, 15)).isEqualTo("RAMDISK-PAYLOAD");
        }
    }

    @Test
    void exposesSecond() throws IOException {
        BinaryContainer container = AndroidBootContainer.open(Path.of(FIXTURE)).orElseThrow();
        ContainerEntry entry = container.findEntry("/second").orElseThrow();
        assertThat(entry.size()).isEqualTo(19);
        try (InputStream is = entry.openStream()) {
            byte[] bytes = is.readAllBytes();
            assertThat(new String(bytes, 0, 19)).isEqualTo("SECOND-STAGE-LOADER");
        }
    }

    @Test
    void exposesDtb() throws IOException {
        BinaryContainer container = AndroidBootContainer.open(Path.of(FIXTURE)).orElseThrow();
        ContainerEntry entry = container.findEntry("/dtb").orElseThrow();
        assertThat(entry.size()).isEqualTo(256);
        try (InputStream is = entry.openStream()) {
            byte[] bytes = is.readAllBytes();
            assertThat(new String(bytes, 0, 11)).isEqualTo("DTB-PAYLOAD");
        }
    }

    @Test
    void handlesMissingSecond() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // second_size at offset 24, little-endian uint32.
        fixture[24] = 0;
        fixture[25] = 0;
        fixture[26] = 0;
        fixture[27] = 0;
        BinaryContainer container = AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length).orElseThrow();
        assertThat(container.findEntry("/second")).isEmpty();
    }

    @Test
    void handlesV0WithoutDtb() throws IOException {
        // Create a v0 header: set header_version to 0 and dtb/recovery fields to 0.
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        fixture[40] = 0; // header_version = 0
        fixture[41] = 0;
        fixture[42] = 0;
        fixture[43] = 0;
        fixture[1632] = 0; // recovery_dtbo_size = 0
        fixture[1633] = 0;
        fixture[1634] = 0;
        fixture[1635] = 0;
        fixture[1648] = 0; // dtb_size = 0
        fixture[1649] = 0;
        fixture[1650] = 0;
        fixture[1651] = 0;
        BinaryContainer container = AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length).orElseThrow();
        assertThat(container.findEntry("/dtb")).isEmpty();
        assertThat(container.findEntry("/second")).isPresent();
    }

    @Test
    void handlesV1WithoutDtb() throws IOException {
        // Create a v1 header: set header_version to 1, dtb_size to 0, and header_size to 1648.
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        fixture[40] = 1; // header_version = 1
        fixture[41] = 0;
        fixture[42] = 0;
        fixture[43] = 0;
        fixture[1648] = 0; // dtb_size = 0
        fixture[1649] = 0;
        fixture[1650] = 0;
        fixture[1651] = 0;
        fixture[1644] = (byte) 0x70; // header_size = 1648 (0x670)
        fixture[1645] = 0x06;
        fixture[1646] = 0x00;
        fixture[1647] = 0x00;
        BinaryContainer container = AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length).orElseThrow();
        assertThat(container.findEntry("/dtb")).isEmpty();
        assertThat(container.findEntry("/second")).isPresent();
    }

    @Test
    void rawEntryEqualsInput() throws IOException {
        byte[] original = Files.readAllBytes(Path.of(FIXTURE));
        BinaryContainer container = AndroidBootContainer.open(Path.of(FIXTURE)).orElseThrow();
        ContainerEntry raw = container.findEntry("/raw").orElseThrow();
        try (InputStream is = raw.openStream()) {
            assertThat(is.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void independentStreams() throws IOException {
        BinaryContainer container = AndroidBootContainer.open(Path.of(FIXTURE)).orElseThrow();
        ContainerEntry kernel = container.findEntry("/kernel").orElseThrow();
        try (InputStream a = kernel.openStream();
             InputStream b = kernel.openStream()) {
            a.skip(1024);
            byte[] fromA = a.readNBytes(14);
            byte[] fromB = b.readNBytes(14);
            assertThat(new String(fromA)).isEqualTo("KERNEL-PAYLOAD");
            assertThat(new String(fromB)).isEqualTo("\0\0\0\0\0\0\0\0\0\0\0\0\0\0");
        }
    }

    @Test
    void noNullEntries() throws IOException {
        BinaryContainer container = AndroidBootContainer.open(Path.of(FIXTURE)).orElseThrow();
        List<ContainerEntry> entries = container.entries();
        assertThat(entries).doesNotContainNull();
        for (ContainerEntry entry : entries) {
            assertThat(entry.name()).isNotNull();
            assertThat(entry.metadata()).isNotNull();
        }
    }

    @Test
    void rejectsComponentBeyondFile() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // kernel_size at offset 8, little-endian uint32. Set it to a huge value.
        fixture[8] = (byte) 0xff;
        fixture[9] = (byte) 0xff;
        fixture[10] = (byte) 0xff;
        fixture[11] = (byte) 0x7f;
        Optional<BinaryContainer> container = AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length);
        assertThat(container).isEmpty();
    }

    @Test
    void opensFromVirtualDisk() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(FIXTURE));
        VirtualDisk disk = new ByteArrayVirtualDisk(data);
        BinaryContainer container = AndroidBootContainer.open(disk).orElseThrow();
        assertThat(container.findEntry("/kernel")).isPresent();
        assertThat(container.findEntry("/dtb")).isPresent();
    }

    @Test
    void largeFileDoesNotLoadWholeFile() throws IOException {
        Path large = Path.of("/tmp/android-boot-large.img");
        try {
            byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
            // Create a sparse 1 GB file with a valid header and tiny kernel at the start.
            try (var channel = java.nio.channels.FileChannel.open(large,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.SPARSE)) {
                channel.write(ByteBuffer.wrap(fixture));
                channel.position(1024L * 1024 * 1024);
                channel.write(ByteBuffer.wrap(new byte[1]));
            }
            BinaryContainer container = AndroidBootContainer.open(large).orElseThrow();
            assertThat(container.size()).isEqualTo(1024L * 1024 * 1024 + 1);
            ContainerEntry kernel = container.findEntry("/kernel").orElseThrow();
            try (InputStream is = kernel.openStream()) {
                byte[] bytes = is.readNBytes(14);
                // First bytes of kernel are zeros; this proves we did not read the whole file.
                assertThat(bytes).containsOnly(0);
            }
        } finally {
            Files.deleteIfExists(large);
        }
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
        public java.io.InputStream openStream() {
            return new java.io.ByteArrayInputStream(data);
        }

        @Override
        public com.github.packageurl.PackageURL packageUrl() {
            try {
                return new com.github.packageurl.PackageURL("pkg:vmdisk/raw/android-boot@1.0");
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
