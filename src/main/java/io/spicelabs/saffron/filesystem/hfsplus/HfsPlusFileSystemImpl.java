/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.hfsplus;

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
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Read-only implementation of the HFS+ (Mac OS Extended) filesystem.
 *
 * <p>HFS+ is a big-endian filesystem used by macOS through macOS 10.13 (High Sierra).
 * It uses a B-tree catalog keyed by (parentID, name) with CNID 2 as the root folder.
 */
public class HfsPlusFileSystemImpl implements FileSystem.HfsPlusFileSystem {

    /** Maximum file size that can be read into memory (256 MB) */
    private static final long MAX_READABLE_SIZE = 256 * 1024 * 1024;

    /** Maximum symlink resolution depth to prevent infinite loops */
    private static final int MAX_SYMLINK_DEPTH = 40;

    /** Root folder CNID */
    private static final int ROOT_CNID = 2;

    private final DiskRegion region;
    private final HfsPlusVolumeHeader volumeHeader;
    private final HfsPlusBTreeReader catalogReader;
    private final HfsPlusBTreeReader overflowBTree;
    private final String volumeName;

    private HfsPlusFileSystemImpl(DiskRegion region, HfsPlusVolumeHeader volumeHeader,
                                   HfsPlusBTreeReader catalogReader,
                                   HfsPlusBTreeReader overflowBTree, String volumeName) {
        this.region = region;
        this.volumeHeader = volumeHeader;
        this.catalogReader = catalogReader;
        this.overflowBTree = overflowBTree;
        this.volumeName = volumeName;
    }

    /**
     * Mounts an HFS+ filesystem from a virtual disk at the given offset.
     */
    public static @NotNull HfsPlusFileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Mounts an HFS+ filesystem from a DiskRegion.
     */
    public static @NotNull HfsPlusFileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        HfsPlusVolumeHeader header = HfsPlusVolumeHeader.read(region);

        HfsPlusBTreeReader catalogReader = HfsPlusBTreeReader.open(
                region, header.catalogExtents(), header.blockSize());

        // Initialize extents overflow B-tree if present
        HfsPlusBTreeReader overflowBTree = null;
        if (!header.extentsOverflowExtents().isEmpty()) {
            try {
                overflowBTree = HfsPlusBTreeReader.open(
                        region, header.extentsOverflowExtents(), header.blockSize());
            } catch (IOException e) {
                // Overflow B-tree is not critical for volumes with small files
            }
        }

        // Try to find volume name from root folder's thread record
        String volumeName = "Untitled";
        try {
            byte[] threadData = catalogReader.findThreadRecord(ROOT_CNID);
            if (threadData != null) {
                Object parsed = HfsPlusCatalogRecord.parse(threadData);
                if (parsed instanceof HfsPlusCatalogRecord.ThreadRecord thread) {
                    volumeName = thread.name();
                }
            }
        } catch (Exception e) {
            // Volume name is non-critical
        }

        return new HfsPlusFileSystemImpl(region, header, catalogReader, overflowBTree, volumeName);
    }

    @Override
    public int blockSize() {
        return volumeHeader.blockSize();
    }

    @Override
    public @NotNull String volumeName() {
        return volumeName;
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        return new HfsPlusDirectory(ROOT_CNID, "/", "/", null);
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
        int currentCnid = ROOT_CNID;
        String currentPath = "";

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            byte[] recordData = catalogReader.findRecord(currentCnid, part);
            if (recordData == null) {
                return Optional.empty();
            }

            Object parsed = HfsPlusCatalogRecord.parse(recordData);
            String entryPath = currentPath + "/" + part;

            if (parsed instanceof HfsPlusCatalogRecord.FolderRecord folder) {
                if (i == parts.length - 1) {
                    return Optional.of(new HfsPlusDirectory(folder.cnid(), part, entryPath, folder));
                }
                currentCnid = folder.cnid();
                currentPath = entryPath;
            } else if (parsed instanceof HfsPlusCatalogRecord.FileRecord file) {
                if (i == parts.length - 1) {
                    if (file.isSymbolicLink()) {
                        return Optional.of(new HfsPlusSymlink(file, entryPath));
                    }
                    return Optional.of(new HfsPlusFile(file, entryPath));
                }
                return Optional.empty(); // Can't traverse through a file
            } else {
                return Optional.empty();
            }
        }

        return Optional.of(root());
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

    private Stream<FileSystemEntry> walkEntry(FileSystemEntry entry, int depth, int maxDepth,
                                               Set<Long> visited) {
        if (depth > maxDepth) {
            return Stream.empty();
        }

        if (entry instanceof HfsPlusDirectory dir) {
            long cnid = (long) dir.cnid;
            if (!visited.add(cnid)) {
                return Stream.empty(); // cycle detected
            }
        }

        Stream<FileSystemEntry> self = Stream.of(entry);

        if (entry instanceof FileSystemEntry.Directory dir && depth < maxDepth) {
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
        return volumeHeader.totalSize();
    }

    @Override
    public long usedSize() {
        return (volumeHeader.totalBlocks() - volumeHeader.freeBlocks()) * volumeHeader.blockSize();
    }

    @Override
    public long freeSize() {
        return volumeHeader.freeBlocks() * volumeHeader.blockSize();
    }

    @Override
    public @NotNull Optional<String> label() {
        return Optional.of(volumeName);
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return volumeHeader.volumeUuid();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("signature", volumeHeader.isHfsx() ? "HFSX" : "HFS+");
        meta.put("version", String.valueOf(volumeHeader.version()));
        meta.put("blockSize", String.valueOf(volumeHeader.blockSize()));
        meta.put("totalBlocks", String.valueOf(volumeHeader.totalBlocks()));
        meta.put("freeBlocks", String.valueOf(volumeHeader.freeBlocks()));
        meta.put("fileCount", String.valueOf(volumeHeader.fileCount()));
        meta.put("folderCount", String.valueOf(volumeHeader.folderCount()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        // Nothing to close - we don't own the disk
    }

    /**
     * Reads file data from the data fork extents, including overflow extents if needed.
     */
    private byte[] readFileData(HfsPlusCatalogRecord.FileRecord file) throws IOException {
        long size = file.dataLogicalSize();
        if (size > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("File too large to read into memory: " + size + " bytes (limit: 256 MB). Use openStream() for large files.", "allocation_size", MAX_READABLE_SIZE, size);
        }
        if (size == 0 || file.dataExtents().isEmpty()) {
            return new byte[0];
        }

        byte[] data = new byte[(int) size];
        int bytesRead = 0;
        int bs = volumeHeader.blockSize();

        // Read from inline extents (up to 8)
        bytesRead = readFromExtents(file.dataExtents(), data, bytesRead, size, bs);

        // If inline extents were not enough, look up overflow extents
        if (bytesRead < size && overflowBTree != null) {
            long blocksRead = 0;
            for (HfsPlusExtent extent : file.dataExtents()) {
                blocksRead += extent.blockCount();
            }

            List<HfsPlusExtent> overflowExtents = overflowBTree.findOverflowExtents(
                    file.cnid(), 0, blocksRead);
            if (!overflowExtents.isEmpty()) {
                bytesRead = readFromExtents(overflowExtents, data, bytesRead, size, bs);
            }
        }

        return data;
    }

    /**
     * Reads data from a list of extents into the destination array.
     *
     * @return the updated bytesRead position
     */
    private int readFromExtents(List<HfsPlusExtent> extents, byte[] data,
                                 int bytesRead, long size, int bs) throws IOException {
        for (HfsPlusExtent extent : extents) {
            if (bytesRead >= size) break;
            long diskOffset = extent.startBlock() * bs;
            long bytesToRead = extent.blockCount() * bs;

            while (bytesToRead > 0 && bytesRead < size) {
                int chunkSize = (int) Math.min(bytesToRead, Math.min(size - bytesRead, 64 * 1024));
                ByteBuffer buf = region.read(diskOffset, chunkSize);
                int actual = Math.min(chunkSize, (int) (size - bytesRead));
                buf.get(data, bytesRead, actual);
                bytesRead += actual;
                diskOffset += chunkSize;
                bytesToRead -= chunkSize;
            }
        }
        return bytesRead;
    }

    /**
     * Lists children of a directory by its CNID.
     */
    private List<FileSystemEntry> listDirectory(int parentCnid, String parentPath) throws IOException {
        List<byte[]> records = catalogReader.findRecordsByParentId(parentCnid);
        List<FileSystemEntry> entries = new ArrayList<>();

        for (byte[] recordData : records) {
            Object parsed = HfsPlusCatalogRecord.parse(recordData);

            if (parsed instanceof HfsPlusCatalogRecord.FolderRecord folder) {
                if (folder.name().isEmpty()) continue; // Skip thread records
                String entryPath = parentPath.equals("/") ? "/" + folder.name() : parentPath + "/" + folder.name();
                entries.add(new HfsPlusDirectory(folder.cnid(), folder.name(), entryPath, folder));
            } else if (parsed instanceof HfsPlusCatalogRecord.FileRecord file) {
                if (file.name().isEmpty()) continue;
                String entryPath = parentPath.equals("/") ? "/" + file.name() : parentPath + "/" + file.name();
                if (file.isSymbolicLink()) {
                    entries.add(new HfsPlusSymlink(file, entryPath));
                } else {
                    entries.add(new HfsPlusFile(file, entryPath));
                }
            }
            // Skip thread records
        }

        return entries;
    }

    // ========================================================================
    // Inner classes
    // ========================================================================

    private class HfsPlusDirectory implements FileSystemEntry.Directory {
        private final int cnid;
        private final String name;
        private final String path;
        private final HfsPlusCatalogRecord.FolderRecord folderRecord;

        HfsPlusDirectory(int cnid, String name, String path,
                         HfsPlusCatalogRecord.FolderRecord folderRecord) {
            this.cnid = cnid;
            this.name = name;
            this.path = path;
            this.folderRecord = folderRecord;
        }

        @Override
        public @NotNull String name() { return name; }

        @Override
        public @NotNull String path() { return path; }

        @Override
        public @NotNull EntryType type() { return EntryType.DIRECTORY; }

        @Override
        public long size() { return 0; }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return folderRecord != null ? folderRecord.creationTime() : Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return folderRecord != null ? folderRecord.modificationTime() : Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return folderRecord != null ? folderRecord.accessTime() : Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            if (folderRecord == null) return Collections.emptyMap();
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("cnid", folderRecord.cnid());
            attrs.put("valence", folderRecord.valence());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            return listDirectory(cnid, path).stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            byte[] recordData = catalogReader.findRecord(cnid, name);
            if (recordData == null) return Optional.empty();

            Object parsed = HfsPlusCatalogRecord.parse(recordData);
            String entryPath = path.equals("/") ? "/" + name : path + "/" + name;

            if (parsed instanceof HfsPlusCatalogRecord.FolderRecord folder) {
                return Optional.of(new HfsPlusDirectory(folder.cnid(), folder.name(), entryPath, folder));
            } else if (parsed instanceof HfsPlusCatalogRecord.FileRecord file) {
                if (file.isSymbolicLink()) {
                    return Optional.of(new HfsPlusSymlink(file, entryPath));
                }
                return Optional.of(new HfsPlusFile(file, entryPath));
            }
            return Optional.empty();
        }
    }

    private class HfsPlusFile implements FileSystemEntry.RegularFile {
        private final HfsPlusCatalogRecord.FileRecord record;
        private final String path;

        HfsPlusFile(HfsPlusCatalogRecord.FileRecord record, String path) {
            this.record = record;
            this.path = path;
        }

        @Override
        public @NotNull String name() { return record.name(); }

        @Override
        public @NotNull String path() { return path; }

        @Override
        public @NotNull EntryType type() { return EntryType.REGULAR_FILE; }

        @Override
        public long size() { return record.dataLogicalSize(); }

        @Override
        public @NotNull Optional<Instant> creationTime() { return record.creationTime(); }

        @Override
        public @NotNull Optional<Instant> modificationTime() { return record.modificationTime(); }

        @Override
        public @NotNull Optional<Instant> accessTime() { return record.accessTime(); }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("cnid", record.cnid());
            attrs.put("fileMode", record.fileMode());
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

    private class HfsPlusSymlink implements FileSystemEntry.SymbolicLink {
        private final HfsPlusCatalogRecord.FileRecord record;
        private final String path;

        HfsPlusSymlink(HfsPlusCatalogRecord.FileRecord record, String path) {
            this.record = record;
            this.path = path;
        }

        @Override
        public @NotNull String name() { return record.name(); }

        @Override
        public @NotNull String path() { return path; }

        @Override
        public long size() { return record.dataLogicalSize(); }

        @Override
        public @NotNull Optional<Instant> creationTime() { return record.creationTime(); }

        @Override
        public @NotNull Optional<Instant> modificationTime() { return record.modificationTime(); }

        @Override
        public @NotNull Optional<Instant> accessTime() { return record.accessTime(); }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("cnid", record.cnid());
            attrs.put("fileMode", record.fileMode());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public @NotNull String target() {
            try {
                byte[] data = readFileData(record);
                return new String(data).trim();
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            return resolveWithDepth(MAX_SYMLINK_DEPTH);
        }

        private @NotNull Optional<FileSystemEntry> resolveWithDepth(int remaining) throws IOException {
            if (remaining <= 0) {
                throw new IOException("Symlink depth exceeded for: " + path);
            }
            String targetPath = target();
            Optional<FileSystemEntry> result;
            if (targetPath.startsWith("/")) {
                result = HfsPlusFileSystemImpl.this.resolve(targetPath);
            } else {
                // Relative path resolution
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                if (parentPath.isEmpty()) parentPath = "/";
                result = HfsPlusFileSystemImpl.this.resolve(parentPath + "/" + targetPath);
            }
            if (result.isPresent() && result.get() instanceof HfsPlusSymlink nested) {
                return nested.resolveWithDepth(remaining - 1);
            }
            return result;
        }
    }

    private static class DirectoryIterator implements Iterator<FileSystemEntry> {
        private final Iterator<FileSystemEntry> delegate;

        DirectoryIterator(FileSystemEntry.Directory dir) throws IOException {
            this.delegate = dir.list().iterator();
        }

        @Override
        public boolean hasNext() { return delegate.hasNext(); }

        @Override
        public FileSystemEntry next() { return delegate.next(); }
    }
}
