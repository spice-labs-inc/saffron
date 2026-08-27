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
 * YAFFS2 (Yet Another Flash File System 2) support: a log-structured NAND
 * filesystem used by embedded and industrial devices.
 *
 * <p>YAFFS2 has no superblock; an image is a sequence of page+spare chunks.
 * See {@link io.spicelabs.saffron.filesystem.yaffs2.Yaffs2Node} for the
 * on-flash structures, {@link io.spicelabs.saffron.filesystem.yaffs2.Yaffs2Superblock}
 * for geometry detection, and
 * {@link io.spicelabs.saffron.filesystem.yaffs2.Yaffs2FileSystemImpl} for
 * the read-only mount implementation.
 */
package io.spicelabs.saffron.filesystem.yaffs2;
