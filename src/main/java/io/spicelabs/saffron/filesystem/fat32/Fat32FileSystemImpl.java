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
package io.spicelabs.saffron.filesystem.fat32;

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
 * Implementation of FAT12/FAT16/FAT32 filesystem reading.
 *
 * <p>This class provides read-only access to FAT filesystems contained
 * within virtual disk images.
 */
public class Fat32FileSystemImpl implements FileSystem.Fat32FileSystem {

    /** Maximum file size that can be read into memory (256 MB) */
    private static final long MAX_READABLE_SIZE = 256 * 1024 * 1024;

    /** End of cluster chain markers */
    private static final int FAT12_EOC = 0x0FF8;
    private static final int FAT16_EOC = 0xFFF8;
    private static final int FAT32_EOC = 0x0FFFFFF8;

    private final DiskRegion region;
    private final FatBootSector bootSector;
    private final int[] fatTable;
    private final long dataRegionOffset;
    private final int clusterSize;
    private final int fatType; // 12, 16, or 32

    private Fat32FileSystemImpl(DiskRegion region, FatBootSector bootSector,
                                 int[] fatTable, long dataRegionOffset, int fatType) {
        this.region = region;
        this.bootSector = bootSector;
        this.fatTable = fatTable;
        this.dataRegionOffset = dataRegionOffset;
        this.clusterSize = bootSector.clusterSize();
        this.fatType = fatType;
    }

    /**
     * Opens a FAT filesystem from a virtual disk.
     *
     * @param disk the virtual disk containing the filesystem
     * @param partitionOffset the byte offset where the partition/filesystem starts
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull Fat32FileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Opens a FAT filesystem from a DiskRegion (supports LVM logical volumes).
     *
     * @param region the disk region containing the filesystem
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull Fat32FileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        FatBootSector bootSector = FatBootSector.read(region);

        // Determine FAT type
        int fatType;
        switch (bootSector.fatType()) {
            case "FAT12" -> fatType = 12;
            case "FAT16" -> fatType = 16;
            default -> fatType = 32;
        }

        // Calculate data region offset
        long rootDirSectors = 0;
        if (!bootSector.isFat32()) {
            rootDirSectors = ((bootSector.rootDirectoryEntries() * 32L) +
                    (bootSector.bytesPerSector() - 1)) / bootSector.bytesPerSector();
        }

        long fatStartSector = bootSector.reservedSectors();
        long fatSectors = bootSector.sectorsPerFat() * bootSector.numberOfFats();
        long dataStartSector = fatStartSector + fatSectors + rootDirSectors;
        long dataRegionOffset = dataStartSector * bootSector.bytesPerSector();

        // Read FAT table
        int[] fatTable = readFatTable(region, bootSector, fatType);

        return new Fat32FileSystemImpl(region, bootSector, fatTable, dataRegionOffset, fatType);
    }

    /**
     * Reads the FAT table into memory.
     */
    private static int[] readFatTable(DiskRegion region, FatBootSector bootSector, int fatType)
            throws IOException {
        long fatOffset = bootSector.reservedSectors() * bootSector.bytesPerSector();
        long fatSize = bootSector.sectorsPerFat() * bootSector.bytesPerSector();

        // Calculate number of clusters
        long rootDirSectors = 0;
        if (!bootSector.isFat32()) {
            rootDirSectors = ((bootSector.rootDirectoryEntries() * 32L) +
                    (bootSector.bytesPerSector() - 1)) / bootSector.bytesPerSector();
        }

        long dataSectors = bootSector.totalSectors() -
                (bootSector.reservedSectors() + (bootSector.numberOfFats() * bootSector.sectorsPerFat()) + rootDirSectors);
        int countOfClusters = (int) (dataSectors / bootSector.sectorsPerCluster()) + 2;

        // Limit FAT read size to avoid memory issues
        int maxFatBytes = Math.min((int) fatSize, 64 * 1024 * 1024); // Max 64MB
        ByteBuffer fatBuffer = region.read(fatOffset, maxFatBytes);
        fatBuffer.order(ByteOrder.LITTLE_ENDIAN);

        int[] fat = new int[Math.min(countOfClusters, maxFatBytes * 8 / (fatType == 12 ? 12 : fatType))];

        switch (fatType) {
            case 12 -> {
                for (int i = 0; i < fat.length && (i * 3 / 2 + 1) < fatBuffer.limit(); i++) {
                    int offset = (i * 3) / 2;
                    if (i % 2 == 0) {
                        fat[i] = (fatBuffer.get(offset) & 0xFF) | ((fatBuffer.get(offset + 1) & 0x0F) << 8);
                    } else {
                        fat[i] = ((fatBuffer.get(offset) & 0xF0) >> 4) | ((fatBuffer.get(offset + 1) & 0xFF) << 4);
                    }
                }
            }
            case 16 -> {
                for (int i = 0; i < fat.length && (i * 2 + 1) < fatBuffer.limit(); i++) {
                    fat[i] = fatBuffer.getShort(i * 2) & 0xFFFF;
                }
            }
            default -> { // FAT32
                for (int i = 0; i < fat.length && (i * 4 + 3) < fatBuffer.limit(); i++) {
                    fat[i] = fatBuffer.getInt(i * 4) & 0x0FFFFFFF;
                }
            }
        }

        return fat;
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        if (bootSector.isFat32()) {
            // FAT32: root directory is a cluster chain starting at rootDirectoryCluster
            return new FatDirectory(bootSector.rootDirectoryCluster(), "/", "/");
        } else {
            // FAT12/16: root directory is at fixed location
            return new FatRootDirectory("/", "/");
        }
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
        return walkEntry(start.get(), 0, maxDepth);
    }

    private Stream<FileSystemEntry> walkEntry(FileSystemEntry entry, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return Stream.empty();
        }

        Stream<FileSystemEntry> self = Stream.of(entry);

        if (entry instanceof FileSystemEntry.Directory dir && depth < maxDepth) {
            try {
                Stream<FileSystemEntry> children = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(
                                new DirectoryIterator(dir), Spliterator.ORDERED
                        ), false
                ).flatMap(child -> walkEntry(child, depth + 1, maxDepth));
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
        // Count used clusters
        long usedClusters = 0;
        int eocMarker = switch (fatType) {
            case 12 -> FAT12_EOC;
            case 16 -> FAT16_EOC;
            default -> FAT32_EOC;
        };

        for (int i = 2; i < fatTable.length; i++) {
            if (fatTable[i] != 0 && fatTable[i] < eocMarker) {
                usedClusters++;
            } else if (fatTable[i] >= eocMarker) {
                usedClusters++; // EOC also indicates used
            }
        }

        return usedClusters * clusterSize;
    }

    @Override
    public long freeSize() {
        return totalSize() - usedSize();
    }

    @Override
    public @NotNull Optional<String> label() {
        return Optional.ofNullable(bootSector.volumeLabel());
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.of(bootSector.uuid());
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fatType", bootSector.fatType());
        meta.put("oemId", bootSector.oemId());
        meta.put("bytesPerSector", String.valueOf(bootSector.bytesPerSector()));
        meta.put("sectorsPerCluster", String.valueOf(bootSector.sectorsPerCluster()));
        meta.put("clusterSize", String.valueOf(clusterSize));
        meta.put("mediaType", bootSector.mediaTypeDescription());
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        // Nothing to close - we don't own the disk
    }

    @Override
    public int sectorsPerCluster() {
        return bootSector.sectorsPerCluster();
    }

    @Override
    public @NotNull String fatType() {
        return bootSector.fatType();
    }

    @Override
    public @NotNull FatFileCounts fileCounts() throws IOException {
        long totalFiles = 0;
        long hiddenSystemFiles = 0;

        try (Stream<FileSystemEntry> walkStream = walk()) {
            for (FileSystemEntry entry : walkStream.toList()) {
                if (entry instanceof FileSystemEntry.RegularFile) {
                    totalFiles++;
                    Map<String, Object> attrs = entry.attributes();
                    boolean hidden = Boolean.TRUE.equals(attrs.get("hidden"));
                    boolean system = Boolean.TRUE.equals(attrs.get("system"));
                    // Libguestfs (Linux FAT driver) excludes files with both hidden AND system attributes
                    if (hidden && system) {
                        hiddenSystemFiles++;
                    }
                }
            }
        }

        return new FatFileCounts(totalFiles, hiddenSystemFiles, totalFiles - hiddenSystemFiles);
    }

    // ========================================================================
    // Internal methods for cluster chain reading
    // ========================================================================

    /**
     * Reads data from a cluster chain.
     */
    byte[] readClusterChain(int startCluster, long fileSize) throws IOException {
        if (fileSize > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("File too large to read into memory: " + fileSize + " bytes (limit: 256 MB). Use openStream() for large files.", "allocation_size", MAX_READABLE_SIZE, fileSize);
        }

        if (startCluster < 2) {
            return new byte[0];
        }

        List<Integer> clusters = getClusterChain(startCluster);
        int totalSize = (int) Math.min(fileSize, (long) clusters.size() * clusterSize);
        byte[] data = new byte[totalSize];

        int offset = 0;
        for (int cluster : clusters) {
            if (offset >= totalSize) break;

            int toRead = Math.min(clusterSize, totalSize - offset);
            long clusterOffset = clusterToOffset(cluster);
            ByteBuffer buf = region.read(clusterOffset, toRead);
            buf.get(data, offset, toRead);
            offset += toRead;
        }

        return data;
    }

    /**
     * Gets the chain of clusters starting from the given cluster.
     */
    List<Integer> getClusterChain(int startCluster) {
        List<Integer> chain = new ArrayList<>();
        int cluster = startCluster;

        int eocMarker = switch (fatType) {
            case 12 -> FAT12_EOC;
            case 16 -> FAT16_EOC;
            default -> FAT32_EOC;
        };

        int maxClusters = fatTable.length;
        int iterations = 0;

        while (cluster >= 2 && cluster < maxClusters && iterations < maxClusters) {
            chain.add(cluster);
            int nextCluster = fatTable[cluster];

            if (nextCluster >= eocMarker || nextCluster < 2) {
                break;
            }

            cluster = nextCluster;
            iterations++;
        }

        return chain;
    }

    /**
     * Converts a cluster number to a byte offset.
     */
    long clusterToOffset(int cluster) {
        return dataRegionOffset + ((long) (cluster - 2) * clusterSize);
    }

    /**
     * Reads a directory from a cluster chain.
     */
    List<FatDirectoryEntry> readDirectory(int startCluster) throws IOException {
        byte[] dirData = readDirectoryClusterChain(startCluster);
        return parseDirectoryEntries(dirData);
    }

    /**
     * Reads a directory's cluster chain without size limit (uses cluster count instead).
     */
    private byte[] readDirectoryClusterChain(int startCluster) throws IOException {
        if (startCluster < 2) {
            return new byte[0];
        }

        List<Integer> clusters = getClusterChain(startCluster);
        int totalSize = clusters.size() * clusterSize;
        byte[] data = new byte[totalSize];

        int offset = 0;
        for (int cluster : clusters) {
            long clusterOffset = clusterToOffset(cluster);
            ByteBuffer buf = region.read(clusterOffset, clusterSize);
            buf.get(data, offset, clusterSize);
            offset += clusterSize;
        }

        return data;
    }

    /**
     * Reads the FAT12/16 root directory (fixed location).
     */
    List<FatDirectoryEntry> readRootDirectory() throws IOException {
        long rootDirOffset =
                (bootSector.reservedSectors() + bootSector.numberOfFats() * bootSector.sectorsPerFat()) *
                        bootSector.bytesPerSector();

        int rootDirSize = bootSector.rootDirectoryEntries() * FatDirectoryEntry.ENTRY_SIZE;
        ByteBuffer buf = region.read(rootDirOffset, rootDirSize);
        byte[] dirData = new byte[rootDirSize];
        buf.get(dirData);

        return parseDirectoryEntries(dirData);
    }

    /**
     * Parses directory entries from raw data.
     */
    private List<FatDirectoryEntry> parseDirectoryEntries(byte[] data) {
        List<FatDirectoryEntry> entries = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Collect LFN fragments
        List<FatDirectoryEntry.LfnFragment> lfnFragments = new ArrayList<>();

        while (buffer.remaining() >= FatDirectoryEntry.ENTRY_SIZE) {
            int pos = buffer.position();
            byte firstByte = buffer.get(pos);

            if (firstByte == FatDirectoryEntry.ENTRY_END) {
                break; // End of directory
            }

            if (firstByte == FatDirectoryEntry.ENTRY_FREE) {
                lfnFragments.clear();
                buffer.position(pos + FatDirectoryEntry.ENTRY_SIZE);
                continue;
            }

            // Check for LFN entry
            byte attr = buffer.get(pos + 11);
            if ((attr & FatDirectoryEntry.ATTR_LONG_NAME) == FatDirectoryEntry.ATTR_LONG_NAME) {
                FatDirectoryEntry.parseLfnEntry(buffer).ifPresent(lfnFragments::add);
                buffer.position(pos + FatDirectoryEntry.ENTRY_SIZE);
                continue;
            }

            // Build long filename from fragments (reverse order)
            Optional<String> longName = Optional.empty();
            if (!lfnFragments.isEmpty()) {
                lfnFragments.sort(Comparator.comparingInt(FatDirectoryEntry.LfnFragment::ordinal));
                StringBuilder sb = new StringBuilder();
                for (FatDirectoryEntry.LfnFragment frag : lfnFragments) {
                    sb.append(frag.text());
                }
                longName = Optional.of(sb.toString());
            }

            FatDirectoryEntry.parse(buffer, longName, fatType == 32).ifPresent(entry -> {
                // Skip . and .. entries
                if (!entry.name().equals(".") && !entry.name().equals("..")) {
                    entries.add(entry);
                }
            });

            lfnFragments.clear();
            buffer.position(pos + FatDirectoryEntry.ENTRY_SIZE);
        }

        return entries;
    }

    // ========================================================================
    // Inner classes for directory/file entries
    // ========================================================================

    /**
     * FAT directory implementation (for FAT32 cluster-based directories).
     */
    private class FatDirectory implements FileSystemEntry.Directory {
        private final int startCluster;
        private final String name;
        private final String path;
        private final Optional<Instant> creationTime;
        private final Optional<Instant> modificationTime;
        private final Optional<Instant> accessTime;
        private final FatDirectoryEntry entry;

        FatDirectory(int startCluster, String name, String path) {
            this(startCluster, name, path, Optional.empty(), Optional.empty(), Optional.empty(), null);
        }

        FatDirectory(int startCluster, String name, String path,
                     Optional<Instant> creationTime, Optional<Instant> modificationTime,
                     Optional<Instant> accessTime, FatDirectoryEntry entry) {
            this.startCluster = startCluster;
            this.name = name;
            this.path = path;
            this.creationTime = creationTime;
            this.modificationTime = modificationTime;
            this.accessTime = accessTime;
            this.entry = entry;
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
            return creationTime;
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return modificationTime;
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return accessTime;
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            if (entry == null) return Collections.emptyMap();
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("readonly", entry.isReadOnly());
            attrs.put("hidden", entry.isHidden());
            attrs.put("system", entry.isSystem());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<FatDirectoryEntry> entries = readDirectory(startCluster);
            return entries.stream().map(e -> createEntry(e, path));
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            try (Stream<FileSystemEntry> entries = list()) {
                return entries.filter(e -> e.name().equalsIgnoreCase(name)).findFirst();
            }
        }
    }

    /**
     * FAT root directory for FAT12/16 (fixed location).
     */
    private class FatRootDirectory implements FileSystemEntry.Directory {
        private final String name;
        private final String path;

        FatRootDirectory(String name, String path) {
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
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<FatDirectoryEntry> entries = readRootDirectory();
            return entries.stream().map(e -> createEntry(e, path));
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            try (Stream<FileSystemEntry> entries = list()) {
                return entries.filter(e -> e.name().equalsIgnoreCase(name)).findFirst();
            }
        }
    }

    /**
     * Creates a FileSystemEntry from a FatDirectoryEntry.
     */
    private FileSystemEntry createEntry(FatDirectoryEntry entry, String parentPath) {
        String entryPath = parentPath.equals("/") ? "/" + entry.name() : parentPath + "/" + entry.name();

        if (entry.isDirectory()) {
            return new FatDirectory(entry.firstCluster(), entry.name(), entryPath,
                    entry.creationTime(), entry.modificationTime(), entry.accessTime(), entry);
        } else {
            return new FatFile(entry, entryPath);
        }
    }

    /**
     * FAT file implementation.
     */
    private class FatFile implements FileSystemEntry.RegularFile {
        private final FatDirectoryEntry entry;
        private final String path;

        FatFile(FatDirectoryEntry entry, String path) {
            this.entry = entry;
            this.path = path;
        }

        @Override
        public @NotNull String name() {
            return entry.name();
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
            return entry.fileSize();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return entry.creationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return entry.modificationTime();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return entry.accessTime();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("readonly", entry.isReadOnly());
            attrs.put("hidden", entry.isHidden());
            attrs.put("system", entry.isSystem());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return readClusterChain(entry.firstCluster(), entry.fileSize());
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return new ByteArrayInputStream(readAllBytes());
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
