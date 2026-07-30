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
package io.spicelabs.saffron.container.fit;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.container.devicetree.DeviceTreeBlob;
import io.spicelabs.saffron.container.devicetree.DeviceTreeNode;
import io.spicelabs.saffron.container.devicetree.DeviceTreeProperty;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A U-Boot Flattened Image Tree (FIT) exposed as a binary container.
 *
 * <p>Each child node under {@code /images} becomes a named entry. Standard
 * aliases ({@code /kernel}, {@code /ramdisk}, {@code /dtb}) are added for the
 * first image of each known type. If a {@code /signature} node exists, it is
 * also exposed.</p>
 */
public final class FitContainer implements BinaryContainer {

    private static final String IMAGES_NODE = "images";
    private static final String SIGNATURE_NODE = "signature";
    private static final String DATA_PROPERTY = "data";

    private final long sourceSize;
    private final List<ContainerEntry> entries;

    private FitContainer(long sourceSize, @NotNull List<ContainerEntry> entries) {
        this.sourceSize = sourceSize;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Attempts to open a FIT container from a file path.
     *
     * @param path the path to examine
     * @return the container, or empty if the file is not a FIT image
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path) throws IOException {
        Optional<DeviceTreeBlob> blob = DeviceTreeBlob.parse(path);
        if (blob.isEmpty()) {
            return Optional.empty();
        }
        return open(blob.get(), Files.size(path));
    }

    /**
     * Attempts to open a FIT container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @return the container, or empty if the disk is not a FIT image
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk) throws IOException {
        Optional<DeviceTreeBlob> blob = DeviceTreeBlob.parse(disk);
        if (blob.isEmpty()) {
            return Optional.empty();
        }
        return open(blob.get(), disk.virtualSize());
    }

    private static @NotNull Optional<BinaryContainer> open(@NotNull DeviceTreeBlob blob, long sourceSize) {
        Optional<DeviceTreeNode> images = blob.root().child(IMAGES_NODE);
        if (images.isEmpty()) {
            return Optional.empty();
        }
        List<ContainerEntry> entries = buildEntries(images.get(), blob.root());
        return Optional.of(new FitContainer(sourceSize, entries));
    }

    private static @NotNull List<ContainerEntry> buildEntries(@NotNull DeviceTreeNode images,
                                                             @NotNull DeviceTreeNode root) {
        List<ContainerEntry> entries = new ArrayList<>();
        ContainerEntry kernelAlias = null;
        ContainerEntry ramdiskAlias = null;
        ContainerEntry dtbAlias = null;

        for (DeviceTreeNode image : images.children()) {
            String nodeName = image.name();
            if (!isValidNodeName(nodeName)) {
                continue;
            }
            String path = "/" + nodeName;
            Map<String, String> metadata = extractMetadata(image);
            byte[] content = dataBytes(image).orElse(new byte[0]);
            FitEntry entry = new FitEntry(path, content, metadata);
            entries.add(entry);

            String type = metadata.get("type");
            if ("kernel".equals(type) && kernelAlias == null) {
                kernelAlias = new FitEntry("/kernel", content, metadata);
            }
            if ("ramdisk".equals(type) && ramdiskAlias == null) {
                ramdiskAlias = new FitEntry("/ramdisk", content, metadata);
            }
            if ("flat_dt".equals(type) && dtbAlias == null) {
                dtbAlias = new FitEntry("/dtb", content, metadata);
            }
        }

        if (kernelAlias != null) {
            entries.add(kernelAlias);
        }
        if (ramdiskAlias != null) {
            entries.add(ramdiskAlias);
        }
        if (dtbAlias != null) {
            entries.add(dtbAlias);
        }

        root.child(SIGNATURE_NODE).ifPresent(signature -> {
            String path = "/" + SIGNATURE_NODE;
            Map<String, String> metadata = extractMetadata(signature);
            byte[] content = dataBytes(signature).orElse(new byte[0]);
            entries.add(new FitEntry(path, content, metadata));
        });

        return entries;
    }

    private static @NotNull Map<String, String> extractMetadata(@NotNull DeviceTreeNode node) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, DeviceTreeProperty> entry : node.properties().entrySet()) {
            String name = entry.getKey();
            if (DATA_PROPERTY.equals(name)) {
                continue;
            }
            metadata.put(name, entry.getValue().asString());
        }
        return Collections.unmodifiableMap(metadata);
    }

    private static @NotNull Optional<byte[]> dataBytes(@NotNull DeviceTreeNode node) {
        return node.property(DATA_PROPERTY).map(DeviceTreeProperty::asBytes);
    }

    private static boolean isValidNodeName(@NotNull String name) {
        if (name.isEmpty()) {
            return false;
        }
        return !name.contains("/") && !name.contains("\0") && !name.contains("..");
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.FIT_IMAGE;
    }

    @Override
    public @NotNull List<ContainerEntry> entries() {
        return entries;
    }

    @Override
    public @NotNull Optional<ContainerEntry> findEntry(@NotNull String path) {
        for (ContainerEntry entry : entries) {
            if (entry.name().equals(path)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("format", ContainerFormat.FIT_IMAGE.getName());
        metadata.put("source_size", Long.toString(sourceSize));
        metadata.put("entry_count", Integer.toString(entries.size()));
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public long size() {
        return sourceSize;
    }
}
