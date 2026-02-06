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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.adapter;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStreamSource} backed by an in-memory byte array.
 *
 * <p>This implementation is useful for testing and for working with
 * small disk images that fit entirely in memory.
 *
 * <p>Example usage:
 * <pre>{@code
 * byte[] data = Files.readAllBytes(path);
 * InputStreamSource source = new ByteArrayInputStreamSource(data, "test-image.qcow2");
 * }</pre>
 */
public final class ByteArrayInputStreamSource implements InputStreamSource {

    private final byte[] data;
    private final int offset;
    private final int length;
    private final String description;

    /**
     * Creates a new byte array source from the entire array.
     *
     * @param data the byte array containing the data
     * @param description a human-readable description
     */
    public ByteArrayInputStreamSource(byte @NotNull [] data, @NotNull String description) {
        this(data, 0, data.length, description);
    }

    /**
     * Creates a new byte array source from a portion of the array.
     *
     * @param data the byte array containing the data
     * @param offset the offset within the array
     * @param length the length of the data
     * @param description a human-readable description
     */
    public ByteArrayInputStreamSource(byte @NotNull [] data, int offset, int length, @NotNull String description) {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException(String.format(
                    "Invalid offset/length: offset=%d, length=%d, array.length=%d",
                    offset, length, data.length));
        }
        if (description == null) {
            throw new IllegalArgumentException("Description cannot be null");
        }
        this.data = data;
        this.offset = offset;
        this.length = length;
        this.description = description;
    }

    @Override
    public @NotNull InputStream openStream() {
        return new ByteArrayInputStream(data, offset, length);
    }

    @Override
    public @NotNull InputStream openStream(long streamOffset) throws IOException {
        if (streamOffset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + streamOffset);
        }
        if (streamOffset > length) {
            throw new IOException(String.format(
                    "Offset %d exceeds data length %d", streamOffset, length));
        }
        int intOffset = (int) streamOffset;
        return new ByteArrayInputStream(data, offset + intOffset, length - intOffset);
    }

    @Override
    public long size() {
        return length;
    }

    @Override
    public @NotNull String getDescription() {
        return description;
    }

    @Override
    public boolean supportsRandomAccess() {
        return true;
    }
}
