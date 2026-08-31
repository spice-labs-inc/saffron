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
package io.spicelabs.saffron.filesystem.yaffs2;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import io.spicelabs.saffron.exception.ResourceLimitException;

/**
 * Read-only YAFFS2 implementation.
 *
 * <p>YAFFS2 is a log-structured NAND filesystem with no superblock: the
 * image is a sequence of fixed-size chunks (page + spare). Chunk 0 of each
 * object holds the 512-byte object header; later chunks hold file data
 * (page-sized, the last chunk truncated to {@code n_bytes}). The directory
 * tree is recovered by grouping object headers by parent id; objects whose
 * parent is the unlinked/deleted sentinel are hidden. Higher sequence
 * numbers win for the same chunk.
 *
 * <p>Hardening: every chunk offset is bounds-checked; chunk ids are capped
 * at the chunk count; names with path separators are dropped; directory
 * walks are cycle-guarded; file reads are lazy (one chunk at a time).
 */
public final class Yaffs2FileSystemImpl implements FileSystem.Yaffs2FileSystem {

    /** Maximum default walk depth (hostile trees must not overflow the stack). */
    private static final int MAX_WALK_DEPTH = 512;

    /** Memory budget: no single file read > 16 MiB. */
    private static final long MAX_READABLE_SIZE = 16 * 1024 * 1024;

    private static final int MAX_SYMLINK_DEPTH = 40;

    private final DiskRegion region;
    private final Yaffs2Superblock superblock;
    private final Map<Long, Yaffs2Node.Header> headers;          // objId -> header
    private final Map<Long, Map<Long, long[]>> dataChunks;       // objId -> chunkId -> [start, length]
    private final Map<Long, List<Long>> children;                // parentId -> objIds
    private final Map<Long, Long> hardlinkTargets;               // objId -> equiv objId

    private Yaffs2FileSystemImpl(DiskRegion region, Yaffs2Superblock sb,
                                 ScanResult scan) {
        this.region = region;
        this.superblock = sb;
        this.headers = scan.headers;
        this.dataChunks = scan.dataChunks;
        this.children = scan.children;
        this.hardlinkTargets = scan.hardlinkTargets;
    }

    /**
     * Mounts a YAFFS2 filesystem from a virtual disk at a partition offset.
     */
    public static @NotNull Yaffs2FileSystemImpl mount(@NotNull VirtualDisk disk,
                                                       long partitionOffset) throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Mounts a YAFFS2 filesystem from a disk region.
     */
    public static @NotNull Yaffs2FileSystemImpl mount(@NotNull DiskRegion region)
            throws IOException {
        Yaffs2Superblock sb = Yaffs2Superblock.read(region)
                .orElseThrow(() -> new IOException("Not a valid yaffs2 filesystem"));
        return new Yaffs2FileSystemImpl(region, sb, scan(region, sb));
    }

    // ========================================================================
    // Scan
    // ========================================================================

    private static final class ScanResult {
        final Map<Long, Yaffs2Node.Header> headers;
        final Map<Long, Map<Long, long[]>> dataChunks;
        final Map<Long, List<Long>> children;
        final Map<Long, Long> hardlinkTargets;

        ScanResult(Map<Long, Yaffs2Node.Header> headers,
                   Map<Long, Map<Long, long[]>> dataChunks,
                   Map<Long, List<Long>> children,
                   Map<Long, Long> hardlinkTargets) {
            this.headers = headers;
            this.dataChunks = dataChunks;
            this.children = children;
            this.hardlinkTargets = hardlinkTargets;
        }
    }

    private static ScanResult scan(DiskRegion region, Yaffs2Superblock sb)
            throws IOException {
        Map<Long, Yaffs2Node.Header> headers = new HashMap<>();
        Map<Long, Long> headerSeq = new HashMap<>();
        Map<Long, Map<Long, long[]>> dataChunks = new HashMap<>();
        Map<Long, Map<Long, Long>> chunkSeq = new HashMap<>();
        Map<Long, Long> hardlinkTargets = new HashMap<>();

        long chunkSize = sb.chunkSize();
        for (long c = 0; c < sb.chunkCount(); c++) {
            long base = SafeMath.safeMultiply(c, chunkSize);

            ByteBuffer spareBuf = region.read(SafeMath.safeAdd(base, sb.pageSize()), 16);
            byte[] spareBytes = new byte[16];
            spareBuf.get(spareBytes);
            Yaffs2Node.Tag tag = Yaffs2Node.decodeTag(spareBytes, sb.bigEndianTags());
            if (tag == null || tag.objId() == 0) {
                continue;
            }
            if (tag.chunkId() == 0xffffffffL) {
                continue; // Erased/unused chunk (trailing padding).
            }
            long objId = tag.objId();

            if (tag.chunkId() == 0 && sb.pageSize() >= Yaffs2Node.HEADER_SIZE) {
                ByteBuffer headBuf = region.read(base, Yaffs2Node.HEADER_SIZE);
                byte[] head = new byte[Yaffs2Node.HEADER_SIZE];
                headBuf.get(head);
                Yaffs2Node.Header header = parseHeader(head, objId, sb.bigEndianData());
                if (header == null) {
                    continue;
                }
                // Highest sequence number wins for the header.
                if (headerSeq.getOrDefault(objId, -1L) > tag.seq()) {
                    continue;
                }
                headerSeq.put(objId, tag.seq());
                headers.put(objId, header);
                if (header.type() == Yaffs2Node.TYPE_HARDLINK && header.equivId() != 0) {
                    hardlinkTargets.put(objId, header.equivId());
                }
                continue;
            }

            // Data chunk: highest seq per chunk id wins.
            if (tag.chunkId() == 0 || tag.chunkId() > sb.chunkCount()) {
                continue;
            }
            Map<Long, Long> seqs = chunkSeq.computeIfAbsent(objId, k -> new HashMap<>());
            if (seqs.getOrDefault(tag.chunkId(), -1L) > tag.seq()) {
                continue;
            }
            seqs.put(tag.chunkId(), tag.seq());
            // Data chunks start at the chunk start (only chunk 0 carries the
            // 512-byte object header); the last chunk is truncated to n_bytes.
            long length = Math.min(tag.nBytes(), (long) sb.pageSize());
            if (length <= 0 || length > sb.pageSize()) {
                continue;
            }
            dataChunks.computeIfAbsent(objId, k -> new TreeMap<>())
                    .put(tag.chunkId(), new long[] {base, length});
        }

        // Build the tree: group headers by parent, hiding deleted objects.
        Map<Long, List<Long>> children = new HashMap<>();
        for (Yaffs2Node.Header header : headers.values()) {
            if (Yaffs2Node.isDeletedParent(header.parentId())) {
                continue;
            }
            children.computeIfAbsent(header.parentId(), k -> new ArrayList<>())
                    .add(header.objId());
        }
        children.values().forEach(l -> l.sort(Comparator.comparingLong(a -> a)));

        return new ScanResult(headers, dataChunks, children, hardlinkTargets);
    }

    private static Yaffs2Node.Header parseHeader(byte[] head, long objId, boolean beData) {
        int type = (int) Yaffs2Node.dataU32(head, Yaffs2Node.HDR_TYPE, beData);
        if (!Yaffs2Node.isKnownType(type)) {
            return null;
        }
        long parent = Yaffs2Node.normalizeId(
                Yaffs2Node.dataU32(head, Yaffs2Node.HDR_PARENT, beData));
        int mode = (int) Yaffs2Node.dataU32(head, Yaffs2Node.HDR_MODE, beData);
        long uid = Yaffs2Node.dataU32(head, Yaffs2Node.HDR_UID, beData);
        long gid = Yaffs2Node.dataU32(head, Yaffs2Node.HDR_GID, beData);
        long mtime = Yaffs2Node.dataU32(head, Yaffs2Node.HDR_MTIME, beData);
        long fileSize = Yaffs2Node.dataU32(head, Yaffs2Node.HDR_FILE_SIZE, beData);
        long equivId = Yaffs2Node.normalizeId(
                Yaffs2Node.dataU32(head, Yaffs2Node.HDR_EQUIV_ID, beData));
        long rdev = Yaffs2Node.dataU32(head, Yaffs2Node.HDR_RDEV, beData);

        int nameLen = 0;
        int nameEnd = Math.min(Yaffs2Node.HDR_NAME + Yaffs2Node.HDR_NAME_LEN, head.length);
        for (int i = Yaffs2Node.HDR_NAME; i < nameEnd; i++) {
            if (head[i] == 0) {
                break;
            }
            nameLen++;
        }
        String name = new String(head, Yaffs2Node.HDR_NAME, nameLen, StandardCharsets.UTF_8);

        String alias = "";
        if (type == Yaffs2Node.TYPE_SYMLINK) {
            int aliasLen = 0;
            int aliasEnd = Math.min(Yaffs2Node.HDR_ALIAS + Yaffs2Node.HDR_ALIAS_LEN, head.length);
            for (int i = Yaffs2Node.HDR_ALIAS; i < aliasEnd; i++) {
                if (head[i] == 0) {
                    break;
                }
                aliasLen++;
            }
            alias = new String(head, Yaffs2Node.HDR_ALIAS, aliasLen, StandardCharsets.UTF_8);
        }

        return new Yaffs2Node.Header(objId, type, parent, name, mode, uid, gid,
                mtime, fileSize, equivId, alias, rdev);
    }

    // ========================================================================
    // Entry construction
    // ========================================================================

    private Optional<FileSystemEntry> entryFor(long objId, String path) throws IOException {
        return entryFor(objId, path, new java.util.HashSet<>());
    }

    private Optional<FileSystemEntry> entryFor(long objId, String path,
                                               java.util.Set<Long> hardlinkChain) throws IOException {
        Yaffs2Node.Header header = headers.get(objId);
        if (header == null) {
            return Optional.empty();
        }
        if (!isSafeName(header.name())) {
            return Optional.empty();
        }
        switch (header.type()) {
            case Yaffs2Node.TYPE_DIRECTORY:
                return directoryFor(header, objId, path);
            case Yaffs2Node.TYPE_FILE:
                return Optional.of(new Yaffs2RegularFile(header, objId, path));
            case Yaffs2Node.TYPE_SYMLINK:
                return Optional.of(new Yaffs2Symlink(header, objId, path));
            case Yaffs2Node.TYPE_HARDLINK: {
                long target = hardlinkTargets.getOrDefault(objId, header.equivId());
                if (target != 0 && target != objId) {
                    if (!hardlinkChain.add(objId) || hardlinkChain.size() > 64) {
                        // Hardlink cycle: fail checked rather than recurse forever.
                        throw new IOException("yaffs2 hardlink cycle at object " + objId);
                    }
                    return entryFor(target, path, hardlinkChain)
                            .map(t -> new HardlinkEntry(t, header.name(), path));
                }
                // Unresolvable hardlink: expose as an empty regular file.
                return Optional.of(new Yaffs2RegularFile(header, objId, path));
            }
            case Yaffs2Node.TYPE_SPECIAL:
                return Optional.of(new Yaffs2SpecialFile(header, objId, path));
            default:
                return Optional.empty();
        }
    }

    private Optional<FileSystemEntry> directoryFor(Yaffs2Node.Header header,
                                                   long objId, String path) {
        return Optional.of(new Yaffs2Directory(header, objId, path));
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
    // File content
    // ========================================================================

    /**
     * Returns the effective size of a file: the greatest chunk extent,
     * {@code (chunkId - 1) * page + chunkLength}. Header file_size fields
     * are NOT trusted (old-style headers leave them uninitialized).
     */
    private long fileSize(Yaffs2Node.Header header, long objId) {
        Map<Long, long[]> chunks = dataChunks.get(objId);
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        long size = 0;
        for (Map.Entry<Long, long[]> e : chunks.entrySet()) {
            long extent = SafeMath.safeAdd(
                    SafeMath.safeMultiply(e.getKey() - 1, superblock.pageSize()),
                    e.getValue()[1]);
            if (extent > size) {
                size = extent;
            }
        }
        return size;
    }

    private InputStream openFileStream(Yaffs2Node.Header header, long objId)
            throws IOException {
        long size = fileSize(header, objId);
        Map<Long, long[]> chunks = dataChunks.getOrDefault(objId, Map.of());
        List<long[]> ranges = new ArrayList<>(chunks.size());
        // [chunkId, dataOffset, length, logicalStart]
        chunks.forEach((id, range) -> ranges.add(new long[] {
                id, range[0], range[1],
                SafeMath.safeMultiply(id - 1, superblock.pageSize())}));
        ranges.sort(Comparator.comparingLong(a -> a[0]));
        return new InputStream() {
            private long pos = 0;
            private int rangeIdx = 0;
            private byte[] current = null;

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
                    while (rangeIdx < ranges.size()
                            && pos >= ranges.get(rangeIdx)[3] + ranges.get(rangeIdx)[2]) {
                        rangeIdx++;
                        current = null;
                    }
                    if (rangeIdx >= ranges.size()
                            || pos < ranges.get(rangeIdx)[3]) {
                        // Hole (or trailing zeros): fill to the next range.
                        long next = rangeIdx < ranges.size()
                                ? ranges.get(rangeIdx)[3] : size;
                        int n = (int) Math.min(next - pos, len - total);
                        Arrays.fill(b, off + total, off + total + n, (byte) 0);
                        pos += n;
                        total += n;
                        continue;
                    }
                    long[] r = ranges.get(rangeIdx);
                    if (current == null) {
                        if (r[2] < 0 || r[2] > MAX_READABLE_SIZE) {
                            throw new ResourceLimitException("YAFFS2 chunk too large: "
                                    + r[2] + " bytes (limit: 16 MB).",
                                    "allocation_size", MAX_READABLE_SIZE, r[2]);
                        }
                        current = new byte[(int) r[2]];
                        ByteBuffer buf = region.read(r[1], current.length);
                        buf.get(current);
                    }
                    int within = (int) (pos - r[3]);
                    int n = (int) Math.min(current.length - within,
                            Math.min(len - total, size - pos));
                    System.arraycopy(current, within, b, off + total, n);
                    pos += n;
                    total += n;
                }
                return total == 0 ? -1 : total;
            }
        };
    }

    // ========================================================================
    // Entry implementations
    // ========================================================================

    private final class Yaffs2Directory implements FileSystemEntry.Directory {
        private final Yaffs2Node.Header header;
        private final long objId;
        private final String name;
        private final String path;

        Yaffs2Directory(Yaffs2Node.Header header, long objId, String path) {
            this.header = header;
            this.objId = objId;
            this.name = header.name().isEmpty() ? "/" : header.name();
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
            return mtime(header);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return headerAttributes(header);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<Long> ids = children.getOrDefault(objId, List.of());
            List<FileSystemEntry> result = new ArrayList<>(ids.size());
            for (long child : ids) {
                String childPath = path.equals("/") ? "/" + nameOf(child) : path + "/" + nameOf(child);
                entryFor(child, childPath).ifPresent(result::add);
            }
            result.sort(Comparator.comparing(FileSystemEntry::name));
            return result.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            List<Long> ids = children.getOrDefault(objId, List.of());
            for (long child : ids) {
                if (name.equals(nameOf(child))) {
                    String childPath = path.equals("/") ? "/" + name : path + "/" + name;
                    return entryFor(child, childPath);
                }
            }
            return Optional.empty();
        }

        private String nameOf(long child) {
            Yaffs2Node.Header h = headers.get(child);
            return h == null ? "" : h.name();
        }
    }

    private final class Yaffs2RegularFile implements FileSystemEntry.RegularFile {
        final Yaffs2Node.Header header;
        final long objId;
        private final String name;
        private final String path;

        Yaffs2RegularFile(Yaffs2Node.Header header, long objId, String path) {
            this.header = header;
            this.objId = objId;
            this.name = header.name();
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
            return fileSize(header, objId);
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return mtime(header);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return headerAttributes(header);
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return openFileStream(header, objId);
        }

        @Override
        public byte @NotNull [] readAllBytes() throws IOException {
            long size = fileSize(header, objId);
            if (size > MAX_READABLE_SIZE) {
                throw new ResourceLimitException("yaffs2 file too large to read into memory: "
                        + size + " bytes (limit: 16 MB). Use openStream() for large files.",
                        "allocation_size", MAX_READABLE_SIZE, size);
            }
            byte[] content = new byte[(int) size];
            try (InputStream in = openFileStream(header, objId)) {
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
    }

    private final class Yaffs2Symlink implements FileSystemEntry.SymbolicLink {
        final Yaffs2Node.Header header;
        final long objId;
        private final String name;
        private final String path;

        Yaffs2Symlink(Yaffs2Node.Header header, long objId, String path) {
            this.header = header;
            this.objId = objId;
            this.name = header.name();
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
            return target().length();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return mtime(header);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return headerAttributes(header);
        }

        @Override
        public @NotNull String target() {
            if (!header.alias().isEmpty()) {
                return header.alias();
            }
            try {
                byte[] data = ((Yaffs2RegularFile) new Yaffs2RegularFile(header, objId, path))
                        .readAllBytes();
                return new String(data, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        public @NotNull Optional<FileSystemEntry> resolve() throws IOException {
            String t = target();
            if (t.isEmpty()) {
                return Optional.empty();
            }
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
            return Yaffs2FileSystemImpl.this.resolve(t);
        }
    }

    private static final class HardlinkEntry implements FileSystemEntry.RegularFile {
        private final FileSystemEntry target;
        private final String name;
        private final String path;

        HardlinkEntry(FileSystemEntry target, String name, String path) {
            this.target = target;
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
            return target.size();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return target.creationTime();
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return target.modificationTime();
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return target.accessTime();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return target.attributes();
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return ((FileSystemEntry.RegularFile) target).openStream();
        }

        @Override
        public byte @NotNull [] readAllBytes() throws IOException {
            return ((FileSystemEntry.RegularFile) target).readAllBytes();
        }
    }

    private final class Yaffs2SpecialFile implements FileSystemEntry.SpecialFile {
        private final Yaffs2Node.Header header;
        private final String name;
        private final String path;

        Yaffs2SpecialFile(Yaffs2Node.Header header, long objId, String path) {
            this.header = header;
            this.name = header.name();
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
            return mtime(header);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return headerAttributes(header);
        }

        @Override
        public @NotNull FileSystemEntry.EntryType type() {
            long dev = header.rdev();
            long mode = header.mode();
            if ((mode & 0xf000) == 0x2000 || (dev != 0 && (mode & 0xf000) == 0)) {
                return FileSystemEntry.EntryType.CHARACTER_DEVICE;
            }
            if ((mode & 0xf000) == 0x6000) {
                return FileSystemEntry.EntryType.BLOCK_DEVICE;
            }
            return FileSystemEntry.EntryType.FIFO;
        }

        @Override
        public @NotNull Optional<Integer> majorDevice() {
            long dev = header.rdev();
            return Optional.of((int) ((dev >> 8) & 0xfff));
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            long dev = header.rdev();
            return Optional.of((int) ((dev & 0xff) | ((dev >> 12) & 0xfff00)));
        }
    }

    private static Optional<Instant> mtime(Yaffs2Node.Header header) {
        if (header.mtime() == 0) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(header.mtime()));
    }

    private static Map<String, Object> headerAttributes(Yaffs2Node.Header header) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (header.mode() != 0) {
            attrs.put("mode", String.format("%o", header.mode() & 0xfff));
        }
        attrs.put("uid", header.uid());
        attrs.put("gid", header.gid());
        attrs.put("objId", header.objId());
        return Map.copyOf(attrs);
    }

    // ========================================================================
    // FileSystem API
    // ========================================================================

    @Override
    public @NotNull FileSystemEntry.Directory root() {
        Yaffs2Node.Header rootHeader = headers.get(Yaffs2Node.ROOT_OBJ_ID);
        if (rootHeader == null) {
            rootHeader = new Yaffs2Node.Header(Yaffs2Node.ROOT_OBJ_ID,
                    Yaffs2Node.TYPE_DIRECTORY, Yaffs2Node.ROOT_OBJ_ID, "/",
                    0x41ed, 0, 0, 0, 0, 0, "", 0);
        }
        return new Yaffs2Directory(rootHeader, Yaffs2Node.ROOT_OBJ_ID, "/");
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
                                                  Set<Long> visited) throws IOException {
        if (maxDepth <= 0) {
            return Stream.of(dir);
        }
        if (dir instanceof Yaffs2Directory yd && !visited.add(yd.objId)) {
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
        return superblock.totalSize();
    }

    @Override
    public long usedSize() {
        return superblock.totalSize();
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
        meta.put("version", "yaffs2");
        meta.put("pageSize", String.valueOf(superblock.pageSize()));
        meta.put("spareSize", String.valueOf(superblock.spareSize()));
        meta.put("chunkCount", String.valueOf(superblock.chunkCount()));
        meta.put("endian", superblock.bigEndianTags() ? "big" : "little");
        meta.put("objectCount", String.valueOf(headers.size()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        // Nothing to close; the region is managed externally.
    }
}
