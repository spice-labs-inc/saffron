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
package io.spicelabs.saffron.filesystem.ext4;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.ResourceLimitException;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.filesystem.ChunkedRegionStream;
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
 * Implementation of ext2/ext3/ext4 filesystem reading.
 *
 * <p>This class provides read-only access to ext filesystems contained
 * within virtual disk images.
 */
public class Ext4FileSystemImpl implements FileSystem.Ext4FileSystem {

    /** Maximum default walk depth (hostile trees must not overflow the stack). */
    private static final int MAX_WALK_DEPTH = 512;

    /** Maximum extent-tree depth (real ext4 extent trees are at most 5). */
    private static final int MAX_EXTENT_TREE_DEPTH = 64;
    private static final long ROOT_INODE = 2;
    private static final int MAX_SYMLINK_DEPTH = 40;

    private final DiskRegion region;
    private final Ext4Superblock superblock;
    private final Ext4BlockGroupDescriptor[] blockGroups;
    private final int inodeSize;
    private final int inodesPerGroup;
    private final int blocksPerGroup;
    private final boolean hasFileType;

    private Ext4FileSystemImpl(DiskRegion region, Ext4Superblock superblock,
                           Ext4BlockGroupDescriptor[] blockGroups, int inodeSize,
                           int inodesPerGroup, int blocksPerGroup, boolean hasFileType) {
        this.region = region;
        this.superblock = superblock;
        this.blockGroups = blockGroups;
        this.inodeSize = inodeSize;
        this.inodesPerGroup = inodesPerGroup;
        this.blocksPerGroup = blocksPerGroup;
        this.hasFileType = hasFileType;
    }

    /**
     * Opens an ext4 filesystem from a virtual disk.
     *
     * @param disk the virtual disk containing the filesystem
     * @param partitionOffset the byte offset where the partition/filesystem starts
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull Ext4FileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset) throws IOException {
        DiskRegion region = DiskRegion.fromPartition(disk, partitionOffset, 0);
        return mount(region);
    }

    /**
     * Opens an ext4 filesystem from a DiskRegion (supports LVM logical volumes).
     *
     * @param region the disk region containing the filesystem
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull Ext4FileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        return tryMount(region)
                .orElseThrow(() -> new IOException(
                        "Invalid or truncated ext4 filesystem (rejected during defensive validation)"));
    }

    /**
     * Attempts to mount an ext4 filesystem from a DiskRegion, returning
     * {@link Optional#empty()} instead of throwing when the region does not
     * contain a coherent, self-consistent ext4 filesystem.
     *
     * <p>This is the defensive entry point. Malformed, truncated, or
     * misdetected data (e.g. a WAR/ZIP that merely resembles ext4) never
     * propagates an exception to the caller; it is reported as "not
     * mountable". All filesystem invariants are validated eagerly so that a
     * subsequent {@link #root()} / {@link #walk()} cannot fail or crash.
     *
     * @param region the disk region containing the filesystem
     * @return the mounted filesystem, or empty if the data is not valid ext4
     */
    public static @NotNull Optional<Ext4FileSystemImpl> tryMount(@NotNull DiskRegion region) {
        return parseValidated(region);
    }

    /**
     * Attempts to mount an ext4 filesystem from a virtual disk at a partition
     * offset, returning {@link Optional#empty()} instead of throwing when the
     * region does not contain a coherent ext4 filesystem.
     *
     * @param disk the virtual disk containing the filesystem
     * @param partitionOffset the byte offset where the partition/filesystem starts
     * @return the mounted filesystem, or empty if the data is not valid ext4
     */
    public static @NotNull Optional<Ext4FileSystemImpl> tryMount(@NotNull VirtualDisk disk, long partitionOffset) {
        return tryMount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Parses and eagerly validates an ext4 filesystem from a region.
     *
     * <p>Every invariant needed for a safe {@link #root()} / {@link #walk()}
     * (superblock sanity, block-group descriptor table bounds, and addressability
     * of the root inode) is checked here. Any malformed or out-of-bounds
     * condition is folded into {@link Optional#empty()} so that no exception
     * escapes to the Saffron API caller for bad input.
     *
     * @param region the disk region containing the filesystem
     * @return the parsed filesystem, or empty if the data is not valid ext4
     */
    private static @NotNull Optional<Ext4FileSystemImpl> parseValidated(@NotNull DiskRegion region) {
        try {
            Ext4Superblock superblock = Ext4Superblock.read(region);

            // Read additional superblock fields we need
            ByteBuffer sb = region.read(Ext4Superblock.SUPERBLOCK_OFFSET, 1024);
            sb.order(ByteOrder.LITTLE_ENDIAN);

            int inodesPerGroup = sb.getInt(40);
            int blocksPerGroup = sb.getInt(32);
            int inodeSize = sb.getShort(88) & 0xFFFF;
            if (inodeSize == 0) {
                inodeSize = 128; // Default for ext2
            }

            int blockSize = superblock.blockSize();
            // Reject implausible block sizes (ext supports 1024..65536, power of two).
            if (blockSize < 1024 || blockSize > 65536 || (blockSize & (blockSize - 1)) != 0) {
                return Optional.empty();
            }
            if (inodesPerGroup <= 0 || blocksPerGroup <= 0) {
                return Optional.empty();
            }
            if (inodeSize < 128 || inodeSize > blockSize) {
                return Optional.empty();
            }

            // Check for file type feature (INCOMPAT_FILETYPE = 0x0002)
            boolean hasFileType = (superblock.incompatFeatures() & Ext4Superblock.INCOMPAT_FILETYPE) != 0;

            // Determine block group descriptor size
            boolean is64Bit = superblock.is64Bit();
            int descSize = Ext4BlockGroupDescriptor.descriptorSize(is64Bit);

            // Calculate number of block groups
            long numBlockGroups = (superblock.blockCount() + blocksPerGroup - 1) / blocksPerGroup;
            if (numBlockGroups <= 0 || numBlockGroups > Integer.MAX_VALUE) {
                return Optional.empty();
            }

            // Read block group descriptors (located immediately after superblock block)
            // Superblock is in block 0 (at offset 1024), descriptors start at block 1 (or same block if block size > 1024)
            long descTableOffset;
            if (blockSize == 1024) {
                descTableOffset = 2 * 1024L; // Block 2
            } else {
                descTableOffset = blockSize; // Block 1
            }

            // Guard: the descriptor table must fit entirely inside the region.
            long descTableBytes = numBlockGroups * (long) descSize;
            if (descTableOffset < 0 || descTableBytes < 0
                    || descTableOffset + descTableBytes > region.size()) {
                return Optional.empty();
            }

            Ext4BlockGroupDescriptor[] blockGroups = new Ext4BlockGroupDescriptor[(int) numBlockGroups];
            for (int i = 0; i < numBlockGroups; i++) {
                ByteBuffer descBuf = region.read(descTableOffset + ((long) i * descSize), descSize);
                blockGroups[i] = Ext4BlockGroupDescriptor.parse(descBuf, is64Bit);
            }

            // Eagerly validate that the root inode (2) is addressable so that a
            // subsequent root()/walk() cannot fail or NPE due to a truncated
            // descriptor/inode table. This is the crash originally observed when a
            // WAR was misdetected as ext4.
            long rootInodeIndex = ROOT_INODE - 1;
            int rootBg = (int) (rootInodeIndex / inodesPerGroup);
            int rootIndexInGroup = (int) (rootInodeIndex % inodesPerGroup);
            if (rootBg >= blockGroups.length) {
                return Optional.empty();
            }
            Ext4BlockGroupDescriptor rootBgDesc = blockGroups[rootBg];
            if (rootBgDesc == null) {
                return Optional.empty();
            }
            long rootInodeOffset = rootBgDesc.inodeTable() * (long) blockSize
                    + (long) rootIndexInGroup * inodeSize;
            if (rootInodeOffset < 0 || rootInodeOffset + inodeSize > region.size()) {
                return Optional.empty();
            }

            return Optional.of(new Ext4FileSystemImpl(region, superblock, blockGroups,
                    inodeSize, inodesPerGroup, blocksPerGroup, hasFileType));
        } catch (IOException | RuntimeException e) {
            // Malformed/truncated data must not propagate to the caller; report
            // the filesystem as not mountable instead of crashing.
            return Optional.empty();
        }
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        Ext4Inode rootInode = readInode(ROOT_INODE);
        return new Ext4Directory(rootInode, "/", "/", this);
    }

    @Override
    public @NotNull Optional<FileSystemEntry> resolve(@NotNull String path) throws IOException {
        return resolve(path, 40); // Max 40 symlink hops
    }

    /**
     * Resolves a path, following symbolic links up to maxSymlinkHops.
     */
    private @NotNull Optional<FileSystemEntry> resolve(@NotNull String path, int maxSymlinkHops) throws IOException {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be absolute: " + path);
        }

        if (path.equals("/")) {
            return Optional.of(root());
        }

        String[] parts = path.substring(1).split("/");
        FileSystemEntry current = root();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            if (!(current instanceof FileSystemEntry.Directory dir)) {
                return Optional.empty();
            }

            Optional<FileSystemEntry> next = dir.find(part);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();

            // If this is a symlink, resolve it
            if (current instanceof FileSystemEntry.SymbolicLink symlink) {
                if (maxSymlinkHops <= 0) {
                    // Symlink depth exceeded: skipped (documented behavior).
                    return Optional.empty(); // Too many symlink hops
                }

                String target = symlink.target();
                String remainingPath = String.join("/", Arrays.copyOfRange(parts, i + 1, parts.length));

                String resolvedTarget;
                if (target.startsWith("/")) {
                    // Absolute symlink target
                    resolvedTarget = target;
                } else {
                    // Relative symlink target - resolve relative to current directory
                    String currentDir = dir.path();
                    if (!currentDir.endsWith("/")) {
                        currentDir = currentDir + "/";
                    }
                    resolvedTarget = currentDir + target;
                }

                // Append remaining path components
                if (!remainingPath.isEmpty()) {
                    resolvedTarget = resolvedTarget + "/" + remainingPath;
                }

                // Normalize the path (remove . and ..)
                resolvedTarget = normalizePath(resolvedTarget);

                // Recursively resolve the symlink target
                return resolve(resolvedTarget, maxSymlinkHops - 1);
            }
        }

        return Optional.of(current);
    }

    /**
     * Normalizes a path by removing . and .. components.
     */
    private String normalizePath(String path) {
        if (path.equals("/")) {
            return "/";
        }

        String[] parts = path.split("/");
        List<String> result = new ArrayList<>();

        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!result.isEmpty()) {
                    result.remove(result.size() - 1);
                }
            } else {
                result.add(part);
            }
        }

        return "/" + String.join("/", result);
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk() throws IOException {
        return walkDirectory(root(), MAX_WALK_DEPTH, new HashSet<>());
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth) throws IOException {
        Optional<FileSystemEntry> entry = resolve(path);
        if (entry.isEmpty()) {
            return Stream.empty();
        }

        if (entry.get() instanceof FileSystemEntry.Directory dir) {
            return walkDirectory(dir, maxDepth, new HashSet<>());
        } else {
            return Stream.of(entry.get());
        }
    }

    private Stream<FileSystemEntry> walkDirectory(FileSystemEntry.Directory dir, int maxDepth,
                                                    Set<Long> visitedDirs) throws IOException {
        if (maxDepth <= 0) {
            return Stream.of(dir);
        }

        // Cycle detection: skip directories we've already visited
        if (dir instanceof Ext4Directory ext4Dir) {
            if (!visitedDirs.add(ext4Dir.inode.inodeNumber())) {
                return Stream.of(dir);
            }
        }

        List<FileSystemEntry> result = new ArrayList<>();
        result.add(dir);

        try (Stream<FileSystemEntry> children = dir.list()) {
            children.forEach(entry -> {
                try {
                    if (entry instanceof FileSystemEntry.Directory subDir) {
                        walkDirectory(subDir, maxDepth - 1, visitedDirs).forEach(result::add);
                    } else {
                        result.add(entry);
                    }
                } catch (IOException e) {
                    // Skip entries that can't be read
                }
            });
        }

        return result.stream();
    }

    @Override
    public long totalSize() {
        return superblock.totalSizeBytes();
    }

    @Override
    public long usedSize() {
        return superblock.usedSizeBytes();
    }

    @Override
    public long freeSize() {
        return superblock.freeSizeBytes();
    }

    @Override
    public @NotNull Optional<String> label() {
        return Optional.ofNullable(superblock.volumeName());
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.of(superblock.uuid());
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("version", superblock.extVersion());
        meta.put("blockSize", String.valueOf(superblock.blockSize()));
        meta.put("inodeCount", String.valueOf(superblock.inodeCount()));
        meta.put("blockCount", String.valueOf(superblock.blockCount()));
        meta.put("freeBlocks", String.valueOf(superblock.freeBlockCount()));
        meta.put("freeInodes", String.valueOf(superblock.freeInodeCount()));
        if (superblock.lastMounted() != null) {
            meta.put("lastMounted", superblock.lastMounted());
        }
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public @NotNull Set<String> features() {
        Set<String> features = new HashSet<>();
        features.addAll(superblock.compatFeatureNames());
        features.addAll(superblock.incompatFeatureNames());
        return features;
    }

    @Override
    public int blockSize() {
        return superblock.blockSize();
    }

    @Override
    public long inodeCount() {
        return superblock.inodeCount();
    }

    @Override
    public void close() {
        // Nothing to close; the disk is managed externally
    }

    // ========================================================================
    // Internal methods
    // ========================================================================

    /**
     * Reads an inode by number.
     */
    Ext4Inode readInode(long inodeNumber) throws IOException {
        // Inode numbers start at 1
        long inodeIndex = inodeNumber - 1;
        int blockGroup = (int) (inodeIndex / inodesPerGroup);
        int indexInGroup = (int) (inodeIndex % inodesPerGroup);

        if (blockGroup < 0 || blockGroup >= blockGroups.length) {
            throw new IOException("Invalid inode number: " + inodeNumber);
        }

        Ext4BlockGroupDescriptor bg = blockGroups[blockGroup];
        if (bg == null) {
            throw new IOException("Missing block group descriptor for inode: " + inodeNumber);
        }

        long inodeTableBlock = bg.inodeTable();
        long inodeOffset = (inodeTableBlock * (long) superblock.blockSize())
                + ((long) indexInGroup * inodeSize);
        if (inodeOffset < 0 || inodeOffset + inodeSize > region.size()) {
            throw new IOException("Inode " + inodeNumber + " table offset out of bounds: " + inodeOffset);
        }

        ByteBuffer inodeBuf = region.read(inodeOffset, inodeSize);
        return Ext4Inode.parse(inodeBuf, inodeSize, inodeNumber);
    }

    /** Maximum file size that can be read into memory (256 MB) */
    private static final long MAX_READABLE_SIZE = 16 * 1024 * 1024;

    /**
     * Reads all data from an inode.
     */
    byte[] readInodeData(Ext4Inode inode) throws IOException {
        if (inode.size() == 0) {
            return new byte[0];
        }

        // For symlinks, check if data is inline
        if (inode.isSymbolicLink() && inode.size() <= 60) {
            byte[] data = new byte[(int) inode.size()];
            System.arraycopy(inode.blockData(), 0, data, 0, (int) inode.size());
            return data;
        }

        if (inode.usesExtents()) {
            return readExtentData(inode);
        } else {
            return readIndirectBlockData(inode);
        }
    }

    /**
     * Reads data using extent tree.
     */
    private byte[] readExtentData(Ext4Inode inode) throws IOException {
        byte[] result = new byte[(int) inode.size()];
        int blockSize = superblock.blockSize();

        List<Ext4Extent.Leaf> extents = collectAllExtents(inode.blockData());

        for (Ext4Extent.Leaf extent : extents) {
            int destOffset = (int) (extent.logicalBlock() * blockSize);
            if (destOffset >= result.length) break;

            int bytesToRead = Math.min(extent.length() * blockSize,
                    result.length - destOffset);

            // Uninitialized extents are allocated but not yet written — read as zeros
            if (!extent.uninitialized()) {
                long physicalOffset = extent.physicalBlock() * blockSize;
                ByteBuffer data = region.read(physicalOffset, bytesToRead);
                data.get(result, destOffset, bytesToRead);
            }
        }

        return result;
    }

    /**
     * Recursively collects all leaf extents.
     */
    private List<Ext4Extent.Leaf> collectAllExtents(byte[] extentData) throws IOException {
        return walkExtentTree(extentData, 0, new HashSet<>(),
                blockNumber -> {
                    ByteBuffer childData = region.read(blockNumber * superblock.blockSize(),
                            superblock.blockSize());
                    byte[] childBytes = new byte[superblock.blockSize()];
                    childData.get(childBytes);
                    return childBytes;
                });
    }

    /** Reader for extent-tree child blocks (seam for cycle tests). */
    @FunctionalInterface
    interface ExtentBlockReader {
        byte[] read(long blockOffset) throws IOException;
    }

    /**
     * Depth-capped, cycle-guarded extent-tree walk (shared by the instance
     * path and the hostile-cycle tests).
     */
    static List<Ext4Extent.Leaf> walkExtentTree(byte[] extentData, int depth,
                                                Set<Long> visitedBlocks,
                                                ExtentBlockReader reader) throws IOException {

        if (depth > MAX_EXTENT_TREE_DEPTH) {
            throw new IOException("ext4 extent tree too deep");
        }
        Ext4Extent.Header header = Ext4Extent.parseHeader(extentData);
        if (!header.isValid()) {
            return List.of();
        }

        if (header.isLeaf()) {
            return Ext4Extent.parseLeafExtents(extentData);
        } else {
            // Internal node - follow index entries
            List<Ext4Extent.Leaf> allLeaves = new ArrayList<>();
            List<Ext4Extent.Index> indices = Ext4Extent.parseIndexExtents(extentData);

            for (Ext4Extent.Index index : indices) {
                if (!visitedBlocks.add(index.leafBlock())) {
                    throw new IOException("ext4 extent tree cycle at block "
                            + index.leafBlock());
                }
                byte[] childBytes = reader.read(index.leafBlock());
                allLeaves.addAll(walkExtentTree(childBytes, depth + 1, visitedBlocks, reader));
            }

            return allLeaves;
        }
    }

    /**
     * Reads data using indirect blocks (legacy ext2/3 style).
     */
    private byte[] readIndirectBlockData(Ext4Inode inode) throws IOException {
        byte[] result = new byte[(int) inode.size()];
        int bytesRead = 0;
        int blockSize = superblock.blockSize();
        ByteBuffer blockBuf = ByteBuffer.wrap(inode.blockData());
        blockBuf.order(ByteOrder.LITTLE_ENDIAN);

        // Direct blocks (0-11)
        for (int i = 0; i < 12 && bytesRead < result.length; i++) {
            long blockNum = blockBuf.getInt(i * 4) & 0xFFFFFFFFL;
            if (blockNum == 0) continue;

            int toRead = Math.min(blockSize, result.length - bytesRead);
            ByteBuffer data = region.read(blockNum * blockSize, toRead);
            data.get(result, bytesRead, toRead);
            bytesRead += toRead;
        }

        // Indirect block (12)
        if (bytesRead < result.length) {
            long indirectBlock = blockBuf.getInt(48) & 0xFFFFFFFFL;
            if (indirectBlock != 0) {
                bytesRead = readIndirectBlocks(indirectBlock, result, bytesRead, 1);
            }
        }

        // Double indirect block (13)
        if (bytesRead < result.length) {
            long doubleIndirectBlock = blockBuf.getInt(52) & 0xFFFFFFFFL;
            if (doubleIndirectBlock != 0) {
                bytesRead = readIndirectBlocks(doubleIndirectBlock, result, bytesRead, 2);
            }
        }

        // Triple indirect block (14)
        if (bytesRead < result.length) {
            long tripleIndirectBlock = blockBuf.getInt(56) & 0xFFFFFFFFL;
            if (tripleIndirectBlock != 0) {
                bytesRead = readIndirectBlocks(tripleIndirectBlock, result, bytesRead, 3);
            }
        }

        return result;
    }

    /**
     * Opens a lazy stream over an indirect-block (legacy ext2/3) file:
     * the block list is metadata-only; data streams in bounded chunks.
     */
    InputStream openIndirectStream(Ext4Inode inode) throws IOException {
        int blockSize = superblock.blockSize();
        List<Long> blocks = new ArrayList<>();
        ByteBuffer blockBuf = ByteBuffer.wrap(inode.blockData());
        blockBuf.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 12; i++) {
            long blockNum = blockBuf.getInt(i * 4) & 0xFFFFFFFFL;
            if (blockNum != 0) {
                blocks.add(blockNum);
            }
        }
        collectIndirectBlocks(blockBuf.getInt(48) & 0xFFFFFFFFL, blocks, 1);
        collectIndirectBlocks(blockBuf.getInt(52) & 0xFFFFFFFFL, blocks, 2);
        collectIndirectBlocks(blockBuf.getInt(56) & 0xFFFFFFFFL, blocks, 3);

        List<ChunkedRegionStream.Segment> segments = new ArrayList<>();
        long remaining = inode.size();
        long logical = 0;
        for (long blockNum : blocks) {
            if (remaining <= 0) {
                break;
            }
            long len = Math.min(blockSize, remaining);
            segments.add(new ChunkedRegionStream.Segment(logical, blockNum * blockSize, len));
            logical += len;
            remaining -= len;
        }
        return new ChunkedRegionStream(region, segments, inode.size());
    }

    private void collectIndirectBlocks(long blockNum, List<Long> blocks, int level)
            throws IOException {
        if (blockNum == 0 || level <= 0) {
            return;
        }
        int blockSize = superblock.blockSize();
        int ptrsPerBlock = blockSize / 4;
        ByteBuffer indirectData = region.read(blockNum * blockSize, blockSize);
        indirectData.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ptrsPerBlock; i++) {
            long ptr = indirectData.getInt(i * 4) & 0xFFFFFFFFL;
            if (ptr == 0) {
                continue;
            }
            if (level == 1) {
                blocks.add(ptr);
            } else {
                collectIndirectBlocks(ptr, blocks, level - 1);
            }
        }
    }

    private int readIndirectBlocks(long blockNum, byte[] result, int bytesRead, int level) throws IOException {
        int blockSize = superblock.blockSize();
        int ptrsPerBlock = blockSize / 4;

        ByteBuffer indirectData = region.read(blockNum * blockSize, blockSize);
        indirectData.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < ptrsPerBlock && bytesRead < result.length; i++) {
            long ptr = indirectData.getInt(i * 4) & 0xFFFFFFFFL;
            if (ptr == 0) continue;

            if (level == 1) {
                int toRead = Math.min(blockSize, result.length - bytesRead);
                ByteBuffer data = region.read(ptr * blockSize, toRead);
                data.get(result, bytesRead, toRead);
                bytesRead += toRead;
            } else {
                bytesRead = readIndirectBlocks(ptr, result, bytesRead, level - 1);
            }
        }

        return bytesRead;
    }

    /**
     * Reads directory entries from a directory inode.
     */
    List<Ext4DirectoryEntry> readDirectoryEntries(Ext4Inode dirInode) throws IOException {
        // R5.2: directory reads get the same 16 MiB cap as files - a
        // hostile directory inode size must not materialize unboundedly.
        if (dirInode.size() > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("ext4 directory too large to read: "
                    + dirInode.size() + " bytes (limit: 16 MB).",
                    "allocation_size", MAX_READABLE_SIZE, dirInode.size());
        }
        byte[] dirData = readInodeData(dirInode);
        return Ext4DirectoryEntry.parseBlock(dirData, hasFileType);
    }

    // ========================================================================
    // FileSystemEntry implementations
    // ========================================================================

    /**
     * Ext4 directory implementation.
     */
    private class Ext4Directory implements FileSystemEntry.Directory {
        private final Ext4Inode inode;
        private final String name;
        private final String path;
        private final Ext4FileSystemImpl fs;

        Ext4Directory(Ext4Inode inode, String name, String path, Ext4FileSystemImpl fs) {
            this.inode = inode;
            this.name = name;
            this.path = path;
            this.fs = fs;
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
        public long size() {
            return inode.size();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return inode.optionalCreationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(inode.modificationTime());
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.of(inode.accessTime());
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode());
            attrs.put("uid", inode.uid());
            attrs.put("gid", inode.gid());
            attrs.put("links", inode.linksCount());
            attrs.put("inode", inode.inodeNumber());
            return attrs;
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<Ext4DirectoryEntry> entries = fs.readDirectoryEntries(inode);
            List<FileSystemEntry> result = new ArrayList<>();

            for (Ext4DirectoryEntry entry : entries) {
                // Skip . and ..
                if (entry.isDot() || entry.isDotDot()) continue;

                String childPath = path.equals("/") ? "/" + entry.name() : path + "/" + entry.name();
                FileSystemEntry fsEntry = createEntry(entry.inode(), entry.name(), childPath);
                if (fsEntry != null) {
                    result.add(fsEntry);
                }
            }

            return result.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            List<Ext4DirectoryEntry> entries = fs.readDirectoryEntries(inode);

            for (Ext4DirectoryEntry entry : entries) {
                if (entry.name().equals(name)) {
                    String childPath = path.equals("/") ? "/" + name : path + "/" + name;
                    return Optional.ofNullable(createEntry(entry.inode(), name, childPath));
                }
            }

            return Optional.empty();
        }

        private FileSystemEntry createEntry(long inodeNum, String name, String path) throws IOException {
            Ext4Inode childInode = fs.readInode(inodeNum);

            if (childInode.isDirectory()) {
                return new Ext4Directory(childInode, name, path, fs);
            } else if (childInode.isRegularFile()) {
                return new Ext4RegularFile(childInode, name, path, fs);
            } else if (childInode.isSymbolicLink()) {
                return new Ext4SymbolicLink(childInode, name, path, fs);
            } else {
                return new Ext4SpecialFile(childInode, name, path);
            }
        }
    }

    /**
     * Ext4 regular file implementation.
     */
    private class Ext4RegularFile implements FileSystemEntry.RegularFile {
        private final Ext4Inode inode;
        private final String name;
        private final String path;
        private final Ext4FileSystemImpl fs;

        Ext4RegularFile(Ext4Inode inode, String name, String path, Ext4FileSystemImpl fs) {
            this.inode = inode;
            this.name = name;
            this.path = path;
            this.fs = fs;
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
        public long size() {
            return inode.size();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return inode.optionalCreationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(inode.modificationTime());
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.of(inode.accessTime());
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode());
            attrs.put("uid", inode.uid());
            attrs.put("gid", inode.gid());
            attrs.put("links", inode.linksCount());
            attrs.put("inode", inode.inodeNumber());
            return attrs;
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            if (inode.size() == 0) {
                return new ByteArrayInputStream(new byte[0]);
            }
            if (inode.usesExtents()) {
                List<Ext4Extent.Leaf> extents = fs.collectAllExtents(inode.blockData());
                return new Ext4ExtentInputStream(fs.region, extents, inode.size(), fs.superblock.blockSize());
            }
            // Indirect-block files: lazy stream over the block list
            // (bounded reads; sparse gaps yield zeros).
            return fs.openIndirectStream(inode);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            if (inode.size() > MAX_READABLE_SIZE) {
                throw new ResourceLimitException(
                        "File too large to read into memory: " + inode.size()
                                + " bytes (limit: 16 MB). Use openStream() for large files.",
                        "allocation_size", MAX_READABLE_SIZE, inode.size());
            }
            return fs.readInodeData(inode);
        }
    }

    /**
     * InputStream that reads extent-by-extent without loading the entire file into memory.
     */
    private static class Ext4ExtentInputStream extends InputStream {
        private final DiskRegion region;
        private final List<Ext4Extent.Leaf> extents;
        private final long fileSize;
        private final int blockSize;
        private long bytesRead;
        private int currentExtentIndex;
        private int offsetInCurrentExtent;

        Ext4ExtentInputStream(DiskRegion region, List<Ext4Extent.Leaf> extents,
                              long fileSize, int blockSize) {
            this.region = region;
            this.extents = extents;
            this.fileSize = fileSize;
            this.blockSize = blockSize;
            this.bytesRead = 0;
            this.currentExtentIndex = 0;
            this.offsetInCurrentExtent = 0;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (bytesRead >= fileSize) {
                return -1;
            }

            int totalRead = 0;
            while (totalRead < len && bytesRead < fileSize) {
                if (currentExtentIndex >= extents.size()) {
                    // No more extents — remaining bytes are sparse (zeros)
                    int remaining = (int) Math.min(len - totalRead, fileSize - bytesRead);
                    java.util.Arrays.fill(buf, off + totalRead, off + totalRead + remaining, (byte) 0);
                    totalRead += remaining;
                    bytesRead += remaining;
                    break;
                }

                Ext4Extent.Leaf extent = extents.get(currentExtentIndex);
                long extentStartByte = (long) extent.logicalBlock() * blockSize;
                int extentSize = extent.length() * blockSize;

                // Handle sparse gap before this extent
                if (bytesRead < extentStartByte) {
                    int gapSize = (int) Math.min(extentStartByte - bytesRead,
                            Math.min(len - totalRead, fileSize - bytesRead));
                    java.util.Arrays.fill(buf, off + totalRead, off + totalRead + gapSize, (byte) 0);
                    totalRead += gapSize;
                    bytesRead += gapSize;
                    continue;
                }

                // Read from current extent
                int posInExtent = (int) (bytesRead - extentStartByte);
                int availInExtent = extentSize - posInExtent;
                int toRead = (int) Math.min(availInExtent,
                        Math.min(len - totalRead, fileSize - bytesRead));

                if (toRead <= 0) {
                    currentExtentIndex++;
                    continue;
                }

                if (extent.uninitialized()) {
                    java.util.Arrays.fill(buf, off + totalRead, off + totalRead + toRead, (byte) 0);
                } else {
                    long physicalOffset = (long) extent.physicalBlock() * blockSize + posInExtent;
                    ByteBuffer data = region.read(physicalOffset, toRead);
                    data.get(buf, off + totalRead, toRead);
                }

                totalRead += toRead;
                bytesRead += toRead;

                if (posInExtent + toRead >= extentSize) {
                    currentExtentIndex++;
                }
            }

            return totalRead == 0 ? -1 : totalRead;
        }
    }

    /**
     * Ext4 symbolic link implementation.
     */
    private class Ext4SymbolicLink implements FileSystemEntry.SymbolicLink {
        private final Ext4Inode inode;
        private final String name;
        private final String path;
        private final Ext4FileSystemImpl fs;

        Ext4SymbolicLink(Ext4Inode inode, String name, String path, Ext4FileSystemImpl fs) {
            this.inode = inode;
            this.name = name;
            this.path = path;
            this.fs = fs;
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
        public long size() {
            return inode.size();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return inode.optionalCreationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(inode.modificationTime());
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.of(inode.accessTime());
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode());
            attrs.put("uid", inode.uid());
            attrs.put("gid", inode.gid());
            attrs.put("inode", inode.inodeNumber());
            return attrs;
        }

        @Override
        public @NotNull String target() {
            Optional<String> inline = inode.inlineSymlinkTarget();
            if (inline.isPresent()) {
                return inline.get();
            }
            // Read target from data blocks
            try {
                byte[] data = fs.readInodeData(inode);
                return new String(data);
            } catch (IOException e) {
                return "";
            }
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
                // Relative path - resolve from parent directory
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                if (parentPath.isEmpty()) parentPath = "/";
                resolvedPath = parentPath + "/" + targetPath;
            }
            Optional<FileSystemEntry> result = fs.resolve(resolvedPath);
            if (result.isPresent() && result.get() instanceof Ext4SymbolicLink nestedLink) {
                return nestedLink.resolveWithDepth(remaining - 1);
            }
            return result;
        }
    }

    /**
     * Ext4 special file implementation.
     */
    private static class Ext4SpecialFile implements FileSystemEntry.SpecialFile {
        private final Ext4Inode inode;
        private final String name;
        private final String path;

        Ext4SpecialFile(Ext4Inode inode, String name, String path) {
            this.inode = inode;
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
        public long size() {
            return 0;
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return inode.optionalCreationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(inode.modificationTime());
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.of(inode.accessTime());
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode());
            attrs.put("uid", inode.uid());
            attrs.put("gid", inode.gid());
            attrs.put("inode", inode.inodeNumber());
            return attrs;
        }

        @Override
        public @NotNull EntryType type() {
            if (inode.isBlockDevice()) return EntryType.BLOCK_DEVICE;
            if (inode.isCharacterDevice()) return EntryType.CHARACTER_DEVICE;
            if (inode.isFifo()) return EntryType.FIFO;
            if (inode.isSocket()) return EntryType.SOCKET;
            return EntryType.UNKNOWN;
        }

        @Override
        public @NotNull Optional<Integer> majorDevice() {
            if (inode.isBlockDevice() || inode.isCharacterDevice()) {
                // Device numbers stored in i_block[0] and i_block[1]
                ByteBuffer bb = ByteBuffer.wrap(inode.blockData());
                bb.order(ByteOrder.LITTLE_ENDIAN);
                int dev = bb.getInt(0);
                return Optional.of((dev >> 8) & 0xFF);
            }
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            if (inode.isBlockDevice() || inode.isCharacterDevice()) {
                ByteBuffer bb = ByteBuffer.wrap(inode.blockData());
                bb.order(ByteOrder.LITTLE_ENDIAN);
                int dev = bb.getInt(0);
                return Optional.of(dev & 0xFF);
            }
            return Optional.empty();
        }
    }
}
