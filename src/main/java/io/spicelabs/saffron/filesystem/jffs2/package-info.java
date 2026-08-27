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
 * JFFS2 (Journalling Flash File System v2) support.
 *
 * <p>JFFS2 is a log-structured flash filesystem with no superblock; an image
 * is a stream of self-describing nodes. See {@link io.spicelabs.saffron.filesystem.jffs2.Jffs2Node}
 * for the on-disk layout, {@link io.spicelabs.saffron.filesystem.jffs2.Jffs2Superblock}
 * for detection, and {@link io.spicelabs.saffron.filesystem.jffs2.Jffs2FileSystemImpl}
 * for the read-only mount implementation.
 */
package io.spicelabs.saffron.filesystem.jffs2;
