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

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * A named byte stream inside a {@link BinaryContainer}.
 */
public interface ContainerEntry {

    /**
     * Returns the entry path within the container (e.g., "/kernel-payload").
     *
     * @return the absolute entry path
     */
    @NotNull String name();

    /**
     * Returns the entry size in bytes.
     *
     * @return the size, or -1 if the size is not known
     */
    long size();

    /**
     * Opens a new {@link InputStream} for reading the entry contents.
     *
     * @return a fresh input stream
     * @throws IOException if an I/O error occurs
     */
    @NotNull InputStream openStream() throws IOException;

    /**
     * Returns container-specific metadata for this entry.
     *
     * @return an unmodifiable metadata map
     */
    @NotNull Map<String, String> metadata();
}
