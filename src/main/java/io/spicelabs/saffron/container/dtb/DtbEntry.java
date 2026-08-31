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

import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.io.ChunkedDisk;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * A named entry inside a {@link DtbContainer}.
 *
 * <p>The entry content is either a defensive copy of the source bytes or a
 * lazy bounded stream over a {@link ChunkedDisk}. Each call to
 * {@link #openStream()} returns a fresh, independent stream.</p>
 */
final class DtbEntry implements ContainerEntry {

    private final String name;
    private final byte[] content;
    private final ChunkedDisk disk;
    private final long offset;
    private final long length;
    private final Map<String, String> metadata;

    DtbEntry(@NotNull String name, byte @NotNull [] content, @NotNull Map<String, String> metadata) {
        if (!name.startsWith("/")) {
            throw new IllegalArgumentException("Entry path must be absolute: " + name);
        }
        this.name = name;
        this.content = content.clone();
        this.disk = null;
        this.offset = 0;
        this.length = this.content.length;
        this.metadata = Collections.unmodifiableMap(metadata);
    }

    DtbEntry(@NotNull String name, @NotNull ChunkedDisk disk, long offset, long length,
             @NotNull Map<String, String> metadata) {
        if (!name.startsWith("/")) {
            throw new IllegalArgumentException("Entry path must be absolute: " + name);
        }
        this.name = name;
        this.content = null;
        this.disk = disk;
        this.offset = offset;
        this.length = length;
        this.metadata = Collections.unmodifiableMap(metadata);
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
        if (disk != null) {
            return disk.stream(offset, length);
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        return metadata;
    }
}
