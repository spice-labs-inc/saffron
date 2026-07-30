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

/**
 * Support for exposing single compressed non-archive payloads as Saffron binary containers.
 *
 * <p>This package detects files such as {@code .gz}, {@code .xz}, and {@code .bz2} that
 * contain a single payload (not a tar archive) and exposes them as a
 * {@link io.spicelabs.saffron.container.BinaryContainer} with one entry: {@code /payload}.</p>
 *
 * <p>Decompression is bounded by {@link io.spicelabs.saffron.SecurityPolicy} and streamed
 * to a temporary file so that arbitrarily large payloads can be represented without loading
 * them into memory.</p>
 */
package io.spicelabs.saffron.container.compressed;
