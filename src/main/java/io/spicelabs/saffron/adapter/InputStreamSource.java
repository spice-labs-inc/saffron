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

import java.io.IOException;
import java.io.InputStream;

/**
 * Abstraction for obtaining input streams to disk image data.
 *
 * <p>This interface allows Saffron to work with different data sources:
 * <ul>
 *   <li>Files on the local filesystem</li>
 *   <li>In-memory byte arrays</li>
 *   <li>Network streams</li>
 *   <li>Seekable random access sources</li>
 * </ul>
 *
 * <p>Implementations should be thread-safe for creating new streams,
 * but individual streams are not required to be thread-safe.
 *
 * <p>This is similar to Baharat's {@code PackageSource} pattern.
 */
public interface InputStreamSource {

    /**
     * Opens a new input stream starting at offset 0.
     *
     * <p>The caller is responsible for closing the returned stream.
     *
     * @return a new input stream
     * @throws IOException if the stream cannot be opened
     */
    @NotNull InputStream openStream() throws IOException;

    /**
     * Opens a new input stream starting at the specified offset.
     *
     * <p>For non-seekable sources, this may need to skip bytes
     * from the beginning. The caller is responsible for closing
     * the returned stream.
     *
     * @param offset the byte offset to start reading from
     * @return a new input stream positioned at the offset
     * @throws IOException if the stream cannot be opened or positioned
     */
    @NotNull InputStream openStream(long offset) throws IOException;

    /**
     * Returns the total size of the source in bytes, if known.
     *
     * @return the size in bytes, or -1 if unknown
     * @throws IOException if the size cannot be determined
     */
    long size() throws IOException;

    /**
     * Returns a human-readable description of this source.
     *
     * <p>Used for error messages and debugging.
     *
     * @return a description of the source (e.g., filename, URL)
     */
    @NotNull String getDescription();

    /**
     * Checks if this source supports efficient random access.
     *
     * <p>If true, calls to {@link #openStream(long)} are efficient.
     * If false, seeking may require reading and discarding data.
     *
     * @return true if random access is efficient
     */
    default boolean supportsRandomAccess() {
        return false;
    }

    /**
     * Closes this source and releases any underlying resources.
     *
     * <p>After calling close, further operations on this source
     * may throw {@link IOException}.
     *
     * @throws IOException if an I/O error occurs
     */
    default void close() throws IOException {
        // Default: no-op
    }
}
