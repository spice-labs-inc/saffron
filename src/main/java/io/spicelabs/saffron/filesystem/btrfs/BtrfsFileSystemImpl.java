/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

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
 * Btrfs filesystem implementation for read-only access.
 */
public class BtrfsFileSystemImpl implements FileSystem.BtrfsFileSystem {

    private static final int MAX_SYMLINK_DEPTH = 40;

    private final DiskRegion region;
    private final long partitionOffset;
    private final BtrfsSuperblock superblock;
    private final BtrfsChunkTree chunkTree;
    private final BtrfsTreeReader treeReader;
    private final long fsTreeRoot;
    private final long rootTreeRoot;
    private final Map<Long, Long> subvolumeTreeRoots = new HashMap<>();

    private BtrfsFileSystemImpl(DiskRegion region, long partitionOffset, BtrfsSuperblock superblock,
                                 BtrfsChunkTree chunkTree, long fsTreeRoot, long rootTreeRoot) {
        this.region = region;
        this.partitionOffset = partitionOffset;
        this.superblock = superblock;
        this.chunkTree = chunkTree;
        this.treeReader = new BtrfsTreeReader(chunkTree, superblock.nodeSize());
        this.fsTreeRoot = fsTreeRoot;
        this.rootTreeRoot = rootTreeRoot;
    }

    /**
     * Mounts a Btrfs filesystem from a virtual disk at the given offset.
     */
    public static BtrfsFileSystemImpl mount(VirtualDisk disk, long offset) throws IOException {
        return mount(DiskRegion.fromPartition(disk, offset, 0), 0);
    }

    /**
     * Mounts a Btrfs filesystem from a disk region at the given offset.
     */
    public static BtrfsFileSystemImpl mount(DiskRegion region, long offset) throws IOException {
        // Read superblock
        BtrfsSuperblock superblock = BtrfsSuperblock.read(region, offset);

        // Parse chunk tree from system chunk array
        BtrfsChunkTree chunkTree = BtrfsChunkTree.parse(region, offset, superblock);

        // Find FS_TREE root by looking up ROOT_ITEM for objectid 5
        BtrfsTreeReader tempReader = new BtrfsTreeReader(chunkTree, superblock.nodeSize());
        long fsTreeRoot = findFsTreeRoot(tempReader, superblock.rootTreeRoot());

        return new BtrfsFileSystemImpl(region, offset, superblock, chunkTree, fsTreeRoot, superblock.rootTreeRoot());
    }

    /**
     * Mounts a Btrfs filesystem and all its subvolumes as separate filesystem instances.
     *
     * <p>The first element is always the main FS_TREE (objectid 5). Subsequent elements
     * are subvolumes (objectid >= 256), each rooted at their own tree root.
     *
     * @param region the disk region containing the filesystem
     * @param offset the byte offset where the filesystem starts
     * @return list of filesystem instances (main + subvolumes)
     * @throws IOException if an I/O error occurs
     */
    public static List<BtrfsFileSystemImpl> mountWithSubvolumes(DiskRegion region, long offset) throws IOException {
        BtrfsFileSystemImpl main = mount(region, offset);
        List<BtrfsFileSystemImpl> all = new ArrayList<>();
        all.add(main);

        // Scan ROOT_TREE for all ROOT_ITEM entries with objectid >= 256
        List<BtrfsTreeReader.SearchResult> rootItems = main.treeReader.scanForType(
                main.rootTreeRoot, BtrfsKey.ROOT_ITEM, 10000);

        for (BtrfsTreeReader.SearchResult result : rootItems) {
            long objId = result.item().key().objectId();
            if (objId < BtrfsKey.FIRST_FREE_OBJECTID) continue;

            byte[] data = result.data();
            if (data.length < 184) continue; // ROOT_ITEM too small to contain bytenr

            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.position(176); // inode (160) + generation (8) + root_dirid (8) = 176
            long treeRoot = buf.getLong();
            if (treeRoot == 0) continue;

            try {
                all.add(new BtrfsFileSystemImpl(region, offset, main.superblock,
                        main.chunkTree, treeRoot, main.rootTreeRoot));
            } catch (Exception e) {
                // Skip subvolumes that fail to mount
            }
        }
        return all;
    }

    private static long findFsTreeRoot(BtrfsTreeReader reader, long rootTreeRoot) throws IOException {
        // Search for ROOT_ITEM with objectid = FS_TREE_OBJECTID (5)
        List<BtrfsTreeReader.SearchResult> results = reader.search(
                rootTreeRoot, BtrfsKey.FS_TREE_OBJECTID, BtrfsKey.ROOT_ITEM);

        if (results.isEmpty()) {
            throw new IOException("FS_TREE root not found");
        }

        // Parse ROOT_ITEM to get the tree root address
        byte[] data = results.get(0).data();
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Skip to bytenr field (offset 176 in ROOT_ITEM)
        // ROOT_ITEM structure: inode (160) + generation (8) + root_dirid (8) + bytenr (8)
        buf.position(160 + 8 + 8);
        return buf.getLong();
    }

    /**
     * Resolves a subvolume tree root address from the ROOT_TREE.
     */
    private long resolveSubvolumeTreeRoot(long subvolumeObjectId) throws IOException {
        Long cached = subvolumeTreeRoots.get(subvolumeObjectId);
        if (cached != null) return cached;

        List<BtrfsTreeReader.SearchResult> results = treeReader.search(
                rootTreeRoot, subvolumeObjectId, BtrfsKey.ROOT_ITEM);
        if (results.isEmpty()) {
            throw new IOException("Subvolume not found: " + subvolumeObjectId);
        }

        byte[] data = results.get(0).data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(176);
        long treeRoot = buf.getLong();
        subvolumeTreeRoots.put(subvolumeObjectId, treeRoot);
        return treeRoot;
    }

    @Override
    public int nodeSize() {
        return superblock.nodeSize();
    }

    @Override
    public int sectorSize() {
        return superblock.sectorSize();
    }

    @Override
    public long generation() {
        return superblock.generation();
    }

    @Override
    public long totalSize() {
        return superblock.totalBytes();
    }

    @Override
    public long usedSize() {
        return superblock.bytesUsed();
    }

    @Override
    public long freeSize() {
        return superblock.freeBytes();
    }

    @Override
    public @NotNull Optional<String> label() {
        String label = superblock.label();
        return label.isEmpty() ? Optional.empty() : Optional.of(label);
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.of(superblock.uuid());
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fsType", "btrfs");
        meta.put("nodeSize", String.valueOf(superblock.nodeSize()));
        meta.put("sectorSize", String.valueOf(superblock.sectorSize()));
        meta.put("generation", String.valueOf(superblock.generation()));
        meta.put("numDevices", String.valueOf(superblock.numDevices()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        return new BtrfsDirectory("/", BtrfsKey.FIRST_FREE_OBJECTID, fsTreeRoot);
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
            Optional<FileSystemEntry> child = dir.find(part);
            if (child.isEmpty()) {
                return Optional.empty();
            }
            current = child.get();
        }
        return Optional.of(current);
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk() throws IOException {
        return walk("/", Integer.MAX_VALUE);
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth) throws IOException {
        Optional<FileSystemEntry> entry = resolve(path);
        if (entry.isEmpty()) {
            return Stream.empty();
        }
        return walkEntry(entry.get(), 0, maxDepth, new HashSet<TreeObjectKey>());
    }

    private record TreeObjectKey(long treeRoot, long objectId) {}

    private Stream<FileSystemEntry> walkEntry(FileSystemEntry entry, int depth, int maxDepth,
                                               Set<TreeObjectKey> ancestorKeys) {
        if (depth > maxDepth) {
            return Stream.empty();
        }

        Stream<FileSystemEntry> self = Stream.of(entry);

        if (entry instanceof BtrfsDirectory btrfsDir && depth < maxDepth) {
            // Cycle detection: skip if this (treeRoot, objectId) pair was already seen in an ancestor
            TreeObjectKey key = new TreeObjectKey(btrfsDir.treeRoot, btrfsDir.objectId);
            if (!ancestorKeys.add(key)) {
                return self;
            }
            Set<TreeObjectKey> childAncestors = new HashSet<>(ancestorKeys);
            try {
                Stream<FileSystemEntry> children = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(btrfsDir.list().iterator(), Spliterator.ORDERED),
                        false
                ).flatMap(child -> {
                    try {
                        return walkEntry(child, depth + 1, maxDepth, childAncestors);
                    } catch (Exception e) {
                        return Stream.empty();
                    }
                });
                return Stream.concat(self, children);
            } catch (IOException e) {
                return self;
            }
        } else if (entry instanceof FileSystemEntry.Directory dir && depth < maxDepth) {
            try {
                Stream<FileSystemEntry> children = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(dir.list().iterator(), Spliterator.ORDERED),
                        false
                ).flatMap(child -> {
                    try {
                        return walkEntry(child, depth + 1, maxDepth, ancestorKeys);
                    } catch (Exception e) {
                        return Stream.empty();
                    }
                });
                return Stream.concat(self, children);
            } catch (IOException e) {
                return self;
            }
        }
        return self;
    }

    @Override
    public void close() {
        // Nothing to close
    }

    private BtrfsInode readInode(long objectId) throws IOException {
        return readInode(fsTreeRoot, objectId);
    }

    private BtrfsInode readInode(long treeRoot, long objectId) throws IOException {
        List<BtrfsTreeReader.SearchResult> results = treeReader.search(
                treeRoot, objectId, BtrfsKey.INODE_ITEM);
        if (results.isEmpty()) {
            throw new IOException("Inode not found: " + objectId);
        }
        return BtrfsInode.parse(results.get(0).data());
    }

    private List<BtrfsDirectoryEntry> readDirEntries(long dirObjectId) throws IOException {
        return readDirEntries(fsTreeRoot, dirObjectId);
    }

    private List<BtrfsDirectoryEntry> readDirEntries(long treeRoot, long dirObjectId) throws IOException {
        Map<String, BtrfsDirectoryEntry> entriesByName = new LinkedHashMap<>();

        // Search DIR_INDEX first (primary, sorted by sequence)
        List<BtrfsTreeReader.SearchResult> indexResults = treeReader.search(
                treeRoot, dirObjectId, BtrfsKey.DIR_INDEX);
        for (BtrfsTreeReader.SearchResult result : indexResults) {
            BtrfsDirectoryEntry entry = BtrfsDirectoryEntry.parse(result.data());
            if (!entry.name().equals(".") && !entry.name().equals("..")) {
                entriesByName.put(entry.name(), entry);
            }
        }

        // Also search DIR_ITEM as fallback for entries not in DIR_INDEX
        List<BtrfsTreeReader.SearchResult> itemResults = treeReader.search(
                treeRoot, dirObjectId, BtrfsKey.DIR_ITEM);
        for (BtrfsTreeReader.SearchResult result : itemResults) {
            BtrfsDirectoryEntry entry = BtrfsDirectoryEntry.parse(result.data());
            if (!entry.name().equals(".") && !entry.name().equals("..")) {
                entriesByName.putIfAbsent(entry.name(), entry);
            }
        }

        return new ArrayList<>(entriesByName.values());
    }

    private byte[] readFileData(long objectId, long size) throws IOException {
        return readFileData(fsTreeRoot, objectId, size);
    }

    private byte[] readFileData(long treeRoot, long objectId, long size) throws IOException {
        if (size > 256 * 1024 * 1024) {
            throw new ResourceLimitException("File too large to read into memory: " + size + " bytes (limit: 256 MB). Use openStream() for large files.", "allocation_size", 256L * 1024 * 1024, size);
        }

        List<BtrfsTreeReader.SearchResult> extents = treeReader.search(
                treeRoot, objectId, BtrfsKey.EXTENT_DATA);

        if (extents.isEmpty()) {
            return new byte[0];
        }

        // Sort extents by offset
        extents.sort(Comparator.comparingLong(e -> e.item().key().offset()));

        ByteBuffer result = ByteBuffer.allocate((int) size);

        for (BtrfsTreeReader.SearchResult extentResult : extents) {
            BtrfsExtentData extent = BtrfsExtentData.parse(extentResult.data());
            long fileOffset = extentResult.item().key().offset();

            if (extent.isInline()) {
                // Inline data (may be compressed)
                byte[] data = extent.inlineData();
                if (extent.isCompressed()) {
                    try {
                        data = decompressExtent(data, (int) extent.ramBytes(), extent.compression());
                    } catch (IOException e) {
                        // Decompression failed - use raw data as fallback
                    }
                }
                int copyLen = (int) Math.min(data.length, size - fileOffset);
                if (fileOffset < size && copyLen > 0) {
                    result.position((int) fileOffset);
                    result.put(data, 0, copyLen);
                }
            } else if (extent.isHole()) {
                // Sparse hole - already zeros
            } else if (extent.isCompressed()) {
                // Read compressed data from disk
                long diskAddr = extent.diskBytenr();
                long compressedSize = extent.diskNumBytes();
                long uncompressedSize = extent.ramBytes();
                long extentOffset = extent.offset();
                long numBytesNeeded = extent.numBytes();

                if (diskAddr != 0 && compressedSize > 0) {
                    try {
                        ByteBuffer compBuf = chunkTree.readLogical(diskAddr, (int) compressedSize);
                        byte[] compressed = new byte[(int) compressedSize];
                        compBuf.get(compressed);

                        byte[] decompressed = decompressExtent(compressed, (int) uncompressedSize, extent.compression());

                        // Copy the needed portion (from offset for numBytes)
                        int srcOff = (int) extentOffset;
                        int copyLen = (int) Math.min(numBytesNeeded, Math.min(decompressed.length - srcOff, size - fileOffset));
                        if (fileOffset < size && copyLen > 0 && srcOff < decompressed.length) {
                            result.position((int) fileOffset);
                            result.put(decompressed, srcOff, copyLen);
                        }
                    } catch (IOException e) {
                        // Decompression failed - leave as zeros
                    }
                }
            } else {
                // Regular extent
                long diskAddr = extent.diskBytenr();
                long extentOffset = extent.offset();
                long numBytes = extent.numBytes();

                ByteBuffer extentBuf = chunkTree.readLogical(diskAddr + extentOffset, (int) numBytes);
                extentBuf.order(ByteOrder.LITTLE_ENDIAN);

                int copyLen = (int) Math.min(numBytes, size - fileOffset);
                if (fileOffset < size && copyLen > 0) {
                    result.position((int) fileOffset);
                    byte[] temp = new byte[copyLen];
                    extentBuf.get(temp);
                    result.put(temp);
                }
            }
        }

        return result.array();
    }

    private String readSymlinkTarget(long objectId) throws IOException {
        return readSymlinkTarget(fsTreeRoot, objectId);
    }

    private String readSymlinkTarget(long treeRoot, long objectId) throws IOException {
        List<BtrfsTreeReader.SearchResult> extents = treeReader.search(
                treeRoot, objectId, BtrfsKey.EXTENT_DATA);

        if (extents.isEmpty()) {
            return "";
        }

        BtrfsExtentData extent = BtrfsExtentData.parse(extents.get(0).data());
        if (extent.isInline()) {
            return new String(extent.inlineData(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }

    /**
     * Decompresses extent data using the appropriate algorithm.
     */
    private static byte[] decompressExtent(byte[] compressed, int uncompressedSize, int compression)
            throws IOException {
        return switch (compression) {
            case BtrfsExtentData.COMPRESS_ZLIB -> decompressZlib(compressed, uncompressedSize);
            case BtrfsExtentData.COMPRESS_ZSTD -> decompressZstd(compressed, uncompressedSize);
            case BtrfsExtentData.COMPRESS_LZO -> throw new IOException("Unsupported Btrfs compression: lzo");
            default -> throw new IOException("Unknown Btrfs compression type: " + compression);
        };
    }

    private static byte[] decompressZlib(byte[] compressed, int uncompressedSize) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(compressed);
            byte[] output = new byte[uncompressedSize];
            int offset = 0;
            while (offset < uncompressedSize && !inflater.finished()) {
                try {
                    int n = inflater.inflate(output, offset, uncompressedSize - offset);
                    if (n == 0 && inflater.needsInput()) break;
                    offset += n;
                } catch (java.util.zip.DataFormatException e) {
                    throw new IOException("Zlib decompression failed", e);
                }
            }
            return output;
        } finally {
            inflater.end();
        }
    }

    private static byte[] decompressZstd(byte[] compressed, int uncompressedSize) throws IOException {
        // Btrfs stores compressed extents in sector-aligned blocks. The actual zstd
        // frame may be smaller than disk_num_bytes (the rest is padding). Use streaming
        // decompression which reads exactly one frame and stops. After the frame ends,
        // the stream may throw on trailing padding bytes — this is expected and handled.
        try (var zis = new com.github.luben.zstd.ZstdInputStream(
                new ByteArrayInputStream(compressed))) {
            byte[] output = new byte[uncompressedSize];
            int totalRead = 0;
            while (totalRead < uncompressedSize) {
                int n;
                try {
                    n = zis.read(output, totalRead, uncompressedSize - totalRead);
                } catch (com.github.luben.zstd.ZstdIOException e) {
                    // End of zstd frame reached; trailing padding in the sector caused
                    // an "Unknown frame descriptor" error — this is normal for Btrfs.
                    break;
                }
                if (n <= 0) break;
                totalRead += n;
            }
            return output;
        } catch (Exception e) {
            throw new IOException("Zstd decompression failed", e);
        }
    }

    // ========================================================================
    // Inner classes for FileSystemEntry implementations
    // ========================================================================

    private class BtrfsDirectory implements FileSystemEntry.Directory {
        private final String path;
        private final long objectId;
        private final long treeRoot;
        private BtrfsInode inode;

        BtrfsDirectory(String path, long objectId, long treeRoot) {
            this.path = path;
            this.objectId = objectId;
            this.treeRoot = treeRoot;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(treeRoot, objectId);
            }
            return inode;
        }

        @Override
        public @NotNull String name() {
            if (path.equals("/")) return "/";
            int idx = path.lastIndexOf('/');
            return idx >= 0 ? path.substring(idx + 1) : path;
        }

        @Override
        public @NotNull String path() {
            return path;
        }

        @Override
        public long size() {
            try {
                return getInode().size();
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            try {
                return Optional.of(getInode().otime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            try {
                return Optional.of(getInode().mtime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            try {
                return Optional.of(getInode().atime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            try {
                BtrfsInode in = getInode();
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("mode", in.mode());
                attrs.put("uid", in.uid());
                attrs.put("gid", in.gid());
                attrs.put("nlink", in.nlink());
                attrs.put("objectId", objectId);
                return attrs;
            } catch (IOException e) {
                return Map.of();
            }
        }

        @Override
        public @NotNull EntryType type() {
            return EntryType.DIRECTORY;
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<BtrfsDirectoryEntry> entries = readDirEntries(treeRoot, objectId);
            List<FileSystemEntry> result = new ArrayList<>();

            for (BtrfsDirectoryEntry entry : entries) {
                String childPath = path.equals("/") ? "/" + entry.name() : path + "/" + entry.name();
                long childObjId = entry.targetObjectId();

                try {
                    FileSystemEntry fsEntry;
                    if (entry.location().type() == BtrfsKey.ROOT_ITEM) {
                        // Subvolume entry — resolve its tree root, root dir is always objectid 256
                        long subvolTreeRoot = resolveSubvolumeTreeRoot(childObjId);
                        fsEntry = new BtrfsDirectory(childPath, BtrfsKey.FIRST_FREE_OBJECTID, subvolTreeRoot);
                    } else {
                        fsEntry = switch (entry.type()) {
                            case BtrfsDirectoryEntry.FT_DIR -> new BtrfsDirectory(childPath, childObjId, treeRoot);
                            case BtrfsDirectoryEntry.FT_REG_FILE -> new BtrfsRegularFile(childPath, childObjId, treeRoot);
                            case BtrfsDirectoryEntry.FT_SYMLINK -> new BtrfsSymlink(childPath, childObjId, treeRoot);
                            case BtrfsDirectoryEntry.FT_CHRDEV, BtrfsDirectoryEntry.FT_BLKDEV ->
                                    new BtrfsSpecialFile(childPath, childObjId, entry.type(), treeRoot);
                            default -> new BtrfsRegularFile(childPath, childObjId, treeRoot);  // Fallback
                        };
                    }
                    result.add(fsEntry);
                } catch (Exception e) {
                    // Skip entries that fail to resolve (e.g. deleted subvolumes)
                }
            }
            return result.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            return list().filter(e -> e.name().equals(name)).findFirst();
        }
    }

    private class BtrfsRegularFile implements FileSystemEntry.RegularFile {
        private final String path;
        private final long objectId;
        private final long treeRoot;
        private BtrfsInode inode;

        BtrfsRegularFile(String path, long objectId, long treeRoot) {
            this.path = path;
            this.objectId = objectId;
            this.treeRoot = treeRoot;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(treeRoot, objectId);
            }
            return inode;
        }

        @Override
        public @NotNull String name() {
            int idx = path.lastIndexOf('/');
            return idx >= 0 ? path.substring(idx + 1) : path;
        }

        @Override
        public @NotNull String path() {
            return path;
        }

        @Override
        public long size() {
            try {
                return getInode().size();
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            try {
                return Optional.of(getInode().otime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            try {
                return Optional.of(getInode().mtime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            try {
                return Optional.of(getInode().atime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            try {
                BtrfsInode in = getInode();
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("mode", in.mode());
                attrs.put("uid", in.uid());
                attrs.put("gid", in.gid());
                attrs.put("nlink", in.nlink());
                attrs.put("objectId", objectId);
                return attrs;
            } catch (IOException e) {
                return Map.of();
            }
        }

        @Override
        public @NotNull EntryType type() {
            return EntryType.REGULAR_FILE;
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            byte[] data = readFileData(treeRoot, objectId, size());
            return new ByteArrayInputStream(data);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return readFileData(treeRoot, objectId, size());
        }
    }

    private class BtrfsSymlink implements FileSystemEntry.SymbolicLink {
        private final String path;
        private final long objectId;
        private final long treeRoot;
        private final String target;
        private BtrfsInode inode;

        BtrfsSymlink(String path, long objectId, long treeRoot) {
            this.path = path;
            this.objectId = objectId;
            this.treeRoot = treeRoot;
            // Read target eagerly since interface doesn't allow IOException
            String targetValue;
            try {
                targetValue = readSymlinkTarget(treeRoot, objectId);
            } catch (IOException e) {
                targetValue = "";
            }
            this.target = targetValue;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(treeRoot, objectId);
            }
            return inode;
        }

        @Override
        public @NotNull String name() {
            int idx = path.lastIndexOf('/');
            return idx >= 0 ? path.substring(idx + 1) : path;
        }

        @Override
        public @NotNull String path() {
            return path;
        }

        @Override
        public long size() {
            try {
                return getInode().size();
            } catch (IOException e) {
                return 0;
            }
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            try {
                return Optional.of(getInode().otime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            try {
                return Optional.of(getInode().mtime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            try {
                return Optional.of(getInode().atime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            try {
                BtrfsInode in = getInode();
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("mode", in.mode());
                attrs.put("uid", in.uid());
                attrs.put("gid", in.gid());
                attrs.put("objectId", objectId);
                return attrs;
            } catch (IOException e) {
                return Map.of();
            }
        }

        @Override
        public @NotNull EntryType type() {
            return EntryType.SYMBOLIC_LINK;
        }

        @Override
        public @NotNull String target() {
            return target;
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            return resolveWithDepth(MAX_SYMLINK_DEPTH);
        }

        private @NotNull Optional<FileSystemEntry> resolveWithDepth(int remaining) throws IOException {
            if (remaining <= 0) {
                throw new IOException("Symlink depth exceeded (max " + MAX_SYMLINK_DEPTH + "): " + path);
            }
            String targetPath = target();
            Optional<FileSystemEntry> resolved;
            if (targetPath.startsWith("/")) {
                resolved = BtrfsFileSystemImpl.this.resolve(targetPath);
            } else {
                // Relative path
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                if (parentPath.isEmpty()) parentPath = "/";
                resolved = BtrfsFileSystemImpl.this.resolve(parentPath + "/" + targetPath);
            }
            if (resolved.isPresent() && resolved.get() instanceof BtrfsSymlink nextLink) {
                return nextLink.resolveWithDepth(remaining - 1);
            }
            return resolved;
        }
    }

    private class BtrfsSpecialFile implements FileSystemEntry.SpecialFile {
        private final String path;
        private final long objectId;
        private final int fileType;
        private final long treeRoot;
        private BtrfsInode inode;

        BtrfsSpecialFile(String path, long objectId, int fileType, long treeRoot) {
            this.path = path;
            this.objectId = objectId;
            this.fileType = fileType;
            this.treeRoot = treeRoot;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(treeRoot, objectId);
            }
            return inode;
        }

        @Override
        public @NotNull String name() {
            int idx = path.lastIndexOf('/');
            return idx >= 0 ? path.substring(idx + 1) : path;
        }

        @Override
        public @NotNull String path() {
            return path;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            try {
                return Optional.of(getInode().otime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            try {
                return Optional.of(getInode().mtime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            try {
                return Optional.of(getInode().atime());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            try {
                BtrfsInode in = getInode();
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("mode", in.mode());
                attrs.put("uid", in.uid());
                attrs.put("gid", in.gid());
                attrs.put("rdev", in.rdev());
                attrs.put("objectId", objectId);
                return attrs;
            } catch (IOException e) {
                return Map.of();
            }
        }

        @Override
        public @NotNull EntryType type() {
            return switch (fileType) {
                case BtrfsDirectoryEntry.FT_CHRDEV -> EntryType.CHARACTER_DEVICE;
                case BtrfsDirectoryEntry.FT_BLKDEV -> EntryType.BLOCK_DEVICE;
                case BtrfsDirectoryEntry.FT_FIFO -> EntryType.FIFO;
                case BtrfsDirectoryEntry.FT_SOCK -> EntryType.SOCKET;
                default -> EntryType.UNKNOWN;
            };
        }

        @Override
        public @NotNull Optional<Integer> majorDevice() {
            try {
                long rdev = getInode().rdev();
                return Optional.of((int) ((rdev >> 8) & 0xFFF));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            try {
                long rdev = getInode().rdev();
                return Optional.of((int) (rdev & 0xFF));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }
}
