/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.hfsplus;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents an HFS+ catalog record.
 *
 * <p>Catalog records come in four types:
 * <ul>
 *   <li>Folder (type 1) - directory metadata</li>
 *   <li>File (type 2) - file metadata with data fork and resource fork</li>
 *   <li>Folder thread (type 3) - maps CNID back to parent + name</li>
 *   <li>File thread (type 4) - maps CNID back to parent + name</li>
 * </ul>
 *
 * <p>HFS+ uses Mac OS epoch: seconds since January 1, 1904 00:00:00 UTC.
 */
public class HfsPlusCatalogRecord {

    public static final int RECORD_TYPE_FOLDER = 1;
    public static final int RECORD_TYPE_FILE = 2;
    public static final int RECORD_TYPE_FOLDER_THREAD = 3;
    public static final int RECORD_TYPE_FILE_THREAD = 4;

    /** Seconds between Mac epoch (1904-01-01) and Unix epoch (1970-01-01) */
    private static final long MAC_TO_UNIX_EPOCH_SECONDS = 2082844800L;

    /**
     * Parsed folder record.
     */
    public record FolderRecord(
            int cnid,
            long createDate,
            long contentModDate,
            long attributeModDate,
            long accessDate,
            int valence,
            String name,
            int parentId
    ) {
        public Optional<Instant> creationTime() {
            return macTimeToInstant(createDate);
        }

        public Optional<Instant> modificationTime() {
            return macTimeToInstant(contentModDate);
        }

        public Optional<Instant> accessTime() {
            return macTimeToInstant(accessDate);
        }
    }

    /**
     * Parsed file record.
     */
    public record FileRecord(
            int cnid,
            long createDate,
            long contentModDate,
            long attributeModDate,
            long accessDate,
            long dataLogicalSize,
            long dataPhysicalSize,
            List<HfsPlusExtent> dataExtents,
            long resourceLogicalSize,
            List<HfsPlusExtent> resourceExtents,
            int fileMode,
            String name,
            int parentId
    ) {
        public Optional<Instant> creationTime() {
            return macTimeToInstant(createDate);
        }

        public Optional<Instant> modificationTime() {
            return macTimeToInstant(contentModDate);
        }

        public Optional<Instant> accessTime() {
            return macTimeToInstant(accessDate);
        }

        public boolean isSymbolicLink() {
            return (fileMode & 0xF000) == 0xA000;
        }
    }

    /**
     * Parsed thread record (folder or file).
     */
    public record ThreadRecord(
            int recordType,
            int parentId,
            String name
    ) {}

    /**
     * Parses a catalog record from a raw B-tree leaf record.
     *
     * @param rawRecord the complete record data (key + value)
     * @return the parsed record, or null if unable to parse
     */
    public static Object parse(byte[] rawRecord) {
        if (rawRecord.length < 8) return null;

        ByteBuffer buf = ByteBuffer.wrap(rawRecord);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Key: keyLength(2) + parentID(4) + nameLength(2) + name(variable)
        int keyLength = buf.getShort(0) & 0xFFFF;
        int parentId = buf.getInt(2);
        String name = HfsPlusBTreeReader.readUnicodeName(rawRecord, 6);

        // Value starts after key, aligned to even offset
        int valueOffset = 2 + keyLength;
        if (valueOffset % 2 != 0) valueOffset++;
        if (valueOffset + 2 > rawRecord.length) return null;

        int recordType = buf.getShort(valueOffset) & 0xFFFF;

        return switch (recordType) {
            case RECORD_TYPE_FOLDER -> parseFolder(rawRecord, valueOffset, parentId, name);
            case RECORD_TYPE_FILE -> parseFile(rawRecord, valueOffset, parentId, name);
            case RECORD_TYPE_FOLDER_THREAD, RECORD_TYPE_FILE_THREAD ->
                    parseThread(rawRecord, valueOffset, recordType);
            default -> null;
        };
    }

    private static FolderRecord parseFolder(byte[] data, int offset, int parentId, String name) {
        if (offset + 88 > data.length) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Folder record layout after recordType(2):
        // flags(2) + valence(4) + cnid(4) + createDate(4) + contentModDate(4)
        // + attributeModDate(4) + accessDate(4) + backupDate(4) + permissions(16) + ...
        int valence = buf.getInt(offset + 4);
        int cnid = buf.getInt(offset + 8);
        long createDate = buf.getInt(offset + 12) & 0xFFFFFFFFL;
        long contentModDate = buf.getInt(offset + 16) & 0xFFFFFFFFL;
        long attributeModDate = buf.getInt(offset + 20) & 0xFFFFFFFFL;
        long accessDate = buf.getInt(offset + 24) & 0xFFFFFFFFL;

        return new FolderRecord(cnid, createDate, contentModDate, attributeModDate,
                accessDate, valence, name, parentId);
    }

    private static FileRecord parseFile(byte[] data, int offset, int parentId, String name) {
        if (offset + 248 > data.length) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.BIG_ENDIAN);

        // File record layout after recordType(2):
        // flags(2) + reserved(4) + cnid(4) + createDate(4) + contentModDate(4)
        // + attributeModDate(4) + accessDate(4) + backupDate(4)
        // + permissions(16: ownerID(4) + groupID(4) + adminFlags(1) + ownerFlags(1) + fileMode(2) + special(4))
        // + userInfo(16) + finderInfo(16)
        // + textEncoding(4) + reserved2(4)
        // + dataFork(80: logicalSize(8) + clumpSize(4) + totalBlocks(4) + extents(64))
        // + resourceFork(80: same layout)
        int cnid = buf.getInt(offset + 8);
        long createDate = buf.getInt(offset + 12) & 0xFFFFFFFFL;
        long contentModDate = buf.getInt(offset + 16) & 0xFFFFFFFFL;
        long attributeModDate = buf.getInt(offset + 20) & 0xFFFFFFFFL;
        long accessDate = buf.getInt(offset + 24) & 0xFFFFFFFFL;

        // File mode in permissions block
        int fileMode = buf.getShort(offset + 40 + 10) & 0xFFFF;

        // Data fork at offset + 88
        int dataForkOffset = offset + 88;
        long dataLogicalSize = buf.getLong(dataForkOffset);
        long dataPhysicalSize = (buf.getInt(dataForkOffset + 12) & 0xFFFFFFFFL);
        List<HfsPlusExtent> dataExtents = readExtents(buf, dataForkOffset + 16);

        // Resource fork at offset + 168
        int resForkOffset = offset + 168;
        long resourceLogicalSize = buf.getLong(resForkOffset);
        List<HfsPlusExtent> resourceExtents = readExtents(buf, resForkOffset + 16);

        return new FileRecord(cnid, createDate, contentModDate, attributeModDate,
                accessDate, dataLogicalSize, dataPhysicalSize, dataExtents,
                resourceLogicalSize, resourceExtents, fileMode, name, parentId);
    }

    private static ThreadRecord parseThread(byte[] data, int offset, int recordType) {
        if (offset + 10 > data.length) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Thread record: recordType(2) + reserved(2) + parentID(4) + name(variable)
        int parentId = buf.getInt(offset + 4);
        String name = HfsPlusBTreeReader.readUnicodeName(data, offset + 8);

        return new ThreadRecord(recordType, parentId, name);
    }

    private static List<HfsPlusExtent> readExtents(ByteBuffer buf, int offset) {
        List<HfsPlusExtent> extents = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int startBlock = buf.getInt(offset + i * 8);
            int blockCount = buf.getInt(offset + i * 8 + 4);
            if (blockCount > 0) {
                extents.add(new HfsPlusExtent(startBlock & 0xFFFFFFFFL, blockCount & 0xFFFFFFFFL));
            }
        }
        return extents;
    }

    private static Optional<Instant> macTimeToInstant(long macTime) {
        if (macTime == 0) return Optional.empty();
        long unixSeconds = macTime - MAC_TO_UNIX_EPOCH_SECONDS;
        return Optional.of(Instant.ofEpochSecond(unixSeconds));
    }
}
