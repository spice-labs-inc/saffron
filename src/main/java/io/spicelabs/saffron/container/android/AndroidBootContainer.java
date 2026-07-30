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
package io.spicelabs.saffron.container.android;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.adapter.ByteArrayInputStreamSource;
import io.spicelabs.saffron.adapter.FileInputStreamSource;
import io.spicelabs.saffron.adapter.InputStreamSource;
import io.spicelabs.saffron.adapter.VirtualDiskInputStreamSource;
import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.io.RegionInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An Android boot image exposed as a Saffron binary container.
 *
 * <p>Supports Android boot image header versions 0, 1, and 2. The container
 * is backed by an {@link InputStreamSource}; each entry is a zero-copy view over
 * a page-aligned region of the source.</p>
 */
public final class AndroidBootContainer implements BinaryContainer {

    private static final int MIN_HEADER_READ = 1660;
    private static final int V0_HEADER_SIZE = 1632;
    private static final int V1_HEADER_SIZE = 1648;
    private static final int V2_HEADER_SIZE = 1660;

    private static final int MAGIC_OFFSET = 0;
    private static final int MAGIC_LENGTH = 8;
    private static final int KERNEL_SIZE_OFFSET = 8;
    private static final int KERNEL_ADDR_OFFSET = 12;
    private static final int RAMDISK_SIZE_OFFSET = 16;
    private static final int RAMDISK_ADDR_OFFSET = 20;
    private static final int SECOND_SIZE_OFFSET = 24;
    private static final int SECOND_ADDR_OFFSET = 28;
    private static final int TAGS_ADDR_OFFSET = 32;
    private static final int PAGE_SIZE_OFFSET = 36;
    private static final int HEADER_VERSION_OFFSET = 40;
    private static final int OS_VERSION_OFFSET = 44;
    private static final int NAME_OFFSET = 48;
    private static final int NAME_LENGTH = 16;
    private static final int CMDLINE_OFFSET = 64;
    private static final int CMDLINE_LENGTH = 512;
    private static final int ID_OFFSET = 576;
    private static final int ID_LENGTH = 32;
    private static final int EXTRA_CMDLINE_OFFSET = 608;
    private static final int EXTRA_CMDLINE_LENGTH = 1024;
    private static final int RECOVERY_DTBO_SIZE_OFFSET = 1632;
    private static final int RECOVERY_DTBO_OFFSET_OFFSET = 1636;
    private static final int HEADER_SIZE_OFFSET = 1644;
    private static final int DTB_SIZE_OFFSET = 1648;
    private static final int DTB_ADDR_OFFSET = 1652;

    private static final byte[] MAGIC = "ANDROID!".getBytes(StandardCharsets.US_ASCII);

    private final long sourceSize;
    private final InputStreamSource source;
    private final Header header;
    private final List<ContainerEntry> entries;
    private final Map<String, ContainerEntry> entryByName;

    private AndroidBootContainer(long sourceSize, @NotNull InputStreamSource source, @NotNull Header header,
                                 @NotNull List<ContainerEntry> entries) {
        this.sourceSize = sourceSize;
        this.source = source;
        this.header = header;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Attempts to open an Android boot container from a file path.
     *
     * @param path the path to examine
     * @return the container, or empty if the file is not a valid Android boot image
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path) throws IOException {
        long size = Files.size(path);
        if (size < MIN_HEADER_READ) {
            return Optional.empty();
        }
        byte[] headerBytes = new byte[MIN_HEADER_READ];
        try (var is = Files.newInputStream(path)) {
            if (is.read(headerBytes) != MIN_HEADER_READ) {
                return Optional.empty();
            }
        }
        Optional<Header> header = parseHeader(ByteBuffer.wrap(headerBytes), size);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        InputStreamSource source = new FileInputStreamSource(path);
        return Optional.of(build(size, source, header.get()));
    }

    /**
     * Attempts to open an Android boot container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @return the container, or empty if the disk is not a valid Android boot image
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size < MIN_HEADER_READ) {
            return Optional.empty();
        }
        ByteBuffer headerBytes = disk.read(0, MIN_HEADER_READ);
        Optional<Header> header = parseHeader(headerBytes, size);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        InputStreamSource source = new VirtualDiskInputStreamSource(disk, size, "android-boot-virtual-disk");
        return Optional.of(build(size, source, header.get()));
    }

    /**
     * Attempts to open an Android boot container from a byte buffer.
     *
     * @param source     the full image bytes; the buffer is not modified
     * @param sourceSize the total size of the image source
     * @return the container, or empty if the buffer is not a valid Android boot image
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull ByteBuffer source, long sourceSize) {
        try {
            if (sourceSize < MIN_HEADER_READ || sourceSize > source.remaining()) {
                return Optional.empty();
            }
            Optional<Header> header = parseHeader(source, sourceSize);
            if (header.isEmpty()) {
                return Optional.empty();
            }
            byte[] bytes = toByteArray(source);
            InputStreamSource streamSource = new ByteArrayInputStreamSource(bytes, getDescription(bytes.length));
            return Optional.of(build(bytes.length, streamSource, header.get()));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static byte @NotNull [] toByteArray(@NotNull ByteBuffer source) {
        ByteBuffer dup = source.duplicate();
        if (dup.hasArray() && !dup.isReadOnly()
                && dup.position() == 0
                && dup.remaining() == dup.array().length) {
            return dup.array();
        }
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return bytes;
    }

    private static @NotNull String getDescription(int length) {
        return "android-boot-byte-buffer[" + length + "]";
    }

    private static @NotNull Optional<Header> parseHeader(@NotNull ByteBuffer buffer, long sourceSize) {
        if (buffer.remaining() < MIN_HEADER_READ) {
            return Optional.empty();
        }
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            if (!isMagic(buffer, MAGIC_OFFSET)) {
                return Optional.empty();
            }

            int pageSize = buffer.getInt(PAGE_SIZE_OFFSET);
            int headerVersion = buffer.getInt(HEADER_VERSION_OFFSET);
            if (!isSupportedPageSize(pageSize) || headerVersion < 0 || headerVersion > 2) {
                return Optional.empty();
            }

            int headerSizeField = buffer.getInt(HEADER_SIZE_OFFSET);
            if (headerVersion == 1 && headerSizeField != V1_HEADER_SIZE) {
                return Optional.empty();
            }
            if (headerVersion == 2 && headerSizeField != V2_HEADER_SIZE) {
                return Optional.empty();
            }

            long kernelSize = Integer.toUnsignedLong(buffer.getInt(KERNEL_SIZE_OFFSET));
            long ramdiskSize = Integer.toUnsignedLong(buffer.getInt(RAMDISK_SIZE_OFFSET));
            long secondSize = Integer.toUnsignedLong(buffer.getInt(SECOND_SIZE_OFFSET));
            long recoveryDtboSize = Integer.toUnsignedLong(buffer.getInt(RECOVERY_DTBO_SIZE_OFFSET));
            long dtbSize = Integer.toUnsignedLong(buffer.getInt(DTB_SIZE_OFFSET));

            if (kernelSize == 0 || ramdiskSize == 0) {
                return Optional.empty();
            }
            if (headerVersion == 0 && (recoveryDtboSize != 0 || dtbSize != 0)) {
                return Optional.empty();
            }
            if (headerVersion == 1 && dtbSize != 0) {
                return Optional.empty();
            }
            if (headerVersion == 2 && (recoveryDtboSize != 0 || dtbSize == 0)) {
                return Optional.empty();
            }

            long kernelAddr = Integer.toUnsignedLong(buffer.getInt(KERNEL_ADDR_OFFSET));
            long ramdiskAddr = Integer.toUnsignedLong(buffer.getInt(RAMDISK_ADDR_OFFSET));
            long secondAddr = Integer.toUnsignedLong(buffer.getInt(SECOND_ADDR_OFFSET));
            long tagsAddr = Integer.toUnsignedLong(buffer.getInt(TAGS_ADDR_OFFSET));
            long dtbAddr = buffer.getLong(DTB_ADDR_OFFSET);
            long recoveryDtboOffset = buffer.getLong(RECOVERY_DTBO_OFFSET_OFFSET);

            String name = readAsciiString(buffer, NAME_OFFSET, NAME_LENGTH);
            String cmdline = readAsciiString(buffer, CMDLINE_OFFSET, CMDLINE_LENGTH);

            long kernelOffset = pageSize;
            long ramdiskOffset = safeAdd(kernelOffset, pageAlign(kernelSize, pageSize));
            long secondOffset = secondSize > 0 ? safeAdd(ramdiskOffset, pageAlign(ramdiskSize, pageSize)) : -1;
            long afterSecond = secondSize > 0
                    ? safeAdd(secondOffset, pageAlign(secondSize, pageSize))
                    : safeAdd(ramdiskOffset, pageAlign(ramdiskSize, pageSize));
            long recoveryDtboOffsetComputed = recoveryDtboSize > 0 ? afterSecond : -1;
            long dtbOffsetComputed = dtbSize > 0 ? afterSecond : -1;

            // Validate that every declared region fits within the source.
            long endOfKernel = safeAdd(kernelOffset, kernelSize);
            long endOfRamdisk = safeAdd(ramdiskOffset, ramdiskSize);
            long endOfSecond = secondSize > 0 ? safeAdd(secondOffset, secondSize) : 0;
            long endOfDtb = dtbSize > 0 ? safeAdd(dtbOffsetComputed, dtbSize) : 0;
            long endOfRecoveryDtbo = recoveryDtboSize > 0 ? safeAdd(recoveryDtboOffsetComputed, recoveryDtboSize) : 0;

            long lastUsed = maxNonNegative(endOfKernel, endOfRamdisk, endOfSecond, endOfDtb, endOfRecoveryDtbo);
            if (lastUsed > sourceSize) {
                return Optional.empty();
            }

            return Optional.of(new Header(headerVersion, pageSize, kernelSize, kernelOffset, kernelAddr,
                    ramdiskSize, ramdiskOffset, ramdiskAddr,
                    secondSize, secondOffset, secondAddr,
                    recoveryDtboSize, recoveryDtboOffset,
                    dtbSize, dtbOffsetComputed, dtbAddr,
                    tagsAddr, name, cmdline));
        } finally {
            buffer.order(originalOrder);
        }
    }

    private static boolean isMagic(@NotNull ByteBuffer buffer, int offset) {
        if (buffer.remaining() < offset + MAGIC_LENGTH) {
            return false;
        }
        for (int i = 0; i < MAGIC_LENGTH; i++) {
            if (buffer.get(offset + i) != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedPageSize(int pageSize) {
        return pageSize == 2048 || pageSize == 4096 || pageSize == 8192 || pageSize == 16384;
    }

    private static long pageAlign(long size, int pageSize) {
        return (size + pageSize - 1) / pageSize * pageSize;
    }

    private static long safeAdd(long a, long b) {
        long result = Math.addExact(a, b);
        if (result < 0) {
            throw new ArithmeticException("overflow");
        }
        return result;
    }

    private static long maxNonNegative(long... values) {
        long max = 0;
        for (long v : values) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    private static @NotNull String readAsciiString(@NotNull ByteBuffer buffer, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            byte b = buffer.get(offset + i);
            if (b == 0) {
                break;
            }
            if (b >= 32 && b < 127) {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    private static @NotNull AndroidBootContainer build(long sourceSize, @NotNull InputStreamSource source,
                                                       @NotNull Header header) {
        List<ContainerEntry> entries = new ArrayList<>();
        entries.add(new AndroidBootEntry("/raw", source, 0, sourceSize));
        entries.add(new AndroidBootEntry("/kernel", source, header.kernelOffset, header.kernelSize));
        entries.add(new AndroidBootEntry("/ramdisk", source, header.ramdiskOffset, header.ramdiskSize));
        if (header.secondSize > 0) {
            entries.add(new AndroidBootEntry("/second", source, header.secondOffset, header.secondSize));
        }
        if (header.dtbSize > 0) {
            entries.add(new AndroidBootEntry("/dtb", source, header.dtbOffset, header.dtbSize));
        }
        return new AndroidBootContainer(sourceSize, source, header, entries);
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.ANDROID_BOOT;
    }

    @Override
    public @NotNull List<ContainerEntry> entries() {
        return entries;
    }

    @Override
    public @NotNull Optional<ContainerEntry> findEntry(@NotNull String path) {
        return Optional.ofNullable(entryByName.get(path));
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("format", ContainerFormat.ANDROID_BOOT.getName());
        meta.put("source_size", Long.toString(sourceSize));
        meta.put("header_version", Integer.toString(header.headerVersion));
        meta.put("page_size", Integer.toString(header.pageSize));
        meta.put("kernel_addr", Long.toString(header.kernelAddr));
        meta.put("ramdisk_addr", Long.toString(header.ramdiskAddr));
        if (header.secondSize > 0) {
            meta.put("second_addr", Long.toString(header.secondAddr));
        }
        if (header.dtbSize > 0) {
            meta.put("dtb_addr", Long.toString(header.dtbAddr));
        }
        meta.put("entry_count", Integer.toString(entries.size()));
        if (!header.name.isEmpty()) {
            meta.put("name", header.name);
        }
        if (!header.cmdline.isEmpty()) {
            meta.put("cmdline", header.cmdline);
        }
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public long size() {
        return sourceSize;
    }

    private record Header(int headerVersion, int pageSize,
                          long kernelSize, long kernelOffset, long kernelAddr,
                          long ramdiskSize, long ramdiskOffset, long ramdiskAddr,
                          long secondSize, long secondOffset, long secondAddr,
                          long recoveryDtboSize, long recoveryDtboOffset,
                          long dtbSize, long dtbOffset, long dtbAddr,
                          long tagsAddr, String name, String cmdline) {
    }

    private static final class AndroidBootEntry implements ContainerEntry {

        private final String name;
        private final InputStreamSource source;
        private final long offset;
        private final long length;

        AndroidBootEntry(@NotNull String name, @NotNull InputStreamSource source, long offset, long length) {
            if (offset < 0) {
                throw new IllegalArgumentException("offset cannot be negative: " + offset);
            }
            if (length < 0) {
                throw new IllegalArgumentException("length cannot be negative: " + length);
            }
            this.name = name;
            this.source = source;
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
            return new RegionInputStream(source.openStream(offset), length);
        }

        @Override
        public @NotNull Map<String, String> metadata() {
            return Collections.emptyMap();
        }
    }
}
