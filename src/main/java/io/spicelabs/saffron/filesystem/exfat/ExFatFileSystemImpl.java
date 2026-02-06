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

import io.spicelabs.saffron.VirtualDisk;
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
 * Implementation of exFAT filesystem reading.
 *
 * <p>This class provides read-only access to exFAT filesystems contained
 * within virtual disk images.
 */
public class ExFatFileSystemImpl implements FileSystem.ExFatFileSystem {

    /** Maximum file size that can be read into memory (256 MB) */
    private static final long MAX_READABLE_SIZE = 256 * 1024 * 1024;

    /** End of cluster chain marker */
    private static final int EXFAT_EOC = 0xFFFFFFF8;

    /** Bad cluster marker */
    private static final int EXFAT_BAD = 0xFFFFFFF7;

    private final DiskRegion region;
    private final ExFatBootSector bootSector;
    private final int[] fatTable;
    private final long clusterHeapOffset;
    private final int clusterSize;
    private final Optional<String> volumeLabel;

    private ExFatFileSystemImpl(DiskRegion region, ExFatBootSector bootSector,
                                 int[] fatTable, Optional<String> volumeLabel) {
        this.region = region;
        this.bootSector = bootSector;
        this.fatTable = fatTable;
        this.clusterHeapOffset = bootSector.clusterHeapOffsetBytes();
        this.clusterSize = bootSector.clusterSize();
        this.volumeLabel = volumeLabel;
    }

    /**
     * Opens an exFAT filesystem from a virtual disk.
     *
     * @param disk the virtual disk containing the filesystem
     * @param partitionOffset the byte offset where the partition/filesystem starts
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull ExFatFileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Opens an exFAT filesystem from a DiskRegion (supports LVM logical volumes).
     *
     * @param region the disk region containing the filesystem
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull ExFatFileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        ExFatBootSector bootSector = ExFatBootSector.read(region);

        // Read FAT table
        int[] fatTable = readFatTable(region, bootSector);

        // Read volume label from root directory
        Optional<String> volumeLabel = readVolumeLabel(region, bootSector, fatTable);

        return new ExFatFileSystemImpl(region, bootSector, fatTable, volumeLabel);
    }

    /**
     * Reads the FAT table into memory.
     */
    private static int[] readFatTable(DiskRegion region, ExFatBootSector bootSector) throws IOException {
        long fatOffset = bootSector.fatOffsetBytes();
        long fatSize = (long) bootSector.fatLength() * bootSector.bytesPerSector();

        // Limit FAT read size to avoid memory issues
        int maxFatBytes = (int) Math.min(fatSize, 64 * 1024 * 1024); // Max 64MB
        ByteBuffer fatBuffer = region.read(fatOffset, maxFatBytes);
        fatBuffer.order(ByteOrder.LITTLE_ENDIAN);

        // exFAT uses 32-bit FAT entries
        int entryCount = Math.min(bootSector.clusterCount() + 2, maxFatBytes / 4);
        int[] fat = new int[entryCount];

        for (int i = 0; i < entryCount && (i * 4 + 3) < fatBuffer.limit(); i++) {
            fat[i] = fatBuffer.getInt(i * 4);
        }

        return fat;
    }

    /**
     * Reads the volume label from the root directory.
     */
    private static Optional<String> readVolumeLabel(DiskRegion region, ExFatBootSector bootSector,
                                                     int[] fatTable) throws IOException {
        long clusterHeapOffset = bootSector.clusterHeapOffsetBytes();
        int clusterSize = bootSector.clusterSize();
        int rootCluster = bootSector.rootDirectoryCluster();

        // Read first cluster of root directory
        long rootOffset = clusterHeapOffset + (long) (rootCluster - 2) * clusterSize;
        ByteBuffer buffer = region.read(rootOffset, clusterSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        return ExFatDirectoryEntry.parseVolumeLabel(buffer);
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        return new ExFatDirectory(bootSector.rootDirectoryCluster(), "/", "/",
                Optional.empty(), Optional.empty(), Optional.empty(), false, null);
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
        for (int i = 2; i < fatTable.length; i++) {
            if (fatTable[i] != 0 && fatTable[i] != EXFAT_BAD) {
                usedClusters++;
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
        return volumeLabel;
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.of(bootSector.uuid());
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fsType", "exFAT");
        meta.put("revision", bootSector.revisionString());
        meta.put("bytesPerSector", String.valueOf(bootSector.bytesPerSector()));
        meta.put("sectorsPerCluster", String.valueOf(bootSector.sectorsPerCluster()));
        meta.put("clusterSize", String.valueOf(clusterSize));
        meta.put("clusterCount", String.valueOf(bootSector.clusterCount()));
        meta.put("percentInUse", String.valueOf(bootSector.percentInUse()));
        if (bootSector.isDirty()) {
            meta.put("dirty", "true");
        }
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        // Nothing to close - we don't own the disk
    }

    @Override
    public int clusterSize() {
        return clusterSize;
    }

    @Override
    public @NotNull String revision() {
        return bootSector.revisionString();
    }

    // ========================================================================
    // Internal methods for cluster chain reading
    // ========================================================================

    /**
     * Reads data from a cluster chain.
     */
    byte[] readClusterChain(int startCluster, long fileSize, boolean noFatChain) throws IOException {
        if (fileSize > MAX_READABLE_SIZE) {
            throw new IOException("File too large to read: " + fileSize + " bytes (max " + MAX_READABLE_SIZE + ")");
        }

        if (startCluster < 2) {
            return new byte[0];
        }

        int totalSize = (int) fileSize;
        byte[] data = new byte[totalSize];
        int offset = 0;

        if (noFatChain) {
            // Contiguous allocation - clusters are consecutive
            int clustersNeeded = (totalSize + clusterSize - 1) / clusterSize;
            for (int i = 0; i < clustersNeeded && offset < totalSize; i++) {
                int toRead = Math.min(clusterSize, totalSize - offset);
                long clusterOffset = clusterToOffset(startCluster + i);
                ByteBuffer buf = region.read(clusterOffset, toRead);
                buf.get(data, offset, toRead);
                offset += toRead;
            }
        } else {
            // Follow FAT chain
            List<Integer> clusters = getClusterChain(startCluster);
            for (int cluster : clusters) {
                if (offset >= totalSize) break;

                int toRead = Math.min(clusterSize, totalSize - offset);
                long clusterOffset = clusterToOffset(cluster);
                ByteBuffer buf = region.read(clusterOffset, toRead);
                buf.get(data, offset, toRead);
                offset += toRead;
            }
        }

        return data;
    }

    /**
     * Gets the chain of clusters starting from the given cluster.
     */
    List<Integer> getClusterChain(int startCluster) {
        List<Integer> chain = new ArrayList<>();
        int cluster = startCluster;
        int maxClusters = fatTable.length;
        int iterations = 0;

        while (cluster >= 2 && cluster < maxClusters && iterations < maxClusters) {
            chain.add(cluster);
            int nextCluster = fatTable[cluster];

            if (nextCluster >= EXFAT_EOC || nextCluster < 2 || nextCluster == EXFAT_BAD) {
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
        return clusterHeapOffset + ((long) (cluster - 2) * clusterSize);
    }

    /**
     * Reads a directory from a cluster chain.
     */
    List<ExFatDirectoryEntry> readDirectory(int startCluster, boolean noFatChain) throws IOException {
        byte[] dirData = readDirectoryClusterChain(startCluster, noFatChain);
        ByteBuffer buffer = ByteBuffer.wrap(dirData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return ExFatDirectoryEntry.parseDirectory(buffer);
    }

    /**
     * Reads a directory's cluster chain.
     */
    private byte[] readDirectoryClusterChain(int startCluster, boolean noFatChain) throws IOException {
        if (startCluster < 2) {
            return new byte[0];
        }

        List<byte[]> chunks = new ArrayList<>();
        int totalSize = 0;

        if (noFatChain) {
            // For contiguous directories, read clusters until we hit end-of-directory
            int cluster = startCluster;
            while (cluster < bootSector.clusterCount() + 2) {
                long clusterOffset = clusterToOffset(cluster);
                ByteBuffer buf = region.read(clusterOffset, clusterSize);
                byte[] chunk = new byte[clusterSize];
                buf.get(chunk);
                chunks.add(chunk);
                totalSize += clusterSize;

                // Check if this cluster contains end of directory
                if (containsEndOfDirectory(chunk)) {
                    break;
                }
                cluster++;
            }
        } else {
            // Follow FAT chain
            List<Integer> clusterChain = getClusterChain(startCluster);
            for (int cluster : clusterChain) {
                long clusterOffset = clusterToOffset(cluster);
                ByteBuffer buf = region.read(clusterOffset, clusterSize);
                byte[] chunk = new byte[clusterSize];
                buf.get(chunk);
                chunks.add(chunk);
                totalSize += clusterSize;
            }
        }

        // Combine chunks
        byte[] data = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, data, offset, chunk.length);
            offset += chunk.length;
        }

        return data;
    }

    /**
     * Checks if a cluster contains the end-of-directory marker.
     */
    private boolean containsEndOfDirectory(byte[] data) {
        for (int i = 0; i + ExFatDirectoryEntry.ENTRY_SIZE <= data.length; i += ExFatDirectoryEntry.ENTRY_SIZE) {
            if (data[i] == ExFatDirectoryEntry.TYPE_END_OF_DIRECTORY) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // Inner classes for directory/file entries
    // ========================================================================

    /**
     * exFAT directory implementation.
     */
    private class ExFatDirectory implements FileSystemEntry.Directory {
        private final int startCluster;
        private final String name;
        private final String path;
        private final Optional<Instant> creationTime;
        private final Optional<Instant> modificationTime;
        private final Optional<Instant> accessTime;
        private final boolean noFatChain;
        private final ExFatDirectoryEntry entry;

        ExFatDirectory(int startCluster, String name, String path,
                       Optional<Instant> creationTime, Optional<Instant> modificationTime,
                       Optional<Instant> accessTime, boolean noFatChain, ExFatDirectoryEntry entry) {
            this.startCluster = startCluster;
            this.name = name;
            this.path = path;
            this.creationTime = creationTime;
            this.modificationTime = modificationTime;
            this.accessTime = accessTime;
            this.noFatChain = noFatChain;
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
            attrs.put("noFatChain", entry.noFatChain());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<ExFatDirectoryEntry> entries = readDirectory(startCluster, noFatChain);
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
     * Creates a FileSystemEntry from an ExFatDirectoryEntry.
     */
    private FileSystemEntry createEntry(ExFatDirectoryEntry entry, String parentPath) {
        String entryPath = parentPath.equals("/") ? "/" + entry.name() : parentPath + "/" + entry.name();

        if (entry.isDirectory()) {
            return new ExFatDirectory(entry.firstCluster(), entry.name(), entryPath,
                    entry.creationTime(), entry.modificationTime(), entry.accessTime(),
                    entry.noFatChain(), entry);
        } else {
            return new ExFatFile(entry, entryPath);
        }
    }

    /**
     * exFAT file implementation.
     */
    private class ExFatFile implements FileSystemEntry.RegularFile {
        private final ExFatDirectoryEntry entry;
        private final String path;

        ExFatFile(ExFatDirectoryEntry entry, String path) {
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
            return entry.dataLength();
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
            attrs.put("noFatChain", entry.noFatChain());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return readClusterChain(entry.firstCluster(), entry.dataLength(), entry.noFatChain());
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
