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
package io.spicelabs.saffron.container.dtb;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.io.ChunkedDisk;
import io.spicelabs.saffron.raw.RawDiskImpl;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.container.devicetree.DeviceTreeBlob;
import io.spicelabs.saffron.container.devicetree.DeviceTreeNode;
import io.spicelabs.saffron.container.devicetree.DeviceTreeProperty;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
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
 * A Device Tree Blob (DTB) exposed as a Saffron binary container.
 *
 * <p>The container always exposes the raw blob as {@code /dtb}. It also decodes the
 * tree and exposes every node property as a separate entry whose path reflects the
 * node hierarchy (e.g., {@code /model}, {@code /chosen/bootargs}).</p>
 *
 * <p>FIT images (DTBs whose root contains an {@code /images} node) are rejected,
 * because they are handled by {@link io.spicelabs.saffron.container.fit.FitContainer}.</p>
 */
public final class DtbContainer implements BinaryContainer {

    private static final String IMAGES_NODE = "images";
    private static final String RAW_ENTRY = "/dtb";

    private final long sourceSize;
    private final byte[] source;
    private final ChunkedDisk disk;
    private final List<ContainerEntry> entries;
    private final Map<String, ContainerEntry> entryByName;
    private final long totalSize;

    private DtbContainer(long sourceSize, byte @NotNull [] source, @NotNull List<ContainerEntry> entries,
                           long totalSize) {
        this.sourceSize = sourceSize;
        this.source = source.clone();
        this.disk = null;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
        this.totalSize = totalSize;
    }

    private DtbContainer(long sourceSize, @NotNull ChunkedDisk disk,
                         @NotNull List<ContainerEntry> entries, long totalSize) {
        this.sourceSize = sourceSize;
        this.source = null;
        this.disk = disk;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
        this.totalSize = totalSize;
    }

    /**
     * Attempts to open a DTB container from a file path.
     *
     * <p>Reads are bounded (see {@link ChunkedDisk}): the file is never
     * loaded into memory as a whole; entries stream from the file on demand.
     * The container owns the file handle and closes it on
     * {@link #close()}.</p>
     *
     * @param path the path to examine
     * @return the container, or empty if the file is not a plain DTB
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path) throws IOException {
        long size = Files.size(path);
        if (size < 4 || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        ChunkedDisk chunked = new ChunkedDisk(RawDiskImpl.open(path), true);
        try {
            Optional<DeviceTreeBlob> blobOpt = DeviceTreeBlob.parse(chunked);
            if (blobOpt.isEmpty()) {
                chunked.close();
                return Optional.empty();
            }
            DeviceTreeBlob blob = blobOpt.get();
            if (blob.root().child(IMAGES_NODE).isPresent()) {
                chunked.close();
                return Optional.empty();
            }
            List<ContainerEntry> entries = buildEntries(blob, chunked);
            return Optional.of(new DtbContainer(size, chunked, entries, blob.totalSize()));
        } catch (RuntimeException | Error e) {
            // Defensive: malformed input must not escape as unchecked.
            chunked.close();
            return Optional.empty();
        }
    }

    /**
     * Attempts to open a DTB container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @return the container, or empty if the disk is not a plain DTB
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size < 4 || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        // Bounded reads: the DTB is parsed through a chunked disk reader and
        // never loaded as a whole; the raw entry streams from the disk.
        ChunkedDisk chunked = new ChunkedDisk(disk);
        try {
            Optional<DeviceTreeBlob> blobOpt = DeviceTreeBlob.parse(chunked);
            if (blobOpt.isEmpty()) {
                return Optional.empty();
            }
            DeviceTreeBlob blob = blobOpt.get();
            if (blob.root().child(IMAGES_NODE).isPresent()) {
                return Optional.empty();
            }
            List<ContainerEntry> entries = buildEntries(blob, chunked);
            return Optional.of(new DtbContainer(size, chunked, entries, blob.totalSize()));
        } catch (RuntimeException | Error e) {
            // Defensive: malformed input must not escape as unchecked
            // (parity with open(Path)).
            return Optional.empty();
        }
    }

    /**
     * Attempts to open a DTB container from a byte buffer.
     *
     * @param source the full DTB bytes; the buffer is not modified
     * @param sourceSize the total size of the DTB source
     * @return the container, or empty if the buffer is not a plain DTB
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull ByteBuffer source, long sourceSize) {
        try {
            Optional<DeviceTreeBlob> blobOpt = DeviceTreeBlob.parse(source);
            if (blobOpt.isEmpty()) {
                return Optional.empty();
            }
            DeviceTreeBlob blob = blobOpt.get();
            // FIT images are not plain DTB.
            if (blob.root().child(IMAGES_NODE).isPresent()) {
                return Optional.empty();
            }
            byte[] sourceBytes = toByteArray(source);
            List<ContainerEntry> entries = buildEntries(blob, sourceBytes);
            return Optional.of(new DtbContainer(sourceSize, sourceBytes, entries, blob.totalSize()));
        } catch (RuntimeException | Error e) {
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

    private static @NotNull List<ContainerEntry> buildEntries(@NotNull DeviceTreeBlob blob, byte @NotNull [] source) {
        List<ContainerEntry> entries = new ArrayList<>();
        entries.add(rawEntry(source));
        collectProperties(blob.root(), "", entries);
        return entries;
    }

    private static @NotNull List<ContainerEntry> buildEntries(@NotNull DeviceTreeBlob blob,
                                                              @NotNull ChunkedDisk disk) {
        List<ContainerEntry> entries = new ArrayList<>();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("type", "raw");
        meta.put("size", Long.toString(disk.size()));
        entries.add(new DtbEntry(RAW_ENTRY, disk, 0, disk.size(), meta));
        collectProperties(blob.root(), "", entries);
        return entries;
    }

    private static @NotNull ContainerEntry rawEntry(byte @NotNull [] source) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("type", "raw");
        meta.put("size", Integer.toString(source.length));
        return new DtbEntry(RAW_ENTRY, source, meta);
    }

    private static void collectProperties(@NotNull DeviceTreeNode node, @NotNull String path,
                                         @NotNull List<ContainerEntry> entries) {
        for (DeviceTreeProperty property : node.properties().values()) {
            String name = property.name();
            if (!isValidName(name)) {
                continue;
            }
            String entryPath = path.isEmpty() ? "/" + name : path + "/" + name;
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", "property");
            meta.put("node", path.isEmpty() ? "/" : path);
            meta.put("property_name", name);
            entries.add(new DtbEntry(entryPath, property.asBytes(), meta));
        }

        for (DeviceTreeNode child : node.children()) {
            String childName = child.name();
            if (!isValidName(childName)) {
                continue;
            }
            String childPath = path.isEmpty() ? "/" + childName : path + "/" + childName;
            collectProperties(child, childPath, entries);
        }
    }

    private static boolean isValidName(@NotNull String name) {
        if (name.isEmpty()) {
            return false;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("\0") || name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.DTB;
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
        meta.put("format", ContainerFormat.DTB.getName());
        meta.put("source_size", Long.toString(sourceSize));
        meta.put("total_size", Long.toString(totalSize));
        meta.put("entry_count", Integer.toString(entries.size()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public long size() {
        return sourceSize;
    }

    /**
     * Releases the backing source when this container opened it itself
     * (path-based opens). Containers created over a caller-provided
     * {@link VirtualDisk} leave the caller's disk untouched.
     */
    @Override
    public void close() throws IOException {
        if (disk != null) {
            disk.close();
        }
    }
}
