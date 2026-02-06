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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.fs;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Represents a filesystem contained within a virtual disk image.
 *
 * <p>This sealed interface provides a unified API for accessing different
 * filesystem types. Each filesystem type has its own implementation that
 * handles format-specific details.
 *
 * @see FileSystemEntry
 */
public sealed interface FileSystem extends Closeable
        permits FileSystem.Ext4FileSystem, FileSystem.NtfsFileSystem,
                FileSystem.Fat32FileSystem, FileSystem.ExFatFileSystem, FileSystem.XfsFileSystem,
                FileSystem.BtrfsFileSystem, FileSystem.HfsPlusFileSystem,
                FileSystem.ApfsFileSystem {

    /**
     * Returns the filesystem type.
     *
     * @return the filesystem type
     */
    @NotNull FileSystemType type();

    /**
     * Returns the root directory of the filesystem.
     *
     * @return the root directory entry
     * @throws IOException if an I/O error occurs
     */
    @NotNull FileSystemEntry.Directory root() throws IOException;

    /**
     * Resolves a path to a filesystem entry.
     *
     * <p>The path must be absolute (starting with "/").
     *
     * @param path the path to resolve
     * @return the entry at the given path, or empty if not found
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if the path is not absolute
     */
    @NotNull Optional<FileSystemEntry> resolve(@NotNull String path) throws IOException;

    /**
     * Walks the filesystem tree starting from the root.
     *
     * <p>This returns a depth-first stream of all entries in the filesystem.
     *
     * @return a stream of all filesystem entries
     * @throws IOException if an I/O error occurs
     */
    @NotNull Stream<FileSystemEntry> walk() throws IOException;

    /**
     * Walks the filesystem tree starting from the given path.
     *
     * @param path the path to start from
     * @param maxDepth the maximum depth to traverse (0 = only the path itself)
     * @return a stream of filesystem entries
     * @throws IOException if an I/O error occurs
     */
    @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth) throws IOException;

    /**
     * Returns the total size of the filesystem in bytes.
     *
     * @return the total filesystem size
     */
    long totalSize();

    /**
     * Returns the used space in bytes.
     *
     * @return the used space
     */
    long usedSize();

    /**
     * Returns the free space in bytes.
     *
     * @return the free space
     */
    long freeSize();

    /**
     * Returns the filesystem label, if set.
     *
     * @return the filesystem label, or empty if not set
     */
    @NotNull Optional<String> label();

    /**
     * Returns the filesystem UUID, if available.
     *
     * @return the filesystem UUID, or empty if not available
     */
    @NotNull Optional<String> uuid();

    /**
     * Returns filesystem-specific metadata.
     *
     * @return an unmodifiable map of metadata
     */
    @NotNull Map<String, String> metadata();

    /**
     * Filesystem type enumeration.
     */
    enum FileSystemType {
        EXT4("ext4", "Linux ext4 filesystem"),
        NTFS("ntfs", "Windows NTFS filesystem"),
        FAT32("fat32", "FAT32 filesystem"),
        EXFAT("exfat", "exFAT filesystem"),
        XFS("xfs", "Linux XFS filesystem"),
        BTRFS("btrfs", "Linux Btrfs filesystem"),
        HFS_PLUS("hfsplus", "macOS HFS+ filesystem"),
        APFS("apfs", "macOS APFS filesystem"),
        UNKNOWN("unknown", "Unknown filesystem type");

        private final String name;
        private final String description;

        FileSystemType(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }

    // ========================================================================
    // Filesystem type implementations (sealed permits)
    // ========================================================================

    /**
     * ext4 filesystem implementation.
     */
    non-sealed interface Ext4FileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.EXT4;
        }

        /**
         * Returns the filesystem features enabled.
         */
        @NotNull java.util.Set<String> features();

        /**
         * Returns the block size in bytes.
         */
        int blockSize();

        /**
         * Returns the inode count.
         */
        long inodeCount();
    }

    /**
     * NTFS filesystem implementation.
     */
    non-sealed interface NtfsFileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.NTFS;
        }

        /**
         * Returns the cluster size in bytes.
         */
        int clusterSize();

        /**
         * Returns the NTFS version.
         */
        @NotNull String version();
    }

    /**
     * FAT32 filesystem implementation.
     */
    non-sealed interface Fat32FileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.FAT32;
        }

        /**
         * Returns the sectors per cluster.
         */
        int sectorsPerCluster();

        /**
         * Returns the FAT type (FAT12, FAT16, FAT32).
         */
        @NotNull String fatType();
    }

    /**
     * exFAT filesystem implementation.
     */
    non-sealed interface ExFatFileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.EXFAT;
        }

        /**
         * Returns the cluster size in bytes.
         */
        int clusterSize();

        /**
         * Returns the exFAT revision.
         */
        @NotNull String revision();
    }

    /**
     * XFS filesystem implementation.
     */
    non-sealed interface XfsFileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.XFS;
        }

        /**
         * Returns the block size in bytes.
         */
        int blockSize();

        /**
         * Returns the sector size in bytes.
         */
        int sectorSize();

        /**
         * Returns the allocation group count.
         */
        int agCount();
    }

    /**
     * Btrfs filesystem implementation.
     */
    non-sealed interface BtrfsFileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.BTRFS;
        }

        /**
         * Returns the node size in bytes (typically 16384).
         */
        int nodeSize();

        /**
         * Returns the sector size in bytes (typically 4096).
         */
        int sectorSize();

        /**
         * Returns the Btrfs generation number.
         */
        long generation();
    }

    /**
     * HFS+ filesystem implementation.
     */
    non-sealed interface HfsPlusFileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.HFS_PLUS;
        }

        /**
         * Returns the block size in bytes.
         */
        int blockSize();

        /**
         * Returns the volume name.
         */
        @NotNull String volumeName();
    }

    /**
     * APFS filesystem implementation.
     */
    non-sealed interface ApfsFileSystem extends FileSystem {
        @Override
        default @NotNull FileSystemType type() {
            return FileSystemType.APFS;
        }

        /**
         * Returns the block size in bytes.
         */
        int blockSize();

        /**
         * Returns the volume name.
         */
        @NotNull String volumeName();
    }
}
