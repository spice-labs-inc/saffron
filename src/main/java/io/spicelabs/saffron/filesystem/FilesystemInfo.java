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
package io.spicelabs.saffron.filesystem;

import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Contains information about a detected filesystem.
 *
 * <p>This record holds metadata extracted from the filesystem's superblock
 * or boot sector without requiring full filesystem parsing.
 *
 * @param type the filesystem type (ext4, NTFS, FAT32, XFS)
 * @param version the filesystem version string
 * @param label the volume label, if set
 * @param uuid the filesystem UUID or serial number
 * @param totalSize the total filesystem size in bytes
 * @param usedSize the used space in bytes (0 if not determined)
 * @param freeSize the free space in bytes (0 if not determined)
 * @param blockSize the block/cluster size in bytes
 * @param inodeCount the number of inodes (0 for non-Unix filesystems)
 */
public record FilesystemInfo(
        @NotNull FileSystemType type,
        @NotNull String version,
        @NotNull Optional<String> label,
        @NotNull Optional<String> uuid,
        long totalSize,
        long usedSize,
        long freeSize,
        int blockSize,
        long inodeCount
) {

    /**
     * Returns the filesystem type name.
     *
     * @return the type name (e.g., "ext4", "ntfs")
     */
    public @NotNull String typeName() {
        return type.getName();
    }

    /**
     * Returns the filesystem description.
     *
     * @return the description
     */
    public @NotNull String description() {
        return type.getDescription();
    }

    /**
     * Returns the used percentage (0-100).
     *
     * @return the used percentage, or 0 if unknown
     */
    public double usedPercentage() {
        if (totalSize == 0) return 0;
        return (usedSize * 100.0) / totalSize;
    }

    /**
     * Returns the free percentage (0-100).
     *
     * @return the free percentage, or 0 if unknown
     */
    public double freePercentage() {
        if (totalSize == 0) return 0;
        return (freeSize * 100.0) / totalSize;
    }

    /**
     * Returns a map of filesystem metadata.
     *
     * @return an unmodifiable map of metadata
     */
    public @NotNull Map<String, String> toMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("fs.type", type.getName());
        metadata.put("fs.version", version);
        label.ifPresent(l -> metadata.put("fs.label", l));
        uuid.ifPresent(u -> metadata.put("fs.uuid", u));
        metadata.put("fs.totalSize", String.valueOf(totalSize));
        if (usedSize > 0) {
            metadata.put("fs.usedSize", String.valueOf(usedSize));
        }
        if (freeSize > 0) {
            metadata.put("fs.freeSize", String.valueOf(freeSize));
        }
        metadata.put("fs.blockSize", String.valueOf(blockSize));
        if (inodeCount > 0) {
            metadata.put("fs.inodeCount", String.valueOf(inodeCount));
        }
        return Map.copyOf(metadata);
    }

    /**
     * Formats the total size as a human-readable string.
     *
     * @return the formatted size (e.g., "10.5 GB")
     */
    public @NotNull String formattedTotalSize() {
        return formatSize(totalSize);
    }

    /**
     * Formats the used size as a human-readable string.
     *
     * @return the formatted size
     */
    public @NotNull String formattedUsedSize() {
        return formatSize(usedSize);
    }

    /**
     * Formats the free size as a human-readable string.
     *
     * @return the formatted size
     */
    public @NotNull String formattedFreeSize() {
        return formatSize(freeSize);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        } else {
            return String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.getName()).append(" (").append(version).append(")");
        label.ifPresent(l -> sb.append(" \"").append(l).append("\""));
        sb.append(" - ").append(formattedTotalSize());
        return sb.toString();
    }
}
