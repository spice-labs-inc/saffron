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
package io.spicelabs.saffron.filesystem.xfs;

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

/**
 * Implementation of XFS filesystem reading.
 *
 * <p>This class provides read-only access to XFS filesystems contained
 * within virtual disk images.
 *
 * <p>XFS key concepts:
 * <ul>
 *   <li>Allocation Groups (AG): Filesystem is divided into AGs for parallelism</li>
 *   <li>Inodes: Stored within AGs, inode number encodes AG + offset</li>
 *   <li>Extents: File data stored as extent lists or B+trees</li>
 *   <li>Directories: Shortform, block, leaf, or node format based on size</li>
 * </ul>
 */
public class XfsFileSystemImpl implements FileSystem.XfsFileSystem {

    private final DiskRegion region;
    private final XfsSuperblock superblock;
    private final int blockSize;
    private final int inodeSize;
    private final int agBlockLog;  // log2(blocks per AG)
    private final int inodePerBlockLog; // log2(inodes per block)
    private final long agBlocks;
    private final boolean isV5;

    private XfsFileSystemImpl(DiskRegion region, XfsSuperblock superblock,
                              int agBlockLog, int inodePerBlockLog) {
        this.region = region;
        this.superblock = superblock;
        this.blockSize = superblock.blockSize();
        this.inodeSize = superblock.inodeSize();
        this.agBlockLog = agBlockLog;
        this.inodePerBlockLog = inodePerBlockLog;
        this.agBlocks = superblock.blocksPerAg();
        this.isV5 = superblock.isV5();
    }

    /**
     * Opens an XFS filesystem from a virtual disk.
     *
     * @param disk the virtual disk containing the filesystem
     * @param partitionOffset the byte offset where the partition/filesystem starts
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull XfsFileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset) throws IOException {
        DiskRegion region = DiskRegion.fromPartition(disk, partitionOffset, 0);
        return mount(region);
    }

    /**
     * Opens an XFS filesystem from a DiskRegion (supports LVM logical volumes).
     *
     * @param region the disk region containing the filesystem
     * @return the filesystem instance
     * @throws IOException if an I/O error occurs or filesystem is invalid
     */
    public static @NotNull XfsFileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        // Read superblock
        XfsSuperblock superblock = XfsSuperblock.read(region);

        // Read additional fields from superblock
        ByteBuffer sb = region.read(0, XfsSuperblock.SUPERBLOCK_SIZE);
        sb.order(ByteOrder.BIG_ENDIAN);

        // Get log values for calculations
        int blockLog = sb.get(120) & 0xFF;      // sb_blocklog
        int inodeLog = sb.get(122) & 0xFF;      // sb_inodelog
        int inopbLog = sb.get(123) & 0xFF;      // sb_inopblog (inodes per block log)
        int agBlockLog = sb.get(124) & 0xFF;    // sb_agblklog

        return new XfsFileSystemImpl(region, superblock, agBlockLog, inopbLog);
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        XfsInode rootInode = readInode(superblock.rootInode());
        return new XfsDirectory(rootInode, "/", "/", this);
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
        return walkDirectory(root(), Integer.MAX_VALUE);
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth) throws IOException {
        Optional<FileSystemEntry> entry = resolve(path);
        if (entry.isEmpty()) {
            return Stream.empty();
        }

        if (entry.get() instanceof FileSystemEntry.Directory dir) {
            return walkDirectory(dir, maxDepth);
        } else {
            return Stream.of(entry.get());
        }
    }

    private Stream<FileSystemEntry> walkDirectory(FileSystemEntry.Directory dir, int maxDepth) throws IOException {
        if (maxDepth <= 0) {
            return Stream.of(dir);
        }

        List<FileSystemEntry> result = new ArrayList<>();
        result.add(dir);

        try (Stream<FileSystemEntry> children = dir.list()) {
            children.forEach(entry -> {
                try {
                    if (entry instanceof FileSystemEntry.Directory subDir) {
                        walkDirectory(subDir, maxDepth - 1).forEach(result::add);
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
        // Approximate: total - (free blocks * block size)
        return totalSize() - freeSize();
    }

    @Override
    public long freeSize() {
        return superblock.freeBlockCount() * blockSize;
    }

    @Override
    public @NotNull Optional<String> label() {
        String label = superblock.volumeLabel();
        return label != null && !label.isEmpty() ? Optional.of(label) : Optional.empty();
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.of(superblock.uuid());
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("version", superblock.version());
        meta.put("blockSize", String.valueOf(blockSize));
        meta.put("inodeSize", String.valueOf(inodeSize));
        meta.put("agCount", String.valueOf(superblock.agCount()));
        meta.put("blocksPerAg", String.valueOf(superblock.blocksPerAg()));
        meta.put("totalBlocks", String.valueOf(superblock.totalBlocks()));
        meta.put("features", superblock.features().toString());
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public int blockSize() {
        return blockSize;
    }

    @Override
    public int sectorSize() {
        return superblock.sectorSize();
    }

    @Override
    public int agCount() {
        return superblock.agCount();
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
     *
     * <p>XFS inode numbers encode: AG number in high bits, inode offset in low bits.
     * The byte offset to an inode is:
     * ag_start_byte + (inode_block * block_size) + (inode_offset_in_block * inode_size)
     */
    XfsInode readInode(long inodeNumber) throws IOException {
        // Decode inode number into AG and offset
        // AG number is in the high bits based on agBlockLog + inodePerBlockLog
        int agNoShift = agBlockLog + inodePerBlockLog;
        int agNo = (int) (inodeNumber >> agNoShift);
        long inodeOffsetInAg = inodeNumber & ((1L << agNoShift) - 1);

        // Calculate which block within the AG contains this inode
        int inodesPerBlock = 1 << inodePerBlockLog;
        long inodeBlock = inodeOffsetInAg / inodesPerBlock;
        int inodeIndexInBlock = (int) (inodeOffsetInAg % inodesPerBlock);

        // Calculate byte offset
        long agStart = (long) agNo * agBlocks * blockSize;
        long inodeByteOffset = agStart + (inodeBlock * blockSize) + (inodeIndexInBlock * inodeSize);

        ByteBuffer inodeBuf = region.read(inodeByteOffset, inodeSize);
        return XfsInode.parse(inodeBuf, inodeSize, inodeNumber);
    }

    /**
     * Reads a filesystem block.
     */
    ByteBuffer readBlock(long blockNumber) throws IOException {
        return region.read(blockNumber * blockSize, blockSize);
    }

    /** Maximum file size that can be read into memory (256 MB) */
    private static final long MAX_READABLE_SIZE = 256 * 1024 * 1024;

    /**
     * Reads all data from an inode.
     */
    byte[] readInodeData(XfsInode inode) throws IOException {
        if (inode.size() == 0) {
            return new byte[0];
        }

        if (inode.size() > MAX_READABLE_SIZE) {
            throw new IOException("File too large to read into memory: " + inode.size() +
                    " bytes (max " + MAX_READABLE_SIZE + " bytes)");
        }

        // For symlinks with inline data
        if (inode.isSymbolicLink() && inode.hasInlineData()) {
            byte[] data = new byte[(int) inode.size()];
            System.arraycopy(inode.dataFork(), 0, data, 0, (int) inode.size());
            return data;
        }

        if (inode.hasInlineData()) {
            // Local format - data is inline in the data fork
            byte[] data = new byte[(int) inode.size()];
            System.arraycopy(inode.dataFork(), 0, data, 0, (int) Math.min(inode.size(), inode.dataFork().length));
            return data;
        } else if (inode.hasExtents()) {
            return readExtentData(inode);
        } else if (inode.hasBtree()) {
            return readBtreeData(inode);
        }

        return new byte[0];
    }

    /**
     * Reads data using extent list.
     */
    private byte[] readExtentData(XfsInode inode) throws IOException {
        byte[] result = new byte[(int) inode.size()];
        List<XfsExtent> extents = XfsExtent.parseExtents(inode.dataFork(), inode.extentCount());

        int bytesRead = 0;
        for (XfsExtent extent : extents) {
            if (bytesRead >= result.length) break;
            if (extent.blockCount() == 0) continue;

            long physicalOffset = extent.physicalBlock() * blockSize;
            int bytesToRead = Math.min(extent.blockCount() * blockSize, result.length - bytesRead);

            ByteBuffer data = region.read(physicalOffset, bytesToRead);
            data.get(result, bytesRead, bytesToRead);
            bytesRead += bytesToRead;
        }

        return result;
    }

    /**
     * Reads data using B+tree extent mapping.
     */
    private byte[] readBtreeData(XfsInode inode) throws IOException {
        byte[] result = new byte[(int) inode.size()];
        List<XfsExtent> allExtents = collectBtreeExtents(inode);

        int bytesRead = 0;
        for (XfsExtent extent : allExtents) {
            if (bytesRead >= result.length) break;
            if (extent.blockCount() == 0) continue;

            long physicalOffset = extent.physicalBlock() * blockSize;
            int bytesToRead = Math.min(extent.blockCount() * blockSize, result.length - bytesRead);

            ByteBuffer data = region.read(physicalOffset, bytesToRead);
            data.get(result, bytesRead, bytesToRead);
            bytesRead += bytesToRead;
        }

        return result;
    }

    /**
     * Collects all extents from a B+tree.
     */
    private List<XfsExtent> collectBtreeExtents(XfsInode inode) throws IOException {
        XfsExtent.BtreeRoot root = XfsExtent.parseBtreeRoot(inode.dataFork());
        List<XfsExtent> allExtents = new ArrayList<>();

        if (root.level() == 0) {
            // Leaf level - extents are in the pointers (interpreted as extent records)
            // This shouldn't happen for BMBT but handle it anyway
            return allExtents;
        }

        // Traverse the tree
        collectBtreeExtentsRecursive(root.pointers(), root.level(), allExtents);
        return allExtents;
    }

    private void collectBtreeExtentsRecursive(List<Long> blockPtrs, int level, List<XfsExtent> extents) throws IOException {
        for (long blockNum : blockPtrs) {
            if (blockNum == 0 || blockNum == -1L) continue;

            ByteBuffer block = readBlock(blockNum);
            byte[] blockBytes = new byte[blockSize];
            block.get(blockBytes);

            XfsExtent.BtreeBlockHeader header = XfsExtent.BtreeBlockHeader.parse(
                    ByteBuffer.wrap(blockBytes), isV5);

            if (!header.isValid()) continue;

            int headerSize = header.headerSize(isV5);

            if (header.level() == 0) {
                // Leaf node - parse extent records
                ByteBuffer recordBuf = ByteBuffer.wrap(blockBytes, headerSize, blockBytes.length - headerSize);
                for (int i = 0; i < header.numrecs(); i++) {
                    if (recordBuf.remaining() < XfsExtent.EXTENT_SIZE) break;
                    byte[] extentBytes = new byte[XfsExtent.EXTENT_SIZE];
                    recordBuf.get(extentBytes);
                    List<XfsExtent> parsed = XfsExtent.parseExtents(extentBytes, 1);
                    extents.addAll(parsed);
                }
            } else {
                // Internal node - recurse
                List<Long> childPtrs = new ArrayList<>();
                ByteBuffer ptrBuf = ByteBuffer.wrap(blockBytes);
                ptrBuf.order(ByteOrder.BIG_ENDIAN);

                // Pointers start after keys
                int keySize = 8; // startoff
                int ptrOffset = headerSize + header.numrecs() * keySize;

                for (int i = 0; i < header.numrecs(); i++) {
                    if (ptrOffset + i * 8 + 8 > blockBytes.length) break;
                    childPtrs.add(ptrBuf.getLong(ptrOffset + i * 8));
                }

                collectBtreeExtentsRecursive(childPtrs, header.level(), extents);
            }
        }
    }

    /**
     * Reads directory entries from a directory inode.
     */
    List<XfsDirectoryEntry> readDirectoryEntries(XfsInode dirInode) throws IOException {
        if (dirInode.hasInlineData()) {
            // Shortform directory
            return XfsDirectoryEntry.parseShortform(dirInode.dataFork(), dirInode.inodeNumber(), isV5);
        } else if (dirInode.hasExtents() || dirInode.hasBtree()) {
            // Block, leaf, or node directory
            return readBlockDirectory(dirInode);
        }

        return List.of();
    }

    /** Maximum number of directory entries to parse from a single directory, to prevent OOM from malicious images. */
    private static final int MAX_DIRECTORY_ENTRIES = 100_000;

    /**
     * XFS directory leaf offset in bytes. Directory extents at or above this logical
     * block offset are leaf/free index blocks, not data blocks containing entries.
     * @see <a href="https://xfs.wiki.kernel.org/index.php/XFS_Filesystem_Structure">XFS Directory v2 Format</a>
     */
    private static final long XFS_DIR2_LEAF_OFFSET = 32L * 1024 * 1024 * 1024;

    /**
     * Reads a block/leaf/node directory.
     * Reads data blocks on demand from each extent — each block is read individually,
     * so memory usage is bounded by the entries list, not by block I/O.
     *
     * <p>XFS directories use a segmented logical address space:
     * <ul>
     *   <li>Data blocks: logical offsets 0 to N (contain actual directory entries)</li>
     *   <li>Leaf blocks: logical offsets starting at 32 GiB / blockSize (hash index)</li>
     *   <li>Free blocks: logical offsets starting at 64 GiB / blockSize (free space mgmt)</li>
     * </ul>
     * Only data-region extents are read; leaf/free extents are skipped.
     */
    private List<XfsDirectoryEntry> readBlockDirectory(XfsInode dirInode) throws IOException {
        List<XfsDirectoryEntry> entries = new ArrayList<>();

        // Get extents from the inode
        List<XfsExtent> extents;
        if (dirInode.hasExtents()) {
            extents = XfsExtent.parseExtents(dirInode.dataFork(), dirInode.extentCount());
        } else if (dirInode.hasBtree()) {
            extents = collectBtreeExtents(dirInode);
        } else {
            return entries;
        }

        // Extents at or above this logical block are leaf/free index blocks, not data
        long leafBlockOffset = XFS_DIR2_LEAF_OFFSET / blockSize;

        for (XfsExtent extent : extents) {
            if (extent.blockCount() == 0 || extent.physicalBlock() == 0) continue;

            // Skip leaf and free-space index extents
            if (extent.logicalOffset() >= leafBlockOffset) continue;

            for (int i = 0; i < extent.blockCount(); i++) {
                // Stop if this block crosses into the leaf region
                if (extent.logicalOffset() + i >= leafBlockOffset) break;

                long physicalOffset = (extent.physicalBlock() + i) * blockSize;

                ByteBuffer blockBuf = region.read(physicalOffset, blockSize);
                byte[] block = new byte[blockSize];
                blockBuf.get(block);

                // Check block magic
                ByteBuffer buf = ByteBuffer.wrap(block);
                buf.order(ByteOrder.BIG_ENDIAN);
                int magic = buf.getInt(0);

                if (magic == 0x58444233 || magic == 0x58443244) {
                    // XDB3 or XD2D - data block
                    entries.addAll(XfsDirectoryEntry.parseBlock(block, blockSize, isV5));
                    if (entries.size() > MAX_DIRECTORY_ENTRIES) {
                        return entries;
                    }
                }
                // Skip leaf blocks (XDL3, XD2L) - they're just indexes
            }
        }

        // Add . and .. if not present
        boolean hasDot = entries.stream().anyMatch(e -> ".".equals(e.name()));
        boolean hasDotDot = entries.stream().anyMatch(e -> "..".equals(e.name()));

        if (!hasDot) {
            entries.add(0, new XfsDirectoryEntry(dirInode.inodeNumber(), ".", XfsDirectoryEntry.FT_DIR));
        }
        if (!hasDotDot) {
            // We'd need to track parent, for now just add a placeholder
            // Real implementations would track this
        }

        return entries;
    }

    // ========================================================================
    // FileSystemEntry implementations
    // ========================================================================

    /**
     * XFS directory implementation.
     */
    private class XfsDirectory implements FileSystemEntry.Directory {
        private final XfsInode inode;
        private final String name;
        private final String path;
        private final XfsFileSystemImpl fs;

        XfsDirectory(XfsInode inode, String name, String path, XfsFileSystemImpl fs) {
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
            return inode.creationTime();
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
            attrs.put("nlink", inode.nlink());
            attrs.put("inode", inode.inodeNumber());
            return attrs;
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<XfsDirectoryEntry> entries = fs.readDirectoryEntries(inode);
            List<FileSystemEntry> result = new ArrayList<>();

            for (XfsDirectoryEntry entry : entries) {
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
            List<XfsDirectoryEntry> entries = fs.readDirectoryEntries(inode);

            for (XfsDirectoryEntry entry : entries) {
                if (entry.name().equals(name)) {
                    String childPath = path.equals("/") ? "/" + name : path + "/" + name;
                    return Optional.ofNullable(createEntry(entry.inode(), name, childPath));
                }
            }

            return Optional.empty();
        }

        private FileSystemEntry createEntry(long inodeNum, String name, String path) throws IOException {
            XfsInode childInode = fs.readInode(inodeNum);

            if (!childInode.isValid()) {
                return null;
            }

            if (childInode.isDirectory()) {
                return new XfsDirectory(childInode, name, path, fs);
            } else if (childInode.isRegularFile()) {
                return new XfsRegularFile(childInode, name, path, fs);
            } else if (childInode.isSymbolicLink()) {
                return new XfsSymbolicLink(childInode, name, path, fs);
            } else {
                return new XfsSpecialFile(childInode, name, path);
            }
        }
    }

    /**
     * XFS regular file implementation.
     */
    private class XfsRegularFile implements FileSystemEntry.RegularFile {
        private final XfsInode inode;
        private final String name;
        private final String path;
        private final XfsFileSystemImpl fs;

        XfsRegularFile(XfsInode inode, String name, String path, XfsFileSystemImpl fs) {
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
            return inode.creationTime();
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
            attrs.put("nlink", inode.nlink());
            attrs.put("inode", inode.inodeNumber());
            return attrs;
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            byte[] data = fs.readInodeData(inode);
            return new ByteArrayInputStream(data);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            return fs.readInodeData(inode);
        }
    }

    /**
     * XFS symbolic link implementation.
     */
    private class XfsSymbolicLink implements FileSystemEntry.SymbolicLink {
        private final XfsInode inode;
        private final String name;
        private final String path;
        private final XfsFileSystemImpl fs;

        XfsSymbolicLink(XfsInode inode, String name, String path, XfsFileSystemImpl fs) {
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
            return inode.creationTime();
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
            try {
                byte[] data = fs.readInodeData(inode);
                return new String(data);
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            String targetPath = target();
            if (targetPath.startsWith("/")) {
                return fs.resolve(targetPath);
            } else {
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                if (parentPath.isEmpty()) parentPath = "/";
                return fs.resolve(parentPath + "/" + targetPath);
            }
        }
    }

    /**
     * XFS special file implementation.
     */
    private static class XfsSpecialFile implements FileSystemEntry.SpecialFile {
        private final XfsInode inode;
        private final String name;
        private final String path;

        XfsSpecialFile(XfsInode inode, String name, String path) {
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
            return inode.creationTime();
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
                ByteBuffer bb = ByteBuffer.wrap(inode.dataFork());
                bb.order(ByteOrder.BIG_ENDIAN);
                if (bb.remaining() >= 4) {
                    int dev = bb.getInt(0);
                    return Optional.of((dev >> 8) & 0xFF);
                }
            }
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            if (inode.isBlockDevice() || inode.isCharacterDevice()) {
                ByteBuffer bb = ByteBuffer.wrap(inode.dataFork());
                bb.order(ByteOrder.BIG_ENDIAN);
                if (bb.remaining() >= 4) {
                    int dev = bb.getInt(0);
                    return Optional.of(dev & 0xFF);
                }
            }
            return Optional.empty();
        }
    }
}
