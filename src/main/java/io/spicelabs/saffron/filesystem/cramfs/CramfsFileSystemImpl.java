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
package io.spicelabs.saffron.filesystem.cramfs;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
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
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import io.spicelabs.saffron.exception.ResourceLimitException;

/**
 * Read-only cramfs ("Compressed ROMFS") implementation.
 *
 * <p>cramfs is a tiny read-only filesystem: a 76-byte superblock, inodes
 * stored sequentially per directory (the directory inode's {@code size}
 * bounds its entry list), and per-4096-byte-block zlib compression. Block
 * pointer values are <em>absolute byte offsets, one past the end of the
 * block</em> (flags in bits 30/31); a pointer equal to the previous one
 * marks a hole.
 *
 * <p>Both little- and big-endian images are supported (mkcramfs writes host
 * byte order).
 *
 * <p>Hardening: every offset is bounds-checked against the effective image
 * size before use; block lengths are capped at twice the block size (kernel
 * parity); unsupported feature flags and direct-pointer blocks fail with a
 * checked {@link IOException}; entry names containing {@code /}, {@code \},
 * {@code ..}, or NUL are dropped.
 */
public final class CramfsFileSystemImpl implements FileSystem.CramfsFileSystem {

    /** Maximum default walk depth (hostile trees must not overflow the stack). */
    private static final int MAX_WALK_DEPTH = 512;

    /** Memory budget: no single file read > 16 MiB. */
    private static final long MAX_READABLE_SIZE = 16 * 1024 * 1024;

    private static final int MAX_SYMLINK_DEPTH = 40;
    private static final long MAX_BLOCK_LEN = 2L * CramfsSuperblock.BLOCK_SIZE;
    private static final int MAX_SYMLINK_TARGET = 4096;

    private final DiskRegion region;
    private final CramfsSuperblock superblock;
    private final long imageSize;

    private CramfsFileSystemImpl(DiskRegion region, CramfsSuperblock superblock) {
        this.region = region;
        this.superblock = superblock;
        this.imageSize = superblock.size();
    }

    /**
     * Mounts a cramfs filesystem from a virtual disk at a partition offset.
     *
     * @param disk the virtual disk
     * @param partitionOffset the byte offset where the filesystem starts
     * @return the mounted filesystem
     * @throws IOException if the region does not contain a cramfs filesystem
     *         or an I/O error occurs
     */
    public static @NotNull CramfsFileSystemImpl mount(@NotNull VirtualDisk disk,
                                                       long partitionOffset) throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Mounts a cramfs filesystem from a disk region.
     *
     * @param region the region containing the filesystem
     * @return the mounted filesystem
     * @throws IOException if the region does not contain a cramfs filesystem
     *         or an I/O error occurs
     */
    public static @NotNull CramfsFileSystemImpl mount(@NotNull DiskRegion region)
            throws IOException {
        CramfsSuperblock sb = CramfsSuperblock.read(region)
                .orElseThrow(() -> new IOException("Not a valid cramfs filesystem"));
        return new CramfsFileSystemImpl(region, sb);
    }

    // ========================================================================
    // Primitive reads
    // ========================================================================

    private int u32(long offset) throws IOException {
        ByteBuffer buf = region.read(offset, 4);
        buf.order(superblock.bigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        return buf.getInt(0);
    }

    private byte[] readBytes(long offset, int length) throws IOException {
        ByteBuffer buf = region.read(offset, length);
        byte[] out = new byte[length];
        buf.get(out);
        return out;
    }

    private record Inode(int mode, int uid, long size, int gid, int namelen, long dataOffset) {

        int type() {
            return mode & CramfsSuperblock.S_IFMT;
        }
    }

    private Inode inodeAt(long offset) throws IOException {
        if (offset < 0 || offset + 12 > imageSize) {
            throw new IOException("cramfs inode out of bounds: " + offset);
        }
        long w0 = u32(offset) & 0xffffffffL;
        long w1 = u32(offset + 4) & 0xffffffffL;
        long w2 = u32(offset + 8) & 0xffffffffL;
        int mode;
        int uid;
        long size;
        int gid;
        int namelen;
        long dataOffset;
        if (superblock.bigEndian()) {
            // Big-endian bitfield packing: high bits first.
            mode = (int) (w0 >>> 16);
            uid = (int) (w0 & 0xffff);
            size = (w1 >>> 8) & 0xffffffL;
            gid = (int) (w1 & 0xff);
            namelen = (int) (w2 >>> 26);
            dataOffset = (w2 & 0x3ffffffL) << 2;
        } else {
            mode = (int) (w0 & 0xffff);
            uid = (int) (w0 >>> 16);
            size = w1 & 0xffffffL;
            gid = (int) (w1 >>> 24) & 0xff;
            namelen = (int) (w2 & 0x3f);
            dataOffset = (w2 >>> 6) << 2;
        }
        return new Inode(mode, uid, size, gid, namelen, dataOffset);
    }

    private static boolean isSafeName(String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0') {
                return false;
            }
        }
        return true;
    }

    // ========================================================================
    // Directory entries
    // ========================================================================

    private final class CramfsDirectory implements FileSystemEntry.Directory {
        private final Inode inode;
        private final String name;
        private final String path;

        CramfsDirectory(Inode inode, String name, String path) {
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
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return inodeAttributes(inode);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<FileSystemEntry> result = new ArrayList<>();
            long end = SafeMath.safeAdd(inode.dataOffset(), inode.size());
            long off = inode.dataOffset();
            while (off + 12 <= end) {
                Inode de = inodeAt(off);
                int nameBytes = de.namelen() * 4;
                if (nameBytes == 0 || off + 12L + nameBytes > end) {
                    break; // Terminator or corrupt entry: stop.
                }
                if (nameBytes > CramfsSuperblock.MAX_NAME_LEN + 4) {
                    break;
                }
                String entryName = entryName(off + 12, nameBytes);
                long childPathOffset = off;
                entryFor(de, entryName, path, childPathOffset).ifPresent(result::add);
                off = SafeMath.safeAdd(off, 12L + nameBytes);
            }
            result.sort(java.util.Comparator.comparing(FileSystemEntry::name));
            return result.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            long end = SafeMath.safeAdd(inode.dataOffset(), inode.size());
            long off = inode.dataOffset();
            while (off + 12 <= end) {
                Inode de = inodeAt(off);
                int nameBytes = de.namelen() * 4;
                if (nameBytes == 0 || off + 12L + nameBytes > end) {
                    break;
                }
                String entryName = entryName(off + 12, nameBytes);
                if (name.equals(entryName)) {
                    return entryFor(de, entryName, path, off);
                }
                off = SafeMath.safeAdd(off, 12L + nameBytes);
            }
            return Optional.empty();
        }

        private String entryName(long nameOffset, int nameBytes) throws IOException {
            byte[] raw = readBytes(nameOffset, nameBytes);
            int len = nameBytes;
            while (len > 0 && raw[len - 1] == 0) {
                len--;
            }
            if (len == 0) {
                return "";
            }
            return new String(raw, 0, len, StandardCharsets.UTF_8);
        }
    }

    private Optional<FileSystemEntry> entryFor(Inode inode, String name, String parentPath,
                                               long inodeOffset) throws IOException {
        if (!isSafeName(name)) {
            return Optional.empty();
        }
        String childPath = parentPath.equals("/") ? "/" + name : parentPath + "/" + name;
        return switch (inode.type()) {
            case CramfsSuperblock.S_IFDIR ->
                    Optional.of(new CramfsDirectory(inode, name, childPath));
            case CramfsSuperblock.S_IFREG ->
                    Optional.of(new CramfsRegularFile(inode, name, childPath));
            case CramfsSuperblock.S_IFLNK -> Optional.of(new CramfsSymlink(
                    inode, name, childPath, readSymlinkTarget(inode)));
            case CramfsSuperblock.S_IFIFO, CramfsSuperblock.S_IFCHR,
                 CramfsSuperblock.S_IFBLK, CramfsSuperblock.S_IFSOCK ->
                    Optional.of(new CramfsSpecialFile(inode, name, childPath));
            default -> Optional.empty();
        };
    }

    private static Map<String, Object> inodeAttributes(Inode inode) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("mode", String.format("%o", inode.mode() & 0xfff));
        attrs.put("uid", inode.uid());
        attrs.put("gid", inode.gid());
        return Map.copyOf(attrs);
    }

    // ========================================================================
    // File data (block pointers + zlib)
    // ========================================================================

    /**
     * Returns the block-pointer table offset and block count for a file-like
     * inode (regular file or symlink).
     */
    private long blockCount(Inode inode) {
        return (inode.size() + CramfsSuperblock.BLOCK_SIZE - 1) / CramfsSuperblock.BLOCK_SIZE;
    }

    /**
     * Computes the data range of block {@code index}: {@code [start, end)}
     * as absolute image offsets. Returns a zero-length range for a hole.
     */
    private long[] blockRange(Inode inode, long index) throws IOException {
        long maxblock = blockCount(inode);
        long table = inode.dataOffset();
        long ptrOffset = SafeMath.safeAdd(table, SafeMath.safeMultiply(index, 4));
        int ptrValue = u32(ptrOffset);
        boolean uncompressed = (ptrValue & CramfsSuperblock.BLK_FLAG_UNCOMPRESSED) != 0;
        if ((ptrValue & CramfsSuperblock.BLK_FLAG_DIRECT_PTR) != 0) {
            throw new IOException("cramfs direct block pointers are not supported");
        }
        long ptr = ptrValue & ~(long) CramfsSuperblock.BLK_FLAGS;

        long start;
        if (index == 0) {
            start = SafeMath.safeAdd(table, SafeMath.safeMultiply(maxblock, 4));
        } else {
            int prevValue = u32(SafeMath.safeSubtract(ptrOffset, 4));
            if ((prevValue & CramfsSuperblock.BLK_FLAG_DIRECT_PTR) != 0) {
                throw new IOException("cramfs direct block pointers are not supported");
            }
            start = prevValue & ~(long) CramfsSuperblock.BLK_FLAGS;
        }

        long len = SafeMath.safeSubtract(ptr, start);
        if (len < 0 || len > MAX_BLOCK_LEN) {
            throw new IOException("cramfs block " + index + " has implausible length " + len);
        }
        if (len > imageSize - start) {
            throw new IOException("cramfs block " + index + " extends beyond the image");
        }
        if (len == 0) {
            return new long[] {start, start, 0}; // hole
        }
        return new long[] {start, SafeMath.safeAdd(start, len), uncompressed ? 1 : 0};
    }

    private InputStream openFileStream(Inode inode) throws IOException {
        if (superblock.compressionMethod() != CramfsSuperblock.COMP_METHOD_GZIP) {
            throw new IOException("cramfs compression method '"
                    + superblock.compressionName()
                    + "' (OpenRG extension) is not supported for content reads");
        }
        if (superblock.blockSizeFromFlags() != CramfsSuperblock.BLOCK_SIZE) {
            throw new IOException("cramfs block size " + superblock.blockSizeFromFlags()
                    + " (OpenRG extension) is not supported for content reads");
        }
        long size = inode.size();
        long maxblock = blockCount(inode);
        if (maxblock == 0 || size == 0) {
            return new ByteArrayInputStream(new byte[0]);
        }
        return new InputStream() {
            private long pos = 0;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (pos >= size) {
                    return -1;
                }
                int total = 0;
                while (total < len && pos < size) {
                    long index = pos / CramfsSuperblock.BLOCK_SIZE;
                    int blockOff = (int) (pos % CramfsSuperblock.BLOCK_SIZE);
                    long[] range = blockRange(inode, index);
                    long blockLen = range[1] - range[0];
                    long remaining = Math.min(
                            CramfsSuperblock.BLOCK_SIZE - blockOff, size - pos);
                    if (blockLen == 0) {
                        // Hole: zeros.
                        int n = (int) Math.min(remaining, len - total);
                        Arrays.fill(b, off + total, off + total + n, (byte) 0);
                        pos += n;
                        total += n;
                        continue;
                    }
                    if (range[2] == 1) {
                        // Uncompressed: raw bytes.
                        int n = (int) Math.min(Math.min(blockLen, remaining), len - total);
                        System.arraycopy(readBytes(range[0], n), 0, b, off + total, n);
                        pos += n;
                        total += n;
                        continue;
                    }
                    // Compressed: zlib.
                    byte[] payload = readBytes(range[0], (int) blockLen);
                    byte[] out = new byte[(int) remaining];
                    Inflater inflater = new Inflater();
                    inflater.setInput(payload);
                    int written;
                    try {
                        written = inflater.inflate(out);
                    } catch (DataFormatException e) {
                        inflater.end();
                        throw new IOException("cramfs zlib decompression failed", e);
                    }
                    inflater.end();
                    if (written != out.length) {
                        throw new IOException("cramfs block decompressed to " + written
                                + " bytes, expected " + out.length);
                    }
                    int n = Math.min(out.length, len - total);
                    System.arraycopy(out, 0, b, off + total, n);
                    pos += n;
                    total += n;
                }
                return total;
            }
        };
    }

    private byte[] readAllContent(Inode inode) throws IOException {
        long size = inode.size();
        if (size > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("cramfs file too large to read into memory: "
                    + size + " bytes (limit: 16 MB). Use openStream() for large files.",
                    "allocation_size", MAX_READABLE_SIZE, size);
        }
        byte[] content = new byte[(int) size];
        try (InputStream in = openFileStream(inode)) {
            int off = 0;
            while (off < content.length) {
                int n = in.read(content, off, content.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return content;
    }

    private String readSymlinkTarget(Inode inode) throws IOException {
        if (inode.size() > MAX_SYMLINK_TARGET) {
            throw new IOException("cramfs symlink target too large: " + inode.size());
        }
        return new String(readAllContent(inode), StandardCharsets.UTF_8);
    }

    // ========================================================================
    // Entry implementations
    // ========================================================================

    private final class CramfsRegularFile implements FileSystemEntry.RegularFile {
        private final Inode inode;
        private final String name;
        private final String path;

        CramfsRegularFile(Inode inode, String name, String path) {
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
            return inode.size();
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
            return inodeAttributes(inode);
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return openFileStream(inode);
        }

        @Override
        public byte @NotNull [] readAllBytes() throws IOException {
            return readAllContent(inode);
        }
    }

    private final class CramfsSymlink implements FileSystemEntry.SymbolicLink {
        private final Inode inode;
        private final String name;
        private final String path;
        private final String target;

        CramfsSymlink(Inode inode, String name, String path, String target) {
            this.inode = inode;
            this.name = name;
            this.path = path;
            this.target = target;
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
            return inodeAttributes(inode);
        }

        @Override
        public @NotNull String target() {
            return target;
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            String t = target;
            if (!t.startsWith("/")) {
                String parent = path.substring(0, path.lastIndexOf('/'));
                if (parent.isEmpty()) {
                    parent = "/";
                }
                if (!parent.endsWith("/")) {
                    parent = parent + "/";
                }
                t = parent + t;
            }
            return CramfsFileSystemImpl.this.resolve(t);
        }
    }

    private final class CramfsSpecialFile implements FileSystemEntry.SpecialFile {
        private final Inode inode;
        private final String name;
        private final String path;

        CramfsSpecialFile(Inode inode, String name, String path) {
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
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return inodeAttributes(inode);
        }

        @Override
        public @NotNull FileSystemEntry.EntryType type() {
            return switch (inode.type()) {
                case CramfsSuperblock.S_IFCHR -> FileSystemEntry.EntryType.CHARACTER_DEVICE;
                case CramfsSuperblock.S_IFBLK -> FileSystemEntry.EntryType.BLOCK_DEVICE;
                case CramfsSuperblock.S_IFIFO -> FileSystemEntry.EntryType.FIFO;
                default -> FileSystemEntry.EntryType.SOCKET;
            };
        }

        @Override
        public @NotNull Optional<Integer> majorDevice() {
            // cramfs stores the device number old-encoded in the inode size.
            long dev = inode.size();
            return Optional.of((int) ((dev >> 8) & 0xff));
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            long dev = inode.size();
            return Optional.of((int) ((dev & 0xff) | ((dev >> 8) & 0xff00)));
        }
    }

    // ========================================================================
    // FileSystem API
    // ========================================================================

    @Override
    public @NotNull FileSystemEntry.Directory root() {
        Inode rootInode = new Inode(
                superblock.rootMode() | 0555, 0, superblock.rootSize(), 0, 0,
                superblock.rootEntriesOffset());
        return new CramfsDirectory(rootInode, "/", "/");
    }

    @Override
    public @NotNull Optional<FileSystemEntry> resolve(@NotNull String path) throws IOException {
        return resolve(path, MAX_SYMLINK_DEPTH);
    }

    private @NotNull Optional<FileSystemEntry> resolve(@NotNull String path, int maxSymlinkHops)
            throws IOException {
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
                String target = symlink.target();
                String resolvedTarget;
                if (target.startsWith("/")) {
                    resolvedTarget = target;
                } else {
                    String parentPath = dir.path();
                    if (!parentPath.endsWith("/")) {
                        parentPath = parentPath + "/";
                    }
                    resolvedTarget = parentPath + target;
                }
                if (!remaining.isEmpty()) {
                    resolvedTarget = resolvedTarget + "/" + remaining;
                }
                return resolve(normalizePath(resolvedTarget), maxSymlinkHops - 1);
            }
        }
        return Optional.of(current);
    }

    private static String normalizePath(String path) {
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
        return walkDirectory(root(), MAX_WALK_DEPTH, new java.util.HashSet<>());
    }

    @Override
    public @NotNull Stream<FileSystemEntry> walk(@NotNull String path, int maxDepth)
            throws IOException {
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
                                                  Set<String> visited) throws IOException {
        if (maxDepth <= 0) {
            return Stream.of(dir);
        }
        if (!visited.add(dir.path())) {
            return Stream.of(dir);
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
                    // Skip entries that cannot be read.
                }
            });
        }
        return result.stream();
    }

    @Override
    public long totalSize() {
        return imageSize;
    }

    @Override
    public long usedSize() {
        return imageSize;
    }

    @Override
    public long freeSize() {
        return 0;
    }

    @Override
    public @NotNull Optional<String> label() {
        return superblock.name().isBlank() ? Optional.empty() : Optional.of(superblock.name());
    }

    @Override
    public @NotNull Optional<String> uuid() {
        return Optional.empty();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("version", "cramfs");
        meta.put("name", superblock.name());
        meta.put("edition", String.valueOf(superblock.edition()));
        meta.put("blockCount", String.valueOf(superblock.blocks()));
        meta.put("fileCount", String.valueOf(superblock.files()));
        meta.put("endian", superblock.bigEndian() ? "big" : "little");
        meta.put("compression", superblock.compressionName());
        meta.put("blockSize", String.valueOf(superblock.blockSizeFromFlags()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        // Nothing to close; the region is managed externally.
    }
}
