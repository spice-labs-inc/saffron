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
package io.spicelabs.saffron.filesystem.exfat;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents an exFAT directory entry.
 *
 * <p>exFAT uses a set of 32-byte directory entries to represent files and directories.
 * A complete file/directory entry consists of:
 * <ul>
 *   <li>One File Directory Entry (type 0x85)</li>
 *   <li>One Stream Extension Entry (type 0xC0)</li>
 *   <li>One or more File Name Extension Entries (type 0xC1)</li>
 * </ul>
 *
 * <p>Other entry types:
 * <ul>
 *   <li>0x00 - End of directory</li>
 *   <li>0x81 - Allocation Bitmap</li>
 *   <li>0x82 - Up-Case Table</li>
 *   <li>0x83 - Volume Label</li>
 * </ul>
 */
public record ExFatDirectoryEntry(
        @NotNull String name,
        int fileAttributes,
        int firstCluster,
        long dataLength,
        long validDataLength,
        @NotNull Optional<Instant> creationTime,
        @NotNull Optional<Instant> modificationTime,
        @NotNull Optional<Instant> accessTime,
        boolean noFatChain
) {

    /** Directory entry size */
    public static final int ENTRY_SIZE = 32;

    // Entry type codes
    public static final int TYPE_END_OF_DIRECTORY = 0x00;
    public static final int TYPE_ALLOCATION_BITMAP = 0x81;
    public static final int TYPE_UPCASE_TABLE = 0x82;
    public static final int TYPE_VOLUME_LABEL = 0x83;
    public static final int TYPE_FILE_ENTRY = 0x85;
    public static final int TYPE_STREAM_EXTENSION = 0xC0;
    public static final int TYPE_FILE_NAME = 0xC1;

    // Entry type masks
    public static final int TYPE_IMPORTANCE_MASK = 0x20; // Critical (0) vs Benign (1)
    public static final int TYPE_CATEGORY_MASK = 0x40;   // Primary (0) vs Secondary (1)
    public static final int TYPE_IN_USE_MASK = 0x80;     // Not in use (0) vs In use (1)

    // File attributes
    public static final int ATTR_READ_ONLY = 0x01;
    public static final int ATTR_HIDDEN = 0x02;
    public static final int ATTR_SYSTEM = 0x04;
    public static final int ATTR_DIRECTORY = 0x10;
    public static final int ATTR_ARCHIVE = 0x20;

    /**
     * Parses directory entries from a buffer and returns file/directory entries.
     *
     * @param buffer the buffer containing directory entries
     * @return list of parsed entries
     */
    public static @NotNull List<ExFatDirectoryEntry> parseDirectory(@NotNull ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        List<ExFatDirectoryEntry> entries = new ArrayList<>();

        while (buffer.remaining() >= ENTRY_SIZE) {
            int startPos = buffer.position();
            int entryType = buffer.get(startPos) & 0xFF;

            // Check for end of directory
            if (entryType == TYPE_END_OF_DIRECTORY) {
                break;
            }

            // Skip if not in use
            if ((entryType & TYPE_IN_USE_MASK) == 0) {
                buffer.position(startPos + ENTRY_SIZE);
                continue;
            }

            // Look for file directory entry
            if (entryType == TYPE_FILE_ENTRY) {
                Optional<ExFatDirectoryEntry> entry = parseFileEntry(buffer);
                entry.ifPresent(entries::add);
            } else {
                // Skip other entry types
                buffer.position(startPos + ENTRY_SIZE);
            }
        }

        return entries;
    }

    /**
     * Parses a complete file entry (file entry + stream extension + file names).
     */
    private static Optional<ExFatDirectoryEntry> parseFileEntry(ByteBuffer buffer) {
        int startPos = buffer.position();

        // Parse File Directory Entry (0x85)
        int entryType = buffer.get(startPos) & 0xFF;
        if (entryType != TYPE_FILE_ENTRY) {
            return Optional.empty();
        }

        int secondaryCount = buffer.get(startPos + 1) & 0xFF;
        // Skip checksum at offset 2-3
        int fileAttributes = buffer.getShort(startPos + 4) & 0xFFFF;
        // Reserved1 at offset 6-7
        int createTimestamp = buffer.getInt(startPos + 8);
        int modifyTimestamp = buffer.getInt(startPos + 12);
        int accessTimestamp = buffer.getInt(startPos + 16);
        int createTimeTenths = buffer.get(startPos + 20) & 0xFF;
        int modifyTimeTenths = buffer.get(startPos + 21) & 0xFF;
        int createUtcOffset = buffer.get(startPos + 22);
        int modifyUtcOffset = buffer.get(startPos + 23);
        int accessUtcOffset = buffer.get(startPos + 24);
        // Reserved2 at offset 25-31

        Optional<Instant> creationTime = parseTimestamp(createTimestamp, createTimeTenths, createUtcOffset);
        Optional<Instant> modificationTime = parseTimestamp(modifyTimestamp, modifyTimeTenths, modifyUtcOffset);
        Optional<Instant> accessTime = parseTimestamp(accessTimestamp, 0, accessUtcOffset);

        buffer.position(startPos + ENTRY_SIZE);

        // We need at least one more entry (stream extension)
        if (secondaryCount < 1 || buffer.remaining() < ENTRY_SIZE) {
            return Optional.empty();
        }

        // Parse Stream Extension Entry (0xC0)
        int streamPos = buffer.position();
        int streamType = buffer.get(streamPos) & 0xFF;
        if (streamType != TYPE_STREAM_EXTENSION) {
            return Optional.empty();
        }

        int generalFlags = buffer.get(streamPos + 1) & 0xFF;
        boolean noFatChain = (generalFlags & 0x02) != 0;
        // Reserved1 at offset 2
        int nameLength = buffer.get(streamPos + 3) & 0xFF;
        // nameHash at offset 4-5
        // Reserved2 at offset 6-7
        long validDataLength = buffer.getLong(streamPos + 8);
        // Reserved3 at offset 16-19
        int firstCluster = buffer.getInt(streamPos + 20);
        long dataLength = buffer.getLong(streamPos + 24);

        buffer.position(streamPos + ENTRY_SIZE);

        // Parse File Name Extension Entries (0xC1)
        StringBuilder nameBuilder = new StringBuilder();
        int nameEntriesNeeded = (nameLength + 14) / 15; // 15 chars per name entry

        for (int i = 0; i < nameEntriesNeeded && buffer.remaining() >= ENTRY_SIZE; i++) {
            int namePos = buffer.position();
            int nameType = buffer.get(namePos) & 0xFF;

            if (nameType != TYPE_FILE_NAME) {
                break;
            }

            // generalFlags at offset 1
            // Characters at offset 2-31 (15 UTF-16LE characters)
            byte[] nameBytes = new byte[30];
            buffer.position(namePos + 2);
            buffer.get(nameBytes);

            String namePart = new String(nameBytes, StandardCharsets.UTF_16LE);
            // Trim at null character
            int nullPos = namePart.indexOf('\0');
            if (nullPos >= 0) {
                namePart = namePart.substring(0, nullPos);
            }
            nameBuilder.append(namePart);

            buffer.position(namePos + ENTRY_SIZE);
        }

        // Skip any remaining secondary entries
        int entriesProcessed = 1 + nameEntriesNeeded; // stream + name entries
        while (entriesProcessed < secondaryCount && buffer.remaining() >= ENTRY_SIZE) {
            buffer.position(buffer.position() + ENTRY_SIZE);
            entriesProcessed++;
        }

        String name = nameBuilder.toString();
        if (name.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ExFatDirectoryEntry(
                name,
                fileAttributes,
                firstCluster,
                dataLength,
                validDataLength,
                creationTime,
                modificationTime,
                accessTime,
                noFatChain
        ));
    }

    /**
     * Parses a volume label entry.
     */
    public static @NotNull Optional<String> parseVolumeLabel(@NotNull ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.remaining() >= ENTRY_SIZE) {
            int startPos = buffer.position();
            int entryType = buffer.get(startPos) & 0xFF;

            if (entryType == TYPE_END_OF_DIRECTORY) {
                break;
            }

            if (entryType == TYPE_VOLUME_LABEL) {
                int charCount = buffer.get(startPos + 1) & 0xFF;
                if (charCount > 0 && charCount <= 11) {
                    byte[] labelBytes = new byte[charCount * 2];
                    buffer.position(startPos + 2);
                    buffer.get(labelBytes);
                    return Optional.of(new String(labelBytes, StandardCharsets.UTF_16LE));
                }
            }

            buffer.position(startPos + ENTRY_SIZE);
        }

        return Optional.empty();
    }

    /**
     * Parses an exFAT timestamp.
     */
    private static Optional<Instant> parseTimestamp(int timestamp, int tenths, int utcOffset) {
        if (timestamp == 0) {
            return Optional.empty();
        }

        // exFAT timestamp format (same bit layout as FAT32):
        // Bits 0-4: seconds/2
        // Bits 5-10: minutes
        // Bits 11-15: hours
        // Bits 16-20: day
        // Bits 21-24: month
        // Bits 25-31: year (from 1980)

        int second = ((timestamp & 0x1F) * 2) + (tenths / 100);
        int minute = (timestamp >> 5) & 0x3F;
        int hour = (timestamp >> 11) & 0x1F;
        int day = (timestamp >> 16) & 0x1F;
        int month = (timestamp >> 21) & 0x0F;
        int year = ((timestamp >> 25) & 0x7F) + 1980;
        int nanos = (tenths % 100) * 10_000_000;

        // UTC offset is in 15-minute increments
        // Bit 7 indicates if offset is valid
        int offsetMinutes = 0;
        if ((utcOffset & 0x80) != 0) {
            offsetMinutes = (utcOffset & 0x7F) * 15;
            if ((utcOffset & 0x40) != 0) {
                offsetMinutes = -offsetMinutes;
            }
        }

        try {
            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second, nanos);
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
            return Optional.of(ldt.toInstant(offset));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Returns true if this entry is a directory.
     */
    public boolean isDirectory() {
        return (fileAttributes & ATTR_DIRECTORY) != 0;
    }

    /**
     * Returns true if this entry is hidden.
     */
    public boolean isHidden() {
        return (fileAttributes & ATTR_HIDDEN) != 0;
    }

    /**
     * Returns true if this entry is read-only.
     */
    public boolean isReadOnly() {
        return (fileAttributes & ATTR_READ_ONLY) != 0;
    }

    /**
     * Returns true if this entry is a system file.
     */
    public boolean isSystem() {
        return (fileAttributes & ATTR_SYSTEM) != 0;
    }
}
