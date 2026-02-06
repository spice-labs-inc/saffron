/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.btrfs;

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
 * Btrfs filesystem implementation for read-only access.
 */
public class BtrfsFileSystemImpl implements FileSystem.BtrfsFileSystem {

    private final DiskRegion region;
    private final long partitionOffset;
    private final BtrfsSuperblock superblock;
    private final BtrfsChunkTree chunkTree;
    private final BtrfsTreeReader treeReader;
    private final long fsTreeRoot;

    private BtrfsFileSystemImpl(DiskRegion region, long partitionOffset, BtrfsSuperblock superblock,
                                 BtrfsChunkTree chunkTree, long fsTreeRoot) {
        this.region = region;
        this.partitionOffset = partitionOffset;
        this.superblock = superblock;
        this.chunkTree = chunkTree;
        this.treeReader = new BtrfsTreeReader(chunkTree, superblock.nodeSize());
        this.fsTreeRoot = fsTreeRoot;
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

        return new BtrfsFileSystemImpl(region, offset, superblock, chunkTree, fsTreeRoot);
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
        return new BtrfsDirectory("/", BtrfsKey.FIRST_FREE_OBJECTID);
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
        return walkEntry(entry.get(), 0, maxDepth);
    }

    private Stream<FileSystemEntry> walkEntry(FileSystemEntry entry, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return Stream.empty();
        }

        Stream<FileSystemEntry> self = Stream.of(entry);

        if (entry instanceof FileSystemEntry.Directory dir && depth < maxDepth) {
            try {
                Stream<FileSystemEntry> children = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(dir.list().iterator(), Spliterator.ORDERED),
                        false
                ).flatMap(child -> {
                    try {
                        return walkEntry(child, depth + 1, maxDepth);
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
        List<BtrfsTreeReader.SearchResult> results = treeReader.search(
                fsTreeRoot, objectId, BtrfsKey.INODE_ITEM);
        if (results.isEmpty()) {
            throw new IOException("Inode not found: " + objectId);
        }
        return BtrfsInode.parse(results.get(0).data());
    }

    private List<BtrfsDirectoryEntry> readDirEntries(long dirObjectId) throws IOException {
        Map<String, BtrfsDirectoryEntry> entriesByName = new LinkedHashMap<>();

        // Search DIR_INDEX first (primary, sorted by sequence)
        List<BtrfsTreeReader.SearchResult> indexResults = treeReader.search(
                fsTreeRoot, dirObjectId, BtrfsKey.DIR_INDEX);
        for (BtrfsTreeReader.SearchResult result : indexResults) {
            BtrfsDirectoryEntry entry = BtrfsDirectoryEntry.parse(result.data());
            if (!entry.name().equals(".") && !entry.name().equals("..")) {
                entriesByName.put(entry.name(), entry);
            }
        }

        // Also search DIR_ITEM as fallback for entries not in DIR_INDEX
        List<BtrfsTreeReader.SearchResult> itemResults = treeReader.search(
                fsTreeRoot, dirObjectId, BtrfsKey.DIR_ITEM);
        for (BtrfsTreeReader.SearchResult result : itemResults) {
            BtrfsDirectoryEntry entry = BtrfsDirectoryEntry.parse(result.data());
            if (!entry.name().equals(".") && !entry.name().equals("..")) {
                entriesByName.putIfAbsent(entry.name(), entry);
            }
        }

        return new ArrayList<>(entriesByName.values());
    }

    private byte[] readFileData(long objectId, long size) throws IOException {
        if (size > 256 * 1024 * 1024) {
            throw new IOException("File too large: " + size + " bytes (max 256MB)");
        }

        List<BtrfsTreeReader.SearchResult> extents = treeReader.search(
                fsTreeRoot, objectId, BtrfsKey.EXTENT_DATA);

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
        List<BtrfsTreeReader.SearchResult> extents = treeReader.search(
                fsTreeRoot, objectId, BtrfsKey.EXTENT_DATA);

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
        try {
            return com.github.luben.zstd.Zstd.decompress(compressed, uncompressedSize);
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
        private BtrfsInode inode;

        BtrfsDirectory(String path, long objectId) {
            this.path = path;
            this.objectId = objectId;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(objectId);
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
            List<BtrfsDirectoryEntry> entries = readDirEntries(objectId);
            List<FileSystemEntry> result = new ArrayList<>();

            for (BtrfsDirectoryEntry entry : entries) {
                String childPath = path.equals("/") ? "/" + entry.name() : path + "/" + entry.name();
                long childObjId = entry.targetObjectId();

                FileSystemEntry fsEntry = switch (entry.type()) {
                    case BtrfsDirectoryEntry.FT_DIR -> new BtrfsDirectory(childPath, childObjId);
                    case BtrfsDirectoryEntry.FT_REG_FILE -> new BtrfsRegularFile(childPath, childObjId);
                    case BtrfsDirectoryEntry.FT_SYMLINK -> new BtrfsSymlink(childPath, childObjId);
                    case BtrfsDirectoryEntry.FT_CHRDEV, BtrfsDirectoryEntry.FT_BLKDEV ->
                            new BtrfsSpecialFile(childPath, childObjId, entry.type());
                    default -> new BtrfsRegularFile(childPath, childObjId);  // Fallback
                };
                result.add(fsEntry);
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
        private BtrfsInode inode;

        BtrfsRegularFile(String path, long objectId) {
            this.path = path;
            this.objectId = objectId;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(objectId);
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
            byte[] data = readFileData(objectId, size());
            return new ByteArrayInputStream(data);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return readFileData(objectId, size());
        }
    }

    private class BtrfsSymlink implements FileSystemEntry.SymbolicLink {
        private final String path;
        private final long objectId;
        private final String target;
        private BtrfsInode inode;

        BtrfsSymlink(String path, long objectId) {
            this.path = path;
            this.objectId = objectId;
            // Read target eagerly since interface doesn't allow IOException
            String targetValue;
            try {
                targetValue = readSymlinkTarget(objectId);
            } catch (IOException e) {
                targetValue = "";
            }
            this.target = targetValue;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(objectId);
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
            String targetPath = target();
            if (targetPath.startsWith("/")) {
                return BtrfsFileSystemImpl.this.resolve(targetPath);
            }
            // Relative path
            String parentPath = path.substring(0, path.lastIndexOf('/'));
            if (parentPath.isEmpty()) parentPath = "/";
            return BtrfsFileSystemImpl.this.resolve(parentPath + "/" + targetPath);
        }
    }

    private class BtrfsSpecialFile implements FileSystemEntry.SpecialFile {
        private final String path;
        private final long objectId;
        private final int fileType;
        private BtrfsInode inode;

        BtrfsSpecialFile(String path, long objectId, int fileType) {
            this.path = path;
            this.objectId = objectId;
            this.fileType = fileType;
        }

        private BtrfsInode getInode() throws IOException {
            if (inode == null) {
                inode = readInode(objectId);
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
