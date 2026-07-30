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

import org.jetbrains.annotations.NotNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream wrapper that exposes a fixed-length region of an underlying
 * stream and then returns end-of-stream.
 *
 * <p>Unlike {@link BoundedInputStream}, this class does not throw if the
 * underlying stream continues beyond the region. It is intended for zero-copy
 * views of a sub-region inside a larger file or disk image.</p>
 */
public final class RegionInputStream extends FilterInputStream {

    private final long length;
    private long bytesRead;

    /**
     * Creates a region stream over the next {@code length} bytes of {@code in}.
     *
     * @param in the underlying input stream, positioned at the region start
     * @param length the number of bytes in the region
     * @throws IllegalArgumentException if length is negative
     */
    public RegionInputStream(@NotNull InputStream in, long length) {
        super(in);
        if (length < 0) {
            throw new IllegalArgumentException("length cannot be negative: " + length);
        }
        this.length = length;
        this.bytesRead = 0;
    }

    @Override
    public int read() throws IOException {
        if (bytesRead >= length) {
            return -1;
        }
        int result = in.read();
        if (result != -1) {
            bytesRead++;
        }
        return result;
    }

    @Override
    public int read(byte @NotNull [] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte @NotNull [] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        long remaining = length - bytesRead;
        if (remaining <= 0) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        int result = in.read(b, off, toRead);
        if (result > 0) {
            bytesRead += result;
        }
        return result;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long remaining = length - bytesRead;
        if (remaining <= 0) {
            return 0;
        }
        long toSkip = Math.min(n, remaining);
        long skipped = in.skip(toSkip);
        bytesRead += skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        int avail = in.available();
        long remaining = length - bytesRead;
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(avail, remaining);
    }

    @Override
    public synchronized void mark(int readlimit) {
        in.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
        in.reset();
    }

    @Override
    public boolean markSupported() {
        return in.markSupported();
    }
}
