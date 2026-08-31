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
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.io.ChunkedDisk;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * A named slice of an ELF file exposed as a binary container entry.
 *
 * <p>The entry holds either a reference to the container's source byte array
 * (in-memory path) or a {@link ChunkedDisk} (bounded streaming path) and an
 * {@code (offset, length)} slice. Each call to {@link #openStream()} returns a
 * fresh, independent stream over that slice.</p>
 */
final class ElfEntry implements ContainerEntry {

    private final String name;
    private final byte[] source;
    private final ChunkedDisk disk;
    private final long offset;
    private final long length;
    private final Map<String, String> metadata;

    ElfEntry(@NotNull String name, byte @NotNull [] source, int offset, int length,
             @NotNull Map<String, String> metadata) {
        if (offset < 0 || length < 0 || offset > source.length || offset + length > source.length) {
            throw new IllegalArgumentException("Invalid ELF entry slice");
        }
        this.name = name;
        this.source = source;
        this.disk = null;
        this.offset = offset;
        this.length = length;
        this.metadata = Collections.unmodifiableMap(metadata);
    }

    ElfEntry(@NotNull String name, @NotNull ChunkedDisk disk, long offset, long length,
             @NotNull Map<String, String> metadata) {
        if (offset < 0 || length < 0 || offset + length > disk.size()) {
            throw new IllegalArgumentException("Invalid ELF entry slice");
        }
        this.name = name;
        this.source = null;
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
        return new ByteArrayInputStream(source, (int) offset, (int) length);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        return metadata;
    }
}
