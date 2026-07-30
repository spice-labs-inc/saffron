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

import io.spicelabs.saffron.adapter.FileInputStreamSource;
import io.spicelabs.saffron.container.ContainerEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * The single `/payload` entry inside a {@link CompressedSingleContainer}.
 *
 * <p>The entry is backed by the temporary decompressed file on disk, so its size
 * is exact and random access is efficient.</p>
 */
final class CompressedSinglePayloadEntry implements ContainerEntry {

    private final Path payloadPath;
    private final long size;

    CompressedSinglePayloadEntry(@NotNull Path payloadPath, long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size cannot be negative: " + size);
        }
        this.payloadPath = payloadPath;
        this.size = size;
    }

    @Override
    public @NotNull String name() {
        return "/payload";
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return new FileInputStreamSource(payloadPath).openStream();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        return Collections.emptyMap();
    }

    /**
     * Returns the backing file path. Used only for cleanup by the container.
     */
    @NotNull Path getPayloadPath() {
        return payloadPath;
    }

    /**
     * Returns the current on-disk size of the payload file, which may differ
     * from the stored size only if the file has been externally modified.
     */
    long currentSize() throws IOException {
        return Files.size(payloadPath);
    }
}
