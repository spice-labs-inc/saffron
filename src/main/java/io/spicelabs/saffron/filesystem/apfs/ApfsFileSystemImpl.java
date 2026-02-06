/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.apfs;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Read-only implementation of the Apple File System (APFS).
 *
 * <p>APFS is a little-endian, copy-on-write filesystem used by macOS 10.13+ (High Sierra and later).
 * It uses a container/volume architecture where a container holds one or more volumes.
 *
 * <p>Key architectural concepts:
 * <ul>
 *   <li>Container superblock at block 0 with magic "NXSB"</li>
 *   <li>Object map (omap) for virtual-to-physical address translation</li>
 *   <li>Per-volume filesystem B-tree containing inodes, directory records, and file extents</li>
 *   <li>Root directory is inode OID 2</li>
 * </ul>
 */
public class ApfsFileSystemImpl implements FileSystem.ApfsFileSystem {

    /** Maximum file size that can be read into memory (256 MB) */
    private static final long MAX_READABLE_SIZE = 256 * 1024 * 1024;

    /** Root directory inode number */
    private static final long ROOT_INODE = 2;

    private final DiskRegion region;
    private final ApfsContainerSuperblock containerSb;
    private final ApfsVolumeSuperblock volumeSb;
    private final ApfsObjectMap containerOmap;
    private final ApfsObjectMap volumeOmap;
    private final ApfsBTreeReader fsBtreeReader;
    private final long fsTreeRootBlock;
    private final int blockSize;

    private ApfsFileSystemImpl(DiskRegion region,
                                ApfsContainerSuperblock containerSb,
                                ApfsVolumeSuperblock volumeSb,
                                ApfsObjectMap containerOmap,
                                ApfsObjectMap volumeOmap,
                                ApfsBTreeReader fsBtreeReader,
                                long fsTreeRootBlock) {
        this.region = region;
        this.containerSb = containerSb;
        this.volumeSb = volumeSb;
        this.containerOmap = containerOmap;
        this.volumeOmap = volumeOmap;
        this.fsBtreeReader = fsBtreeReader;
        this.fsTreeRootBlock = fsTreeRootBlock;
        this.blockSize = containerSb.blockSize();
    }

    /**
     * Mounts an APFS filesystem from a virtual disk at the given offset.
     */
    public static @NotNull ApfsFileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Mounts an APFS filesystem from a DiskRegion.
     * Mounts the first volume in the container by default.
     */
    public static @NotNull ApfsFileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        return mount(region, 0);
    }

    /**
     * Mounts a specific APFS volume by index from a DiskRegion.
     *
     * @param region the disk region containing the APFS container
     * @param volumeIndex zero-based index of the volume to mount
     * @return the mounted filesystem
     * @throws IOException if an I/O error occurs or the volume index is out of range
     */
    public static @NotNull ApfsFileSystemImpl mount(@NotNull DiskRegion region, int volumeIndex) throws IOException {
        // Step 1: Read container superblock
        ApfsContainerSuperblock containerSb = ApfsContainerSuperblock.read(region);
        int blockSize = containerSb.blockSize();

        // Step 2: Open container object map
        ApfsObjectMap containerOmap = ApfsObjectMap.open(region, blockSize, containerSb.omapOid());

        // Step 3: Find and read the requested volume superblock
        if (containerSb.volumeOids().isEmpty()) {
            throw new IOException("No APFS volumes found in container");
        }
        if (volumeIndex < 0 || volumeIndex >= containerSb.volumeOids().size()) {
            throw new IOException("Volume index " + volumeIndex + " out of range (0.."
                    + (containerSb.volumeOids().size() - 1) + ")");
        }

        long volumeOid = containerSb.volumeOids().get(volumeIndex);
        long volumePhysBlock = containerOmap.resolve(volumeOid, containerSb.xid());
        if (volumePhysBlock < 0) {
            throw new IOException("Failed to resolve volume OID: " + volumeOid);
        }

        ApfsVolumeSuperblock volumeSb = ApfsVolumeSuperblock.read(region, blockSize, volumePhysBlock);

        // Step 4: Open volume object map
        // apfs_omap_oid is a physical OID — it directly identifies the block number
        long volumeOmapPhysBlock = volumeSb.omapOid();
        ApfsObjectMap volumeOmap = ApfsObjectMap.open(region, blockSize, volumeOmapPhysBlock);

        // Step 5: Locate filesystem B-tree root
        long fsTreeRootBlock = volumeOmap.resolve(volumeSb.rootTreeOid(), volumeSb.xid());
        if (fsTreeRootBlock < 0) {
            throw new IOException("Failed to resolve filesystem B-tree root OID: " + volumeSb.rootTreeOid());
        }

        ApfsBTreeReader fsBtreeReader = new ApfsBTreeReader(region, blockSize);

        return new ApfsFileSystemImpl(region, containerSb, volumeSb, containerOmap,
                volumeOmap, fsBtreeReader, fsTreeRootBlock);
    }

    /**
     * Returns the number of volumes in the APFS container.
     *
     * @param region the disk region containing the APFS container
     * @return the number of volumes
     * @throws IOException if an I/O error occurs
     */
    public static int volumeCount(@NotNull DiskRegion region) throws IOException {
        ApfsContainerSuperblock containerSb = ApfsContainerSuperblock.read(region);
        return containerSb.volumeOids().size();
    }

    @Override
    public int blockSize() {
        return blockSize;
    }

    @Override
    public @NotNull String volumeName() {
        return volumeSb.volumeName();
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        ApfsInodeRecord rootInode = readInode(ROOT_INODE);
        return new ApfsDirectory(ROOT_INODE, "/", "/", rootInode);
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
        long currentOid = ROOT_INODE;
        String currentPath = "";

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            // Look up directory record
            ApfsDirectoryRecord drec = findDirectoryRecord(currentOid, part);
            if (drec == null) {
                return Optional.empty();
            }

            long fileId = drec.fileId();
            String entryPath = currentPath + "/" + part;

            // Read the inode to determine type
            ApfsInodeRecord inode = readInode(fileId);
            if (inode == null) {
                return Optional.empty();
            }

            if (i == parts.length - 1) {
                return Optional.of(createEntry(inode, part, entryPath));
            }

            if (!inode.isDirectory()) {
                return Optional.empty();
            }

            currentOid = fileId;
            currentPath = entryPath;
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
        return containerSb.blockCount() * blockSize;
    }

    @Override
    public long usedSize() {
        return volumeSb.allocCount() * blockSize;
    }

    @Override
    public long freeSize() {
        long used = usedSize();
        long total = totalSize();
        return used <= total ? total - used : 0;
    }

    @Override
    public @NotNull Optional<String> label() {
        return Optional.of(volumeSb.volumeName());
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return containerSb.uuid();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("containerBlockSize", String.valueOf(blockSize));
        meta.put("containerBlockCount", String.valueOf(containerSb.blockCount()));
        meta.put("volumeName", volumeSb.volumeName());
        meta.put("volumeIndex", String.valueOf(volumeSb.fsIndex()));
        meta.put("volumeCount", String.valueOf(containerSb.volumeOids().size()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        // Nothing to close
    }

    // ========================================================================
    // Internal: Filesystem B-tree operations
    // ========================================================================

    /**
     * Reads an inode record from the filesystem B-tree.
     */
    private ApfsInodeRecord readInode(long oid) throws IOException {
        BiFunction<Long, Long, Long> resolver = volumeOmap.resolver();

        List<ApfsBTreeReader.KVEntry> entries = fsBtreeReader.collectPrefix(
                fsTreeRootBlock,
                (key, unused) -> {
                    if (key.length < 8) return false;
                    long keyOid = ApfsDirectoryRecord.getKeyOid(key);
                    long keyType = ApfsDirectoryRecord.getKeyType(key);
                    return keyOid == oid && keyType == ApfsInodeRecord.KEY_TYPE_INODE;
                },
                resolver
        );

        if (entries.isEmpty()) return null;

        ApfsBTreeReader.KVEntry entry = entries.get(0);
        return ApfsInodeRecord.parse(entry.key(), entry.val());
    }

    /**
     * Finds a directory record by parent OID and name.
     */
    private ApfsDirectoryRecord findDirectoryRecord(long parentOid, String name) throws IOException {
        BiFunction<Long, Long, Long> resolver = volumeOmap.resolver();

        List<ApfsBTreeReader.KVEntry> entries = fsBtreeReader.collectPrefix(
                fsTreeRootBlock,
                (key, unused) -> ApfsDirectoryRecord.isDrecForParent(key, parentOid),
                resolver
        );

        for (ApfsBTreeReader.KVEntry entry : entries) {
            ApfsDirectoryRecord drec = ApfsDirectoryRecord.parse(entry.key(), entry.val());
            if (drec != null && drec.name().equals(name)) {
                return drec;
            }
        }

        // Try case-insensitive
        for (ApfsBTreeReader.KVEntry entry : entries) {
            ApfsDirectoryRecord drec = ApfsDirectoryRecord.parse(entry.key(), entry.val());
            if (drec != null && drec.name().equalsIgnoreCase(name)) {
                return drec;
            }
        }

        return null;
    }

    /**
     * Lists all directory records for a parent OID.
     */
    private List<ApfsDirectoryRecord> listDirectoryRecords(long parentOid) throws IOException {
        BiFunction<Long, Long, Long> resolver = volumeOmap.resolver();

        List<ApfsBTreeReader.KVEntry> entries = fsBtreeReader.collectPrefix(
                fsTreeRootBlock,
                (key, unused) -> ApfsDirectoryRecord.isDrecForParent(key, parentOid),
                resolver
        );

        List<ApfsDirectoryRecord> records = new ArrayList<>();
        for (ApfsBTreeReader.KVEntry entry : entries) {
            ApfsDirectoryRecord drec = ApfsDirectoryRecord.parse(entry.key(), entry.val());
            if (drec != null) {
                records.add(drec);
            }
        }

        return records;
    }

    /**
     * Reads file data from file extent records.
     */
    private byte[] readFileData(long privateId, long fileSize) throws IOException {
        if (fileSize > MAX_READABLE_SIZE) {
            throw new IOException("File too large to read: " + fileSize + " bytes");
        }
        if (fileSize == 0) {
            return new byte[0];
        }

        BiFunction<Long, Long, Long> resolver = volumeOmap.resolver();

        // Collect all file extent records for this private_id
        List<ApfsBTreeReader.KVEntry> entries = fsBtreeReader.collectPrefix(
                fsTreeRootBlock,
                (key, unused) -> ApfsFileExtent.isFileExtentForId(key, privateId),
                resolver
        );

        // Sort by logical offset
        List<ApfsFileExtent> extents = new ArrayList<>();
        for (ApfsBTreeReader.KVEntry entry : entries) {
            ApfsFileExtent extent = ApfsFileExtent.parse(entry.key(), entry.val());
            if (extent != null) {
                extents.add(extent);
            }
        }
        extents.sort(Comparator.comparingLong(ApfsFileExtent::logicalOffset));

        byte[] data = new byte[(int) fileSize];
        for (ApfsFileExtent extent : extents) {
            long logicalStart = extent.logicalOffset();
            long physBlock = extent.physicalBlock();
            long length = extent.length();

            if (logicalStart >= fileSize) break;

            long toRead = Math.min(length, fileSize - logicalStart);
            long diskOffset = physBlock * blockSize;

            int offset = (int) logicalStart;
            while (toRead > 0) {
                int chunkSize = (int) Math.min(toRead, 64 * 1024);
                ByteBuffer buf = region.read(diskOffset, chunkSize);
                int actual = (int) Math.min(chunkSize, fileSize - offset);
                buf.get(data, offset, actual);
                offset += actual;
                diskOffset += chunkSize;
                toRead -= chunkSize;
            }
        }

        return data;
    }

    private FileSystemEntry createEntry(ApfsInodeRecord inode, String name, String path) {
        if (inode.isDirectory()) {
            return new ApfsDirectory(inode.oid(), name, path, inode);
        } else if (inode.isSymbolicLink()) {
            return new ApfsSymlink(inode, name, path);
        } else {
            return new ApfsFile(inode, name, path);
        }
    }

    // ========================================================================
    // Inner classes
    // ========================================================================

    private class ApfsDirectory implements FileSystemEntry.Directory {
        private final long oid;
        private final String name;
        private final String path;
        private final ApfsInodeRecord inode;

        ApfsDirectory(long oid, String name, String path, ApfsInodeRecord inode) {
            this.oid = oid;
            this.name = name;
            this.path = path;
            this.inode = inode;
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
            return inode != null ? inode.creationTime() : Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return inode != null ? inode.modificationTime() : Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return inode != null ? inode.accessTimeInstant() : Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            if (inode == null) return Collections.emptyMap();
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode());
            attrs.put("uid", inode.uid());
            attrs.put("gid", inode.gid());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<ApfsDirectoryRecord> drecs = listDirectoryRecords(oid);
            List<FileSystemEntry> entries = new ArrayList<>();

            for (ApfsDirectoryRecord drec : drecs) {
                String entryName = drec.name();
                if (entryName.isEmpty()) continue;
                String entryPath = path.equals("/") ? "/" + entryName : path + "/" + entryName;

                ApfsInodeRecord inode = readInode(drec.fileId());
                if (inode != null) {
                    entries.add(createEntry(inode, entryName, entryPath));
                }
            }

            return entries.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            ApfsDirectoryRecord drec = findDirectoryRecord(oid, name);
            if (drec == null) return Optional.empty();

            ApfsInodeRecord inode = readInode(drec.fileId());
            if (inode == null) return Optional.empty();

            String entryPath = path.equals("/") ? "/" + name : path + "/" + name;
            return Optional.of(createEntry(inode, drec.name(), entryPath));
        }
    }

    private class ApfsFile implements FileSystemEntry.RegularFile {
        private final ApfsInodeRecord inode;
        private final String name;
        private final String path;

        ApfsFile(ApfsInodeRecord inode, String name, String path) {
            this.inode = inode;
            this.name = name;
            this.path = path;
        }

        @Override
        public @NotNull String name() { return name; }

        @Override
        public @NotNull String path() { return path; }

        @Override
        public @NotNull EntryType type() { return EntryType.REGULAR_FILE; }

        @Override
        public long size() {
            return inode.dataStreamSize() > 0 ? inode.dataStreamSize() : inode.uncompressedSize();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() { return inode.creationTime(); }

        @Override
        public @NotNull Optional<Instant> modificationTime() { return inode.modificationTime(); }

        @Override
        public @NotNull Optional<Instant> accessTime() { return inode.accessTimeInstant(); }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode());
            attrs.put("uid", inode.uid());
            attrs.put("gid", inode.gid());
            return Collections.unmodifiableMap(attrs);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return readFileData(inode.privateId(), size());
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return new ByteArrayInputStream(readAllBytes());
        }
    }

    private class ApfsSymlink implements FileSystemEntry.SymbolicLink {
        private final ApfsInodeRecord inode;
        private final String name;
        private final String path;

        ApfsSymlink(ApfsInodeRecord inode, String name, String path) {
            this.inode = inode;
            this.name = name;
            this.path = path;
        }

        @Override
        public @NotNull String name() { return name; }

        @Override
        public @NotNull String path() { return path; }

        @Override
        public long size() {
            return inode.dataStreamSize() > 0 ? inode.dataStreamSize() : inode.uncompressedSize();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() { return inode.creationTime(); }

        @Override
        public @NotNull Optional<Instant> modificationTime() { return inode.modificationTime(); }

        @Override
        public @NotNull Optional<Instant> accessTime() { return inode.accessTimeInstant(); }

        @Override
        public @NotNull Map<String, Object> attributes() { return Collections.emptyMap(); }

        @Override
        public @NotNull String target() {
            try {
                byte[] data = readFileData(inode.privateId(), size());
                return new String(data, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            String targetPath = target();
            if (targetPath.startsWith("/")) {
                return ApfsFileSystemImpl.this.resolve(targetPath);
            }
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            if (parentPath.isEmpty()) parentPath = "/";
            return ApfsFileSystemImpl.this.resolve(parentPath + "/" + targetPath);
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
