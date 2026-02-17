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
 * An InputStream wrapper that enforces a maximum byte limit.
 *
 * <p>This is a critical security component for preventing decompression bombs
 * and other resource exhaustion attacks. When the limit is exceeded, an
 * {@link IOException} is thrown with a descriptive message.
 *
 * <p>The behavior is:
 * <ul>
 *   <li>Reading exactly up to the limit succeeds</li>
 *   <li>Attempting to read beyond the limit when more data is available throws</li>
 *   <li>When limit is reached and underlying stream is at EOF, returns -1</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Limit decompression to 16 MB
 * try (BoundedInputStream bis = new BoundedInputStream(compressedStream, 16 * 1024 * 1024)) {
 *     byte[] data = bis.readAllBytes();
 * } catch (IOException e) {
 *     if (e.getMessage().contains("limit exceeded")) {
 *         // Potential decompression bomb detected
 *     }
 * }
 * }</pre>
 */
public class BoundedInputStream extends FilterInputStream {

    private final long limit;
    private long bytesRead;
    private long mark;
    private boolean limitExceeded;

    /**
     * Creates a new BoundedInputStream with the specified limit.
     *
     * @param in the underlying input stream
     * @param limit the maximum number of bytes allowed to be read
     * @throws IllegalArgumentException if limit is negative
     */
    public BoundedInputStream(@NotNull InputStream in, long limit) {
        super(in);
        if (limit < 0) {
            throw new IllegalArgumentException("Limit must be non-negative: " + limit);
        }
        this.limit = limit;
        this.bytesRead = 0;
        this.mark = -1;
        this.limitExceeded = false;
    }

    /**
     * Returns the maximum number of bytes that can be read.
     *
     * @return the byte limit
     */
    public long getLimit() {
        return limit;
    }

    /**
     * Returns the number of bytes read so far.
     *
     * @return bytes read count
     */
    public long getBytesRead() {
        return bytesRead;
    }

    /**
     * Returns the number of bytes remaining before the limit is reached.
     *
     * @return remaining bytes, or 0 if limit exceeded
     */
    public long getRemaining() {
        return Math.max(0, limit - bytesRead);
    }

    @Override
    public int read() throws IOException {
        // At limit - check if underlying stream has more data
        if (bytesRead >= limit) {
            // Try to read from underlying stream to see if there's more data
            int next = in.read();
            if (next != -1) {
                // There's more data but we're at limit - throw
                limitExceeded = true;
                throwLimitExceeded();
            }
            return -1; // Underlying stream is also at EOF
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

        // Calculate remaining budget
        long remaining = limit - bytesRead;

        if (remaining <= 0) {
            // At limit - check if underlying stream has more data
            int next = in.read();
            if (next != -1) {
                // There's more data but we're at limit - throw
                limitExceeded = true;
                throwLimitExceeded();
            }
            return -1; // Underlying stream is also at EOF
        }

        // If request exceeds remaining budget and underlying stream has more data, throw
        if (len > remaining) {
            // Read what we can first
            int maxRead = (int) remaining;
            int result = in.read(b, off, maxRead);
            if (result > 0) {
                bytesRead += result;
            }

            // Now check if there's more data that would exceed limit
            if (result >= 0 && bytesRead >= limit) {
                int next = in.read();
                if (next != -1) {
                    // There's more data but we're at limit - throw
                    limitExceeded = true;
                    throwLimitExceeded();
                }
            }
            return result;
        }

        // Normal read within budget
        int result = in.read(b, off, len);
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

        long remaining = limit - bytesRead;

        if (remaining <= 0) {
            // At limit - check if underlying stream has more data
            if (in.available() > 0 || in.read() != -1) {
                limitExceeded = true;
                throwLimitExceeded();
            }
            return 0;
        }

        // If skip amount exceeds remaining, throw if there's more data
        if (n > remaining) {
            long skipped = in.skip(remaining);
            bytesRead += skipped;

            // Check if there's more data
            if (in.available() > 0 || in.read() != -1) {
                limitExceeded = true;
                throwLimitExceeded();
            }
            return skipped;
        }

        long skipped = in.skip(n);
        bytesRead += skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        int avail = in.available();
        long remaining = limit - bytesRead;
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(avail, remaining);
    }

    @Override
    public synchronized void mark(int readlimit) {
        in.mark(readlimit);
        mark = bytesRead;
    }

    @Override
    public synchronized void reset() throws IOException {
        in.reset();
        if (mark >= 0) {
            bytesRead = mark;
            limitExceeded = false;
        }
    }

    @Override
    public boolean markSupported() {
        return in.markSupported();
    }

    private void throwLimitExceeded() throws IOException {
        throw new IOException(String.format(
                "Byte limit exceeded: read %d bytes but limit is %d (potential decompression bomb)",
                bytesRead, limit));
    }

    /**
     * Checks if the limit has been exceeded.
     *
     * @return true if an attempt was made to read beyond the limit
     */
    public boolean isLimitExceeded() {
        return limitExceeded;
    }
}
