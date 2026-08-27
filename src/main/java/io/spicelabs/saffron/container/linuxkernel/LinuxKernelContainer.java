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
import io.spicelabs.saffron.io.ChunkedDisk;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 *
 * <p>The container can be backed either by an in-memory byte array or by a
 * {@link ChunkedDisk} (bounded reads — the artifact is never loaded as a
 * whole). Entries stream from the backing source on demand.
 */
final class LinuxKernelContainer implements BinaryContainer {

    private static final byte[] GZIP_MAGIC = {(byte) 0x1f, (byte) 0x8b};
    private static final byte[] CPIO_NEWC_MAGIC = "070701".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CPIO_CRC_MAGIC = "070702".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_CERTIFICATE = "-----END CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final int DTB_MAGIC = 0xd00d_feed;

    private final byte[] data;          // in-memory backing (may be null)
    private final ChunkedDisk disk;     // bounded disk backing (may be null)
    private final int length;
    private final KernelType type;
    private final long payloadOffset;
    private final long payloadSize;

    /**
     * Memoized entry list and name index. The container is immutable
     * (never rebound to a different source), so the memoized list is
     * valid for the container's lifetime. Computed lazily because the
     * scans (gzip detection, decompression, appended-region sweeps) are
     * expensive — especially over a chunked disk.
     */
    private volatile List<ContainerEntry> memoizedEntries;
    private volatile Map<String, ContainerEntry> memoizedEntryByName;

    LinuxKernelContainer(byte[] data) {
        this.data = data.clone();
        this.disk = null;
        this.length = this.data.length;
        this.type = LinuxKernelContainerFactory.detectType(this.data, this.data.length);
        this.payloadOffset = computePayloadOffset(this.data, type);
        this.payloadSize = computePayloadSize(this.data, type, payloadOffset, this.data.length);
    }

    LinuxKernelContainer(@NotNull ChunkedDisk disk) throws IOException {
        this.data = null;
        this.disk = disk;
        this.length = (int) disk.size();
        byte[] header = disk.copyRange(0, 512);
        this.type = LinuxKernelContainerFactory.detectType(header, disk.size());
        this.payloadOffset = computePayloadOffsetDisk(disk, type);
        this.payloadSize = computePayloadSizeDisk(disk, type, payloadOffset, disk.size());
    }

    private int len() {
        return length;
    }

    private int get(int i) throws IOException {
        if (data != null) {
            return data[i] & 0xff;
        }
        return disk.get(i);
    }

    private int getInt(int i, ByteOrder order) throws IOException {
        if (data != null) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data, i, 4).order(order);
            return buf.getInt(0);
        }
        return disk.getInt(i, order);
    }

    private InputStream stream(int off, int len) {
        if (data != null) {
            return new ByteArrayInputStream(data, off, len);
        }
        return disk.stream(off, len);
    }

    private byte[] copy(int off, int len) throws IOException {
        if (data != null) {
            return Arrays.copyOfRange(data, off, off + len);
        }
        return disk.copyRange(off, len);
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.LINUX_KERNEL;
    }

    @Override
    public @NotNull List<ContainerEntry> entries() {
        List<ContainerEntry> result = memoizedEntries;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            result = memoizedEntries;
            if (result != null) {
                return result;
            }
            result = computeEntries();
            Map<String, ContainerEntry> byName = new LinkedHashMap<>();
            for (ContainerEntry entry : result) {
                byName.putIfAbsent(entry.name(), entry);
            }
            memoizedEntryByName = Collections.unmodifiableMap(byName);
            memoizedEntries = result;
            return result;
        }
    }

    private @NotNull List<ContainerEntry> computeEntries() {
        List<ContainerEntry> entries = new ArrayList<>();
        entries.add(new KernelPayloadEntry());
        findConfigGz().ifPresent(entries::add);

        Optional<byte[]> decompressedPayload = decompressPayload(type, payloadOffset, payloadSize);
        if (decompressedPayload.isPresent()) {
            byte[] payload = decompressedPayload.get();
            findInitramfs(payload).ifPresent(entries::add);
            findDtb(payload).ifPresent(entries::add);
            findCertificates(payload).ifPresent(entries::add);
        }

        // Real kernels often append a DTB and X.509 certificates after the
        // compressed payload region. Scan the trailing bytes for them.
        long payloadEnd = payloadOffset + payloadSize;
        if (payloadEnd < len()) {
            findDtbAppended(payloadEnd).ifPresent(entries::add);
            findCertificatesAppended(payloadEnd).ifPresent(entries::add);
        }

        return Collections.unmodifiableList(entries);
    }

    @Override
    public @NotNull Optional<ContainerEntry> findEntry(@NotNull String path) {
        // Populate the memoized list once, then use the name index.
        entries();
        Map<String, ContainerEntry> byName = memoizedEntryByName;
        if (byName != null) {
            return Optional.ofNullable(byName.get(path));
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
        return len();
    }

    private @NotNull Optional<ContainerEntry> findConfigGz() {
        try {
            int offset = findGzipStreamWithConfig();
            if (offset < 0) {
                return Optional.empty();
            }
            int size = gzipMemberSize(offset);
            if (size <= 0) {
                return Optional.empty();
            }
            return Optional.of(new SlicedEntry("/config.gz", offset, size));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private @NotNull Optional<byte[]> decompressPayload(KernelType type, long payloadOffset,
                                                        long payloadSize) {
        try {
            if (payloadOffset < 0 || payloadOffset > len() || payloadSize < 0
                    || payloadOffset + payloadSize > len()) {
                return Optional.empty();
            }
            int offset = (int) payloadOffset;
            int length = (int) payloadSize;
            byte[] payload = copy(offset, length);

            return switch (type) {
                case BZIMAGE, GZIP_IMAGE -> KernelDecompressor.decompress(payload, 0, length);
                case UIMAGE -> {
                    int compression = uImageCompression();
                    if (compression == 0) {
                        yield Optional.empty();
                    }
                    yield KernelDecompressor.decompressUImage(payload, 0, length, compression);
                }
                case IMAGE, ZIMAGE, UNKNOWN -> Optional.empty();
            };
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private int uImageCompression() throws IOException {
        if (len() < 36) {
            return 0;
        }
        return get(34);
    }

    private int uImageDataSizeDisk() throws IOException {
        if (len() < 12) {
            return 0;
        }
        return getInt(12, ByteOrder.BIG_ENDIAN);
    }

    private int bzImageSetupSectsDisk() throws IOException {
        if (len() < 0x1f2) {
            return 0;
        }
        return get(0x1f1);
    }

    private long bzImageSetupSizeDisk() throws IOException {
        return (bzImageSetupSectsDisk() + 1L) * 512L;
    }

    private long bzImagePayloadLengthDisk() throws IOException {
        if (bzImageSetupSizeDisk() < 0x250) {
            return -1;
        }
        long length = getInt(0x24c, ByteOrder.LITTLE_ENDIAN) & 0xffffffffL;
        return length > 0 ? length : -1;
    }

    private long arm64ImageSizeDisk() throws IOException {
        if (len() < 0x10) {
            return -1;
        }
        long size = getInt(0x08, ByteOrder.LITTLE_ENDIAN) & 0xffffffffL;
        return size > 0 ? size : -1;
    }

    private long computePayloadSizeDisk(ChunkedDisk disk, KernelType type, long payloadOffset,
                                        long sourceSize) {
        long fallback = Math.max(0, sourceSize - payloadOffset);
        return switch (type) {
            case BZIMAGE -> {
                try {
                    long length = bzImagePayloadLengthDisk();
                    yield length > 0 ? length : fallback;
                } catch (IOException e) {
                    yield fallback;
                }
            }
            case UIMAGE -> {
                try {
                    long length = uImageDataSizeDisk() & 0xffffffffL;
                    yield length > 0 ? Math.min(length, fallback) : fallback;
                } catch (IOException e) {
                    yield fallback;
                }
            }
            case IMAGE, GZIP_IMAGE -> {
                try {
                    long size = arm64ImageSizeDisk();
                    yield size > 0 ? Math.min(size, fallback) : fallback;
                } catch (IOException e) {
                    yield fallback;
                }
            }
            case ZIMAGE, UNKNOWN -> fallback;
        };
    }

    private long computePayloadOffsetDisk(ChunkedDisk disk, KernelType type) {
        return switch (type) {
            case UIMAGE -> 64; // U-Boot uImage header is 64 bytes
            case BZIMAGE -> {
                try {
                    yield Math.min(bzImageSetupSizeDisk(), len());
                } catch (IOException e) {
                    yield 0;
                }
            }
            case ZIMAGE, IMAGE, GZIP_IMAGE, UNKNOWN -> 0;
        };
    }

    private static long computePayloadSize(byte[] data, KernelType type, long payloadOffset,
                                           long sourceSize) {
        long fallback = Math.max(0, sourceSize - payloadOffset);
        return switch (type) {
            case BZIMAGE -> {
                long length = bzImagePayloadLengthBytes(data);
                yield length > 0 ? length : fallback;
            }
            case UIMAGE -> {
                long length = uImageDataSizeBytes(data) & 0xffffffffL;
                yield length > 0 ? Math.min(length, fallback) : fallback;
            }
            case IMAGE, GZIP_IMAGE -> {
                long size = arm64ImageSizeBytes(data);
                yield size > 0 ? Math.min(size, fallback) : fallback;
            }
            case ZIMAGE, UNKNOWN -> fallback;
        };
    }

    private static long computePayloadOffset(byte[] data, KernelType type) {
        return switch (type) {
            case UIMAGE -> 64; // U-Boot uImage header is 64 bytes
            case BZIMAGE -> Math.min(bzImageSetupSizeBytes(data), data.length);
            case ZIMAGE, IMAGE, GZIP_IMAGE, UNKNOWN -> 0;
        };
    }

    private static int uImageDataSizeBytes(byte[] data) {
        if (data.length < 12) {
            return 0;
        }
        return java.nio.ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt(12);
    }

    private static int bzImageSetupSectsBytes(byte[] data) {
        if (data.length < 0x1f2) {
            return 0;
        }
        return data[0x1f1] & 0xFF;
    }

    private static long bzImageSetupSizeBytes(byte[] data) {
        return (bzImageSetupSectsBytes(data) + 1L) * 512L;
    }

    private static long bzImagePayloadLengthBytes(byte[] data) {
        if (bzImageSetupSizeBytes(data) < 0x250) {
            return -1;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long length = buf.getInt(0x24c) & 0xffffffffL;
        return length > 0 ? length : -1;
    }

    private static long arm64ImageSizeBytes(byte[] data) {
        if (data.length < 0x10) {
            return -1;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long size = buf.getLong(0x08);
        return size > 0 ? size : -1;
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
            int magic = java.nio.ByteBuffer.wrap(decompressed).order(ByteOrder.BIG_ENDIAN).getInt(i);
            if (magic == DTB_MAGIC) {
                int totalSize = java.nio.ByteBuffer.wrap(decompressed).order(ByteOrder.BIG_ENDIAN).getInt(i + 4);
                if (totalSize >= 40 && i + totalSize <= decompressed.length) {
                    return Optional.of(new BytesEntry("/dtb", Arrays.copyOfRange(decompressed, i, i + totalSize)));
                }
            }
        }
        return Optional.empty();
    }

    private @NotNull Optional<ContainerEntry> findDtbAppended(long start) {
        try {
            for (int i = (int) start; i <= len() - 8; i++) {
                if (getInt(i, ByteOrder.BIG_ENDIAN) == DTB_MAGIC) {
                    int totalSize = getInt(i + 4, ByteOrder.BIG_ENDIAN);
                    if (totalSize >= 40 && i + totalSize <= len()) {
                        int from = i;
                        int to = i + totalSize;
                        return Optional.of(new SlicedEntry("/dtb", from, to - from));
                    }
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return Optional.empty();
    }

    private @NotNull Optional<ContainerEntry> findCertificatesAppended(long start) {
        try {
            int i = (int) start;
            int firstBegin = -1;
            int lastEnd = -1;
            int count = 0;
            while (i <= len() - BEGIN_CERTIFICATE.length) {
                int begin = indexOf(i, BEGIN_CERTIFICATE);
                if (begin < 0) {
                    break;
                }
                int end = indexOf(begin + BEGIN_CERTIFICATE.length, END_CERTIFICATE);
                if (end < 0) {
                    break;
                }
                end += END_CERTIFICATE.length;
                if (firstBegin < 0) {
                    firstBegin = begin;
                }
                lastEnd = end;
                count++;
                i = end;
            }
            if (count == 0) {
                return Optional.empty();
            }
            return Optional.of(new SlicedEntry("/certificates", firstBegin, lastEnd - firstBegin));
        } catch (IOException e) {
            return Optional.empty();
        }
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

    private int indexOf(int fromIndex, byte[] pattern) throws IOException {
        outer:
        for (int i = fromIndex; i <= len() - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (get(i + j) != (pattern[j] & 0xff)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
    private int findGzipStreamWithConfig() throws IOException {
        byte[] marker = "CONFIG_".getBytes();
        for (int i = 0; i < len() - 2; i++) {
            if (get(i) == (GZIP_MAGIC[0] & 0xff) && get(i + 1) == (GZIP_MAGIC[1] & 0xff)) {
                if (i + 2 < len() && get(i + 2) != 0x08) {
                    continue; // not deflate
                }
                try (GZIPInputStream gz = new GZIPInputStream(stream(i, len() - i))) {
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
    private int gzipMemberSize(int offset) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(stream(offset, len() - offset))) {
            byte[] buf = new byte[8192];
            while (gz.read(buf) >= 0) {
                // Drain the member to find its end.
            }
            for (int i = offset + 2; i < len() - 1; i++) {
                if (get(i) == (GZIP_MAGIC[0] & 0xff) && get(i + 1) == (GZIP_MAGIC[1] & 0xff)) {
                    return i - offset;
                }
            }
            return len() - offset;
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
            return stream((int) payloadOffset, (int) payloadSize);
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
            return stream(offset, length);
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
