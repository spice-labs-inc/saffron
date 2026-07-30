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
package io.spicelabs.saffron.container.dmg;

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
 * An Apple UDIF disk image (DMG) container exposed through Saffron's binary
 * container API.
 *
 * <p>This implementation reads the {@code koly} footer at the end of the file,
 * validates it, and exposes the data fork as a single {@code /raw} entry. It
 * does not parse the resource fork, blkx tables, or any filesystem inside the
 * image.</p>
 */
public final class DmgContainer implements BinaryContainer {

    private static final byte @NotNull [] KOLY_MAGIC = {0x6b, 0x6f, 0x6c, 0x79}; // "koly"
    private static final int FOOTER_SIZE = 512;

    private final long sourceSize;
    private final InputStreamSource source;
    private final long dataForkOffset;
    private final long dataForkLength;
    private final int version;
    private final List<ContainerEntry> entries;
    private final Map<String, ContainerEntry> entryByName;

    private DmgContainer(long sourceSize, @NotNull InputStreamSource source, long dataForkOffset,
                         long dataForkLength, int version, @NotNull List<ContainerEntry> entries) {
        this.sourceSize = sourceSize;
        this.source = source;
        this.dataForkOffset = dataForkOffset;
        this.dataForkLength = dataForkLength;
        this.version = version;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Attempts to open a DMG container from a file path.
     *
     * @param path the path to examine
     * @param policy the security policy governing resource limits
     * @return the container, or empty if the file is not a valid DMG
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path,
                                                        @NotNull SecurityPolicy policy) throws IOException {
        long size = Files.size(path);
        if (size < FOOTER_SIZE) {
            return Optional.empty();
        }
        ByteBuffer footer = readFooter(path, size);
        ParsedFooter parsed = parseFooter(footer, size);
        if (parsed == null) {
            return Optional.empty();
        }
        InputStreamSource source = new FileInputStreamSource(path);
        return Optional.of(build(size, source, parsed));
    }

    /**
     * Attempts to open a DMG container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @param policy the security policy governing resource limits
     * @return the container, or empty if the disk is not a valid DMG
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk,
                                                        @NotNull SecurityPolicy policy) throws IOException {
        long size = disk.virtualSize();
        if (size < FOOTER_SIZE) {
            return Optional.empty();
        }
        ByteBuffer footer = disk.read(size - FOOTER_SIZE, FOOTER_SIZE);
        ParsedFooter parsed = parseFooter(footer, size);
        if (parsed == null) {
            return Optional.empty();
        }
        InputStreamSource source = new VirtualDiskInputStreamSource(disk, size, "dmg-virtual-disk");
        return Optional.of(build(size, source, parsed));
    }

    /**
     * Attempts to open a DMG container from a byte buffer.
     *
     * @param source the full source bytes; the buffer is not modified
     * @param sourceSize the total size of the source
     * @param policy the security policy governing resource limits
     * @return the container, or empty if the buffer is not a valid DMG
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull ByteBuffer source, long sourceSize,
                                                        @NotNull SecurityPolicy policy) {
        try {
            if (sourceSize < FOOTER_SIZE || sourceSize > source.remaining()) {
                return Optional.empty();
            }
            ByteBuffer footer = sliceFooter(source, sourceSize);
            ParsedFooter parsed = parseFooter(footer, sourceSize);
            if (parsed == null) {
                return Optional.empty();
            }
            byte[] dataFork = copyRange(source, (int) parsed.dataForkOffset, (int) parsed.dataForkLength);
            InputStreamSource streamSource = new ByteArrayInputStreamSource(
                    dataFork, 0, dataFork.length, getDescription(dataFork.length));
            return Optional.of(build(sourceSize, streamSource, parsed));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static @NotNull ByteBuffer readFooter(@NotNull Path path, long sourceSize) throws IOException {
        byte[] footer = new byte[FOOTER_SIZE];
        try (var channel = Files.newByteChannel(path, java.nio.file.StandardOpenOption.READ)) {
            channel.position(sourceSize - FOOTER_SIZE);
            if (channel.read(ByteBuffer.wrap(footer)) != FOOTER_SIZE) {
                throw new IOException("Failed to read DMG footer from " + path);
            }
        }
        return ByteBuffer.wrap(footer).order(ByteOrder.BIG_ENDIAN);
    }

    private static @NotNull ByteBuffer sliceFooter(@NotNull ByteBuffer source, long sourceSize) {
        ByteBuffer dup = source.duplicate();
        dup.position((int) (sourceSize - FOOTER_SIZE));
        dup.limit((int) sourceSize);
        return dup.slice().order(ByteOrder.BIG_ENDIAN);
    }

    private static byte @NotNull [] copyRange(@NotNull ByteBuffer source, int offset, int length) {
        ByteBuffer dup = source.duplicate();
        dup.position(offset);
        dup.limit(offset + length);
        byte[] bytes = new byte[length];
        dup.get(bytes);
        return bytes;
    }

    private static ParsedFooter parseFooter(@NotNull ByteBuffer footer, long sourceSize) {
        ByteBuffer buffer = footer.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < FOOTER_SIZE) {
            return null;
        }
        if (!isMagic(buffer)) {
            return null;
        }
        int version = buffer.getInt(4);
        if (version <= 0) {
            return null;
        }
        int headerSize = buffer.getInt(8);
        if (headerSize != FOOTER_SIZE) {
            return null;
        }
        long dataForkOffset = buffer.getLong(24);
        long dataForkLength = buffer.getLong(32);
        long rsrcForkOffset = buffer.getLong(40);
        long rsrcForkLength = buffer.getLong(48);
        long xmlOffset = buffer.getLong(216);
        long xmlLength = buffer.getLong(224);
        if (dataForkOffset < 0 || dataForkLength < 0 || rsrcForkOffset < 0 || rsrcForkLength < 0
                || xmlOffset < 0 || xmlLength < 0) {
            return null;
        }
        long maxPayload = sourceSize - FOOTER_SIZE;
        if (!isRegionValid(dataForkOffset, dataForkLength, maxPayload)
                || !isRegionValid(rsrcForkOffset, rsrcForkLength, maxPayload)
                || !isRegionValid(xmlOffset, xmlLength, maxPayload)) {
            return null;
        }
        return new ParsedFooter(dataForkOffset, dataForkLength, version);
    }

    private static boolean isRegionValid(long offset, long length, long maxPayload) {
        if (offset == 0 && length == 0) {
            return true;
        }
        long end;
        try {
            end = Math.addExact(offset, length);
        } catch (ArithmeticException e) {
            return false;
        }
        return end <= maxPayload;
    }

    private static boolean isMagic(@NotNull ByteBuffer buffer) {
        if (buffer.remaining() < KOLY_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < KOLY_MAGIC.length; i++) {
            if (buffer.get(i) != KOLY_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull DmgContainer build(long sourceSize, @NotNull InputStreamSource source,
                                             @NotNull ParsedFooter parsed) {
        List<ContainerEntry> entries = new ArrayList<>();
        entries.add(new DmgEntry("/raw", source, 0, parsed.dataForkLength));
        return new DmgContainer(sourceSize, source, parsed.dataForkOffset, parsed.dataForkLength,
                parsed.version, entries);
    }

    private static @NotNull String getDescription(int length) {
        return "dmg-byte-buffer[" + length + "]";
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.DMG;
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
        meta.put("format", ContainerFormat.DMG.getName());
        meta.put("source_size", Long.toString(sourceSize));
        meta.put("entry_count", Integer.toString(entries.size()));
        meta.put("dmg.version", Integer.toString(version));
        meta.put("dmg.data_fork_offset", Long.toString(dataForkOffset));
        meta.put("dmg.data_fork_length", Long.toString(dataForkLength));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public long size() {
        return sourceSize;
    }

    private record ParsedFooter(long dataForkOffset, long dataForkLength, int version) {
    }

    private static final class DmgEntry implements ContainerEntry {

        private final String name;
        private final InputStreamSource source;
        private final long offset;
        private final long length;

        DmgEntry(@NotNull String name, @NotNull InputStreamSource source, long offset, long length) {
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
