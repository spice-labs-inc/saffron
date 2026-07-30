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
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.io.BinaryReader;
import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class SquashfsFileSystemImpl implements FileSystem.SquashfsFileSystem {

    private static final int METADATA_BLOCK_SIZE = 8192;
    private static final int MAX_SYMLINK_DEPTH = 40;

    private final DiskRegion region;
    private final SquashfsSuperblock superblock;
    private final SquashfsCompressor compressor;
    private final SquashfsMetadataTable inodeTable;
    private final SquashfsMetadataTable directoryTable;
    private final long[] ids;
    private final List<SquashfsFragmentEntry> fragmentEntries;
    private final Map<Long, SquashfsInode> inodeCache;
    private final SquashfsInode.DirectoryInode rootInode;

    private SquashfsFileSystemImpl(DiskRegion region, SquashfsSuperblock superblock,
                                   SquashfsCompressor compressor, SquashfsMetadataTable inodeTable,
                                   SquashfsMetadataTable directoryTable, long[] ids,
                                   List<SquashfsFragmentEntry> fragmentEntries,
                                   SquashfsInode.DirectoryInode rootInode) {
        this.region = region;
        this.superblock = superblock;
        this.compressor = compressor;
        this.inodeTable = inodeTable;
        this.directoryTable = directoryTable;
        this.ids = ids;
        this.fragmentEntries = fragmentEntries;
        this.inodeCache = new LinkedHashMap<>();
        this.rootInode = rootInode;
    }

    public static @NotNull SquashfsFileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset) throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    public static @NotNull SquashfsFileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        SquashfsSuperblock superblock = SquashfsSuperblock.read(region)
                .orElseThrow(() -> new IOException("Not a valid squashfs filesystem"));
        SquashfsCompressor compressor = SquashfsCompressor.forId(superblock.compressionId());

        SquashfsMetadataTable inodeTable = SquashfsMetadataTable.read(region, compressor, superblock.inodeTableStart(), superblock.directoryTableStart());
        SquashfsMetadataTable directoryTable = SquashfsMetadataTable.read(region, compressor, superblock.directoryTableStart(), superblock.fragmentTableStart());
        long[] ids = readIdTable(region, compressor, superblock);
        List<SquashfsFragmentEntry> fragments = readFragmentTable(region, compressor, superblock);

        SquashfsInode root = readInode(inodeTable, superblock.rootInodeRef(), superblock.blockSize());
        if (!(root instanceof SquashfsInode.DirectoryInode dir)) {
            throw new IOException("Squashfs root inode is not a directory");
        }

        return new SquashfsFileSystemImpl(region, superblock, compressor, inodeTable,
                directoryTable, ids, fragments, dir);
    }


    private static MetadataBlock readMetadataBlock(DiskRegion region, SquashfsCompressor compressor, long offset) throws IOException {
        if (offset < 0 || offset + 2 > region.size()) {
            throw new IOException("Metadata block header out of bounds");
        }
        ByteBuffer headerBuf = region.read(offset, 2);
        headerBuf.order(ByteOrder.LITTLE_ENDIAN);
        int header = headerBuf.getShort(0) & 0xffff;
        int size = header & 0x7fff;
        boolean compressed = (header & 0x8000) == 0;
        if (size == 0 || size > METADATA_BLOCK_SIZE) {
            throw new IOException("Invalid squashfs metadata block size: " + size);
        }
        long dataOffset = SafeMath.safeAdd(offset, 2);
        long nextOffset = SafeMath.safeAdd(dataOffset, size);
        if (nextOffset > region.size()) {
            throw new IOException("Squashfs metadata block exceeds image size");
        }
        byte[] data = new byte[size];
        region.read(dataOffset, size).get(data);
        if (compressed) {
            data = compressor.decompress(data, METADATA_BLOCK_SIZE);
        }
        return new MetadataBlock(data, nextOffset);
    }

    private static long[] readIdTable(DiskRegion region, SquashfsCompressor compressor, SquashfsSuperblock sb) throws IOException {
        int idCount = sb.idCount();
        long[] ids = new long[idCount];
        if (idCount == 0) {
            return ids;
        }
        int blockCount = (int) SafeMath.safeCeilDiv(idCount, 2048);
        long tableEnd = SafeMath.safeAdd(sb.idTableStart(), SafeMath.safeMultiply((long) blockCount, 8L));
        if (tableEnd > region.size()) {
            throw new IOException("Squashfs ID table offset list out of bounds");
        }
        ByteBuffer offsetBuf = region.read(sb.idTableStart(), blockCount * 8);
        offsetBuf.order(ByteOrder.LITTLE_ENDIAN);
        int idIndex = 0;
        for (int i = 0; i < blockCount; i++) {
            long blockOffset = offsetBuf.getLong(i * 8);
            int count = Math.min(2048, idCount - idIndex);
            if (blockOffset < 0 || blockOffset > region.size()) {
                throw new IOException("Squashfs ID table block offset out of bounds");
            }
            MetadataBlock block = readMetadataBlock(region, compressor, blockOffset);
            BinaryReader reader = BinaryReader.littleEndian(new ByteArrayInputStream(block.data));
            for (int j = 0; j < count; j++) {
                ids[idIndex++] = reader.readUInt32();
            }
        }
        return ids;
    }

    private static List<SquashfsFragmentEntry> readFragmentTable(DiskRegion region, SquashfsCompressor compressor,
                                                                  SquashfsSuperblock sb) throws IOException {
        int fragmentCount = sb.fragmentEntryCount();
        List<SquashfsFragmentEntry> entries = new ArrayList<>(fragmentCount);
        if (fragmentCount == 0) {
            return entries;
        }
        int blockCount = (int) SafeMath.safeCeilDiv(fragmentCount, 512);
        long tableEnd = SafeMath.safeAdd(sb.fragmentTableStart(), SafeMath.safeMultiply((long) blockCount, 8L));
        if (tableEnd > region.size()) {
            throw new IOException("Squashfs fragment table offset list out of bounds");
        }
        ByteBuffer offsetBuf = region.read(sb.fragmentTableStart(), blockCount * 8);
        offsetBuf.order(ByteOrder.LITTLE_ENDIAN);
        int entryIndex = 0;
        for (int i = 0; i < blockCount; i++) {
            long blockOffset = offsetBuf.getLong(i * 8);
            int count = Math.min(512, fragmentCount - entryIndex);
            if (blockOffset < 0 || blockOffset > region.size()) {
                throw new IOException("Squashfs fragment table block offset out of bounds");
            }
            MetadataBlock block = readMetadataBlock(region, compressor, blockOffset);
            BinaryReader reader = BinaryReader.littleEndian(new ByteArrayInputStream(block.data));
            for (int j = 0; j < count; j++) {
                long start = reader.readInt64();
                int size = reader.readInt32();
                reader.skip(4);
                entries.add(new SquashfsFragmentEntry(start, size));
                entryIndex++;
            }
        }
        return entries;
    }

    private static SquashfsInode readInode(SquashfsMetadataTable inodeTable, long inodeRef, int blockSize) throws IOException {
        long blockStart = (inodeRef >>> 16) & 0xffffffffL;
        int blockOffset = (int) (inodeRef & 0xffffL);
        BinaryReader reader = inodeTable.readerAt(blockStart, blockOffset);
        return parseInode(reader, blockSize);
    }

    private static SquashfsInode parseInode(BinaryReader reader, int blockSize) throws IOException {
        int inodeType = reader.readUInt16();
        int mode = reader.readUInt16();
        int uidIndex = reader.readUInt16();
        int gidIndex = reader.readUInt16();
        long modifiedTime = reader.readUInt32();
        long inodeNumber = reader.readUInt32();

        return switch (inodeType) {
            case 1 -> parseBasicDirectoryInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, 0xffffffffL);
            case 8 -> parseExtendedDirectoryInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber);
            case 2 -> parseBasicFileInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, 0xffffffffL, blockSize);
            case 9 -> parseExtendedFileInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, blockSize);
            case 3 -> parseBasicSymlinkInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, 0xffffffffL);
            case 10 -> parseExtendedSymlinkInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber);
            case 4, 5 -> parseBasicDeviceInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, 0xffffffffL);
            case 11, 12 -> parseExtendedDeviceInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber);
            case 6, 7 -> parseBasicIpcInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, 0xffffffffL);
            case 13, 14 -> parseExtendedIpcInode(reader, inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber);
            default -> throw new IOException("Unknown squashfs inode type: " + inodeType);
        };
    }

    private static SquashfsInode.DirectoryInode parseBasicDirectoryInode(BinaryReader reader, int inodeType, int mode,
                                                                         int uidIndex, int gidIndex, long modifiedTime,
                                                                         long inodeNumber, long xattrIndex) throws IOException {
        long dirBlockStart = reader.readUInt32();
        long hardLinkCount = reader.readUInt32();
        int fileSize = reader.readUInt16();
        int blockOffset = reader.readUInt16();
        long parentInodeNumber = reader.readUInt32();
        return new SquashfsInode.DirectoryInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, fileSize, dirBlockStart, parentInodeNumber, blockOffset);
    }

    private static SquashfsInode.DirectoryInode parseExtendedDirectoryInode(BinaryReader reader, int inodeType, int mode,
                                                                          int uidIndex, int gidIndex, long modifiedTime,
                                                                          long inodeNumber) throws IOException {
        long hardLinkCount = reader.readUInt32();
        long fileSize = reader.readUInt32();
        long dirBlockStart = reader.readUInt32();
        long parentInodeNumber = reader.readUInt32();
        int indexCount = reader.readUInt16();
        int blockOffset = reader.readUInt16();
        long xattrIndex = reader.readInt32() & 0xffffffffL;
        for (int i = 0; i < indexCount; i++) {
            reader.skip(8);
            int nameSize = reader.readInt32();
            reader.skip(nameSize + 1);
        }
        return new SquashfsInode.DirectoryInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, fileSize, dirBlockStart, parentInodeNumber, blockOffset);
    }

    private static SquashfsInode.FileInode parseBasicFileInode(BinaryReader reader, int inodeType, int mode,
                                                               int uidIndex, int gidIndex, long modifiedTime,
                                                               long inodeNumber, long xattrIndex,
                                                               int blockSize) throws IOException {
        long blocksStart = reader.readUInt32();
        int fragmentBlockIndex = (int) reader.readUInt32();
        int fragmentOffset = (int) reader.readUInt32();
        long fileSize = reader.readUInt32();
        int blockCount = blockCount(fileSize, fragmentBlockIndex, blockSize);
        int[] blockSizes = new int[blockCount];
        for (int i = 0; i < blockCount; i++) {
            blockSizes[i] = reader.readInt32();
        }
        return new SquashfsInode.FileInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                blocksStart, fileSize, 0, 1, fragmentBlockIndex, fragmentOffset, blockSizes);
    }

    private static SquashfsInode.FileInode parseExtendedFileInode(BinaryReader reader, int inodeType, int mode,
                                                                  int uidIndex, int gidIndex, long modifiedTime,
                                                                  long inodeNumber, int blockSize) throws IOException {
        long blocksStart = reader.readInt64();
        long fileSize = reader.readInt64();
        long sparse = reader.readInt64();
        long hardLinkCount = reader.readUInt32();
        int fragmentBlockIndex = (int) reader.readUInt32();
        int fragmentOffset = (int) reader.readUInt32();
        long xattrIndex = reader.readInt32() & 0xffffffffL;
        int blockCount = blockCount(fileSize, fragmentBlockIndex, blockSize);
        int[] blockSizes = new int[blockCount];
        for (int i = 0; i < blockCount; i++) {
            blockSizes[i] = reader.readInt32();
        }
        return new SquashfsInode.FileInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                blocksStart, fileSize, sparse, hardLinkCount, fragmentBlockIndex, fragmentOffset, blockSizes);
    }

    private static int blockCount(long fileSize, int fragmentBlockIndex, int blockSize) {
        if (fileSize == 0) {
            return 0;
        }
        if (fragmentBlockIndex == 0xffffffffL) {
            return (int) SafeMath.safeCeilDiv(fileSize, blockSize);
        }
        return (int) (fileSize / blockSize);
    }

    private static SquashfsInode.SymlinkInode parseBasicSymlinkInode(BinaryReader reader, int inodeType, int mode,
                                                                    int uidIndex, int gidIndex, long modifiedTime,
                                                                    long inodeNumber, long xattrIndex) throws IOException {
        long hardLinkCount = reader.readUInt32();
        int targetSize = (int) reader.readUInt32();
        byte[] targetBytes = reader.readBytes(targetSize);
        return new SquashfsInode.SymlinkInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, new String(targetBytes, StandardCharsets.UTF_8));
    }

    private static SquashfsInode.SymlinkInode parseExtendedSymlinkInode(BinaryReader reader, int inodeType, int mode,
                                                                         int uidIndex, int gidIndex, long modifiedTime,
                                                                         long inodeNumber) throws IOException {
        long hardLinkCount = reader.readUInt32();
        int targetSize = (int) reader.readUInt32();
        byte[] targetBytes = reader.readBytes(targetSize);
        long xattrIndex = reader.readInt32() & 0xffffffffL;
        return new SquashfsInode.SymlinkInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, new String(targetBytes, StandardCharsets.UTF_8));
    }

    private static SquashfsInode.SpecialInode parseBasicDeviceInode(BinaryReader reader, int inodeType, int mode,
                                                                    int uidIndex, int gidIndex, long modifiedTime,
                                                                    long inodeNumber, long xattrIndex) throws IOException {
        long hardLinkCount = reader.readUInt32();
        long device = reader.readUInt32();
        return new SquashfsInode.SpecialInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, device);
    }

    private static SquashfsInode.SpecialInode parseExtendedDeviceInode(BinaryReader reader, int inodeType, int mode,
                                                                       int uidIndex, int gidIndex, long modifiedTime,
                                                                       long inodeNumber) throws IOException {
        long hardLinkCount = reader.readUInt32();
        long device = reader.readUInt32();
        long xattrIndex = reader.readInt32() & 0xffffffffL;
        return new SquashfsInode.SpecialInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, device);
    }

    private static SquashfsInode.SpecialInode parseBasicIpcInode(BinaryReader reader, int inodeType, int mode,
                                                                 int uidIndex, int gidIndex, long modifiedTime,
                                                                 long inodeNumber, long xattrIndex) throws IOException {
        long hardLinkCount = reader.readUInt32();
        return new SquashfsInode.SpecialInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, 0);
    }

    private static SquashfsInode.SpecialInode parseExtendedIpcInode(BinaryReader reader, int inodeType, int mode,
                                                                    int uidIndex, int gidIndex, long modifiedTime,
                                                                    long inodeNumber) throws IOException {
        long hardLinkCount = reader.readUInt32();
        long xattrIndex = reader.readInt32() & 0xffffffffL;
        return new SquashfsInode.SpecialInode(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex,
                hardLinkCount, 0);
    }

    private SquashfsInode resolveInode(long inodeRef) throws IOException {
        SquashfsInode cached = inodeCache.get(inodeRef);
        if (cached != null) {
            return cached;
        }
        SquashfsInode inode = readInode(inodeTable, inodeRef, superblock.blockSize());
        inodeCache.put(inodeRef, inode);
        return inode;
    }

    private List<DirectoryEntry> readDirectoryEntries(SquashfsInode.DirectoryInode dirInode) throws IOException {
        List<DirectoryEntry> entries = new ArrayList<>();
        BinaryReader reader = directoryTable.readerAt(dirInode.dirBlockStart, dirInode.blockOffset);
        int totalSize = (int) dirInode.fileSize;
        if (totalSize < 3) {
            return entries;
        }
        int toRead = totalSize - 3;
        int consumed = 0;
        while (consumed < toRead) {
            int count = reader.readInt32() + 1;
            int headerStart = reader.readInt32();
            int baseInodeNumber = (int) reader.readUInt32();
            consumed += 12;
            for (int i = 0; i < count; i++) {
                int offset = reader.readUInt16();
                int inodeOffset = reader.readInt16();
                int type = reader.readUInt16();
                int nameSize = (reader.readUInt16() & 0xffff) + 1;
                byte[] nameBytes = reader.readBytes(nameSize);
                consumed += 8 + nameSize;
                if (consumed > toRead) {
                    throw new IOException("Directory entry exceeds declared size");
                }
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                long childInodeRef = (((long) headerStart) << 16) | (offset & 0xffffL);
                long childInodeNumber = (baseInodeNumber + inodeOffset) & 0xffffffffL;
                entries.add(new DirectoryEntry(name, childInodeRef, childInodeNumber, type));
            }
        }
        return entries;
    }

    private byte[] readFragmentBlock(SquashfsFragmentEntry fragment) throws IOException {
        if (fragment.size == 0) {
            return new byte[0];
        }
        boolean uncompressed = (fragment.size & 0x01000000) != 0;
        int compressedSize = fragment.size & 0x00ffffff;
        if (compressedSize > superblock.blockSize()) {
            throw new IOException("Squashfs fragment block size exceeds block size");
        }
        int outputSize = superblock.blockSize();
        if (fragment.start < 0 || fragment.start + compressedSize > region.size()) {
            throw new IOException("Squashfs fragment block out of bounds");
        }
        byte[] data = new byte[compressedSize];
        region.read(fragment.start, compressedSize).get(data);
        return uncompressed ? data : compressor.decompress(data, outputSize);
    }

    private int lookupUid(int index) {
        if (index < 0 || index >= ids.length) {
            return index;
        }
        return (int) ids[index];
    }

    private int lookupGid(int index) {
        if (index < 0 || index >= ids.length) {
            return index;
        }
        return (int) ids[index];
    }

    @Override
    public @NotNull FileSystemEntry.Directory root() throws IOException {
        return new SquashfsDirectory(rootInode, "/", "/");
    }

    @Override
    public @NotNull Optional<FileSystemEntry> resolve(@NotNull String path) throws IOException {
        return resolve(path, MAX_SYMLINK_DEPTH);
    }

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
            if (part.isEmpty()) {
                continue;
            }
            if (!(current instanceof FileSystemEntry.Directory dir)) {
                return Optional.empty();
            }
            Optional<FileSystemEntry> next = dir.find(part);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
            if (current instanceof FileSystemEntry.SymbolicLink symlink) {
                if (maxSymlinkHops <= 0) {
                    return Optional.empty();
                }
                String remaining = String.join("/", Arrays.copyOfRange(parts, i + 1, parts.length));
                String resolvedTarget;
                if (symlink.target().startsWith("/")) {
                    resolvedTarget = symlink.target();
                } else {
                    String parentPath = dir.path();
                    if (!parentPath.endsWith("/")) {
                        parentPath = parentPath + "/";
                    }
                    resolvedTarget = parentPath + symlink.target();
                }
                if (!remaining.isEmpty()) {
                    resolvedTarget = resolvedTarget + "/" + remaining;
                }
                resolvedTarget = normalizePath(resolvedTarget);
                return resolve(resolvedTarget, maxSymlinkHops - 1);
            }
        }
        return Optional.of(current);
    }

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
        return walkDirectory(root(), Integer.MAX_VALUE, new java.util.HashSet<>());
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth) throws IOException {
        Optional<FileSystemEntry> entry = resolve(path);
        if (entry.isEmpty()) {
            return Stream.empty();
        }
        if (entry.get() instanceof FileSystemEntry.Directory dir) {
            return walkDirectory(dir, maxDepth, new java.util.HashSet<>());
        }
        return Stream.of(entry.get());
    }

    private Stream<FileSystemEntry> walkDirectory(FileSystemEntry.Directory dir, int maxDepth,
                                                   java.util.Set<Long> visited) throws IOException {
        if (maxDepth <= 0) {
            return Stream.of(dir);
        }
        if (dir instanceof SquashfsDirectory sd) {
            if (!visited.add(sd.inode.inodeNumber)) {
                return Stream.of(dir);
            }
        }
        List<FileSystemEntry> result = new ArrayList<>();
        result.add(dir);
        try (Stream<FileSystemEntry> children = dir.list()) {
            children.forEach(entry -> {
                try {
                    if (entry instanceof FileSystemEntry.Directory subDir) {
                        walkDirectory(subDir, maxDepth - 1, visited).forEach(result::add);
                    } else {
                        result.add(entry);
                    }
                } catch (IOException e) {
                    // Skip entries that cannot be read
                }
            });
        }
        return result.stream();
    }

    @Override
    public long totalSize() {
        return superblock.bytesUsed();
    }

    @Override
    public long usedSize() {
        return superblock.bytesUsed();
    }

    @Override
    public long freeSize() {
        return 0;
    }

    @Override
    public @NotNull Optional<String> label() {
        return Optional.empty();
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.empty();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("version", superblock.versionMajor() + "." + superblock.versionMinor());
        meta.put("blockSize", String.valueOf(superblock.blockSize()));
        meta.put("inodeCount", String.valueOf(superblock.inodeCount()));
        meta.put("compression", compression());
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public @NotNull String compression() {
        return superblock.compressionName();
    }

    @Override
    public int blockSize() {
        return superblock.blockSize();
    }

    @Override
    public void close() {
        // Nothing to close; region is managed externally
    }

    private record MetadataBlock(byte[] data, long nextOffset) {
    }

    private record SquashfsFragmentEntry(long start, int size) {
    }

    private record DirectoryEntry(String name, long inodeRef, long inodeNumber, int type) {
    }

    private FileSystemEntry createEntry(String name, String path, DirectoryEntry entry) throws IOException {
        SquashfsInode inode = resolveInode(entry.inodeRef());
        return createEntryFromInode(inode, name, path);
    }

    private FileSystemEntry createEntryFromInode(SquashfsInode inode, String name, String path) throws IOException {
        return switch (inode) {
            case SquashfsInode.DirectoryInode d -> new SquashfsDirectory(d, name, path);
            case SquashfsInode.FileInode f -> new SquashfsRegularFile(f, name, path);
            case SquashfsInode.SymlinkInode s -> new SquashfsSymbolicLink(s, name, path);
            case SquashfsInode.SpecialInode s -> new SquashfsSpecialFile(s, name, path);
        };
    }

    private FileSystemEntry.EntryType mapEntryType(int inodeType) {
        return switch (inodeType) {
            case 1, 8 -> FileSystemEntry.EntryType.DIRECTORY;
            case 2, 9 -> FileSystemEntry.EntryType.REGULAR_FILE;
            case 3, 10 -> FileSystemEntry.EntryType.SYMBOLIC_LINK;
            case 4, 11 -> FileSystemEntry.EntryType.BLOCK_DEVICE;
            case 5, 12 -> FileSystemEntry.EntryType.CHARACTER_DEVICE;
            case 6, 13 -> FileSystemEntry.EntryType.FIFO;
            case 7, 14 -> FileSystemEntry.EntryType.SOCKET;
            default -> FileSystemEntry.EntryType.UNKNOWN;
        };
    }

    private class SquashfsDirectory implements FileSystemEntry.Directory {
        private final SquashfsInode.DirectoryInode inode;
        private final String name;
        private final String path;

        SquashfsDirectory(SquashfsInode.DirectoryInode inode, String name, String path) {
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
            return inode.fileSize;
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(Instant.ofEpochSecond(inode.modifiedTime));
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode);
            attrs.put("uid", lookupUid(inode.uidIndex));
            attrs.put("gid", lookupGid(inode.gidIndex));
            attrs.put("inode", inode.inodeNumber);
            return attrs;
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<DirectoryEntry> entries = readDirectoryEntries(inode);
            List<FileSystemEntry> result = new ArrayList<>(entries.size());
            for (DirectoryEntry entry : entries) {
                if (entry.name.isEmpty()) {
                    continue;
                }
                String childPath = path.equals("/") ? "/" + entry.name : path + "/" + entry.name;
                result.add(createEntry(entry.name, childPath, entry));
            }
            return result.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            for (DirectoryEntry entry : readDirectoryEntries(inode)) {
                if (entry.name.equals(name)) {
                    String childPath = path.equals("/") ? "/" + name : path + "/" + name;
                    return Optional.of(createEntry(name, childPath, entry));
                }
            }
            return Optional.empty();
        }
    }

    private class SquashfsRegularFile implements FileSystemEntry.RegularFile {
        private final SquashfsInode.FileInode inode;
        private final String name;
        private final String path;

        SquashfsRegularFile(SquashfsInode.FileInode inode, String name, String path) {
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
            return inode.fileSize;
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(Instant.ofEpochSecond(inode.modifiedTime));
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode);
            attrs.put("uid", lookupUid(inode.uidIndex));
            attrs.put("gid", lookupGid(inode.gidIndex));
            attrs.put("inode", inode.inodeNumber);
            return attrs;
        }

    @Override
    public @NotNull InputStream openStream() throws IOException {
            return new SquashfsInputStream(inode);
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            if (inode.fileSize > Integer.MAX_VALUE) {
                throw new IOException("File too large to read into memory: " + inode.fileSize);
            }
            try (InputStream in = new SquashfsInputStream(inode)) {
                return in.readAllBytes();
            }
        }
    }

    /**
     * Streams the contents of a squashfs regular file one block at a time. No
     * byte array sized by the file's declared length is allocated up front; only
     * the currently needed data block (and optionally the shared fragment block)
     * is kept in memory.
     */
    private class SquashfsInputStream extends InputStream {
        private final SquashfsInode.FileInode inode;
        private final int blockSize;
        private long position;
        private int currentBlockIndex;
        private long currentBlockOffset;
        private byte[] currentBlock;
        private int currentBlockPos;
        private byte[] fragmentBlock;
        private boolean fragmentLoaded;
        private boolean closed;

        SquashfsInputStream(SquashfsInode.FileInode inode) throws IOException {
            this.inode = inode;
            this.blockSize = superblock.blockSize();
            validateFileSize(inode);
            this.position = 0;
            this.currentBlockIndex = 0;
            this.currentBlockOffset = inode.blocksStart;
            this.currentBlock = null;
            this.currentBlockPos = 0;
            this.fragmentBlock = null;
            this.fragmentLoaded = false;
        }

        private void validateFileSize(SquashfsInode.FileInode inode) throws IOException {
            if (inode.fileSize < 0) {
                throw new IOException("Negative squashfs file size: " + inode.fileSize);
            }
            long maxUncompressed = SafeMath.safeMultiply((long) inode.blockSizes.length, (long) blockSize);
            if (inode.hasFragment()) {
                maxUncompressed = SafeMath.safeAdd(maxUncompressed, blockSize);
            }
            if (inode.fileSize > maxUncompressed) {
                throw new IOException("Squashfs file size " + inode.fileSize
                        + " exceeds available data blocks (max " + maxUncompressed + ")");
            }
            long totalCompressed = 0;
            for (int sizeField : inode.blockSizes) {
                int compressedSize = sizeField & 0x00ffffff;
                totalCompressed = SafeMath.safeAdd(totalCompressed, compressedSize);
            }
            long dataEnd = SafeMath.safeAdd(inode.blocksStart, totalCompressed);
            if (dataEnd > region.size()) {
                throw new IOException("Squashfs file data blocks exceed image size");
            }
        }

        @Override
        public int read() throws IOException {
            ensureOpen();
            if (position >= inode.fileSize) {
                return -1;
            }
            ensureBlockLoaded();
            if (currentBlock == null || currentBlock.length == 0) {
                return -1;
            }
            byte b = currentBlock[currentBlockPos++];
            position++;
            return b & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            if (b == null) {
                throw new NullPointerException();
            }
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            if (position >= inode.fileSize) {
                return -1;
            }
            int totalRead = 0;
            while (totalRead < len && position < inode.fileSize) {
                ensureBlockLoaded();
                if (currentBlock == null || currentBlock.length == 0) {
                    break;
                }
                int available = currentBlock.length - currentBlockPos;
                int toRead = (int) Math.min(len - totalRead, available);
                toRead = (int) Math.min(toRead, inode.fileSize - position);
                System.arraycopy(currentBlock, currentBlockPos, b, off + totalRead, toRead);
                currentBlockPos += toRead;
                position += toRead;
                totalRead += toRead;
            }
            return totalRead == 0 ? -1 : totalRead;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
        }

        private void ensureBlockLoaded() throws IOException {
            if (currentBlock != null && currentBlockPos < currentBlock.length) {
                return;
            }
            if (currentBlockIndex >= inode.blockSizes.length) {
                loadFragment();
                return;
            }
            int sizeField = inode.blockSizes[currentBlockIndex];
            boolean uncompressed = (sizeField & 0x01000000) != 0;
            int compressedSize = sizeField & 0x00ffffff;
            if (compressedSize == 0) {
                currentBlock = new byte[blockSize];
            } else {
                if (currentBlockOffset < 0 || currentBlockOffset + compressedSize > region.size()) {
                    throw new IOException("Squashfs data block out of bounds");
                }
                byte[] data = new byte[compressedSize];
                region.read(currentBlockOffset, compressedSize).get(data);
                currentBlock = uncompressed ? data : compressor.decompress(data, blockSize);
            }
            currentBlockPos = 0;
            currentBlockOffset = SafeMath.safeAdd(currentBlockOffset,
                    compressedSize == 0 ? 0 : compressedSize);
            currentBlockIndex++;
        }

        private void loadFragment() throws IOException {
            if (currentBlock != null) {
                return;
            }
            long fragmentSize = inode.fileSize - SafeMath.safeMultiply((long) inode.blockSizes.length, (long) blockSize);
            if (!inode.hasFragment() || fragmentSize <= 0) {
                currentBlock = new byte[0];
                return;
            }
            if (!fragmentLoaded) {
                if (inode.fragmentBlockIndex < 0 || inode.fragmentBlockIndex >= fragmentEntries.size()) {
                    throw new IOException("Squashfs fragment index out of bounds");
                }
                SquashfsFragmentEntry fragment = fragmentEntries.get(inode.fragmentBlockIndex);
                fragmentBlock = readFragmentBlock(fragment);
                fragmentLoaded = true;
            }
            if (inode.fragmentOffset < 0 || inode.fragmentOffset + fragmentSize > fragmentBlock.length) {
                throw new IOException("Squashfs fragment offset out of bounds");
            }
            currentBlock = fragmentBlock;
            currentBlockPos = inode.fragmentOffset;
        }

        @Override
        public void close() {
            closed = true;
            currentBlock = null;
            fragmentBlock = null;
        }
    }

    private class SquashfsSymbolicLink implements FileSystemEntry.SymbolicLink {
        private final SquashfsInode.SymlinkInode inode;
        private final String name;
        private final String path;

        SquashfsSymbolicLink(SquashfsInode.SymlinkInode inode, String name, String path) {
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
            return inode.target.length();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(Instant.ofEpochSecond(inode.modifiedTime));
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode);
            attrs.put("uid", lookupUid(inode.uidIndex));
            attrs.put("gid", lookupGid(inode.gidIndex));
            attrs.put("inode", inode.inodeNumber);
            return attrs;
        }

        @Override
        public @NotNull String target() {
            return inode.target;
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            return resolveWithDepth(MAX_SYMLINK_DEPTH);
        }

        private Optional<FileSystemEntry> resolveWithDepth(int remaining) throws IOException {
            if (remaining <= 0) {
                return Optional.empty();
            }
            String resolvedTarget;
            if (target().startsWith("/")) {
                resolvedTarget = target();
            } else {
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                if (parentPath.isEmpty()) {
                    parentPath = "/";
                }
                if (!parentPath.endsWith("/")) {
                    parentPath = parentPath + "/";
                }
                resolvedTarget = parentPath + target();
            }
            resolvedTarget = normalizePath(resolvedTarget);
            Optional<FileSystemEntry> result = SquashfsFileSystemImpl.this.resolve(resolvedTarget, remaining - 1);
            if (result.isPresent() && result.get() instanceof SquashfsSymbolicLink nested) {
                return nested.resolveWithDepth(remaining - 1);
            }
            return result;
        }
    }

    private class SquashfsSpecialFile implements FileSystemEntry.SpecialFile {
        private final SquashfsInode.SpecialInode inode;
        private final String name;
        private final String path;

        SquashfsSpecialFile(SquashfsInode.SpecialInode inode, String name, String path) {
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
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return Optional.of(Instant.ofEpochSecond(inode.modifiedTime));
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("mode", inode.mode);
            attrs.put("uid", lookupUid(inode.uidIndex));
            attrs.put("gid", lookupGid(inode.gidIndex));
            attrs.put("inode", inode.inodeNumber);
            return attrs;
        }

        @Override
        public @NotNull FileSystemEntry.EntryType type() {
            return mapEntryType(inode.inodeType);
        }

        @Override
        public @NotNull Optional<Integer> majorDevice() {
            if (inode.inodeType == 4 || inode.inodeType == 5 || inode.inodeType == 11 || inode.inodeType == 12) {
                return Optional.of((int) ((inode.device & 0xfff00L) >> 8));
            }
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            if (inode.inodeType == 4 || inode.inodeType == 5 || inode.inodeType == 11 || inode.inodeType == 12) {
                return Optional.of((int) ((inode.device & 0xffL) | ((inode.device >> 12) & 0xfff00L)));
            }
            return Optional.empty();
        }
    }
}
