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
package io.spicelabs.saffron.container;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Internal abstraction for a structured binary that is not a disk image or
 * filesystem but contains named byte streams.
 */
public interface BinaryContainer extends Closeable {

    /**
     * Returns the container format.
     *
     * @return the format
     */
    @NotNull ContainerFormat format();

    /**
     * Returns all entries in the container.
     *
     * @return the list of entries
     */
    @NotNull List<ContainerEntry> entries();

    /**
     * Finds an entry by its absolute path.
     *
     * @param path the entry path
     * @return the entry, or empty if not found
     */
    @NotNull Optional<ContainerEntry> findEntry(@NotNull String path);

    /**
     * Returns container-level metadata.
     *
     * @return an unmodifiable metadata map
     */
    @NotNull Map<String, String> metadata();

    /**
     * Returns the source size in bytes.
     *
     * @return the source size
     */
    long size();

    /**
     * Releases resources held by this container.
     *
     * <p>The default implementation is a no-op; containers that create temporary
     * files or hold other closeable resources should override this method.</p>
     *
     * @throws IOException if an I/O error occurs during cleanup
     */
    @Override
    default void close() throws IOException {
        // Default: no resources to release
    }
}
