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
 * cramfs ("Compressed ROMFS") support: a tiny read-only compressed
 * filesystem used by legacy router firmware.
 *
 * <p>See {@link io.spicelabs.saffron.filesystem.cramfs.CramfsSuperblock} for
 * the superblock format and
 * {@link io.spicelabs.saffron.filesystem.cramfs.CramfsFileSystemImpl} for
 * the read-only mount implementation.
 */
package io.spicelabs.saffron.filesystem.cramfs;
