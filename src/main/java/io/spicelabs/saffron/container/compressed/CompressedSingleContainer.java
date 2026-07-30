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
package io.spicelabs.saffron.container.compressed;

import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.container.ContainerFormat;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A binary container that represents a single compressed non-archive payload.
 *
 * <p>The container decompresses the source bytes to a temporary file and exposes
 * exactly one entry: {@code /payload}. The temporary file is deleted when the
 * container is closed.</p>
 */
public final class CompressedSingleContainer implements BinaryContainer {

    private final long sourceSize;
    private final CompressedSingleFormat compression;
    private final long decompressedSize;
    private final Path payloadPath;
    private final List<ContainerEntry> entries;
    private final Map<String, String> metadata;
    private boolean closed;

    CompressedSingleContainer(long sourceSize,
                              @NotNull CompressedSingleFormat compression,
                              long decompressedSize,
                              @NotNull Path payloadPath) {
        if (sourceSize < 0) {
            throw new IllegalArgumentException("sourceSize cannot be negative: " + sourceSize);
        }
        if (decompressedSize < 0) {
            throw new IllegalArgumentException("decompressedSize cannot be negative: " + decompressedSize);
        }
        this.sourceSize = sourceSize;
        this.compression = compression;
        this.decompressedSize = decompressedSize;
        this.payloadPath = payloadPath;
        this.entries = Collections.singletonList(new CompressedSinglePayloadEntry(payloadPath, decompressedSize));
        this.metadata = buildMetadata();
        this.closed = false;
    }

    private @NotNull Map<String, String> buildMetadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("format", ContainerFormat.COMPRESSED_SINGLE.getName());
        meta.put("compression", compression.getName());
        meta.put("source_size", Long.toString(sourceSize));
        meta.put("decompressed_size", Long.toString(decompressedSize));
        meta.put("entry_count", Integer.toString(entries.size()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.COMPRESSED_SINGLE;
    }

    @Override
    public @NotNull List<ContainerEntry> entries() {
        return entries;
    }

    @Override
    public @NotNull Optional<ContainerEntry> findEntry(@NotNull String path) {
        if ("/payload".equals(path)) {
            return Optional.of(entries.get(0));
        }
        return Optional.empty();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public long size() {
        return sourceSize;
    }

    /**
     * Returns the detected compression format.
     */
    public @NotNull CompressedSingleFormat getCompression() {
        return compression;
    }

    /**
     * Returns the decompressed payload size in bytes.
     */
    public long getDecompressedSize() {
        return decompressedSize;
    }

    /**
     * Returns the path to the temporary decompressed payload file.
     * This is intended for tests and cleanup code only.
     */
    @NotNull Path getPayloadPath() {
        return payloadPath;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        Files.deleteIfExists(payloadPath);
    }

    @Override
    public @NotNull String toString() {
        return "CompressedSingleContainer{format=" + compression.getName()
                + ", sourceSize=" + sourceSize
                + ", decompressedSize=" + decompressedSize
                + ", payloadPath=" + payloadPath
                + "}";
    }
}
