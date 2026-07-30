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
package io.spicelabs.saffron.container.rpi;

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
 * A Raspberry Pi firmware blob exposed as a Saffron binary container.
 *
 * <p>The container is backed by an {@link InputStreamSource}, so arbitrarily large
 * files can be represented without loading them into memory. Each entry is a
 * zero-copy view over a region of the source.</p>
 */
public final class RpiFirmwareContainer implements BinaryContainer {

    private static final int BOOTCODE_PADDING_SIZE = 512;
    private static final int BOOTCODE_CODE_OFFSET = 0x200;

    private final long sourceSize;
    private final InputStreamSource source;
    private final boolean bootcode;
    private final List<ContainerEntry> entries;
    private final Map<String, ContainerEntry> entryByName;

    private RpiFirmwareContainer(long sourceSize, @NotNull InputStreamSource source, boolean bootcode,
                                 @NotNull List<ContainerEntry> entries) {
        this.sourceSize = sourceSize;
        this.source = source;
        this.bootcode = bootcode;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Attempts to open an RPi firmware container from a file path.
     *
     * @param path the path to examine
     * @return the container, or empty if the file is not a valid RPi firmware blob
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0) {
            return Optional.empty();
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int probeSize = switch (name) {
            case "bootcode.bin" -> BOOTCODE_CODE_OFFSET + 1;
            case "fixup.dat" -> RpiFirmwareContainerFactory.FIXUP_PATTERN_WINDOW;
            default -> 0;
        };
        if (probeSize == 0) {
            return Optional.empty();
        }
        ByteBuffer probe = RpiFirmwareContainerFactory.probeFile(path, probeSize);
        if (!RpiFirmwareContainerFactory.looksLikeRpiFirmware(path, probe, size)) {
            return Optional.empty();
        }
        boolean isBootcode = "bootcode.bin".equals(name);
        InputStreamSource source = new FileInputStreamSource(path);
        return Optional.of(build(size, source, isBootcode));
    }

    /**
     * Attempts to open an RPi firmware container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @return the container, or empty if the disk is not a valid RPi firmware blob
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size <= BOOTCODE_PADDING_SIZE) {
            return Optional.empty();
        }
        int probeLength = (int) Math.min(BOOTCODE_CODE_OFFSET + 1, size);
        ByteBuffer data = disk.read(0, probeLength);
        if (!RpiFirmwareContainerFactory.looksLikeRpiFirmware(data, size)) {
            return Optional.empty();
        }
        InputStreamSource source = new VirtualDiskInputStreamSource(disk, size, "rpi-firmware-virtual-disk");
        // VirtualDisk content detection only identifies bootcode.bin.
        return Optional.of(build(size, source, true));
    }

    /**
     * Attempts to open an RPi firmware container from a byte buffer.
     *
     * @param source     the full firmware bytes; the buffer is not modified
     * @param sourceSize the total size of the firmware source
     * @return the container, or empty if the buffer is not a valid RPi firmware blob
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull ByteBuffer source, long sourceSize) {
        try {
            if (sourceSize <= 0) {
                return Optional.empty();
            }
            if (sourceSize > source.remaining()) {
                // Declared size cannot exceed the bytes actually available in the buffer.
                return Optional.empty();
            }
            if (!RpiFirmwareContainerFactory.looksLikeRpiFirmware(source, sourceSize)) {
                return Optional.empty();
            }
            byte[] bytes = toByteArray(source);
            InputStreamSource streamSource = new ByteArrayInputStreamSource(bytes, getDescription(bytes.length));
            // ByteBuffer content detection only identifies bootcode.bin.
            return Optional.of(build(bytes.length, streamSource, true));
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
        return "rpi-firmware-byte-buffer[" + length + "]";
    }

    private static @NotNull RpiFirmwareContainer build(long sourceSize, @NotNull InputStreamSource source,
                                                       boolean isBootcode) {
        List<ContainerEntry> entries = new ArrayList<>();
        entries.add(new RpiFirmwareEntry("/raw", source, 0, sourceSize));
        try {
            buildSections(sourceSize, source, isBootcode, entries);
        } catch (IllegalArgumentException e) {
            // Fallback to /raw only.
            entries.clear();
            entries.add(new RpiFirmwareEntry("/raw", source, 0, sourceSize));
        }
        return new RpiFirmwareContainer(sourceSize, source, isBootcode, entries);
    }

    private static void buildSections(long sourceSize, @NotNull InputStreamSource source, boolean isBootcode,
                                      @NotNull List<ContainerEntry> entries) {
        if (isBootcode) {
            // bootcode.bin: /bootcode at 0x200.
            if (sourceSize > BOOTCODE_CODE_OFFSET) {
                long codeLength = Math.subtractExact(sourceSize, BOOTCODE_CODE_OFFSET);
                entries.add(new RpiFirmwareEntry("/bootcode", source, BOOTCODE_CODE_OFFSET, codeLength));
            }
        } else {
            // fixup.dat: the whole file is the fixup table.
            entries.add(new RpiFirmwareEntry("/fixup", source, 0, sourceSize));
        }
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.RPI_FIRMWARE;
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
        meta.put("format", ContainerFormat.RPI_FIRMWARE.getName());
        meta.put("source_size", Long.toString(sourceSize));
        meta.put("entry_count", Integer.toString(entries.size()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public long size() {
        return sourceSize;
    }

    /**
     * A single entry backed by a region of the source stream.
     */
    private static final class RpiFirmwareEntry implements ContainerEntry {

        private final String name;
        private final InputStreamSource source;
        private final long offset;
        private final long length;

        RpiFirmwareEntry(@NotNull String name, @NotNull InputStreamSource source, long offset, long length) {
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
