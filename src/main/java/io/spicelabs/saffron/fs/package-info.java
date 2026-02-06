/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

/**
 * Filesystem abstractions for reading filesystem contents within disk images.
 *
 * <p>This package provides a unified API for accessing files and directories
 * across different filesystem types (ext4, NTFS, FAT32, XFS).
 *
 * <h2>Key Interfaces</h2>
 * <ul>
 *   <li>{@link io.spicelabs.saffron.fs.FileSystem} - Represents a mounted filesystem</li>
 *   <li>{@link io.spicelabs.saffron.fs.FileSystemEntry} - Represents a file, directory, or special file</li>
 * </ul>
 *
 * @see io.spicelabs.saffron.fs.FileSystem
 * @see io.spicelabs.saffron.fs.FileSystemEntry
 */
package io.spicelabs.saffron.fs;
