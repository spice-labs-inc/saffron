/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.linuxkernel;

import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.container.linuxkernel.LinuxKernelContainerFactory.KernelType;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * A Linux kernel image exposed as a binary container.
 */
final class LinuxKernelContainer implements BinaryContainer {

    private static final byte[] GZIP_MAGIC = {(byte) 0x1f, (byte) 0x8b};
    private static final byte[] CPIO_NEWC_MAGIC = "070701".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CPIO_CRC_MAGIC = "070702".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_CERTIFICATE = "-----END CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final int DTB_MAGIC = 0xd00d_feed;

    private final byte[] data;
    private final KernelType type;
    private final long payloadOffset;
    private final long payloadSize;

    LinuxKernelContainer(byte[] data) {
        this.data = data.clone();
        this.type = LinuxKernelContainerFactory.detectType(data, data.length);
        this.payloadOffset = computePayloadOffset(data, type);
        this.payloadSize = computePayloadSize(data, type, payloadOffset, data.length);
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.LINUX_KERNEL;
    }

    @Override
    public @NotNull List<ContainerEntry> entries() {
        List<ContainerEntry> entries = new ArrayList<>();
        entries.add(new KernelPayloadEntry());
        findConfigGz().ifPresent(entries::add);

        Optional<byte[]> decompressedPayload = decompressPayload(data, type, payloadOffset, payloadSize);
        if (decompressedPayload.isPresent()) {
            byte[] payload = decompressedPayload.get();
            findInitramfs(payload).ifPresent(entries::add);
            findDtb(payload).ifPresent(entries::add);
            findCertificates(payload).ifPresent(entries::add);
        }

        // Real kernels often append a DTB and X.509 certificates after the
        // compressed payload region. Scan any raw bytes following the declared
        // payload end for these optional components.
        long payloadEnd = Math.addExact(payloadOffset, payloadSize);
        if (payloadEnd < data.length) {
            byte[] appended = safeCopy(data, (int) payloadEnd, (int) (data.length - payloadEnd));
            findDtb(appended).ifPresent(entries::add);
            findCertificates(appended).ifPresent(entries::add);
        }

        return Collections.unmodifiableList(entries);
    }

    @Override
    public @NotNull Optional<ContainerEntry> findEntry(@NotNull String path) {
        for (ContainerEntry entry : entries()) {
            if (entry.name().equals(path)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("format", "linux_kernel");
        meta.put("kernel_type", type.name().toLowerCase());
        meta.put("payload_offset", Long.toString(payloadOffset));
        meta.put("payload_size", Long.toString(payloadSize));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public long size() {
        return data.length;
    }

    private @NotNull Optional<ContainerEntry> findConfigGz() {
        int offset = findGzipStreamWithConfig(data);
        if (offset < 0) {
            return Optional.empty();
        }
        int size = gzipMemberSize(data, offset);
        if (size <= 0) {
            return Optional.empty();
        }
        return Optional.of(new SlicedEntry("/config.gz", offset, size));
    }

    /**
     * Returns the decompressed payload bytes, or empty if the payload is not
     * compressed or decompression fails.
     */
    private static @NotNull Optional<byte[]> decompressPayload(byte[] data, KernelType type, long payloadOffset, long payloadSize) {
        long offsetLong = payloadOffset;
        long lengthLong = payloadSize;
        if (offsetLong < 0 || offsetLong > data.length || lengthLong < 0 || offsetLong + lengthLong > data.length) {
            return Optional.empty();
        }
        int offset = (int) offsetLong;
        int length = (int) lengthLong;

        return switch (type) {
            case BZIMAGE, GZIP_IMAGE -> KernelDecompressor.decompress(data, offset, length);
            case UIMAGE -> {
                int compression = uImageCompression(data);
                if (compression == 0) {
                    yield Optional.empty();
                }
                yield KernelDecompressor.decompressUImage(data, offset, length, compression);
            }
            case IMAGE, ZIMAGE, UNKNOWN -> Optional.empty();
        };
    }

    private static byte[] safeCopy(byte[] data, int offset, int length) {
        if (offset < 0 || length <= 0 || offset > data.length) {
            return new byte[0];
        }
        int end = Math.min(offset + length, data.length);
        return Arrays.copyOfRange(data, offset, end);
    }

    private static int uImageCompression(byte[] data) {
        if (data.length < 36) {
            return 0;
        }
        return data[34] & 0xFF;
    }

    private static int uImageDataSize(byte[] data) {
        if (data.length < 12) {
            return 0;
        }
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt(12);
    }

    private static int bzImageSetupSects(byte[] data) {
        if (data.length < 0x1f2) {
            return 0;
        }
        return data[0x1f1] & 0xFF;
    }

    private static long bzImageSetupSize(byte[] data) {
        return (bzImageSetupSects(data) + 1L) * 512L;
    }

    private static long bzImagePayloadLength(byte[] data) {
        // payload_length is at offset 0x24c in the bzImage header.
        // Only trust this field when the setup region is large enough to contain it.
        if (bzImageSetupSize(data) < 0x250) {
            return -1;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long length = buf.getInt(0x24c) & 0xffffffffL;
        return length > 0 ? length : -1;
    }

    private static long arm64ImageSize(byte[] data) {
        // image_size is at offset 0x08 in the ARM64 Image header.
        if (data.length < 0x10) {
            return -1;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long size = buf.getLong(0x08);
        return size > 0 ? size : -1;
    }

    private static long computePayloadSize(byte[] data, KernelType type, long payloadOffset, long sourceSize) {
        long fallback = Math.max(0, sourceSize - payloadOffset);
        return switch (type) {
            case BZIMAGE -> {
                long length = bzImagePayloadLength(data);
                yield length > 0 ? length : fallback;
            }
            case UIMAGE -> {
                long length = uImageDataSize(data) & 0xffffffffL;
                yield length > 0 ? Math.min(length, fallback) : fallback;
            }
            case IMAGE, GZIP_IMAGE -> {
                long size = arm64ImageSize(data);
                yield size > 0 ? Math.min(size, fallback) : fallback;
            }
            case ZIMAGE, UNKNOWN -> fallback;
        };
    }

    private static long computePayloadOffset(byte[] data, KernelType type) {
        return switch (type) {
            case UIMAGE -> 64; // U-Boot uImage header is 64 bytes
            case BZIMAGE -> Math.min(bzImageSetupSize(data), data.length);
            case ZIMAGE, IMAGE, GZIP_IMAGE, UNKNOWN -> 0;
        };
    }

    private static @NotNull Optional<ContainerEntry> findInitramfs(byte[] decompressed) {
        for (int i = 0; i <= decompressed.length - CPIO_NEWC_MAGIC.length; i++) {
            if (isCpioMagic(decompressed, i)) {
                Optional<byte[]> archive = extractCpioArchive(decompressed, i);
                if (archive.isPresent() && archive.get().length > 0) {
                    return Optional.of(new BytesEntry("/initramfs", archive.get()));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isCpioMagic(byte[] data, int offset) {
        return matches(data, offset, CPIO_NEWC_MAGIC) || matches(data, offset, CPIO_CRC_MAGIC);
    }

    private static boolean matches(byte[] data, int offset, byte[] pattern) {
        if (offset < 0 || offset + pattern.length > data.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull Optional<byte[]> extractCpioArchive(byte[] data, int start) {
        CountingInputStream counter = new CountingInputStream(new ByteArrayInputStream(data, start, data.length - start));
        try (CpioArchiveInputStream cpio = new CpioArchiveInputStream(counter)) {
            Optional<CpioArchiveEntry> entry;
            while ((entry = nextEntry(cpio)).isPresent()) {
                if ("TRAILER!!!".equals(entry.get().getName())) {
                    break;
                }
                long remaining = entry.get().getSize();
                while (remaining > 0) {
                    long skipped = cpio.skip(remaining);
                    if (skipped <= 0) {
                        break;
                    }
                    remaining -= skipped;
                }
            }
            int count = counter.getCount();
            if (count <= 0) {
                return Optional.empty();
            }
            int end = Math.min(data.length, start + count);
            return Optional.of(Arrays.copyOfRange(data, start, end));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static @NotNull Optional<CpioArchiveEntry> nextEntry(@NotNull CpioArchiveInputStream cpio) throws IOException {
        CpioArchiveEntry entry = cpio.getNextEntry();
        return entry == null ? Optional.empty() : Optional.of(entry);
    }

    private static @NotNull Optional<ContainerEntry> findDtb(byte[] decompressed) {
        for (int i = 0; i <= decompressed.length - 8; i++) {
            if (ByteBuffer.wrap(decompressed).order(ByteOrder.BIG_ENDIAN).getInt(i) == DTB_MAGIC) {
                int totalSize = ByteBuffer.wrap(decompressed).order(ByteOrder.BIG_ENDIAN).getInt(i + 4);
                if (totalSize >= 40 && i + totalSize <= decompressed.length) {
                    return Optional.of(new BytesEntry("/dtb", Arrays.copyOfRange(decompressed, i, i + totalSize)));
                }
            }
        }
        return Optional.empty();
    }

    private static @NotNull Optional<ContainerEntry> findCertificates(byte[] decompressed) {
        List<byte[]> certs = new ArrayList<>();
        int i = 0;
        while (i <= decompressed.length - BEGIN_CERTIFICATE.length) {
            int begin = indexOf(decompressed, BEGIN_CERTIFICATE, i);
            if (begin < 0) {
                break;
            }
            int end = indexOf(decompressed, END_CERTIFICATE, begin + BEGIN_CERTIFICATE.length);
            if (end < 0) {
                break;
            }
            end += END_CERTIFICATE.length;
            certs.add(Arrays.copyOfRange(decompressed, begin, end));
            i = end;
        }
        if (certs.isEmpty()) {
            return Optional.empty();
        }
        if (certs.size() == 1) {
            return Optional.of(new BytesEntry("/certificates", certs.get(0)));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int j = 0; j < certs.size(); j++) {
            if (j > 0) {
                out.write('\n');
            }
            out.write(certs.get(j), 0, certs.get(j).length);
        }
        return Optional.of(new BytesEntry("/certificates", out.toByteArray()));
    }

    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Scans the kernel image for a gzip member whose decompressed content
     * contains the {@code CONFIG_} marker used by {@code config.gz}.
     */
    private static int findGzipStreamWithConfig(byte[] data) {
        byte[] marker = "CONFIG_".getBytes();
        for (int i = 0; i < data.length - 2; i++) {
            if (data[i] == GZIP_MAGIC[0] && data[i + 1] == GZIP_MAGIC[1]) {
                if (i + 2 < data.length && data[i + 2] != 0x08) {
                    continue; // not deflate
                }
                try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data, i, data.length - i))) {
                    if (streamContains(gz, marker)) {
                        return i;
                    }
                } catch (IOException | IllegalArgumentException e) {
                    // Not a valid config.gz stream; keep scanning
                }
            }
        }
        return -1;
    }

    /**
     * Returns true if the input stream contains the given byte sequence,
     * using a sliding window to detect markers that span read boundaries.
     */
    private static boolean streamContains(InputStream in, byte[] marker) throws IOException {
        byte[] window = new byte[marker.length];
        int filled = 0;
        int b;
        while ((b = in.read()) >= 0) {
            if (filled < marker.length) {
                window[filled++] = (byte) b;
            } else {
                System.arraycopy(window, 1, window, 0, marker.length - 1);
                window[marker.length - 1] = (byte) b;
            }
            if (filled == marker.length && arrayEquals(window, marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean arrayEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the compressed size of a gzip member starting at {@code offset},
     * or -1 if it cannot be determined safely.
     */
    private static int gzipMemberSize(byte[] data, int offset) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data, offset, data.length - offset))) {
            byte[] buf = new byte[8192];
            while (gz.read(buf) >= 0) {
                // Drain the member to find its end.
            }
            for (int i = offset + 2; i < data.length - 1; i++) {
                if (data[i] == GZIP_MAGIC[0] && data[i + 1] == GZIP_MAGIC[1]) {
                    return i - offset;
                }
            }
            return data.length - offset;
        } catch (IOException e) {
            return -1;
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        private int count = 0;

        CountingInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte @NotNull [] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            count += (int) skipped;
            return skipped;
        }

        int getCount() {
            return count;
        }
    }

    private final class KernelPayloadEntry implements ContainerEntry {
        @Override
        public @NotNull String name() {
            return "/kernel-payload";
        }

        @Override
        public long size() {
            return payloadSize;
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return new ByteArrayInputStream(data, (int) payloadOffset, (int) payloadSize);
        }

        @Override
        public @NotNull Map<String, String> metadata() {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", "payload");
            return Collections.unmodifiableMap(meta);
        }
    }

    private final class SlicedEntry implements ContainerEntry {
        private final String name;
        private final int offset;
        private final int length;

        SlicedEntry(String name, int offset, int length) {
            this.name = name;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public @NotNull String name() {
            return name;
        }

        @Override
        public long size() {
            return length;
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return new ByteArrayInputStream(data, offset, length);
        }

        @Override
        public @NotNull Map<String, String> metadata() {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", "embedded");
            return Collections.unmodifiableMap(meta);
        }
    }

    private static final class BytesEntry implements ContainerEntry {
        private final String name;
        private final byte[] content;

        BytesEntry(String name, byte[] content) {
            this.name = name;
            this.content = content.clone();
        }

        @Override
        public @NotNull String name() {
            return name;
        }

        @Override
        public long size() {
            return content.length;
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return new ByteArrayInputStream(content);
        }

        @Override
        public @NotNull Map<String, String> metadata() {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", "embedded");
            return Collections.unmodifiableMap(meta);
        }
    }
}
