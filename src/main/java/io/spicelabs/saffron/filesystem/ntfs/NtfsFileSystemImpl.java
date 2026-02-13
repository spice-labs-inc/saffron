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

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.ResourceLimitException;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Implementation of NTFS filesystem reading.
 *
 * <p>This class provides read-only access to NTFS filesystems contained
 * within virtual disk images.
 */
public class NtfsFileSystemImpl implements FileSystem.NtfsFileSystem {

    /** Maximum file size that can be read into memory (256 MB) */
    private static final long MAX_READABLE_SIZE = 256 * 1024 * 1024;

    /** Maximum symlink resolution depth to prevent infinite loops */
    private static final int MAX_SYMLINK_DEPTH = 40;

    private final DiskRegion region;
    private final NtfsBootSector bootSector;
    private final int mftRecordSize;
    private final int clusterSize;
    private final Map<Long, MftRecord> mftCache;
    private final Optional<String> volumeLabel;
    private final List<NtfsAttribute.DataRun> mftDataRuns;
    private long cachedUsedClusters = -1;

    private NtfsFileSystemImpl(DiskRegion region, NtfsBootSector bootSector,
                                Optional<String> volumeLabel,
                                List<NtfsAttribute.DataRun> mftDataRuns) {
        this.region = region;
        this.bootSector = bootSector;
        this.mftRecordSize = bootSector.mftRecordSize();
        this.clusterSize = bootSector.clusterSize();
        this.mftCache = new HashMap<>();
        this.volumeLabel = volumeLabel;
        this.mftDataRuns = mftDataRuns;
    }

    /**
     * Opens an NTFS filesystem from a virtual disk.
     *
     * @param disk the virtual disk containing the filesystem
     * @param partitionOffset the byte offset where the partition/filesystem starts
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull NtfsFileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Opens an NTFS filesystem from a DiskRegion (supports LVM logical volumes).
     *
     * @param region the disk region containing the filesystem
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull NtfsFileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        NtfsBootSector bootSector = NtfsBootSector.read(region);

        // Read volume label from $Volume MFT record
        Optional<String> volumeLabel = readVolumeLabel(region, bootSector);

        // Read MFT data runs from $MFT record (record 0)
        // This is needed to handle fragmented MFTs
        List<NtfsAttribute.DataRun> mftDataRuns = readMftDataRuns(region, bootSector);

        return new NtfsFileSystemImpl(region, bootSector, volumeLabel, mftDataRuns);
    }

    /**
     * Reads the MFT data runs from the $MFT record (record 0).
     * The MFT can be fragmented across multiple data runs.
     */
    private static List<NtfsAttribute.DataRun> readMftDataRuns(DiskRegion region, NtfsBootSector bootSector)
            throws IOException {
        long mftOffset = bootSector.mftOffsetBytes();
        int recordSize = bootSector.mftRecordSize();

        // Read $MFT record (MFT record 0)
        ByteBuffer buf = region.read(mftOffset, recordSize);

        Optional<MftRecord> record = MftRecord.parse(buf, MftRecord.MFT_RECORD_MFT, recordSize);
        if (record.isEmpty()) {
            // Fall back to empty list if we can't read $MFT
            return List.of();
        }

        // Get $DATA attribute
        Optional<NtfsAttribute> dataAttr = record.get().findAttribute(NtfsAttribute.TYPE_DATA);
        if (dataAttr.isEmpty() || dataAttr.get().isResident()) {
            // If no data runs, fall back to simple linear MFT
            return List.of();
        }

        return dataAttr.get().dataRuns();
    }

    /**
     * Reads the volume label from the $Volume MFT record.
     */
    private static Optional<String> readVolumeLabel(DiskRegion region, NtfsBootSector bootSector)
            throws IOException {
        long mftOffset = bootSector.mftOffsetBytes();
        int recordSize = bootSector.mftRecordSize();

        // Read $Volume record (MFT record 3)
        long volumeRecordOffset = mftOffset + (long) MftRecord.MFT_RECORD_VOLUME * recordSize;
        ByteBuffer buf = region.read(volumeRecordOffset, recordSize);

        Optional<MftRecord> record = MftRecord.parse(buf, MftRecord.MFT_RECORD_VOLUME, recordSize);
        if (record.isEmpty()) {
            return Optional.empty();
        }

        // Look for $VOLUME_NAME attribute
        return record.get().findAttribute(NtfsAttribute.TYPE_VOLUME_NAME)
                .filter(NtfsAttribute::isResident)
                .map(attr -> {
                    byte[] data = attr.residentData();
                    if (data.length >= 2) {
                        return new String(data, java.nio.charset.StandardCharsets.UTF_16LE);
                    }
                    return null;
                });
    }

    /**
     * Reads an MFT record by record number, resolving $ATTRIBUTE_LIST if present.
     */
    MftRecord readMftRecord(long recordNumber) throws IOException {
        // Check cache first
        MftRecord cached = mftCache.get(recordNumber);
        if (cached != null) {
            return cached;
        }

        MftRecord baseRecord = readRawMftRecord(recordNumber);

        // Check for $ATTRIBUTE_LIST — if present, merge attributes from extension records
        Optional<NtfsAttribute> attrListAttr = baseRecord.findAttribute(NtfsAttribute.TYPE_ATTRIBUTE_LIST);
        if (attrListAttr.isPresent()) {
            baseRecord = resolveAttributeList(baseRecord, attrListAttr.get());
        }

        mftCache.put(recordNumber, baseRecord);
        return baseRecord;
    }

    /**
     * Reads a raw MFT record without resolving $ATTRIBUTE_LIST or caching.
     */
    private MftRecord readRawMftRecord(long recordNumber) throws IOException {
        long byteOffsetInMft = recordNumber * mftRecordSize;

        long recordOffset;
        if (mftDataRuns.isEmpty()) {
            recordOffset = bootSector.mftOffsetBytes() + byteOffsetInMft;
        } else {
            recordOffset = findMftRecordOffset(byteOffsetInMft);
            if (recordOffset < 0) {
                throw new IOException("MFT record " + recordNumber + " is outside MFT data runs");
            }
        }

        ByteBuffer buf = region.read(recordOffset, mftRecordSize);
        Optional<MftRecord> record = MftRecord.parse(buf, (int) recordNumber, mftRecordSize);

        if (record.isEmpty()) {
            throw new IOException("Failed to read MFT record " + recordNumber);
        }

        return record.get();
    }

    /**
     * Resolves $ATTRIBUTE_LIST by reading extension MFT records and merging their attributes
     * into the base record. This is needed for large directories and files whose attributes
     * overflow a single MFT record (1024 bytes).
     *
     * <p>$ATTRIBUTE_LIST entry format:
     * <pre>
     * Offset  Size  Description
     * 0       4     Attribute type
     * 4       2     Record length
     * 6       1     Name length (in characters)
     * 7       1     Name offset
     * 8       8     Starting VCN
     * 16      8     MFT file reference (lower 48 bits = record number)
     * 24      2     Attribute ID
     * </pre>
     */
    private MftRecord resolveAttributeList(MftRecord baseRecord, NtfsAttribute attrListAttr) throws IOException {
        byte[] attrListData;
        if (attrListAttr.isResident()) {
            attrListData = attrListAttr.residentData();
        } else {
            attrListData = readDataRuns(attrListAttr.dataRuns(), (int) attrListAttr.dataSize());
        }

        List<NtfsAttribute> mergedAttributes = new ArrayList<>(baseRecord.attributes());
        Set<Long> processedRecords = new HashSet<>();
        processedRecords.add((long) baseRecord.recordNumber());

        ByteBuffer buf = ByteBuffer.wrap(attrListData);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;

        while (offset + 26 <= attrListData.length) {
            int recordLength = buf.getShort(offset + 4) & 0xFFFF;
            if (recordLength < 26 || offset + recordLength > attrListData.length) break;

            long mftRef = buf.getLong(offset + 16) & 0x0000FFFFFFFFFFFFL;

            if (mftRef != baseRecord.recordNumber() && processedRecords.add(mftRef)) {
                try {
                    MftRecord extensionRecord = readRawMftRecord(mftRef);
                    mergedAttributes.addAll(extensionRecord.attributes());
                } catch (Exception e) {
                    // Skip unreadable extension records
                }
            }

            offset += recordLength;
        }

        return new MftRecord(
            baseRecord.recordNumber(),
            baseRecord.flags(),
            baseRecord.sequenceNumber(),
            baseRecord.hardLinkCount(),
            mergedAttributes
        );
    }

    /**
     * Finds the disk offset for a byte offset within the MFT, handling fragmentation.
     * Returns -1 if the offset is outside the MFT data runs.
     */
    private long findMftRecordOffset(long byteOffsetInMft) {
        long currentOffset = 0;

        for (NtfsAttribute.DataRun run : mftDataRuns) {
            long runBytes = run.length() * clusterSize;

            if (byteOffsetInMft >= currentOffset && byteOffsetInMft < currentOffset + runBytes) {
                // Found the run containing this offset
                if (run.sparse()) {
                    return -1; // Sparse run - no data
                }
                long offsetWithinRun = byteOffsetInMft - currentOffset;
                return (run.lcn() * clusterSize) + offsetWithinRun;
            }

            currentOffset += runBytes;
        }

        return -1; // Offset is outside all runs
    }

    /**
     * Reads file data from an MFT record.
     */
    byte[] readFileData(MftRecord record) throws IOException {
        Optional<NtfsAttribute> dataAttr = record.findAttribute(NtfsAttribute.TYPE_DATA);
        if (dataAttr.isEmpty()) {
            return new byte[0];
        }

        NtfsAttribute attr = dataAttr.get();

        if (attr.isResident()) {
            return attr.residentData();
        }

        // Non-resident data - read from data runs
        long dataSize = attr.dataSize();
        if (dataSize > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("File too large to read into memory: " + dataSize + " bytes (limit: 256 MB). Use openStream() for large files.", "allocation_size", MAX_READABLE_SIZE, dataSize);
        }

        if (attr.isCompressed()) {
            return readCompressedDataRuns(attr.dataRuns(), (int) dataSize, attr.compressionUnitSize());
        }

        return readDataRuns(attr.dataRuns(), (int) dataSize);
    }

    /**
     * Reads data from data runs.
     */
    private byte[] readDataRuns(List<NtfsAttribute.DataRun> runs, int dataSize) throws IOException {
        byte[] data = new byte[dataSize];
        int offset = 0;

        for (NtfsAttribute.DataRun run : runs) {
            if (offset >= dataSize) {
                break;
            }

            int runBytes = (int) Math.min(run.length() * clusterSize, dataSize - offset);

            if (run.sparse()) {
                // Sparse run - fill with zeros
                Arrays.fill(data, offset, offset + runBytes, (byte) 0);
            } else {
                // Read from disk
                long clusterOffset = run.lcn() * clusterSize;
                ByteBuffer buf = region.read(clusterOffset, runBytes);
                buf.get(data, offset, runBytes);
            }

            offset += runBytes;
        }

        return data;
    }

    /**
     * Reads compressed data from data runs using LZNT1 decompression.
     * Compressed data is organized in compression units (typically 16 clusters).
     * A sparse run within a compression unit means the preceding non-sparse run
     * is compressed; a full-size non-sparse run is stored uncompressed.
     */
    private byte[] readCompressedDataRuns(List<NtfsAttribute.DataRun> runs, int dataSize,
                                           int compressionUnitClusters) throws IOException {
        byte[] result = new byte[dataSize];
        int resultOffset = 0;
        int unitBytes = compressionUnitClusters * clusterSize;

        int i = 0;
        while (i < runs.size() && resultOffset < dataSize) {
            NtfsAttribute.DataRun run = runs.get(i);

            if (run.sparse()) {
                // Standalone sparse run — fill with zeros
                int bytes = (int) Math.min(run.length() * clusterSize, dataSize - resultOffset);
                // Already zeros in result array
                resultOffset += bytes;
                i++;
                continue;
            }

            // Check if next run is sparse (indicates this run is compressed)
            boolean isCompressedUnit = false;
            long sparseLength = 0;
            if (i + 1 < runs.size() && runs.get(i + 1).sparse()) {
                NtfsAttribute.DataRun sparseRun = runs.get(i + 1);
                // A sparse run following a non-sparse run within a compression unit
                // means the data in the non-sparse run is LZNT1 compressed
                if (run.length() + sparseRun.length() == compressionUnitClusters) {
                    isCompressedUnit = true;
                    sparseLength = sparseRun.length();
                }
            }

            if (isCompressedUnit) {
                // Read the compressed data
                int compressedBytes = (int) (run.length() * clusterSize);
                long clusterOffset = run.lcn() * clusterSize;
                byte[] compressed = new byte[compressedBytes];
                ByteBuffer buf = region.read(clusterOffset, compressedBytes);
                buf.get(compressed);

                // Decompress
                int decompressedSize = Math.min(unitBytes, dataSize - resultOffset);
                try {
                    byte[] decompressed = NtfsLznt1Decompressor.decompress(compressed, decompressedSize);
                    System.arraycopy(decompressed, 0, result, resultOffset,
                            Math.min(decompressed.length, dataSize - resultOffset));
                } catch (Exception e) {
                    // If decompression fails, skip this unit (leave zeros)
                }
                resultOffset += decompressedSize;
                i += 2; // Skip both the data run and the sparse run
            } else {
                // Uncompressed run — read directly
                int runBytes = (int) Math.min(run.length() * clusterSize, dataSize - resultOffset);
                long clusterOffset = run.lcn() * clusterSize;
                ByteBuffer buf = region.read(clusterOffset, runBytes);
                buf.get(result, resultOffset, runBytes);
                resultOffset += runBytes;
                i++;
            }
        }

        return result;
    }

    /**
     * Lists directory entries from an MFT record.
     * Deduplicates entries by MFT record number (handles short/long filename pairs).
     */
    List<MftRecord> listDirectory(MftRecord dirRecord) throws IOException {
        // Use a map to deduplicate by MFT record number
        Map<Long, MftRecord> entriesByRecord = new LinkedHashMap<>();

        // Check $INDEX_ROOT attribute first
        Optional<NtfsAttribute> indexRootAttr = dirRecord.findAttribute(NtfsAttribute.TYPE_INDEX_ROOT);
        if (indexRootAttr.isEmpty()) {
            return new ArrayList<>();
        }

        Optional<NtfsAttribute.IndexRoot> indexRoot = indexRootAttr.get().asIndexRoot();
        if (indexRoot.isEmpty()) {
            return new ArrayList<>();
        }

        // Process entries from INDEX_ROOT
        for (NtfsAttribute.IndexEntry entry : indexRoot.get().entries()) {
            if (entry.isLastEntry()) {
                continue;
            }

            long mftRef = entry.mftReference();
            if (mftRef != 0 && mftRef != dirRecord.recordNumber() && !entriesByRecord.containsKey(mftRef)) {
                try {
                    MftRecord childRecord = readMftRecord(mftRef);
                    if (childRecord.isInUse()) {
                        entriesByRecord.put(mftRef, childRecord);
                    }
                } catch (Exception e) {
                    // Skip unreadable records
                }
            }
        }

        // Also check $INDEX_ALLOCATION for large directories
        Optional<NtfsAttribute> indexAllocAttr = dirRecord.findAttribute(NtfsAttribute.TYPE_INDEX_ALLOCATION);
        if (indexAllocAttr.isPresent() && !indexAllocAttr.get().isResident()) {
            try {
                // Use the index block size from INDEX_ROOT (may differ from boot sector)
                int indexBlockSize = indexRoot.get().indexBlockSize();
                if (indexBlockSize < 512) {
                    indexBlockSize = bootSector.indexRecordSize();
                }
                for (MftRecord record : readIndexAllocation(indexAllocAttr.get(), indexBlockSize)) {
                    long mftRef = record.recordNumber();
                    if (!entriesByRecord.containsKey(mftRef)) {
                        entriesByRecord.put(mftRef, record);
                    }
                }
            } catch (Exception e) {
                // Skip if index allocation is unreadable
            }
        }

        return new ArrayList<>(entriesByRecord.values());
    }

    /**
     * Reads entries from $INDEX_ALLOCATION for large directories.
     * This handles the B+ tree structure where entries may have SUBNODE pointers
     * that need to be followed to read all entries.
     */
    private List<MftRecord> readIndexAllocation(NtfsAttribute indexAllocAttr, int indexBlockSize) throws IOException {
        List<MftRecord> entries = new ArrayList<>();
        byte[] indexData = readDataRuns(indexAllocAttr.dataRuns(), (int) indexAllocAttr.dataSize());
        ByteBuffer buf = ByteBuffer.wrap(indexData);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Track which VCNs we've processed to avoid infinite loops
        Set<Long> processedVcns = new HashSet<>();

        // Process all INDX blocks
        for (int blockOffset = 0; blockOffset + indexBlockSize <= indexData.length; blockOffset += indexBlockSize) {
            long vcn = blockOffset / indexBlockSize;
            if (processedVcns.contains(vcn)) {
                continue;
            }
            processedVcns.add(vcn);

            // Check INDX signature
            if (buf.getInt(blockOffset) != 0x58444E49) { // "INDX"
                continue;
            }

            // Apply fixup if needed
            int updateSeqOffset = buf.getShort(blockOffset + 4) & 0xFFFF;
            int updateSeqSize = buf.getShort(blockOffset + 6) & 0xFFFF;
            applyIndexFixup(buf, blockOffset, updateSeqOffset, updateSeqSize, indexBlockSize);

            // Index header is at offset 24
            int entriesOffset = buf.getInt(blockOffset + 24) + 24;

            // Parse index entries
            int entryOffset = blockOffset + entriesOffset;
            while (entryOffset + 16 < blockOffset + indexBlockSize) {
                long mftRef = buf.getLong(entryOffset) & 0x0000FFFFFFFFFFFFL;
                int entryLength = buf.getShort(entryOffset + 8) & 0xFFFF;
                int flags = buf.getShort(entryOffset + 12) & 0xFFFF;

                if (entryLength < 16) {
                    break;
                }

                // Check for subnode pointer (VCN at end of entry)
                if ((flags & NtfsAttribute.IndexEntry.FLAG_SUBNODE) != 0 && entryLength >= 24) {
                    // Subnode VCN is at the last 8 bytes of the entry
                    long subnodeVcn = buf.getLong(entryOffset + entryLength - 8);
                    // The VCN points to a child INDX block - we'll process it in a later iteration
                    // since we iterate through all blocks anyway
                }

                // Add this entry if it's not the LAST marker
                if ((flags & NtfsAttribute.IndexEntry.FLAG_LAST) == 0 && mftRef != 0) {
                    try {
                        MftRecord childRecord = readMftRecord(mftRef);
                        if (childRecord.isInUse()) {
                            entries.add(childRecord);
                        }
                    } catch (Exception e) {
                        // Skip unreadable records
                    }
                }

                // Stop at LAST entry but still check its subnode if present
                if ((flags & NtfsAttribute.IndexEntry.FLAG_LAST) != 0) {
                    break;
                }

                entryOffset += entryLength;
            }
        }

        return entries;
    }

    /**
     * Applies fixup to an index buffer.
     */
    private void applyIndexFixup(ByteBuffer buf, int blockOffset, int updateSeqOffset, int updateSeqSize, int blockSize) {
        if (updateSeqSize < 2) {
            return;
        }

        int seqArrayOffset = blockOffset + updateSeqOffset;
        short updateSeqNum = buf.getShort(seqArrayOffset);

        int sectorSize = 512;
        for (int i = 1; i < updateSeqSize && (i * sectorSize - 2) < blockSize; i++) {
            int sectorEndOffset = blockOffset + (i * sectorSize) - 2;
            if (sectorEndOffset + 2 > buf.limit()) {
                break;
            }

            short originalValue = buf.getShort(seqArrayOffset + i * 2);
            buf.putShort(sectorEndOffset, originalValue);
        }
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        MftRecord rootRecord = readMftRecord(MftRecord.MFT_RECORD_ROOT);
        return new NtfsDirectory(rootRecord, "/", "/");
    }

    @Override
    public @NotNull Optional<FileSystemEntry> resolve(@NotNull String path) throws IOException {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be absolute: " + path);
        }

        if (path.equals("/")) {
            return Optional.of(root());
        }

        String[] parts = path.substring(1).split("/");
        FileSystemEntry current = root();

        for (String part : parts) {
            if (part.isEmpty()) continue;

            if (!(current instanceof FileSystemEntry.Directory dir)) {
                return Optional.empty();
            }

            Optional<FileSystemEntry> next = dir.find(part);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
        }

        return Optional.of(current);
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk() throws IOException {
        return walk("/", Integer.MAX_VALUE);
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth) throws IOException {
        Optional<FileSystemEntry> start = resolve(path);
        if (start.isEmpty()) {
            return Stream.empty();
        }
        Set<Long> visited = new HashSet<>();
        return walkEntry(start.get(), 0, maxDepth, visited);
    }

    private Stream<FileSystemEntry> walkEntry(FileSystemEntry entry, int depth, int maxDepth, Set<Long> visited) {
        if (depth > maxDepth) {
            return Stream.empty();
        }

        Stream<FileSystemEntry> self = Stream.of(entry);

        if (entry instanceof NtfsDirectory dir && depth < maxDepth) {
            long mftRecordNumber = (long) dir.record.recordNumber();
            if (!visited.add(mftRecordNumber)) {
                // Already visited this directory — cycle detected, skip children
                return self;
            }
            try {
                Stream<FileSystemEntry> children = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(
                                new DirectoryIterator(dir), Spliterator.ORDERED
                        ), false
                ).flatMap(child -> walkEntry(child, depth + 1, maxDepth, visited));
                return Stream.concat(self, children);
            } catch (Exception e) {
                return self;
            }
        }

        return self;
    }

    @Override
    public long totalSize() {
        return bootSector.totalSizeBytes();
    }

    @Override
    public long usedSize() {
        return computeUsedClusters() * clusterSize;
    }

    @Override
    public long freeSize() {
        long used = usedSize();
        return used > 0 ? totalSize() - used : 0;
    }

    /**
     * Reads the $Bitmap MFT record and counts used clusters. Result is cached.
     */
    private long computeUsedClusters() {
        if (cachedUsedClusters >= 0) {
            return cachedUsedClusters;
        }
        try {
            MftRecord bitmapRecord = readMftRecord(MftRecord.MFT_RECORD_BITMAP);
            byte[] bitmapData = readFileData(bitmapRecord);
            long totalClusters = bootSector.totalSizeBytes() / clusterSize;
            long usedCount = 0;
            for (byte b : bitmapData) {
                usedCount += Integer.bitCount(b & 0xFF);
            }
            cachedUsedClusters = Math.min(usedCount, totalClusters);
        } catch (IOException e) {
            cachedUsedClusters = 0;
        }
        return cachedUsedClusters;
    }

    @Override
    public @NotNull Optional<String> label() {
        return volumeLabel;
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.of(bootSector.uuid());
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fsType", "NTFS");
        meta.put("bytesPerSector", String.valueOf(bootSector.bytesPerSector()));
        meta.put("sectorsPerCluster", String.valueOf(bootSector.sectorsPerCluster()));
        meta.put("clusterSize", String.valueOf(clusterSize));
        meta.put("mftRecordSize", String.valueOf(mftRecordSize));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        mftCache.clear();
    }

    @Override
    public int clusterSize() {
        return clusterSize;
    }

    @Override
    public byte[] readAlternateStream(FileSystemEntry.RegularFile file, String streamName) throws IOException {
        if (!(file instanceof NtfsFile ntfsFile)) {
            throw new IllegalArgumentException("File is not from this NTFS filesystem");
        }
        if (streamName == null || streamName.isEmpty()) {
            throw new IllegalArgumentException("Stream name must not be null or empty");
        }

        List<NtfsAttribute> dataAttrs = ntfsFile.record.findAttributes(NtfsAttribute.TYPE_DATA);
        for (NtfsAttribute attr : dataAttrs) {
            if (attr.name().isPresent() && attr.name().get().equals(streamName)) {
                if (attr.isResident()) {
                    return attr.residentData();
                }

                long dataSize = attr.dataSize();
                if (dataSize > MAX_READABLE_SIZE) {
                    throw new ResourceLimitException(
                            "Alternate data stream too large to read into memory: " + dataSize +
                            " bytes (limit: 256 MB).",
                            "allocation_size", MAX_READABLE_SIZE, dataSize);
                }

                if (attr.isCompressed()) {
                    return readCompressedDataRuns(attr.dataRuns(), (int) dataSize, attr.compressionUnitSize());
                }

                return readDataRuns(attr.dataRuns(), (int) dataSize);
            }
        }

        throw new IOException("Alternate data stream not found: " + streamName);
    }

    @Override
    public @NotNull String version() {
        return "3.1"; // Most common NTFS version
    }

    /**
     * Builds an attributes map from an MFT record.
     */
    private static Map<String, Object> buildNtfsAttributes(MftRecord record) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("recordNumber", (long) record.recordNumber());
        attrs.put("hardLinkCount", record.hardLinkCount());
        attrs.put("flags", record.flags());

        record.findAttribute(NtfsAttribute.TYPE_STANDARD_INFORMATION)
                .flatMap(NtfsAttribute::asStandardInformation)
                .ifPresent(si -> attrs.put("fileAttributes", si.fileAttributes()));

        return Collections.unmodifiableMap(attrs);
    }

    // ========================================================================
    // Inner classes for directory/file entries
    // ========================================================================

    /**
     * NTFS directory implementation.
     */
    private class NtfsDirectory implements FileSystemEntry.Directory {
        private final MftRecord record;
        private final String name;
        private final String path;

        NtfsDirectory(MftRecord record, String name, String path) {
            this.record = record;
            this.name = name;
            this.path = path;
        }

        @Override
        public @NotNull String name() {
            return name;
        }

        @Override
        public @NotNull String path() {
            return path;
        }

        @Override
        public @NotNull EntryType type() {
            return EntryType.DIRECTORY;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return record.getCreationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return record.getModificationTime();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return record.getAccessTime();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return buildNtfsAttributes(record);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<MftRecord> children = listDirectory(record);
            return children.stream()
                    .map(child -> createEntry(child, path))
                    .filter(Optional::isPresent)
                    .map(Optional::get);
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            try (Stream<FileSystemEntry> entries = list()) {
                return entries.filter(e -> e.name().equalsIgnoreCase(name)).findFirst();
            }
        }
    }

    /**
     * Creates a FileSystemEntry from an MFT record.
     */
    private Optional<FileSystemEntry> createEntry(MftRecord record, String parentPath) {
        Optional<String> fileName = record.getLongFileName();
        if (fileName.isEmpty()) {
            return Optional.empty();
        }

        String name = fileName.get();

        // Skip . and .. and system metadata files
        if (name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.startsWith("$") && record.recordNumber() < 24) {
            return Optional.empty();
        }

        String entryPath = parentPath.equals("/") ? "/" + name : parentPath + "/" + name;

        if (record.isDirectory()) {
            return Optional.of(new NtfsDirectory(record, name, entryPath));
        }

        // Check for reparse point (symlink/junction)
        Optional<NtfsAttribute> reparseAttr = record.findAttribute(NtfsAttribute.TYPE_REPARSE_POINT);
        if (reparseAttr.isPresent() && reparseAttr.get().isResident()) {
            String target = parseReparsePoint(reparseAttr.get().residentData());
            if (target != null) {
                return Optional.of(new NtfsSymlink(record, name, entryPath, target));
            }
        }

        return Optional.of(new NtfsFile(record, name, entryPath));
    }

    /**
     * Parses a reparse point buffer to extract the target path.
     * Supports symlinks (0xA000000C) and junction/mount points (0xA0000003).
     */
    private static String parseReparsePoint(byte[] data) {
        if (data.length < 8) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int reparseTag = buf.getInt(0);

        if (reparseTag == 0xA000000C) {
            // IO_REPARSE_TAG_SYMLINK
            if (data.length < 20) return null;
            int printNameOffset = buf.getShort(12) & 0xFFFF;
            int printNameLength = buf.getShort(14) & 0xFFFF;
            int headerOffset = 20; // After reparse header + symlink data header
            if (headerOffset + printNameOffset + printNameLength > data.length) return null;
            if (printNameLength == 0) return null;
            return new String(data, headerOffset + printNameOffset, printNameLength,
                    java.nio.charset.StandardCharsets.UTF_16LE);
        } else if (reparseTag == (int) 0xA0000003L) {
            // IO_REPARSE_TAG_MOUNT_POINT (junction)
            if (data.length < 16) return null;
            int printNameOffset = buf.getShort(12) & 0xFFFF;
            int printNameLength = buf.getShort(14) & 0xFFFF;
            int headerOffset = 16; // After reparse header + mount point data header
            if (headerOffset + printNameOffset + printNameLength > data.length) return null;
            if (printNameLength == 0) return null;
            return new String(data, headerOffset + printNameOffset, printNameLength,
                    java.nio.charset.StandardCharsets.UTF_16LE);
        }

        return null; // Unsupported reparse tag
    }

    /**
     * NTFS file implementation.
     */
    private class NtfsFile implements FileSystemEntry.RegularFile {
        private final MftRecord record;
        private final String name;
        private final String path;

        NtfsFile(MftRecord record, String name, String path) {
            this.record = record;
            this.name = name;
            this.path = path;
        }

        @Override
        public @NotNull String name() {
            return name;
        }

        @Override
        public @NotNull String path() {
            return path;
        }

        @Override
        public @NotNull EntryType type() {
            return EntryType.REGULAR_FILE;
        }

        @Override
        public long size() {
            return record.getFileSize();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return record.getCreationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return record.getModificationTime();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return record.getAccessTime();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>(buildNtfsAttributes(record));

            // Expose Alternate Data Streams (named $DATA attributes)
            List<NtfsAttribute> dataAttrs = record.findAttributes(NtfsAttribute.TYPE_DATA);
            int adsCount = 0;
            for (NtfsAttribute dataAttr : dataAttrs) {
                if (dataAttr.name().isPresent()) {
                    String streamName = dataAttr.name().get();
                    long streamSize = dataAttr.isResident()
                            ? dataAttr.residentData().length
                            : dataAttr.dataSize();
                    attrs.put("ntfs.ads." + streamName, streamSize);
                    adsCount++;
                }
            }
            if (adsCount > 0) {
                attrs.put("ntfs.ads.count", adsCount);
            }

            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return readFileData(record);
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return new ByteArrayInputStream(readAllBytes());
        }
    }

    /**
     * NTFS symbolic link (reparse point) implementation.
     */
    private class NtfsSymlink implements FileSystemEntry.SymbolicLink {
        private final MftRecord record;
        private final String name;
        private final String path;
        private final String target;

        NtfsSymlink(MftRecord record, String name, String path, String target) {
            this.record = record;
            this.name = name;
            this.path = path;
            this.target = target;
        }

        @Override
        public @NotNull String name() { return name; }

        @Override
        public @NotNull String path() { return path; }

        @Override
        public long size() { return record.getFileSize(); }

        @Override
        public @NotNull Optional<Instant> creationTime() { return record.getCreationTime(); }

        @Override
        public @NotNull Optional<Instant> modificationTime() { return record.getModificationTime(); }

        @Override
        public @NotNull Optional<Instant> accessTime() { return record.getAccessTime(); }

        @Override
        public @NotNull Map<String, Object> attributes() { return buildNtfsAttributes(record); }

        @Override
        public @NotNull String target() {
            // Normalize Windows path separators to forward slashes
            String normalized = target.replace('\\', '/');
            // Strip \\?\ prefix if present
            if (normalized.startsWith("//?/")) {
                normalized = normalized.substring(4);
            }
            return normalized;
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            return resolveWithDepth(MAX_SYMLINK_DEPTH);
        }

        private Optional<FileSystemEntry> resolveWithDepth(int remaining) throws IOException {
            if (remaining <= 0) {
                throw new IOException("Symlink depth exceeded (limit: " + MAX_SYMLINK_DEPTH + "): " + path);
            }
            String targetPath = target();
            String resolvedPath;
            if (targetPath.startsWith("/")) {
                resolvedPath = targetPath;
            } else {
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                if (parentPath.isEmpty()) parentPath = "/";
                resolvedPath = parentPath + "/" + targetPath;
            }
            Optional<FileSystemEntry> result = NtfsFileSystemImpl.this.resolve(resolvedPath);
            if (result.isPresent() && result.get() instanceof NtfsSymlink nestedLink) {
                return nestedLink.resolveWithDepth(remaining - 1);
            }
            return result;
        }
    }

    /**
     * Iterator for directory listing.
     */
    private static class DirectoryIterator implements Iterator<FileSystemEntry> {
        private final Iterator<FileSystemEntry> delegate;

        DirectoryIterator(FileSystemEntry.Directory dir) throws IOException {
            this.delegate = dir.list().iterator();
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public FileSystemEntry next() {
            return delegate.next();
        }
    }
}
