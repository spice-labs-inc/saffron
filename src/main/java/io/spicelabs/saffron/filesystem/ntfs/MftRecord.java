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
package io.spicelabs.saffron.filesystem.ntfs;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents an NTFS Master File Table (MFT) record.
 *
 * <p>MFT record structure:
 * <pre>
 * Offset  Size  Description
 * 0       4     Signature "FILE"
 * 4       2     Update sequence offset
 * 6       2     Update sequence size
 * 8       8     Log file sequence number
 * 16      2     Sequence number
 * 18      2     Hard link count
 * 20      2     First attribute offset
 * 22      2     Flags (0x01 = in use, 0x02 = directory)
 * 24      4     Used size of record
 * 28      4     Allocated size of record
 * 32      8     Base record reference
 * 40      2     Next attribute ID
 * 42      2     Padding (XP+)
 * 44      4     Record number (XP+)
 * </pre>
 */
public record MftRecord(
        int recordNumber,
        int flags,
        int sequenceNumber,
        int hardLinkCount,
        @NotNull List<NtfsAttribute> attributes
) {

    /** MFT record signature "FILE" */
    public static final int SIGNATURE_FILE = 0x454C4946;

    /** MFT record signature "BAAD" (bad record) */
    public static final int SIGNATURE_BAAD = 0x44414142;

    /** Flag: Record is in use */
    public static final int FLAG_IN_USE = 0x0001;

    /** Flag: Record is a directory */
    public static final int FLAG_DIRECTORY = 0x0002;

    /**
     * Approximate retained attribute payload size in bytes (used by the
     * MFT cache's byte budget: merged attribute lists can be large).
     */
    public long attributePayloadBytes() {
        long total = 0;
        for (NtfsAttribute attr : attributes) {
            total += attr.dataSize();
        }
        return total;
    }

    /** System MFT record numbers */
    public static final int MFT_RECORD_MFT = 0;
    public static final int MFT_RECORD_MFT_MIRROR = 1;
    public static final int MFT_RECORD_LOG_FILE = 2;
    public static final int MFT_RECORD_VOLUME = 3;
    public static final int MFT_RECORD_ATTR_DEF = 4;
    public static final int MFT_RECORD_ROOT = 5;
    public static final int MFT_RECORD_BITMAP = 6;
    public static final int MFT_RECORD_BOOT = 7;
    public static final int MFT_RECORD_BAD_CLUSTERS = 8;
    public static final int MFT_RECORD_SECURE = 9;
    public static final int MFT_RECORD_UPCASE = 10;
    public static final int MFT_RECORD_EXTEND = 11;

    /**
     * Parses an MFT record from a buffer.
     *
     * @param buffer the buffer containing the record data
     * @param recordNumber the MFT record number
     * @param recordSize the expected record size in bytes
     * @return the parsed record, or empty if invalid
     */
    public static @NotNull Optional<MftRecord> parse(@NotNull ByteBuffer buffer, int recordNumber, int recordSize) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int startPos = buffer.position();

        if (buffer.remaining() < 48) {
            return Optional.empty();
        }

        // Check signature
        int signature = buffer.getInt(startPos);
        if (signature != SIGNATURE_FILE) {
            return Optional.empty();
        }

        // Read header fields
        int updateSeqOffset = buffer.getShort(startPos + 4) & 0xFFFF;
        int updateSeqSize = buffer.getShort(startPos + 6) & 0xFFFF;
        int seqNum = buffer.getShort(startPos + 16) & 0xFFFF;
        int linkCount = buffer.getShort(startPos + 18) & 0xFFFF;
        int firstAttrOffset = buffer.getShort(startPos + 20) & 0xFFFF;
        int flags = buffer.getShort(startPos + 22) & 0xFFFF;
        int usedSize = buffer.getInt(startPos + 24);

        // Apply fixup (update sequence)
        if (!applyFixup(buffer, startPos, updateSeqOffset, updateSeqSize, recordSize, usedSize)) {
            return Optional.empty();
        }

        // Parse attributes
        List<NtfsAttribute> attributes = new ArrayList<>();
        int attrOffset = firstAttrOffset;

        while (attrOffset + 4 < usedSize && attrOffset < recordSize) {
            buffer.position(startPos + attrOffset);
            int attrType = buffer.getInt();

            if (attrType == -1 || attrType == 0xFFFFFFFF) {
                break; // End of attributes
            }

            int attrLength = buffer.getInt(startPos + attrOffset + 4);
            if (attrLength < 16 || attrOffset + attrLength > recordSize) {
                break;
            }

            buffer.position(startPos + attrOffset);
            byte[] attrData = new byte[attrLength];
            buffer.get(attrData);

            NtfsAttribute.parse(attrData).ifPresent(attributes::add);

            attrOffset += attrLength;
        }

        return Optional.of(new MftRecord(recordNumber, flags, seqNum, linkCount, attributes));
    }

    /**
     * Applies the update sequence fixup to correct sector end markers.
     */
    private static boolean applyFixup(ByteBuffer buffer, int startPos,
                                       int updateSeqOffset, int updateSeqSize,
                                       int recordSize, int usedSize) {
        if (updateSeqSize < 2) {
            return true; // No fixup needed
        }

        int seqArrayOffset = startPos + updateSeqOffset;
        if (seqArrayOffset + updateSeqSize * 2 > buffer.limit()) {
            return false;
        }

        short updateSeqNum = buffer.getShort(seqArrayOffset);

        // Apply fixup to each sector
        int sectorSize = 512;
        for (int i = 1; i < updateSeqSize && (i * sectorSize - 2) < recordSize; i++) {
            int sectorEndOffset = startPos + (i * sectorSize) - 2;
            if (sectorEndOffset + 2 > buffer.limit()) {
                break;
            }

            short actualValue = buffer.getShort(sectorEndOffset);

            // Only verify fixup for sectors that contain used data.
            // Sectors beyond usedSize may not have been written with the
            // current update sequence (common on NTFS v1.2 / Windows NT 4.0).
            int sectorStart = (i - 1) * sectorSize;
            if (sectorStart < usedSize && actualValue != updateSeqNum) {
                return false; // Fixup mismatch in used portion
            }

            // Apply replacement regardless (safe for both cases)
            short originalValue = buffer.getShort(seqArrayOffset + i * 2);
            buffer.putShort(sectorEndOffset, originalValue);
        }

        return true;
    }

    /**
     * Returns true if this record is in use.
     */
    public boolean isInUse() {
        return (flags & FLAG_IN_USE) != 0;
    }

    /**
     * Returns true if this record is a directory.
     */
    public boolean isDirectory() {
        return (flags & FLAG_DIRECTORY) != 0;
    }

    /**
     * Finds an attribute by type.
     */
    public @NotNull Optional<NtfsAttribute> findAttribute(int type) {
        return attributes.stream()
                .filter(a -> a.type() == type)
                .findFirst();
    }

    /**
     * Finds all attributes of a given type.
     */
    public @NotNull List<NtfsAttribute> findAttributes(int type) {
        return attributes.stream()
                .filter(a -> a.type() == type)
                .toList();
    }

    /**
     * Gets the filename from $FILE_NAME attribute.
     */
    public @NotNull Optional<String> getFileName() {
        return findAttribute(NtfsAttribute.TYPE_FILE_NAME)
                .flatMap(attr -> attr.asFileName())
                .map(fn -> fn.fileName());
    }

    /**
     * Gets the long filename (preferring Win32 namespace).
     */
    public @NotNull Optional<String> getLongFileName() {
        List<NtfsAttribute> fileNameAttrs = findAttributes(NtfsAttribute.TYPE_FILE_NAME);

        // Prefer Win32 or Win32+DOS namespace
        for (NtfsAttribute attr : fileNameAttrs) {
            Optional<NtfsAttribute.FileName> fn = attr.asFileName();
            if (fn.isPresent()) {
                int ns = fn.get().namespace();
                if (ns == NtfsAttribute.FileName.NAMESPACE_WIN32 ||
                    ns == NtfsAttribute.FileName.NAMESPACE_WIN32_AND_DOS) {
                    return Optional.of(fn.get().fileName());
                }
            }
        }

        // Fall back to any filename
        return getFileName();
    }

    /**
     * Gets the parent directory reference.
     */
    public @NotNull Optional<Long> getParentReference() {
        return findAttribute(NtfsAttribute.TYPE_FILE_NAME)
                .flatMap(attr -> attr.asFileName())
                .map(fn -> fn.parentReference());
    }

    /**
     * Gets the file size from $DATA attribute.
     */
    public long getFileSize() {
        return findAttribute(NtfsAttribute.TYPE_DATA)
                .map(attr -> attr.isResident() ? attr.residentData().length : attr.dataSize())
                .orElse(0L);
    }

    /**
     * Gets the creation time from $STANDARD_INFORMATION.
     */
    public @NotNull Optional<Instant> getCreationTime() {
        return findAttribute(NtfsAttribute.TYPE_STANDARD_INFORMATION)
                .flatMap(attr -> attr.asStandardInformation())
                .flatMap(si -> si.creationTime());
    }

    /**
     * Gets the modification time from $STANDARD_INFORMATION.
     */
    public @NotNull Optional<Instant> getModificationTime() {
        return findAttribute(NtfsAttribute.TYPE_STANDARD_INFORMATION)
                .flatMap(attr -> attr.asStandardInformation())
                .flatMap(si -> si.modificationTime());
    }

    /**
     * Gets the access time from $STANDARD_INFORMATION.
     */
    public @NotNull Optional<Instant> getAccessTime() {
        return findAttribute(NtfsAttribute.TYPE_STANDARD_INFORMATION)
                .flatMap(attr -> attr.asStandardInformation())
                .flatMap(si -> si.accessTime());
    }
}
