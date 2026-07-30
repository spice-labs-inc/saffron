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
package io.spicelabs.saffron.container;

import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Adapts a {@link BinaryContainer} to the {@link FileSystem} API so that
 * Goat Rodeo can traverse it without changes.
 */
public final class BinaryContainerFileSystemImpl implements FileSystem.BinaryContainerFileSystem {

    private final BinaryContainer container;
    private final FileSystemEntry.Directory root;

    private BinaryContainerFileSystemImpl(@NotNull BinaryContainer container) {
        this.container = container;
        this.root = new RootDirectory();
    }

    /**
     * Wraps a container in a filesystem.
     *
     * @param container the container to wrap
     * @return a filesystem view of the container
     */
    public static @NotNull BinaryContainerFileSystemImpl mount(@NotNull BinaryContainer container) {
        return new BinaryContainerFileSystemImpl(container);
    }

    @Override
    public @NotNull String containerFormat() {
        return container.format().getName();
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        return root;
    }

    @Override
    public @NotNull Optional<FileSystemEntry> resolve(@NotNull String path) throws IOException {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be absolute: " + path);
        }
        if ("/".equals(path)) {
            return Optional.of(root);
        }
        Optional<ContainerEntry> entry = container.findEntry(path);
        return entry.map(this::toFileEntry);
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk() throws IOException {
        return Stream.concat(Stream.of(root),
                container.entries().stream().map(this::toFileEntry));
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth) throws IOException {
        if (maxDepth < 0) {
            return Stream.empty();
        }
        if ("/".equals(path)) {
            return walk();
        }
        return resolve(path).stream();
    }

    @Override
    public long totalSize() {
        return container.size();
    }

    @Override
    public long usedSize() {
        return container.entries().stream().mapToLong(ContainerEntry::size).sum();
    }

    @Override
    public long freeSize() {
        return 0;
    }

    @Override
    public @NotNull Optional<String> label() {
        return Optional.ofNullable(container.metadata().get("label"));
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.empty();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        return container.metadata();
    }

    @Override
    public void close() throws IOException {
        container.close();
    }

    private @NotNull FileSystemEntry toFileEntry(@NotNull ContainerEntry entry) {
        return new FileEntry(entry);
    }

    private final class RootDirectory implements FileSystemEntry.Directory {
        @Override
        public @NotNull String name() {
            return "";
        }

        @Override
        public @NotNull String path() {
            return "/";
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            return container.entries().stream().map(BinaryContainerFileSystemImpl.this::toFileEntry);
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            return resolve("/" + name);
        }
    }

    private final class FileEntry implements FileSystemEntry.RegularFile {
        private final ContainerEntry entry;

        FileEntry(ContainerEntry entry) {
            this.entry = entry;
        }

        @Override
        public @NotNull String name() {
            String name = entry.name();
            int slash = name.lastIndexOf('/');
            return slash >= 0 ? name.substring(slash + 1) : name;
        }

        @Override
        public @NotNull String path() {
            return entry.name();
        }

        @Override
        public long size() {
            return entry.size();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.putAll(entry.metadata());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return entry.openStream();
        }

        @Override
        public byte @NotNull [] readAllBytes() throws IOException {
            try (InputStream is = entry.openStream()) {
                return is.readAllBytes();
            }
        }
    }
}
