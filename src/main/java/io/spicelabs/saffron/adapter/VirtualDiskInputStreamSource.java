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
package io.spicelabs.saffron.adapter;

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An {@link InputStreamSource} backed by a {@link VirtualDisk}.
 *
 * <p>This adapter allows any binary container to treat a virtual disk as a
 * seekable source without loading the whole disk into memory. Reads are performed
 * in chunks on demand via {@link VirtualDisk#read(long, int)}.</p>
 */
public final class VirtualDiskInputStreamSource implements InputStreamSource {

    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;

    private final VirtualDisk disk;
    private final long size;
    private final String description;

    /**
     * Creates a new source backed by the given virtual disk.
     *
     * @param disk the virtual disk to read from
     * @param size the total size of the relevant region in bytes
     * @param description a human-readable description
     */
    public VirtualDiskInputStreamSource(@NotNull VirtualDisk disk, long size, @NotNull String description) {
        if (disk == null) {
            throw new IllegalArgumentException("disk cannot be null");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size cannot be negative: " + size);
        }
        if (description == null) {
            throw new IllegalArgumentException("description cannot be null");
        }
        this.disk = disk;
        this.size = size;
        this.description = description;
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return openStream(0);
    }

    @Override
    public @NotNull InputStream openStream(long offset) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }
        if (offset > size) {
            throw new IllegalArgumentException("Offset cannot exceed size: " + offset + " > " + size);
        }
        return new VirtualDiskRegionInputStream(disk, offset, size - offset);
    }

    @Override
    public long size() throws IOException {
        return size;
    }

    @Override
    public @NotNull String getDescription() {
        return description;
    }

    @Override
    public boolean supportsRandomAccess() {
        return true;
    }

    /**
     * An InputStream over a region of a VirtualDisk, reading chunks on demand.
     */
    private static final class VirtualDiskRegionInputStream extends InputStream {

        private final VirtualDisk disk;
        private long position;
        private long remaining;
        private ByteBuffer buffer;

        VirtualDiskRegionInputStream(@NotNull VirtualDisk disk, long offset, long length) {
            this.disk = disk;
            this.position = offset;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (buffer == null || !buffer.hasRemaining()) {
                fillBuffer();
            }
            if (buffer == null || !buffer.hasRemaining()) {
                return -1;
            }
            position++;
            remaining--;
            return buffer.get() & 0xff;
        }

        @Override
        public int read(byte @NotNull [] b, int off, int len) throws IOException {
            if (b == null) {
                throw new IllegalArgumentException("buffer cannot be null");
            }
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IllegalArgumentException("Invalid buffer range");
            }
            if (len == 0) {
                return 0;
            }
            if (buffer == null || !buffer.hasRemaining()) {
                fillBuffer();
            }
            if (buffer == null || !buffer.hasRemaining()) {
                return -1;
            }
            int toRead = Math.min(len, buffer.remaining());
            buffer.get(b, off, toRead);
            position += toRead;
            remaining -= toRead;
            return toRead;
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }
            long toSkip = Math.min(n, remaining);
            position += toSkip;
            remaining -= toSkip;
            buffer = null;
            return toSkip;
        }

        @Override
        public int available() {
            return buffer != null ? buffer.remaining() : 0;
        }

        private void fillBuffer() throws IOException {
            if (remaining <= 0) {
                buffer = null;
                return;
            }
            int chunk = (int) Math.min(remaining, DEFAULT_CHUNK_SIZE);
            buffer = disk.read(position, chunk);
            if (buffer == null || !buffer.hasRemaining()) {
                buffer = null;
            }
        }
    }
}
