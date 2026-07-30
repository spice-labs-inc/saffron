/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.android;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detection tests for Android boot images.
 */
class AndroidBootContainerDetectionTest {

    private static final String FIXTURE = "src/test/resources/android-boot/boot.img";

    @Test
    void detectsBootImage() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(FIXTURE));
        assertThat(format).hasValue(ContainerFormat.ANDROID_BOOT);
    }

    @Test
    void detectsBootImageFromBuffer() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(FIXTURE));
        Optional<ContainerFormat> format = ContainerDetector.detect(ByteBuffer.wrap(data));
        assertThat(format).hasValue(ContainerFormat.ANDROID_BOOT);
    }

    @Test
    void detectsBootImageFromVirtualDisk() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(FIXTURE));
        VirtualDisk disk = new ByteArrayVirtualDisk(data);
        Optional<ContainerFormat> format = ContainerDetector.detect(disk);
        assertThat(format).hasValue(ContainerFormat.ANDROID_BOOT);
    }

    @Test
    void rejectsRandomData() {
        byte[] random = new byte[4096];
        for (int i = 0; i < random.length; i++) {
            random[i] = (byte) (i * 7 + 13);
        }
        Optional<ContainerFormat> format = ContainerDetector.detect(ByteBuffer.wrap(random));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsTruncatedHeader() {
        byte[] truncated = new byte[10];
        System.arraycopy("ANDROID!".getBytes(), 0, truncated, 0, 8);
        Optional<ContainerFormat> format = ContainerDetector.detect(ByteBuffer.wrap(truncated));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsInvalidMagic() {
        byte[] data = new byte[2048];
        System.arraycopy("NOTROID!".getBytes(), 0, data, 0, 8);
        Optional<ContainerFormat> format = ContainerDetector.detect(ByteBuffer.wrap(data));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsUnsupportedPageSize() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(FIXTURE));
        // Page size is at offset 36 (little-endian uint32). Set it to an invalid value.
        data[36] = 0x01;
        data[37] = 0x00;
        data[38] = 0x00;
        data[39] = 0x00;
        Optional<ContainerFormat> format = ContainerDetector.detect(ByteBuffer.wrap(data));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsUnsupportedVersion() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(FIXTURE));
        // Header version is at offset 40 (little-endian uint32). Set it to 5.
        data[40] = 0x05;
        data[41] = 0x00;
        data[42] = 0x00;
        data[43] = 0x00;
        Optional<ContainerFormat> format = ContainerDetector.detect(ByteBuffer.wrap(data));
        assertThat(format).isEmpty();
    }

    @Test
    void strongerFormatWinsOverAndroidMagic() throws IOException {
        // A real ELF file must be detected as ELF, not Android boot, because ELF is checked first.
        Path elf = Path.of("src/test/resources/elf/libmbedx509.so");
        assertThat(elf).exists();
        Optional<ContainerFormat> format = ContainerDetector.detect(elf);
        assertThat(format).hasValue(ContainerFormat.ELF);
    }

    @Test
    void detectsV3ButContainerRejectsIt() {
        // v3 header: magic, kernel_size, ramdisk_size, os_version, header_size=1580,
        // reserved 4 words, header_version=3, cmdline 1536 bytes.
        byte[] header = new byte[1580];
        System.arraycopy("ANDROID!".getBytes(), 0, header, 0, 8);
        header[8] = 0x01; // kernel_size = 1
        header[16] = 0x01; // ramdisk_size = 1
        header[20] = (byte) 0x2c; // header_size = 1580 (0x62c)
        header[21] = 0x06;
        header[22] = 0x00;
        header[23] = 0x00;
        header[40] = 0x03; // header_version = 3
        Optional<ContainerFormat> detected = ContainerDetector.detect(ByteBuffer.wrap(header));
        assertThat(detected).hasValue(ContainerFormat.ANDROID_BOOT);
        // The container parser rejects v3, so mounting returns empty.
        assertThat(AndroidBootContainer.open(ByteBuffer.wrap(header), header.length)).isEmpty();
    }

    static final class ByteArrayVirtualDisk implements VirtualDisk.RawDisk {
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
