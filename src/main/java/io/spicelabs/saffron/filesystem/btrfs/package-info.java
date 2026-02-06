/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

/**
 * Btrfs filesystem implementation for read-only access.
 *
 * <p>This package provides support for reading Btrfs (B-tree filesystem) images,
 * commonly used by Fedora, openSUSE, and other Linux distributions.
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link io.spicelabs.saffron.filesystem.btrfs.BtrfsSuperblock} - Superblock parsing</li>
 *   <li>{@link io.spicelabs.saffron.filesystem.btrfs.BtrfsFileSystemImpl} - Main filesystem implementation</li>
 * </ul>
 *
 * <h2>Btrfs Structure Overview</h2>
 * <ul>
 *   <li>Superblock at offset 64KB (65536 bytes)</li>
 *   <li>All metadata stored in B-trees</li>
 *   <li>Logical-to-physical address mapping via chunk tree</li>
 *   <li>Default subvolume (FS_TREE, object ID 5)</li>
 * </ul>
 *
 * @see io.spicelabs.saffron.fs.FileSystem.BtrfsFileSystem
 */
package io.spicelabs.saffron.filesystem.btrfs;
