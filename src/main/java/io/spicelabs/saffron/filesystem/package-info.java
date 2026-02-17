/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Filesystem detection and metadata reading.
 *
 * <p>This package provides classes for detecting and parsing filesystem
 * metadata from virtual disk images. It supports reading superblocks
 * and boot sectors to extract filesystem information.
 *
 * <h2>Supported filesystems:</h2>
 * <ul>
 *   <li><b>ext2/ext3/ext4</b> - Linux extended filesystems</li>
 *   <li><b>NTFS</b> - Windows NT filesystem</li>
 *   <li><b>FAT12/FAT16/FAT32</b> - DOS/Windows FAT filesystems</li>
 *   <li><b>XFS</b> - Linux XFS filesystem</li>
 * </ul>
 *
 * <h2>Example usage:</h2>
 * <pre>{@code
 * try (VirtualDisk disk = DiskReader.open(path)) {
 *     // Detect filesystem at partition start
 *     Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, partitionOffset);
 *
 *     if (info.isPresent()) {
 *         System.out.println("Type: " + info.get().type());
 *         System.out.println("Label: " + info.get().label().orElse("(none)"));
 *         System.out.println("UUID: " + info.get().uuid().orElse("(none)"));
 *         System.out.println("Size: " + info.get().formattedTotalSize());
 *     }
 * }
 * }</pre>
 *
 * @see io.spicelabs.saffron.filesystem.FilesystemDetector
 * @see io.spicelabs.saffron.filesystem.FilesystemInfo
 */
package io.spicelabs.saffron.filesystem;
