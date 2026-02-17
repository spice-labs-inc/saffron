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
package io.spicelabs.saffron.partition;

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a GUID Partition Table (GPT).
 *
 * <p>GPT header structure (92 bytes minimum):
 * <pre>
 * Offset  Size   Description
 * 0       8      Signature ("EFI PART")
 * 8       4      Revision
 * 12      4      Header size
 * 16      4      CRC32 of header
 * 20      4      Reserved
 * 24      8      Current LBA (location of this header)
 * 32      8      Backup LBA (location of backup header)
 * 40      8      First usable LBA
 * 48      8      Last usable LBA
 * 56      16     Disk GUID
 * 72      8      Partition entries LBA
 * 80      4      Number of partition entries
 * 84      4      Size of partition entry
 * 88      4      CRC32 of partition entries
 * </pre>
 */
public record GptPartitionTable(
        @NotNull UUID diskGuid,
        long revision,
        long firstUsableLba,
        long lastUsableLba,
        @NotNull List<Partition> partitions
) implements PartitionTable {

    /** GPT signature */
    public static final long GPT_SIGNATURE = 0x5452415020494645L; // "EFI PART"

    /** GPT header LBA (always sector 1) */
    public static final int GPT_HEADER_LBA = 1;

    /** Minimum GPT header size */
    public static final int MIN_HEADER_SIZE = 92;

    /** Standard partition entry size */
    public static final int STANDARD_ENTRY_SIZE = 128;

    @Override
    public @NotNull Type type() {
        return Type.GPT;
    }

    @Override
    public @NotNull String diskSignature() {
        return diskGuid.toString();
    }

    /**
     * Attempts to parse a GPT partition table from the disk.
     *
     * @param disk the virtual disk to read from
     * @return an Optional containing the GPT, or empty if invalid
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<GptPartitionTable> tryParse(@NotNull VirtualDisk disk) throws IOException {
        if (disk.virtualSize() < 512 * 3) {
            return Optional.empty();
        }

        // Read GPT header from LBA 1
        ByteBuffer header = disk.read(GPT_HEADER_LBA * 512L, 512);
        header.order(ByteOrder.LITTLE_ENDIAN);

        // Check signature
        long signature = header.getLong();
        if (signature != GPT_SIGNATURE) {
            return Optional.empty();
        }

        // Revision
        long revision = header.getInt() & 0xFFFFFFFFL;

        // Header size
        int headerSize = header.getInt();
        if (headerSize < MIN_HEADER_SIZE) {
            return Optional.empty();
        }

        // Skip CRC32 and reserved
        header.getInt(); // CRC32
        header.getInt(); // Reserved

        // Current LBA
        long currentLba = header.getLong();

        // Backup LBA
        long backupLba = header.getLong();

        // First usable LBA
        long firstUsableLba = header.getLong();

        // Last usable LBA
        long lastUsableLba = header.getLong();

        // Disk GUID
        UUID diskGuid = readGuid(header);

        // Partition entries LBA
        long entriesLba = header.getLong();

        // Number of partition entries
        int numEntries = header.getInt();

        // Size of each entry
        int entrySize = header.getInt();
        if (entrySize < STANDARD_ENTRY_SIZE) {
            return Optional.empty();
        }

        // Limit entries for safety
        if (numEntries > 256) {
            numEntries = 256;
        }

        // Read partition entries
        List<Partition> partitions = new ArrayList<>();
        long entriesOffset = entriesLba * 512;
        int totalEntriesSize = numEntries * entrySize;

        ByteBuffer entriesBuffer = disk.read(entriesOffset, totalEntriesSize);
        entriesBuffer.order(ByteOrder.LITTLE_ENDIAN);

        int partitionIndex = 0;
        for (int i = 0; i < numEntries; i++) {
            int entryOffset = i * entrySize;
            entriesBuffer.position(entryOffset);

            Optional<GptPartition> partitionOpt = parsePartitionEntry(entriesBuffer, partitionIndex, entrySize);
            if (partitionOpt.isPresent()) {
                partitions.add(partitionOpt.get());
                partitionIndex++;
            }
        }

        return Optional.of(new GptPartitionTable(
                diskGuid,
                revision,
                firstUsableLba,
                lastUsableLba,
                List.copyOf(partitions)
        ));
    }

    /**
     * Parses a single GPT partition entry.
     *
     * @return the parsed partition, or empty if the entry is unused
     */
    private static @NotNull Optional<GptPartition> parsePartitionEntry(ByteBuffer buffer, int index, int entrySize) {
        int startPos = buffer.position();

        // Type GUID
        UUID typeGuid = readGuid(buffer);
        if (typeGuid.equals(GptPartition.TYPE_UNUSED)) {
            return Optional.empty();
        }

        // Unique GUID
        UUID uniqueGuid = readGuid(buffer);

        // Starting LBA
        long startLba = buffer.getLong();

        // Ending LBA (inclusive)
        long endLba = buffer.getLong();

        // Attributes
        long attributes = buffer.getLong();

        // Partition name (UTF-16LE, remaining bytes up to entry size)
        int nameStart = buffer.position();
        int nameLength = Math.min(72, entrySize - (nameStart - startPos));
        Optional<String> name = Optional.empty();
        if (nameLength > 0) {
            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            name = parseUtf16Le(nameBytes);
        }

        return Optional.of(new GptPartition(index, typeGuid, uniqueGuid, startLba, endLba, attributes, name));
    }

    /**
     * Reads a GUID from the buffer (mixed-endian format).
     */
    private static UUID readGuid(ByteBuffer buffer) {
        // GPT uses mixed-endian GUID format:
        // First 3 components are little-endian, last 2 are big-endian
        int data1 = buffer.getInt();
        short data2 = buffer.getShort();
        short data3 = buffer.getShort();
        byte[] data4 = new byte[8];
        buffer.get(data4);

        // Convert to standard UUID format
        long msb = ((long) (data1 & 0xFFFFFFFFL) << 32) |
                   ((long) (data2 & 0xFFFF) << 16) |
                   (data3 & 0xFFFF);

        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            lsb = (lsb << 8) | (data4[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }

    /**
     * Parses a null-terminated UTF-16LE string.
     *
     * @return the parsed string, or empty if the string is empty
     */
    private static @NotNull Optional<String> parseUtf16Le(byte[] bytes) {
        // Find null terminator
        int length = 0;
        for (int i = 0; i < bytes.length - 1; i += 2) {
            if (bytes[i] == 0 && bytes[i + 1] == 0) {
                break;
            }
            length += 2;
        }
        if (length == 0) {
            return Optional.empty();
        }
        return Optional.of(new String(bytes, 0, length, StandardCharsets.UTF_16LE));
    }

    /**
     * Returns the GPT revision as a version string.
     */
    public String revisionString() {
        int major = (int) ((revision >> 16) & 0xFFFF);
        int minor = (int) (revision & 0xFFFF);
        return major + "." + minor;
    }
}
