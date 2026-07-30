/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.linuxkernel;

import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests extraction of optional kernel components (initramfs cpio, DTB,
 * X.509 certificates) from synthetic kernel images.
 */
class LinuxKernelExtractionTest {

    /**
     * Verifies that a cpio initramfs archive embedded in a gzip-compressed
     * bzImage payload is exposed as {@code /initramfs}.
     */
    @Test
    void extractsInitramfsFromBzImagePayload() throws IOException {
        byte[] cpio = makeCpioArchive("hello.txt", "hello, world");
        byte[] payload = gzipBytes(cpio);
        byte[] image = makeBzImage(payload);

        FileSystem fs = mount(image);

        Optional<FileSystemEntry> initramfs = fs.resolve("/initramfs");
        assertThat(initramfs).isPresent();
        byte[] raw = ((FileSystemEntry.RegularFile) initramfs.get()).readAllBytes();
        assertThat(containsCpioEntry(raw, "hello.txt")).isTrue();
    }

    /**
     * Verifies that a PEM certificate embedded in a gzip-compressed U-Boot
     * uImage payload is exposed as {@code /certificates}.
     */
    @Test
    void extractsCertificateFromUImagePayload() throws IOException {
        byte[] cert = makePemCertificate("Synthetic Cert");
        byte[] payload = padTo(gzipBytes(cert), 512);
        byte[] image = makeUImage(1, payload); // 1 = gzip compression

        FileSystem fs = mount(image);

        Optional<FileSystemEntry> certs = fs.resolve("/certificates");
        assertThat(certs).isPresent();
        String text = new String(((FileSystemEntry.RegularFile) certs.get()).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(text).contains("BEGIN CERTIFICATE").contains("Synthetic Cert");
    }

    private static FileSystem mount(byte[] image) throws IOException {
        return BinaryContainerMount.mount(new TestRawDisk(image))
                .orElseThrow(() -> new AssertionError("Expected kernel container to mount"));
    }

    private static byte[] makeBzImage(byte[] payload) {
        // The bzImage real-mode header must be large enough to contain the
        // payload_length field at offset 0x24c. Use two setup sectors so the
        // setup region is 1024 bytes and write the actual compressed payload size.
        byte[] header = new byte[1024];
        header[0] = 'M';
        header[1] = 'Z';
        header[0x1f1] = 1; // setup_sects = 1 => payload at 1024
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(0x24c, payload.length);
        header[510] = (byte) 0x55;
        header[511] = (byte) 0xAA;
        return concat(header, padTo(payload, 512));
    }

    private static byte[] makeUImage(int compression, byte[] payload) {
        byte[] header = new byte[64];
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0, 0x27051956); // uImage magic
        buf.putInt(12, payload.length); // data size
        buf.put(34, (byte) compression);
        return concat(header, payload);
    }

    private static byte[] makeCpioArchive(String name, String content) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             CpioArchiveOutputStream cpio = new CpioArchiveOutputStream(out, "newc")) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            CpioArchiveEntry entry = new CpioArchiveEntry(name);
            entry.setSize(data.length);
            cpio.putArchiveEntry(entry);
            cpio.write(data);
            cpio.closeArchiveEntry();
            cpio.finish();
            return out.toByteArray();
        }
    }

    private static boolean containsCpioEntry(byte[] archive, String name) throws IOException {
        try (CpioArchiveInputStream cpio = new CpioArchiveInputStream(new ByteArrayInputStream(archive))) {
            CpioArchiveEntry entry;
            while ((entry = cpio.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] makeDtb(int totalSize) {
        byte[] dtb = new byte[totalSize];
        ByteBuffer.wrap(dtb).order(ByteOrder.BIG_ENDIAN).putInt(0, 0xd00dfeed);
        ByteBuffer.wrap(dtb).order(ByteOrder.BIG_ENDIAN).putInt(4, totalSize);
        return dtb;
    }

    private static byte[] makePemCertificate(String body) {
        return ("-----BEGIN CERTIFICATE-----\n"
                + body + "\n"
                + "-----END CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] gzipBytes(byte[] data) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
            gz.finish();
            return out.toByteArray();
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] padTo(byte[] data, int minLength) {
        if (data.length >= minLength) {
            return data;
        }
        byte[] padded = new byte[minLength];
        System.arraycopy(data, 0, padded, 0, data.length);
        return padded;
    }

    /**
     * Minimal VirtualDisk backed by a byte array.
     */
    static class TestRawDisk implements io.spicelabs.saffron.VirtualDisk.RawDisk {
        private final byte[] data;

        TestRawDisk(byte[] data) {
            this.data = data.clone();
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            byte[] result = new byte[length];
            int available = (int) Math.max(0, Math.min(length, data.length - offset));
            if (available > 0 && offset >= 0) {
                System.arraycopy(data, (int) offset, result, 0, available);
            }
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
        public InputStream openStream() {
            return new ByteArrayInputStream(data);
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
        public void close() {}
    }
}
