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
package io.spicelabs.saffron.io;

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded random access over a {@link VirtualDisk}.
 *
 * <p>All reads are issued in chunks of at most {@value #CHUNK_SIZE} bytes and
 * at most {@value #MAX_RESIDENT_CHUNKS} chunks are kept resident (LRU), so no
 * single disk read exceeds {@value #CHUNK_SIZE} bytes and resident memory is
 * bounded by {@code CHUNK_SIZE * MAX_RESIDENT_CHUNKS}. This class exists so
 * container parsers can traverse arbitrarily large artifacts without ever
 * loading the whole artifact into memory.
 *
 * <p>When constructed with {@code closeSource = true} (e.g. by path-based
 * container opens that open the source file themselves), {@link #close()}
 * closes the underlying disk; otherwise the caller retains ownership.</p>
 */
public final class ChunkedDisk implements Closeable {

    /** Chunk size for disk reads (well under the 1 MiB read cap). */
    public static final int CHUNK_SIZE = 256 * 1024;

    /** Maximum number of resident chunks (LRU-evicted). */
    public static final int MAX_RESIDENT_CHUNKS = 4;

    private VirtualDisk disk;
    private final boolean closeSource;
    private final long size;
    private final Map<Long, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);

    public ChunkedDisk(@NotNull VirtualDisk disk) {
        this(disk, false);
    }

    public ChunkedDisk(@NotNull VirtualDisk disk, boolean closeSource) {
        this.disk = disk;
        this.closeSource = closeSource;
        this.size = disk.virtualSize();
    }

    public long size() {
        return size;
    }

    @Override
    public void close() throws IOException {
        VirtualDisk toClose = disk;
        disk = null;
        if (closeSource && toClose != null) {
            toClose.close();
        }
    }

    /** Returns a single byte at an absolute offset. */
    public int get(long offset) throws IOException {
        checkOffset(offset, 1);
        long chunk = offset / CHUNK_SIZE;
        int within = (int) (offset % CHUNK_SIZE);
        return chunk(chunk)[within] & 0xff;
    }

    /** Reads a big-endian unsigned 16-bit value at an absolute offset. */
    public int getUnsignedShort(long offset) throws IOException {
        return (get(offset) << 8) | get(offset + 1);
    }

    /** Reads a big-endian 32-bit value at an absolute offset. */
    public long getUnsignedInt(long offset) throws IOException {
        return ((long) get(offset) << 24) | ((long) get(offset + 1) << 16)
                | ((long) get(offset + 2) << 8) | get(offset + 3);
    }

    /**
     * Reads a 32-bit value at an absolute offset with explicit byte order.
     * Values straddling a chunk boundary are assembled from individual chunk
     * reads.
     */
    public int getInt(long offset, @NotNull ByteOrder order) throws IOException {
        checkOffset(offset, 4);
        int b0 = get(offset);
        int b1 = get(offset + 1);
        int b2 = get(offset + 2);
        int b3 = get(offset + 3);
        if (order == ByteOrder.BIG_ENDIAN) {
            return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        }
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    /**
     * Reads a 64-bit value at an absolute offset with explicit byte order.
     * Values straddling a chunk boundary are assembled from individual chunk
     * reads.
     */
    public long getLong(long offset, @NotNull ByteOrder order) throws IOException {
        checkOffset(offset, 8);
        long value = 0;
        if (order == ByteOrder.BIG_ENDIAN) {
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | get(offset + i);
            }
        } else {
            for (int i = 7; i >= 0; i--) {
                value = (value << 8) | get(offset + i);
            }
        }
        return value;
    }

    /** Copies a range into a caller buffer, reading in bounded chunks. */
    public void copyRange(long offset, byte[] dst, int dstOff, int length) throws IOException {
        checkOffset(offset, length);
        if (length < 0 || dstOff < 0 || dstOff + length > dst.length) {
            throw new IndexOutOfBoundsException("copyRange bounds");
        }
        long remaining = length;
        long pos = offset;
        while (remaining > 0) {
            long chunk = pos / CHUNK_SIZE;
            int within = (int) (pos % CHUNK_SIZE);
            byte[] data = chunk(chunk);
            int n = (int) Math.min(remaining, CHUNK_SIZE - within);
            System.arraycopy(data, within, dst, dstOff + (int) (length - remaining), n);
            pos += n;
            remaining -= n;
        }
    }

    /**
     * Returns a defensive copy of a range. The copy is performed in bounded
     * chunks (no single disk read exceeds {@value #CHUNK_SIZE} bytes), but the
     * returned array itself has the requested length: callers needing strict
     * memory bounds should use {@link #stream(long, long)} instead.
     */
    public byte @NotNull [] copyRange(long offset, int length) throws IOException {
        byte[] out = new byte[length];
        copyRange(offset, out, 0, length);
        return out;
    }

    /**
     * Returns a stream over a range of the disk. Reads are issued in bounded
     * chunks; no large intermediate buffer is held.
     */
    public @NotNull InputStream stream(long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException("stream bounds: offset=" + offset
                    + " length=" + length + " size=" + size);
        }
        return new InputStream() {
            private long remaining = length;
            private long pos = offset;
            private final byte[] one = new byte[1];

            @Override
            public int read() throws IOException {
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (remaining == 0) {
                    return -1;
                }
                long chunk = pos / CHUNK_SIZE;
                int within = (int) (pos % CHUNK_SIZE);
                byte[] data = chunk(chunk);
                int n = (int) Math.min(Math.min(len, remaining), CHUNK_SIZE - within);
                System.arraycopy(data, within, b, off, n);
                pos += n;
                remaining -= n;
                return n;
            }
        };
    }

    /** Returns a slice of the resident chunk cache, for inspection in tests. */
    int residentChunkCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private byte[] chunk(long chunk) throws IOException {
        VirtualDisk source = disk;
        if (source == null) {
            throw new IOException("ChunkedDisk is closed");
        }
        synchronized (cache) {
            byte[] data = cache.get(chunk);
            if (data != null) {
                return data;
            }
            long offset = chunk * CHUNK_SIZE;
            int len = (int) Math.min(CHUNK_SIZE, size - offset);
            ByteBuffer buf = source.read(offset, len);
            byte[] bytes = new byte[len];
            buf.get(bytes);
            cache.put(chunk, bytes);
            while (cache.size() > MAX_RESIDENT_CHUNKS) {
                var it = cache.entrySet().iterator();
                it.next();
                it.remove();
            }
            return bytes;
        }
    }

    private void checkOffset(long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw new IndexOutOfBoundsException("ChunkedDisk bounds: offset=" + offset
                    + " length=" + length + " size=" + size);
        }
    }
}
