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
package io.spicelabs.saffron.filesystem.ext4;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an ext4 directory entry.
 *
 * <p>Directory entry structure (linear directory):
 * <pre>
 * Offset  Size  Description
 * 0       4     inode (inode number)
 * 4       2     rec_len (record length)
 * 6       1     name_len (name length)
 * 7       1     file_type (file type, if feature enabled)
 * 8       N     name (file name, not null-terminated)
 * </pre>
 *
 * <p>File types:
 * <ul>
 *   <li>0 = EXT4_FT_UNKNOWN</li>
 *   <li>1 = EXT4_FT_REG_FILE</li>
 *   <li>2 = EXT4_FT_DIR</li>
 *   <li>3 = EXT4_FT_CHRDEV</li>
 *   <li>4 = EXT4_FT_BLKDEV</li>
 *   <li>5 = EXT4_FT_FIFO</li>
 *   <li>6 = EXT4_FT_SOCK</li>
 *   <li>7 = EXT4_FT_SYMLINK</li>
 * </ul>
 */
public record Ext4DirectoryEntry(
        long inode,
        int recLen,
        int nameLen,
        int fileType,
        @NotNull String name
) {
    // File type constants
    public static final int FT_UNKNOWN = 0;
    public static final int FT_REG_FILE = 1;
    public static final int FT_DIR = 2;
    public static final int FT_CHRDEV = 3;
    public static final int FT_BLKDEV = 4;
    public static final int FT_FIFO = 5;
    public static final int FT_SOCK = 6;
    public static final int FT_SYMLINK = 7;

    /**
     * Parses directory entries from a block of data.
     *
     * @param data the directory block data
     * @param hasFileType whether the filesystem has file type feature
     * @return list of directory entries
     */
    public static @NotNull List<Ext4DirectoryEntry> parseBlock(@NotNull byte[] data, boolean hasFileType) {
        List<Ext4DirectoryEntry> entries = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int offset = 0;
        while (offset < data.length) {
            if (offset + 8 > data.length) break;

            long inode = buffer.getInt(offset) & 0xFFFFFFFFL;
            int recLen = buffer.getShort(offset + 4) & 0xFFFF;
            int nameLen = buffer.get(offset + 6) & 0xFF;
            int fileType = hasFileType ? (buffer.get(offset + 7) & 0xFF) : FT_UNKNOWN;

            // Validate record length
            if (recLen < 8 || recLen > data.length - offset) {
                break;
            }

            // Skip deleted entries (inode == 0) but not "." or ".."
            if (inode != 0 && nameLen > 0 && nameLen <= recLen - 8) {
                byte[] nameBytes = new byte[nameLen];
                buffer.position(offset + 8);
                buffer.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                entries.add(new Ext4DirectoryEntry(inode, recLen, nameLen, fileType, name));
            }

            offset += recLen;
        }

        return entries;
    }

    /**
     * Returns whether this entry is a directory.
     */
    public boolean isDirectory() {
        return fileType == FT_DIR;
    }

    /**
     * Returns whether this entry is a regular file.
     */
    public boolean isRegularFile() {
        return fileType == FT_REG_FILE;
    }

    /**
     * Returns whether this entry is a symbolic link.
     */
    public boolean isSymbolicLink() {
        return fileType == FT_SYMLINK;
    }

    /**
     * Returns whether this is the current directory entry (".").
     */
    public boolean isDot() {
        return ".".equals(name);
    }

    /**
     * Returns whether this is the parent directory entry ("..").
     */
    public boolean isDotDot() {
        return "..".equals(name);
    }
}
