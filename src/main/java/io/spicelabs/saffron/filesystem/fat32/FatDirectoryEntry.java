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
package io.spicelabs.saffron.filesystem.fat32;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Represents a FAT directory entry.
 *
 * <p>Standard 32-byte directory entry format:
 * <pre>
 * Offset  Size  Description
 * 0       8     Short filename (8 characters, space-padded)
 * 8       3     Extension (3 characters, space-padded)
 * 11      1     Attributes
 * 12      1     Reserved (NT)
 * 13      1     Creation time (tenths of second)
 * 14      2     Creation time
 * 16      2     Creation date
 * 18      2     Last access date
 * 20      2     First cluster high (FAT32)
 * 22      2     Last modified time
 * 24      2     Last modified date
 * 26      2     First cluster low
 * 28      4     File size
 * </pre>
 */
public record FatDirectoryEntry(
        @NotNull String name,
        byte attributes,
        int firstCluster,
        long fileSize,
        @NotNull Optional<Instant> creationTime,
        @NotNull Optional<Instant> modificationTime,
        @NotNull Optional<Instant> accessTime
) {
    /** Directory entry size */
    public static final int ENTRY_SIZE = 32;

    /** Attribute: Read-only */
    public static final byte ATTR_READ_ONLY = 0x01;

    /** Attribute: Hidden */
    public static final byte ATTR_HIDDEN = 0x02;

    /** Attribute: System */
    public static final byte ATTR_SYSTEM = 0x04;

    /** Attribute: Volume label */
    public static final byte ATTR_VOLUME_ID = 0x08;

    /** Attribute: Directory */
    public static final byte ATTR_DIRECTORY = 0x10;

    /** Attribute: Archive */
    public static final byte ATTR_ARCHIVE = 0x20;

    /** Long filename entry marker (all 4 attribute bits set) */
    public static final byte ATTR_LONG_NAME = 0x0F;

    /** First byte indicating entry is free */
    public static final byte ENTRY_FREE = (byte) 0xE5;

    /** First byte indicating end of directory */
    public static final byte ENTRY_END = 0x00;

    /** First byte indicating deleted entry with 0xE5 actual first char */
    public static final byte ENTRY_KANJI = 0x05;

    /**
     * Parses a standard directory entry from a buffer.
     *
     * @param buffer the buffer positioned at the entry
     * @param longName optional long filename from preceding LFN entries
     * @return the parsed entry, or empty if this is a free/end/LFN entry
     */
    public static @NotNull Optional<FatDirectoryEntry> parse(@NotNull ByteBuffer buffer,
                                                              @NotNull Optional<String> longName) {
        return parse(buffer, longName, true);
    }

    /**
     * Parses a standard directory entry from a buffer.
     *
     * @param buffer the buffer positioned at the entry
     * @param longName optional long filename from preceding LFN entries
     * @param isFat32 true if this is a FAT32 filesystem (offset 20 is cluster high);
     *                false for FAT12/16 (offset 20 is reserved/EA index, must be ignored)
     * @return the parsed entry, or empty if this is a free/end/LFN entry
     */
    public static @NotNull Optional<FatDirectoryEntry> parse(@NotNull ByteBuffer buffer,
                                                              @NotNull Optional<String> longName,
                                                              boolean isFat32) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int startPos = buffer.position();

        byte firstByte = buffer.get(startPos);

        // Check for free or end of directory
        if (firstByte == ENTRY_FREE || firstByte == ENTRY_END) {
            return Optional.empty();
        }

        byte attr = buffer.get(startPos + 11);

        // Check for long filename entry (skip these, they're processed separately)
        if ((attr & ATTR_LONG_NAME) == ATTR_LONG_NAME) {
            return Optional.empty();
        }

        // Parse short filename
        byte[] nameBytes = new byte[8];
        byte[] extBytes = new byte[3];
        buffer.position(startPos);
        buffer.get(nameBytes);
        buffer.get(extBytes);

        // Handle special first byte
        if (nameBytes[0] == ENTRY_KANJI) {
            nameBytes[0] = (byte) 0xE5;
        }

        String shortName = new String(nameBytes, StandardCharsets.US_ASCII).trim();
        String extension = new String(extBytes, StandardCharsets.US_ASCII).trim();

        // Build the full name
        String name;
        if (longName.isPresent()) {
            name = longName.get();
        } else if (!extension.isEmpty()) {
            name = shortName + "." + extension;
        } else {
            name = shortName;
        }

        // Skip volume labels (any entry with VOLUME_ID set; LFN entries already handled above)
        if ((attr & ATTR_VOLUME_ID) != 0) {
            return Optional.empty();
        }

        // Parse times
        int creationTimeTenths = buffer.get(startPos + 13) & 0xFF;
        int creationTimeRaw = buffer.getShort(startPos + 14) & 0xFFFF;
        int creationDateRaw = buffer.getShort(startPos + 16) & 0xFFFF;
        int accessDateRaw = buffer.getShort(startPos + 18) & 0xFFFF;
        int modTimeRaw = buffer.getShort(startPos + 22) & 0xFFFF;
        int modDateRaw = buffer.getShort(startPos + 24) & 0xFFFF;

        Optional<Instant> creationTime = parseDateTime(creationDateRaw, creationTimeRaw, creationTimeTenths);
        Optional<Instant> modificationTime = parseDateTime(modDateRaw, modTimeRaw, 0);
        Optional<Instant> accessTime = parseDateTime(accessDateRaw, 0, 0);

        // Parse cluster number
        // On FAT12/16, offset 20 is reserved (OS/2 uses it for EA index) — must be ignored
        int clusterHigh = isFat32 ? (buffer.getShort(startPos + 20) & 0xFFFF) : 0;
        int clusterLow = buffer.getShort(startPos + 26) & 0xFFFF;
        int firstCluster = (clusterHigh << 16) | clusterLow;

        // Parse file size
        long fileSize = buffer.getInt(startPos + 28) & 0xFFFFFFFFL;

        return Optional.of(new FatDirectoryEntry(name, attr, firstCluster, fileSize,
                creationTime, modificationTime, accessTime));
    }

    /**
     * Parses a long filename entry and returns the name fragment.
     *
     * @param buffer the buffer positioned at the LFN entry
     * @return the name fragment and sequence number, or empty if not an LFN entry
     */
    public static @NotNull Optional<LfnFragment> parseLfnEntry(@NotNull ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int startPos = buffer.position();

        byte attr = buffer.get(startPos + 11);
        if ((attr & ATTR_LONG_NAME) != ATTR_LONG_NAME) {
            return Optional.empty();
        }

        int sequence = buffer.get(startPos) & 0xFF;
        boolean isLast = (sequence & 0x40) != 0;
        int ordinal = sequence & 0x3F;

        // Extract name characters (13 UTF-16LE chars per LFN entry)
        StringBuilder sb = new StringBuilder();

        // Characters 1-5 at offset 1
        for (int i = 0; i < 5; i++) {
            char c = buffer.getChar(startPos + 1 + (i * 2));
            if (c == 0x0000 || c == 0xFFFF) break;
            sb.append(c);
        }

        // Characters 6-11 at offset 14
        for (int i = 0; i < 6; i++) {
            char c = buffer.getChar(startPos + 14 + (i * 2));
            if (c == 0x0000 || c == 0xFFFF) break;
            sb.append(c);
        }

        // Characters 12-13 at offset 28
        for (int i = 0; i < 2; i++) {
            char c = buffer.getChar(startPos + 28 + (i * 2));
            if (c == 0x0000 || c == 0xFFFF) break;
            sb.append(c);
        }

        return Optional.of(new LfnFragment(ordinal, isLast, sb.toString()));
    }

    /**
     * Long filename fragment.
     */
    public record LfnFragment(int ordinal, boolean isLast, String text) {}

    /**
     * Parses a DOS date/time to Instant.
     */
    private static Optional<Instant> parseDateTime(int date, int time, int tenths) {
        if (date == 0) {
            return Optional.empty();
        }

        int year = ((date >> 9) & 0x7F) + 1980;
        int month = (date >> 5) & 0x0F;
        int day = date & 0x1F;

        int hour = (time >> 11) & 0x1F;
        int minute = (time >> 5) & 0x3F;
        int second = ((time & 0x1F) * 2) + (tenths / 100);
        int nanos = (tenths % 100) * 10_000_000;

        try {
            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second, nanos);
            return Optional.of(ldt.toInstant(ZoneOffset.UTC));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Returns true if this entry is a directory.
     */
    public boolean isDirectory() {
        return (attributes & ATTR_DIRECTORY) != 0;
    }

    /**
     * Returns true if this entry is hidden.
     */
    public boolean isHidden() {
        return (attributes & ATTR_HIDDEN) != 0;
    }

    /**
     * Returns true if this entry is read-only.
     */
    public boolean isReadOnly() {
        return (attributes & ATTR_READ_ONLY) != 0;
    }

    /**
     * Returns true if this entry is a system file.
     */
    public boolean isSystem() {
        return (attributes & ATTR_SYSTEM) != 0;
    }
}
