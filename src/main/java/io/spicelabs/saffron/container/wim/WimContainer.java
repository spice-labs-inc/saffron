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
package io.spicelabs.saffron.container.wim;

import io.spicelabs.saffron.SecurityPolicy;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A Windows Imaging Format (WIM) container exposed through Saffron's binary
 * container API.
 *
 * <p>This implementation parses only the WIM file header. It exposes the whole
 * source as a single {@code /raw} entry and reports header metadata. It does
 * not extract images, files, or compressed resources.</p>
 */
public final class WimContainer implements BinaryContainer {

    private static final byte @NotNull [] WIM_MAGIC = {0x4d, 0x53, 0x57, 0x49, 0x4d, 0x00, 0x00, 0x00};
    private static final int MIN_HEADER_SIZE = 208;

    private final long sourceSize;
    private final InputStreamSource source;
    private final int headerSize;
    private final int version;
    private final int flags;
    private final int imageCount;
    private final List<ContainerEntry> entries;
    private final Map<String, ContainerEntry> entryByName;

    private WimContainer(long sourceSize, @NotNull InputStreamSource source, int headerSize,
                         int version, int flags, int imageCount, @NotNull List<ContainerEntry> entries) {
        this.sourceSize = sourceSize;
        this.source = source;
        this.headerSize = headerSize;
        this.version = version;
        this.flags = flags;
        this.imageCount = imageCount;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Attempts to open a WIM container from a file path.
     *
     * @param path the path to examine
     * @param policy the security policy governing resource limits
     * @return the container, or empty if the file is not a valid WIM
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path,
                                                        @NotNull SecurityPolicy policy) throws IOException {
        long size = Files.size(path);
        if (size < MIN_HEADER_SIZE) {
            return Optional.empty();
        }
        ByteBuffer header = readHeader(path, MIN_HEADER_SIZE);
        ParsedHeader parsed = parseHeader(header, size);
        if (parsed == null) {
            return Optional.empty();
        }
        InputStreamSource source = new FileInputStreamSource(path);
        return Optional.of(build(size, source, parsed));
    }

    /**
     * Attempts to open a WIM container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @param policy the security policy governing resource limits
     * @return the container, or empty if the disk is not a valid WIM
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk,
                                                        @NotNull SecurityPolicy policy) throws IOException {
        long size = disk.virtualSize();
        if (size < MIN_HEADER_SIZE) {
            return Optional.empty();
        }
        int readLength = (int) Math.min(MIN_HEADER_SIZE, size);
        ByteBuffer header = disk.read(0, readLength);
        ParsedHeader parsed = parseHeader(header, size);
        if (parsed == null) {
            return Optional.empty();
        }
        InputStreamSource source = new VirtualDiskInputStreamSource(disk, size, "wim-virtual-disk");
        return Optional.of(build(size, source, parsed));
    }

    /**
     * Attempts to open a WIM container from a byte buffer.
     *
     * @param source the full source bytes; the buffer is not modified
     * @param sourceSize the total size of the source
     * @param policy the security policy governing resource limits
     * @return the container, or empty if the buffer is not a valid WIM
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull ByteBuffer source, long sourceSize,
                                                        @NotNull SecurityPolicy policy) {
        try {
            if (sourceSize < MIN_HEADER_SIZE || sourceSize > source.remaining()) {
                return Optional.empty();
            }
            ParsedHeader parsed = parseHeader(source, sourceSize);
            if (parsed == null) {
                return Optional.empty();
            }
            byte[] bytes = toByteArray(source);
            if (bytes.length < MIN_HEADER_SIZE) {
                return Optional.empty();
            }
            InputStreamSource streamSource = new ByteArrayInputStreamSource(bytes, getDescription(bytes.length));
            return Optional.of(build(bytes.length, streamSource, parsed));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static @NotNull ByteBuffer readHeader(@NotNull Path path, int length) throws IOException {
        byte[] header = new byte[length];
        try (var is = Files.newInputStream(path)) {
            if (is.read(header) != length) {
                throw new IOException("Failed to read WIM header from " + path);
            }
        }
        return ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ParsedHeader parseHeader(@NotNull ByteBuffer header, long sourceSize) {
        if (sourceSize < MIN_HEADER_SIZE) {
            return null;
        }
        ByteBuffer buffer = header.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < MIN_HEADER_SIZE) {
            return null;
        }
        if (!isMagic(buffer)) {
            return null;
        }
        int headerSize = buffer.getInt(8);
        if (headerSize < MIN_HEADER_SIZE || headerSize > sourceSize) {
            return null;
        }
        int version = buffer.getInt(12);
        if (version == 0) {
            return null;
        }
        int flags = buffer.getInt(16);
        int imageCount = buffer.getInt(44);
        if (imageCount < 0) {
            return null;
        }
        return new ParsedHeader(headerSize, version, flags, imageCount);
    }

    private static boolean isMagic(@NotNull ByteBuffer buffer) {
        if (buffer.remaining() < WIM_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < WIM_MAGIC.length; i++) {
            if (buffer.get(i) != WIM_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull WimContainer build(long sourceSize, @NotNull InputStreamSource source,
                                             @NotNull ParsedHeader parsed) {
        List<ContainerEntry> entries = new ArrayList<>();
        entries.add(new WimEntry("/raw", source, 0, sourceSize));
        return new WimContainer(sourceSize, source, parsed.headerSize, parsed.version,
                parsed.flags, parsed.imageCount, entries);
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
        return "wim-byte-buffer[" + length + "]";
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.WIM;
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
        meta.put("format", ContainerFormat.WIM.getName());
        meta.put("source_size", Long.toString(sourceSize));
        meta.put("entry_count", Integer.toString(entries.size()));
        meta.put("wim.header_size", Integer.toString(headerSize));
        meta.put("wim.version", String.format(Locale.ROOT, "0x%08x", version));
        meta.put("wim.flags", String.format(Locale.ROOT, "0x%08x", flags));
        meta.put("wim.image_count", Integer.toString(imageCount));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public long size() {
        return sourceSize;
    }

    private record ParsedHeader(int headerSize, int version, int flags, int imageCount) {
    }

    private static final class WimEntry implements ContainerEntry {

        private final String name;
        private final InputStreamSource source;
        private final long offset;
        private final long length;

        WimEntry(@NotNull String name, @NotNull InputStreamSource source, long offset, long length) {
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
