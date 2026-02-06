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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Represents an entry (file, directory, or special file) within a filesystem.
 *
 * <p>This sealed interface follows the pattern of Baharat's entry types,
 * using records for immutable data carriers where appropriate.
 *
 * <p>FileSystemEntry is designed to work across different filesystem types
 * (ext4, NTFS, FAT32, XFS) while exposing common attributes uniformly.
 *
 * @see FileSystem
 */
public sealed interface FileSystemEntry
        permits FileSystemEntry.RegularFile, FileSystemEntry.Directory,
                FileSystemEntry.SymbolicLink, FileSystemEntry.SpecialFile {

    /**
     * Returns the name of this entry (filename only, not full path).
     *
     * @return the entry name
     */
    @NotNull String name();

    /**
     * Returns the full path of this entry within the filesystem.
     *
     * @return the absolute path from filesystem root
     */
    @NotNull String path();

    /**
     * Returns the size in bytes.
     *
     * <p>For directories, this may be the directory entry size or 0
     * depending on the filesystem.
     *
     * @return the size in bytes
     */
    long size();

    /**
     * Returns the creation time, if available.
     *
     * <p>Not all filesystems track creation time.
     *
     * @return the creation time, or empty if not available
     */
    @NotNull Optional<Instant> creationTime();

    /**
     * Returns the last modification time.
     *
     * @return the modification time, or empty if not available
     */
    @NotNull Optional<Instant> modificationTime();

    /**
     * Returns the last access time, if available.
     *
     * @return the access time, or empty if not available
     */
    @NotNull Optional<Instant> accessTime();

    /**
     * Returns filesystem-specific attributes.
     *
     * <p>The map contains attributes specific to the underlying filesystem:
     * <ul>
     *   <li>ext4: mode, uid, gid, flags, xattrs</li>
     *   <li>NTFS: attributes, security descriptor, streams</li>
     *   <li>FAT32: attributes (readonly, hidden, system, archive)</li>
     *   <li>XFS: mode, uid, gid, project id</li>
     * </ul>
     *
     * @return an unmodifiable map of attributes
     */
    @NotNull Map<String, Object> attributes();

    /**
     * Returns the type of this entry.
     *
     * @return the entry type
     */
    @NotNull EntryType type();

    /**
     * Entry type enumeration.
     */
    enum EntryType {
        REGULAR_FILE,
        DIRECTORY,
        SYMBOLIC_LINK,
        BLOCK_DEVICE,
        CHARACTER_DEVICE,
        FIFO,
        SOCKET,
        UNKNOWN
    }

    // ========================================================================
    // Entry type implementations
    // ========================================================================

    /**
     * A regular file entry.
     */
    non-sealed interface RegularFile extends FileSystemEntry {
        @Override
        default @NotNull EntryType type() {
            return EntryType.REGULAR_FILE;
        }

        /**
         * Opens an InputStream to read the file contents.
         *
         * @return an InputStream for reading the file
         * @throws IOException if an I/O error occurs
         */
        @NotNull InputStream openStream() throws IOException;

        /**
         * Reads the entire file contents into a byte array.
         *
         * <p>Use with caution for large files. Consider using
         * {@link #openStream()} for large files.
         *
         * @return the file contents
         * @throws IOException if an I/O error occurs
         * @throws OutOfMemoryError if the file is too large
         */
        byte[] readAllBytes() throws IOException;
    }

    /**
     * A directory entry.
     */
    non-sealed interface Directory extends FileSystemEntry {
        @Override
        default @NotNull EntryType type() {
            return EntryType.DIRECTORY;
        }

        /**
         * Lists the entries in this directory.
         *
         * @return a stream of directory entries
         * @throws IOException if an I/O error occurs
         */
        @NotNull Stream<FileSystemEntry> list() throws IOException;

        /**
         * Finds an entry by name in this directory.
         *
         * @param name the entry name to find
         * @return the entry, or empty if not found
         * @throws IOException if an I/O error occurs
         */
        @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException;
    }

    /**
     * A symbolic link entry.
     */
    non-sealed interface SymbolicLink extends FileSystemEntry {
        @Override
        default @NotNull EntryType type() {
            return EntryType.SYMBOLIC_LINK;
        }

        /**
         * Returns the target path of the symbolic link.
         *
         * @return the link target path
         */
        @NotNull String target();

        /**
         * Resolves the symbolic link to its target entry.
         *
         * @return the target entry, or empty if the target doesn't exist
         * @throws IOException if an I/O error occurs
         */
        @NotNull Optional<FileSystemEntry> resolve() throws IOException;
    }

    /**
     * A special file entry (device, FIFO, socket).
     */
    non-sealed interface SpecialFile extends FileSystemEntry {
        /**
         * Returns the device major number, if applicable.
         *
         * @return the major device number, or empty
         */
        @NotNull Optional<Integer> majorDevice();

        /**
         * Returns the device minor number, if applicable.
         *
         * @return the minor device number, or empty
         */
        @NotNull Optional<Integer> minorDevice();
    }

    // ========================================================================
    // Record implementations for simple immutable entries
    // ========================================================================

    /**
     * Basic file information record.
     *
     * @param name the file name
     * @param path the full path
     * @param size the size in bytes
     * @param creationTime the creation time (may be null)
     * @param modificationTime the modification time (may be null)
     * @param accessTime the access time (may be null)
     * @param type the entry type
     */
    record BasicInfo(
            @NotNull String name,
            @NotNull String path,
            long size,
            @Nullable Instant creationTime,
            @Nullable Instant modificationTime,
            @Nullable Instant accessTime,
            @NotNull EntryType type
    ) {}

    /**
     * POSIX-style permissions.
     *
     * @param mode the Unix mode bits (e.g., 0755)
     * @param uid the owner user ID
     * @param gid the owner group ID
     * @param owner the owner name (may be null if not available)
     * @param group the group name (may be null if not available)
     */
    record PosixPermissions(
            int mode,
            int uid,
            int gid,
            @Nullable String owner,
            @Nullable String group
    ) {
        /**
         * Returns true if the owner has read permission.
         */
        public boolean ownerRead() {
            return (mode & 0400) != 0;
        }

        /**
         * Returns true if the owner has write permission.
         */
        public boolean ownerWrite() {
            return (mode & 0200) != 0;
        }

        /**
         * Returns true if the owner has execute permission.
         */
        public boolean ownerExecute() {
            return (mode & 0100) != 0;
        }

        /**
         * Returns true if the group has read permission.
         */
        public boolean groupRead() {
            return (mode & 040) != 0;
        }

        /**
         * Returns true if the group has write permission.
         */
        public boolean groupWrite() {
            return (mode & 020) != 0;
        }

        /**
         * Returns true if the group has execute permission.
         */
        public boolean groupExecute() {
            return (mode & 010) != 0;
        }

        /**
         * Returns true if others have read permission.
         */
        public boolean othersRead() {
            return (mode & 04) != 0;
        }

        /**
         * Returns true if others have write permission.
         */
        public boolean othersWrite() {
            return (mode & 02) != 0;
        }

        /**
         * Returns true if others have execute permission.
         */
        public boolean othersExecute() {
            return (mode & 01) != 0;
        }

        /**
         * Returns true if the setuid bit is set.
         */
        public boolean setuid() {
            return (mode & 04000) != 0;
        }

        /**
         * Returns true if the setgid bit is set.
         */
        public boolean setgid() {
            return (mode & 02000) != 0;
        }

        /**
         * Returns true if the sticky bit is set.
         */
        public boolean sticky() {
            return (mode & 01000) != 0;
        }
    }
}
