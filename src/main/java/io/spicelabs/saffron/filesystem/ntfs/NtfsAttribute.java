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
 * Represents an NTFS attribute within an MFT record.
 *
 * <p>Attribute header structure (common):
 * <pre>
 * Offset  Size  Description
 * 0       4     Attribute type
 * 4       4     Total length
 * 8       1     Non-resident flag
 * 9       1     Name length (in characters)
 * 10      2     Name offset
 * 12      2     Flags
 * 14      2     Attribute ID
 * </pre>
 *
 * <p>Resident attribute (non-resident = 0):
 * <pre>
 * 16      4     Value length
 * 20      2     Value offset
 * 22      2     Flags
 * </pre>
 *
 * <p>Non-resident attribute (non-resident = 1):
 * <pre>
 * 16      8     Start VCN
 * 24      8     End VCN
 * 32      2     Data runs offset
 * 34      2     Compression unit size
 * 36      4     Padding
 * 40      8     Allocated size
 * 48      8     Data size (actual)
 * 56      8     Initialized size
 * </pre>
 */
public record NtfsAttribute(
        int type,
        int totalLength,
        boolean isResident,
        @NotNull Optional<String> name,
        int flags,
        byte[] residentData,
        long startVcn,
        long endVcn,
        @NotNull List<DataRun> dataRuns,
        long allocatedSize,
        long dataSize,
        long initializedSize,
        int compressionUnitSize
) {

    /** Flag indicating the attribute is compressed */
    public static final int FLAG_COMPRESSED = 0x0001;

    /** Returns true if this attribute is compressed */
    public boolean isCompressed() {
        return (flags & FLAG_COMPRESSED) != 0 && compressionUnitSize > 0;
    }

    // Attribute types
    public static final int TYPE_STANDARD_INFORMATION = 0x10;
    public static final int TYPE_ATTRIBUTE_LIST = 0x20;
    public static final int TYPE_FILE_NAME = 0x30;
    public static final int TYPE_OBJECT_ID = 0x40;
    public static final int TYPE_SECURITY_DESCRIPTOR = 0x50;
    public static final int TYPE_VOLUME_NAME = 0x60;
    public static final int TYPE_VOLUME_INFORMATION = 0x70;
    public static final int TYPE_DATA = 0x80;
    public static final int TYPE_INDEX_ROOT = 0x90;
    public static final int TYPE_INDEX_ALLOCATION = 0xA0;
    public static final int TYPE_BITMAP = 0xB0;
    public static final int TYPE_REPARSE_POINT = 0xC0;
    public static final int TYPE_EA_INFORMATION = 0xD0;
    public static final int TYPE_EA = 0xE0;

    /**
     * Parses an attribute from raw data.
     */
    public static @NotNull Optional<NtfsAttribute> parse(byte[] data) {
        if (data.length < 16) {
            return Optional.empty();
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int type = buf.getInt(0);
        int totalLength = buf.getInt(4);
        boolean nonResident = buf.get(8) != 0;
        int nameLength = buf.get(9) & 0xFF;
        int nameOffset = buf.getShort(10) & 0xFFFF;
        int flags = buf.getShort(12) & 0xFFFF;

        // Parse attribute name if present
        Optional<String> name = Optional.empty();
        if (nameLength > 0 && nameOffset + nameLength * 2 <= data.length) {
            byte[] nameBytes = new byte[nameLength * 2];
            System.arraycopy(data, nameOffset, nameBytes, 0, nameBytes.length);
            name = Optional.of(new String(nameBytes, StandardCharsets.UTF_16LE));
        }

        if (!nonResident) {
            // Resident attribute
            if (data.length < 24) {
                return Optional.empty();
            }

            int valueLength = buf.getInt(16);
            int valueOffset = buf.getShort(20) & 0xFFFF;

            byte[] residentData = new byte[0];
            if (valueLength > 0 && valueOffset + valueLength <= data.length) {
                residentData = new byte[valueLength];
                System.arraycopy(data, valueOffset, residentData, 0, valueLength);
            }

            return Optional.of(new NtfsAttribute(
                    type, totalLength, true, name, flags,
                    residentData,
                    0, 0, List.of(), 0, residentData.length, residentData.length, 0
            ));
        } else {
            // Non-resident attribute
            if (data.length < 64) {
                return Optional.empty();
            }

            long startVcn = buf.getLong(16);
            long endVcn = buf.getLong(24);
            int dataRunsOffset = buf.getShort(32) & 0xFFFF;
            int compressionUnitLog = buf.getShort(34) & 0xFFFF;
            long allocatedSize = buf.getLong(40);
            long dataSize = buf.getLong(48);
            long initializedSize = buf.getLong(56);

            // Parse data runs
            List<DataRun> dataRuns = parseDataRuns(data, dataRunsOffset);

            return Optional.of(new NtfsAttribute(
                    type, totalLength, false, name, flags,
                    new byte[0],
                    startVcn, endVcn, dataRuns, allocatedSize, dataSize, initializedSize,
                    compressionUnitLog > 0 ? (1 << compressionUnitLog) : 0
            ));
        }
    }

    /**
     * Parses data runs (cluster runs) for non-resident attributes.
     */
    private static List<DataRun> parseDataRuns(byte[] data, int offset) {
        List<DataRun> runs = new ArrayList<>();
        long currentLcn = 0;

        while (offset < data.length) {
            int header = data[offset] & 0xFF;
            if (header == 0) {
                break; // End of data runs
            }

            int lengthSize = header & 0x0F;
            int offsetSize = (header >> 4) & 0x0F;

            if (lengthSize == 0 || offset + 1 + lengthSize + offsetSize > data.length) {
                break;
            }

            // Read length (unsigned)
            long length = 0;
            for (int i = 0; i < lengthSize; i++) {
                length |= ((long) (data[offset + 1 + i] & 0xFF)) << (i * 8);
            }

            // Read offset (signed)
            long lcnOffset = 0;
            if (offsetSize > 0) {
                for (int i = 0; i < offsetSize; i++) {
                    lcnOffset |= ((long) (data[offset + 1 + lengthSize + i] & 0xFF)) << (i * 8);
                }
                // Sign extend if negative
                if ((data[offset + lengthSize + offsetSize] & 0x80) != 0) {
                    for (int i = offsetSize; i < 8; i++) {
                        lcnOffset |= 0xFFL << (i * 8);
                    }
                }
            }

            if (offsetSize > 0) {
                currentLcn += lcnOffset;
                runs.add(new DataRun(currentLcn, length, false));
            } else {
                // Sparse run (no LCN offset means sparse/hole)
                runs.add(new DataRun(0, length, true));
            }

            offset += 1 + lengthSize + offsetSize;
        }

        return runs;
    }

    /**
     * Parses this attribute as $STANDARD_INFORMATION.
     */
    public @NotNull Optional<StandardInformation> asStandardInformation() {
        if (type != TYPE_STANDARD_INFORMATION || !isResident || residentData.length < 48) {
            return Optional.empty();
        }
        return Optional.of(StandardInformation.parse(residentData));
    }

    /**
     * Parses this attribute as $FILE_NAME.
     */
    public @NotNull Optional<FileName> asFileName() {
        if (type != TYPE_FILE_NAME || !isResident || residentData.length < 66) {
            return Optional.empty();
        }
        return Optional.of(FileName.parse(residentData));
    }

    /**
     * Parses this attribute as $INDEX_ROOT.
     */
    public @NotNull Optional<IndexRoot> asIndexRoot() {
        if (type != TYPE_INDEX_ROOT || !isResident || residentData.length < 16) {
            return Optional.empty();
        }
        return Optional.of(IndexRoot.parse(residentData));
    }

    /**
     * Returns the attribute type name.
     */
    public @NotNull String typeName() {
        return switch (type) {
            case TYPE_STANDARD_INFORMATION -> "$STANDARD_INFORMATION";
            case TYPE_ATTRIBUTE_LIST -> "$ATTRIBUTE_LIST";
            case TYPE_FILE_NAME -> "$FILE_NAME";
            case TYPE_OBJECT_ID -> "$OBJECT_ID";
            case TYPE_SECURITY_DESCRIPTOR -> "$SECURITY_DESCRIPTOR";
            case TYPE_VOLUME_NAME -> "$VOLUME_NAME";
            case TYPE_VOLUME_INFORMATION -> "$VOLUME_INFORMATION";
            case TYPE_DATA -> "$DATA";
            case TYPE_INDEX_ROOT -> "$INDEX_ROOT";
            case TYPE_INDEX_ALLOCATION -> "$INDEX_ALLOCATION";
            case TYPE_BITMAP -> "$BITMAP";
            case TYPE_REPARSE_POINT -> "$REPARSE_POINT";
            default -> String.format("$UNKNOWN(0x%02X)", type);
        };
    }

    /**
     * A data run (cluster run) for non-resident attributes.
     */
    public record DataRun(long lcn, long length, boolean sparse) {}

    /**
     * $STANDARD_INFORMATION attribute content.
     */
    public record StandardInformation(
            @NotNull Optional<Instant> creationTime,
            @NotNull Optional<Instant> modificationTime,
            @NotNull Optional<Instant> mftModificationTime,
            @NotNull Optional<Instant> accessTime,
            int fileAttributes
    ) {
        public static StandardInformation parse(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            Optional<Instant> creation = parseNtfsTime(buf.getLong(0));
            Optional<Instant> modification = parseNtfsTime(buf.getLong(8));
            Optional<Instant> mftMod = parseNtfsTime(buf.getLong(16));
            Optional<Instant> access = parseNtfsTime(buf.getLong(24));
            int attrs = buf.getInt(32);

            return new StandardInformation(creation, modification, mftMod, access, attrs);
        }
    }

    /**
     * $FILE_NAME attribute content.
     */
    public record FileName(
            long parentReference,
            @NotNull Optional<Instant> creationTime,
            @NotNull Optional<Instant> modificationTime,
            @NotNull Optional<Instant> mftModificationTime,
            @NotNull Optional<Instant> accessTime,
            long allocatedSize,
            long realSize,
            int fileAttributes,
            int namespace,
            @NotNull String fileName
    ) {
        public static final int NAMESPACE_POSIX = 0;
        public static final int NAMESPACE_WIN32 = 1;
        public static final int NAMESPACE_DOS = 2;
        public static final int NAMESPACE_WIN32_AND_DOS = 3;

        public static FileName parse(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            long parentRef = buf.getLong(0) & 0x0000FFFFFFFFFFFFL;
            Optional<Instant> creation = parseNtfsTime(buf.getLong(8));
            Optional<Instant> modification = parseNtfsTime(buf.getLong(16));
            Optional<Instant> mftMod = parseNtfsTime(buf.getLong(24));
            Optional<Instant> access = parseNtfsTime(buf.getLong(32));
            long allocated = buf.getLong(40);
            long realSize = buf.getLong(48);
            int attrs = buf.getInt(56);
            // skip reparse tag at 60
            int nameLength = buf.get(64) & 0xFF;
            int namespace = buf.get(65) & 0xFF;

            String fileName = "";
            if (nameLength > 0 && data.length >= 66 + nameLength * 2) {
                byte[] nameBytes = new byte[nameLength * 2];
                System.arraycopy(data, 66, nameBytes, 0, nameBytes.length);
                fileName = new String(nameBytes, StandardCharsets.UTF_16LE);
            }

            return new FileName(parentRef, creation, modification, mftMod, access,
                    allocated, realSize, attrs, namespace, fileName);
        }
    }

    /**
     * $INDEX_ROOT attribute content.
     */
    public record IndexRoot(
            int attributeType,
            int collationRule,
            int indexBlockSize,
            int clustersPerIndexBlock,
            int indexFlags,
            @NotNull List<IndexEntry> entries
    ) {
        public static IndexRoot parse(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            int attrType = buf.getInt(0);
            int collation = buf.getInt(4);
            int blockSize = buf.getInt(8);
            int clustersPerBlock = buf.get(12) & 0xFF;

            // Index header at offset 16
            int entriesOffset = buf.getInt(16) + 16; // Relative to index header
            int indexSize = buf.getInt(20);
            int indexFlags = buf.getInt(28);

            List<IndexEntry> entries = parseIndexEntries(data, entriesOffset, indexSize);

            return new IndexRoot(attrType, collation, blockSize, clustersPerBlock, indexFlags, entries);
        }
    }

    /**
     * An index entry in $INDEX_ROOT or $INDEX_ALLOCATION.
     */
    public record IndexEntry(
            long mftReference,
            int indexFlags,
            @NotNull Optional<FileName> fileName
    ) {
        public static final int FLAG_SUBNODE = 0x01;
        public static final int FLAG_LAST = 0x02;

        public boolean isLastEntry() {
            return (indexFlags & FLAG_LAST) != 0;
        }

        public boolean hasSubnode() {
            return (indexFlags & FLAG_SUBNODE) != 0;
        }
    }

    /**
     * Parses index entries.
     */
    private static List<IndexEntry> parseIndexEntries(byte[] data, int offset, int maxSize) {
        List<IndexEntry> entries = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        while (offset + 16 < data.length && offset < maxSize + 16) {
            long mftRef = buf.getLong(offset) & 0x0000FFFFFFFFFFFFL;
            int entryLength = buf.getShort(offset + 8) & 0xFFFF;
            int streamLength = buf.getShort(offset + 10) & 0xFFFF;
            int flags = buf.getInt(offset + 12);

            if (entryLength < 16 || offset + entryLength > data.length) {
                break;
            }

            Optional<FileName> fileName = Optional.empty();
            if (streamLength >= 66 && (flags & IndexEntry.FLAG_LAST) == 0) {
                byte[] fnData = new byte[streamLength];
                System.arraycopy(data, offset + 16, fnData, 0, Math.min(streamLength, data.length - offset - 16));
                fileName = Optional.of(FileName.parse(fnData));
            }

            entries.add(new IndexEntry(mftRef, flags, fileName));

            if ((flags & IndexEntry.FLAG_LAST) != 0) {
                break;
            }

            offset += entryLength;
        }

        return entries;
    }

    /**
     * Converts an NTFS timestamp (100-nanosecond intervals since Jan 1, 1601) to Instant.
     */
    private static Optional<Instant> parseNtfsTime(long ntfsTime) {
        if (ntfsTime == 0) {
            return Optional.empty();
        }

        // NTFS epoch is Jan 1, 1601. Difference to Unix epoch is 116444736000000000 * 100ns
        long epochDiff = 116444736000000000L;
        long unixNanos = (ntfsTime - epochDiff) * 100;
        long unixSeconds = unixNanos / 1_000_000_000L;
        int nanoAdjustment = (int) (unixNanos % 1_000_000_000L);

        if (nanoAdjustment < 0) {
            unixSeconds--;
            nanoAdjustment += 1_000_000_000;
        }

        try {
            return Optional.of(Instant.ofEpochSecond(unixSeconds, nanoAdjustment));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
