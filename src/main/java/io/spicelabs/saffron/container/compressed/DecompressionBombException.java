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
package io.spicelabs.saffron.container.compressed;

import io.spicelabs.saffron.exception.ResourceLimitException;
import org.jetbrains.annotations.NotNull;

/**
 * Exception thrown when a compressed single payload would exceed the configured
 * decompressed size limit.
 */
public final class DecompressionBombException extends ResourceLimitException {

    public DecompressionBombException(long limit, long attempted) {
        super(
                String.format("Decompressed size %d exceeds limit %d (potential decompression bomb)",
                        attempted, limit),
                "decompressed_size",
                limit,
                attempted);
    }

    public static @NotNull DecompressionBombException of(long limit, long attempted) {
        return new DecompressionBombException(limit, attempted);
    }
}
