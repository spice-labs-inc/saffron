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
package io.spicelabs.saffron.filesystem.jffs2;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.anarres.lzo.LzoDecompressor1x;
import org.anarres.lzo.lzo_uintp;
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
import java.util.Comparator;
import java.util.HashMap;
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
 * Read-only JFFS2 filesystem implementation.
 *
 * <p>JFFS2 is a log-structured flash filesystem with no superblock. Mounting
 * scans the whole region for nodes and resolves the log semantics: for each
 * directory entry the highest version wins (an entry whose latest version has
 * ino 0 is deleted), and for each file byte range the highest-version
 * fragment wins. Unwritten ranges read as zeros (sparse files).
 *
 * <p>Hardening invariants:
 * <ul>
 *   <li>Node lengths are always bounds-checked against the region size before
 *       any allocation is derived from them;</li>
 *   <li>nodes with an invalid header or node CRC are skipped (matching the
 *       kernel's behaviour), never trusted;</li>
 *   <li>decompressed fragment sizes are capped at the region size;</li>
 *   <li>unsupported compression ids fail with a checked {@link IOException};</li>
 *   <li>entry names containing {@code /}, {@code \}, {@code ..}, or NUL are
 *       dropped (path-traversal hardening).</li>
 * </ul>
 */
public final class Jffs2FileSystemImpl implements FileSystem.Jffs2FileSystem {

    /** Maximum default walk depth (hostile trees must not overflow the stack). */
    private static final int MAX_WALK_DEPTH = 512;

    /** Memory budget: no single file read > 16 MiB. */
    private static final long MAX_READABLE_SIZE = 16 * 1024 * 1024;

    private static final int MAX_SYMLINK_DEPTH = 40;

    /** Upper bound on a single fragment's decompressed size (see decompress()). */
    private static final long MAX_FRAGMENT_SIZE = 1024 * 1024;

    private final DiskRegion region;
    private final long totalSize;
    private final long usedSize;
    private final long nodeCount;
    private final Map<Long, Jffs2Node.InodeMeta> inodeMeta;
    private final Map<Long, List<Jffs2Node.InodeFragment>> fragments;
    private final Map<Long, Map<String, Jffs2Node.Dirent>> dirents;

    private Jffs2FileSystemImpl(DiskRegion region, ScanResult scan) {
        this.region = region;
        this.totalSize = scan.size;
        this.usedSize = scan.usedSize;
        this.nodeCount = scan.nodeCount;
        this.inodeMeta = scan.inodeMeta;
        this.fragments = scan.fragments;
        this.dirents = scan.dirents;
    }

    /**
     * Mounts a JFFS2 filesystem from a virtual disk at a partition offset.
     *
     * @param disk the virtual disk
     * @param partitionOffset the byte offset where the filesystem starts
     * @return the mounted filesystem
     * @throws IOException if the region does not contain a JFFS2 filesystem
     *         or an I/O error occurs
     */
    public static @NotNull Jffs2FileSystemImpl mount(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Mounts a JFFS2 filesystem from a disk region.
     *
     * @param region the region containing the filesystem
     * @return the mounted filesystem
     * @throws IOException if the region does not contain a JFFS2 filesystem
     *         or an I/O error occurs
     */
    public static @NotNull Jffs2FileSystemImpl mount(@NotNull DiskRegion region) throws IOException {
        ScanResult scan = scan(region);
        if (scan.nodeCount == 0) {
            throw new IOException("Not a valid jffs2 filesystem: no valid nodes found");
        }
        return new Jffs2FileSystemImpl(region, scan);
    }

    // ========================================================================
    // Node scan
    // ========================================================================

    private record ScanResult(
            Map<Long, Jffs2Node.InodeMeta> inodeMeta,
            Map<Long, List<Jffs2Node.InodeFragment>> fragments,
            Map<Long, Map<String, Jffs2Node.Dirent>> dirents,
            long usedSize,
            long nodeCount,
            long size) {
    }

    private static ScanResult scan(DiskRegion region) throws IOException {
        long size = region.size();
        Map<Long, Jffs2Node.InodeMeta> meta = new HashMap<>();
        Map<Long, List<Jffs2Node.InodeFragment>> frags = new HashMap<>();
        Map<Long, Map<String, Jffs2Node.Dirent>> dirents = new HashMap<>();
        long used = 0;
        long nodes = 0;

        long offset = 0;
        while (offset + Jffs2Node.COMMON_HEADER_SIZE <= size) {
            ByteBuffer hdr = region.read(offset, Jffs2Node.COMMON_HEADER_SIZE);
            hdr.order(ByteOrder.LITTLE_ENDIAN);

            int magic = hdr.getShort(0) & 0xffff;
            if (magic != Jffs2Node.MAGIC) {
                // 0xFF erase-block padding or end of the node area.
                break;
            }

            int nodetype = hdr.getShort(2) & 0xffff;
            long totlen = hdr.getInt(4) & 0xffffffffL;
            if (totlen < Jffs2Node.COMMON_HEADER_SIZE) {
                break; // Untrustworthy length: cannot continue safely.
            }
            // Nodes advance by their 4-byte-aligned length: real images store
            // the true (possibly unaligned) totlen and pad the body with 0xFF.
            long advance = SafeMath.alignUp(totlen, 4);
            if (advance > size - offset) {
                break; // Truncated node: stop, do not follow its length.
            }

            byte[] hdrBytes = new byte[8];
            hdr.position(0);
            hdr.get(hdrBytes);
            int storedHdrCrc = hdr.getInt(8);
            if (storedHdrCrc != Jffs2Node.crc32(hdrBytes, 0, hdrBytes.length)) {
                // Corrupt header: skip the node (kernel behaviour), the
                // length has already been bounds-checked.
                offset += advance;
                continue;
            }

            nodes++;
            used = SafeMath.safeAdd(used, totlen);

            if (nodetype == Jffs2Node.NODETYPE_INODE && totlen >= Jffs2Node.INODE_HEADER_SIZE) {
                parseInodeNode(region, offset, totlen, meta, frags);
            } else if (nodetype == Jffs2Node.NODETYPE_DIRENT && totlen >= Jffs2Node.DIRENT_HEADER_SIZE) {
                parseDirentNode(region, offset, totlen, dirents);
            }
            // CLEANMARKER, PADDING, SUMMARY, XATTR, XREF, unknown → skip.

            offset += advance;
        }

        return new ScanResult(meta, frags, dirents, used, nodes, size);
    }

    private static void parseInodeNode(DiskRegion region, long offset, long totlen,
                                       Map<Long, Jffs2Node.InodeMeta> meta,
                                       Map<Long, List<Jffs2Node.InodeFragment>> frags)
            throws IOException {
        ByteBuffer node = region.read(offset, Jffs2Node.INODE_HEADER_SIZE);
        node.order(ByteOrder.LITTLE_ENDIAN);

        // node_crc covers bytes 0..59 (common header + inode fields through flags).
        byte[] crcTarget = new byte[60];
        node.position(0);
        node.get(crcTarget);
        int storedNodeCrc = node.getInt(64);
        if (storedNodeCrc != Jffs2Node.crc32(crcTarget, 0, crcTarget.length)) {
            return; // Corrupt node: skip.
        }

        long ino = node.getInt(12) & 0xffffffffL;
        long version = node.getInt(16) & 0xffffffffL;
        int mode = node.getInt(20);
        int uid = node.getShort(24) & 0xffff;
        int gid = node.getShort(26) & 0xffff;
        long isize = node.getInt(28) & 0xffffffffL;
        long mtime = node.getInt(36) & 0xffffffffL;
        long fragOffset = node.getInt(44) & 0xffffffffL;
        long csize = node.getInt(48) & 0xffffffffL;
        long dsize = node.getInt(52) & 0xffffffffL;
        int compr = node.get(56) & 0xff;

        long dataOffset = SafeMath.safeAdd(offset, Jffs2Node.INODE_HEADER_SIZE);
        if (csize < 0 || dataOffset + csize > offset + totlen) {
            return; // Payload exceeds the node: skip.
        }

        Jffs2Node.InodeMeta existing = meta.get(ino);
        if (existing == null || version > existing.version()) {
            meta.put(ino, new Jffs2Node.InodeMeta(ino, version, mode, uid, gid, isize, mtime));
        }
        frags.computeIfAbsent(ino, k -> new ArrayList<>())
                .add(new Jffs2Node.InodeFragment(ino, version, fragOffset, csize, dsize, compr, dataOffset));
    }

    private static void parseDirentNode(DiskRegion region, long offset, long totlen,
                                        Map<Long, Map<String, Jffs2Node.Dirent>> dirents)
            throws IOException {
        ByteBuffer node = region.read(offset, Jffs2Node.DIRENT_HEADER_SIZE);
        node.order(ByteOrder.LITTLE_ENDIAN);

        // node_crc covers bytes 0..31 (common header + fields through unused).
        byte[] crcTarget = new byte[32];
        node.position(0);
        node.get(crcTarget);
        int storedNodeCrc = node.getInt(32);
        if (storedNodeCrc != Jffs2Node.crc32(crcTarget, 0, crcTarget.length)) {
            return; // Corrupt node: skip.
        }

        long pino = node.getInt(12) & 0xffffffffL;
        long version = node.getInt(16) & 0xffffffffL;
        long ino = node.getInt(20) & 0xffffffffL;
        int nsize = node.get(28) & 0xff;
        int type = node.get(29) & 0xff;
        int storedNameCrc = node.getInt(36);

        if (nsize == 0 || nsize > Jffs2Node.MAX_NAME_LEN
                || nsize > totlen - Jffs2Node.DIRENT_HEADER_SIZE) {
            return; // Implausible name length: skip.
        }

        long nameOffset = SafeMath.safeAdd(offset, Jffs2Node.DIRENT_HEADER_SIZE);
        ByteBuffer nameBuf = region.read(nameOffset, nsize);
        byte[] nameBytes = new byte[nsize];
        nameBuf.get(nameBytes);
        if (storedNameCrc != Jffs2Node.crc32(nameBytes, 0, nameBytes.length)) {
            return; // Corrupt name: skip.
        }

        String name = new String(nameBytes, StandardCharsets.UTF_8);
        if (!isSafeName(name)) {
            return; // Path-traversal hardening.
        }

        Map<String, Jffs2Node.Dirent> byName = dirents.computeIfAbsent(pino, k -> new HashMap<>());
        Jffs2Node.Dirent existing = byName.get(name);
        if (existing == null || version > existing.version()) {
            byName.put(name, new Jffs2Node.Dirent(pino, version, ino, type, name));
        }
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
    // Entry construction
    // ========================================================================

    private Optional<FileSystemEntry> entryFor(Jffs2Node.Dirent dirent, String path)
            throws IOException {
        if (dirent.ino() == 0) {
            return Optional.empty(); // Deleted entry.
        }
        if (dirent.ino() != Jffs2Node.ROOT_INO && !inodeMeta.containsKey(dirent.ino())) {
            // The dirent references an inode with no valid inode node:
            // treat the entry as non-existent (kernel lookup would fail).
            return Optional.empty();
        }
        int type = dirent.type();
        if (type == Jffs2Node.DT_UNKNOWN) {
            Jffs2Node.InodeMeta meta = inodeMeta.get(dirent.ino());
            type = dtFromMode(meta == null ? 0 : meta.mode());
        }
        return switch (type) {
            case Jffs2Node.DT_DIR -> Optional.of(new Jffs2Directory(dirent.ino(), dirent.name(), path));
            case Jffs2Node.DT_REG -> Optional.of(new Jffs2RegularFile(dirent.ino(), dirent.name(), path));
            case Jffs2Node.DT_LNK -> Optional.of(new Jffs2Symlink(
                    dirent.ino(), dirent.name(), path,
                    readSymlinkTarget(dirent.ino())));
            case Jffs2Node.DT_FIFO, Jffs2Node.DT_CHR, Jffs2Node.DT_BLK, Jffs2Node.DT_SOCK ->
                    Optional.of(new Jffs2SpecialFile(dirent.ino(), dirent.name(), path, type));
            default -> Optional.empty();
        };
    }

    private static int dtFromMode(int mode) {
        return switch (mode & Jffs2Node.S_IFMT) {
            case Jffs2Node.S_IFDIR -> Jffs2Node.DT_DIR;
            case Jffs2Node.S_IFREG -> Jffs2Node.DT_REG;
            case Jffs2Node.S_IFLNK -> Jffs2Node.DT_LNK;
            case Jffs2Node.S_IFCHR -> Jffs2Node.DT_CHR;
            case Jffs2Node.S_IFBLK -> Jffs2Node.DT_BLK;
            case Jffs2Node.S_IFIFO -> Jffs2Node.DT_FIFO;
            case Jffs2Node.S_IFSOCK -> Jffs2Node.DT_SOCK;
            default -> Jffs2Node.DT_UNKNOWN;
        };
    }

    // ========================================================================
    // File content assembly
    // ========================================================================

    /**
     * Resolves the fragment list for an inode: for each byte range the
     * highest version wins, and fragments are ordered by offset.
     */
    private List<Jffs2Node.InodeFragment> resolveFragments(long ino) {
        List<Jffs2Node.InodeFragment> fs = fragments.get(ino);
        if (fs == null || fs.isEmpty()) {
            return List.of();
        }
        List<Jffs2Node.InodeFragment> sorted = new ArrayList<>(fs);
        sorted.sort(Comparator.comparingLong(Jffs2Node.InodeFragment::offset)
                .thenComparingLong(Jffs2Node.InodeFragment::version));

        List<Jffs2Node.InodeFragment> resolved = new ArrayList<>(sorted.size());
        Jffs2Node.InodeFragment last = null;
        for (Jffs2Node.InodeFragment f : sorted) {
            if (last != null && last.offset() == f.offset()) {
                if (f.version() > last.version()) {
                    resolved.set(resolved.size() - 1, f);
                    last = f;
                }
            } else {
                resolved.add(f);
                last = f;
            }
        }
        return resolved;
    }

    /**
     * Opens a lazy stream over the resolved file content. Fragments are
     * decompressed on demand and unwritten ranges read as zeros, so memory
     * usage is bounded by the largest fragment rather than the logical file
     * size (which can legitimately exceed the image size because of
     * compression, in particular COMPR_ZERO payloads).
     */
    private InputStream openFileStream(long ino) throws IOException {
        Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
        if (meta == null) {
            return new ByteArrayInputStream(new byte[0]);
        }
        long isize = meta.isize();
        List<Jffs2Node.InodeFragment> resolved = resolveFragments(ino);
        return new InputStream() {
            private long pos = 0;
            private Jffs2Node.InodeFragment currentFrag = null;
            private byte[] currentData = null;

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
                if (pos >= isize) {
                    return -1;
                }
                int total = 0;
                while (total < len && pos < isize) {
                    Jffs2Node.InodeFragment frag = fragmentCovering(resolved, pos);
                    if (frag == null) {
                        // Gap: zeros up to the next fragment (or EOF).
                        long next = nextFragmentOffset(resolved, pos);
                        long gap = Math.min(next, isize) - pos;
                        int n = (int) Math.min(gap, len - total);
                        Arrays.fill(b, off + total, off + total + n, (byte) 0);
                        pos += n;
                        total += n;
                        continue;
                    }
                    if (currentFrag != frag) {
                        currentFrag = frag;
                        currentData = decompress(frag);
                    }
                    long fragPos = pos - frag.offset();
                    if (fragPos >= currentData.length) {
                        // Past this fragment's end: resume at its tail.
                        pos = Math.min(frag.offset() + currentData.length, isize);
                        continue;
                    }
                    int n = (int) Math.min(currentData.length - fragPos, len - total);
                    System.arraycopy(currentData, (int) fragPos, b, off + total, n);
                    pos += n;
                    total += n;
                }
                return total;
            }
        };
    }

    private static Jffs2Node.InodeFragment fragmentCovering(
            List<Jffs2Node.InodeFragment> resolved, long pos) {
        for (Jffs2Node.InodeFragment f : resolved) {
            if (pos < f.offset()) {
                return null;
            }
            if (pos < f.offset() + f.dsize()) {
                return f;
            }
        }
        return null;
    }

    private static long nextFragmentOffset(List<Jffs2Node.InodeFragment> resolved, long pos) {
        for (Jffs2Node.InodeFragment f : resolved) {
            if (f.offset() > pos) {
                return f.offset();
            }
        }
        return Long.MAX_VALUE;
    }

    private byte[] readAllContent(long ino) throws IOException {
        Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
        if (meta == null) {
            return new byte[0];
        }
        long isize = meta.isize();
        if (isize > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("JFFS2 file too large to read into memory: "
                    + isize + " bytes (limit: 16 MB). Use openStream() for large files.",
                    "allocation_size", MAX_READABLE_SIZE, isize);
        }
        byte[] content = new byte[(int) isize];
        try (InputStream in = openFileStream(ino)) {
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

    /**
     * Reads a symlink target. Kernel symlink targets are bounded by PATH_MAX;
     * anything larger is rejected as corrupt.
     */
    private String readSymlinkTarget(long ino) throws IOException {
        Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
        if (meta == null || meta.isize() > 4096) {
            throw new IOException("JFFS2 symlink " + ino + " target too large or missing inode");
        }
        return new String(readAllContent(ino), StandardCharsets.UTF_8);
    }

    private byte[] decompress(Jffs2Node.InodeFragment frag) throws IOException {
        long dsize = frag.dsize();
        // Real JFFS2 implementations never write a data node larger than the
        // target flash page (default 4 KiB; 1 MiB is far beyond any real page
        // size). This caps the allocation derived from an untrusted field.
        if (dsize > MAX_FRAGMENT_SIZE) {
            throw new IOException("JFFS2 fragment decompressed size " + dsize
                    + " exceeds the maximum plausible page size " + MAX_FRAGMENT_SIZE);
        }
        if (dsize > Integer.MAX_VALUE) {
            throw new IOException("JFFS2 fragment too large: " + dsize);
        }
        int outLen = (int) dsize;
        byte[] out = new byte[outLen];

        switch (frag.compr()) {
            case Jffs2Node.COMPR_NONE, Jffs2Node.COMPR_COPY -> {
                if (frag.csize() != dsize) {
                    throw new IOException("JFFS2 uncompressed fragment size mismatch: csize="
                            + frag.csize() + " dsize=" + dsize);
                }
                region.read(frag.dataOffset(), outLen).get(out);
            }
            case Jffs2Node.COMPR_ZERO -> {
                // No payload: `out` is already all zeros.
            }
            case Jffs2Node.COMPR_ZLIB -> {
                byte[] payload = readPayload(frag);
                Inflater inflater = new Inflater();
                inflater.setInput(payload);
                int written;
                try {
                    written = inflater.inflate(out);
                } catch (DataFormatException e) {
                    inflater.end();
                    throw new IOException("JFFS2 zlib decompression failed", e);
                }
                inflater.end();
                if (written != outLen) {
                    throw new IOException("JFFS2 zlib decompression size mismatch: expected "
                            + outLen + " got " + written);
                }
            }
            case Jffs2Node.COMPR_LZO -> {
                byte[] payload = readPayload(frag);
                lzo_uintp written = new lzo_uintp(outLen);
                int result = LzoDecompressor1x.decompress(payload, 0, payload.length,
                        out, 0, written, null);
                if (result != 0) {
                    throw new IOException("JFFS2 LZO decompression failed: " + result);
                }
                if (written.value != outLen) {
                    throw new IOException("JFFS2 LZO decompression size mismatch: expected "
                            + outLen + " got " + written.value);
                }
            }
            case Jffs2Node.COMPR_RTIME -> {
                byte[] payload = readPayload(frag);
                rtimeDecompress(payload, out);
            }
            default -> throw new IOException("Unsupported JFFS2 compression id: "
                    + frag.compr());
        }
        return out;
    }

    private byte[] readPayload(Jffs2Node.InodeFragment frag) throws IOException {
        if (frag.csize() > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("JFFS2 fragment payload too large: "
                    + frag.csize() + " bytes (limit: 16 MB).",
                    "allocation_size", MAX_READABLE_SIZE, frag.csize());
        }
        byte[] payload = new byte[(int) frag.csize()];
        region.read(frag.dataOffset(), payload.length).get(payload);
        return payload;
    }

    /**
     * Kernel rtime decompression ({@code fs/jffs2/compr_rtime.c}): the stream
     * is a sequence of (verbatim byte, repeat count) pairs; a repeat copies
     * from the last-occurrence position of that byte value in the output.
     */
    private static void rtimeDecompress(byte[] in, byte[] out) throws IOException {
        int[] positions = new int[256];
        int outpos = 0;
        int pos = 0;
        while (outpos < out.length) {
            if (pos >= in.length) {
                throw new IOException("JFFS2 rtime stream truncated");
            }
            int value = in[pos++] & 0xff;
            out[outpos++] = (byte) value;
            if (pos >= in.length) {
                throw new IOException("JFFS2 rtime stream truncated");
            }
            int repeat = in[pos++] & 0xff;
            int backoffs = positions[value];
            positions[value] = outpos;
            if (repeat != 0) {
                if (outpos + repeat > out.length) {
                    throw new IOException("JFFS2 rtime repeat exceeds output size");
                }
                if (backoffs + repeat >= outpos) {
                    while (repeat-- > 0) {
                        out[outpos++] = out[backoffs++];
                    }
                } else {
                    System.arraycopy(out, backoffs, out, outpos, repeat);
                    outpos += repeat;
                }
            }
        }
    }

    // ========================================================================
    // Entry implementations
    // ========================================================================

    private final class Jffs2Directory implements FileSystemEntry.Directory {
        private final long ino;
        private final String name;
        private final String path;

        Jffs2Directory(long ino, String name, String path) {
            this.ino = ino;
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
            return inodeTime(ino, false);
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return inodeTime(ino, true);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return inodeAttributes(ino);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            Map<String, Jffs2Node.Dirent> entries = dirents.getOrDefault(ino, Map.of());
            List<FileSystemEntry> result = new ArrayList<>(entries.size());
            for (Map.Entry<String, Jffs2Node.Dirent> e : entries.entrySet()) {
                if (e.getValue().ino() == 0) {
                    continue; // Deleted.
                }
                String childPath = path.equals("/") ? "/" + e.getKey() : path + "/" + e.getKey();
                entryFor(e.getValue(), childPath).ifPresent(result::add);
            }
            result.sort(Comparator.comparing(FileSystemEntry::name));
            return result.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            Map<String, Jffs2Node.Dirent> entries = dirents.getOrDefault(ino, Map.of());
            Jffs2Node.Dirent dirent = entries.get(name);
            if (dirent == null || dirent.ino() == 0) {
                return Optional.empty();
            }
            String childPath = path.equals("/") ? "/" + name : path + "/" + name;
            return entryFor(dirent, childPath);
        }
    }

    private final class Jffs2RegularFile implements FileSystemEntry.RegularFile {
        private final long ino;
        private final String name;
        private final String path;

        Jffs2RegularFile(long ino, String name, String path) {
            this.ino = ino;
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
            Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
            return meta == null ? 0 : meta.isize();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return inodeTime(ino, false);
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return inodeTime(ino, true);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return inodeAttributes(ino);
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return openFileStream(ino);
        }

        @Override
        public byte @NotNull [] readAllBytes() throws IOException {
            return readAllContent(ino);
        }
    }

    private final class Jffs2Symlink implements FileSystemEntry.SymbolicLink {
        private final long ino;
        private final String name;
        private final String path;
        private final String target;

        Jffs2Symlink(long ino, String name, String path, String target) {
            this.ino = ino;
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
            Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
            return meta == null ? 0 : meta.isize();
        }

        @Override
        public @NotNull Optional<Instant> creationTime() {
            return inodeTime(ino, false);
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return inodeTime(ino, true);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return inodeAttributes(ino);
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
            return Jffs2FileSystemImpl.this.resolve(t);
        }
    }

    private final class Jffs2SpecialFile implements FileSystemEntry.SpecialFile {
        private final long ino;
        private final String name;
        private final String path;
        private final int direntType;

        Jffs2SpecialFile(long ino, String name, String path, int direntType) {
            this.ino = ino;
            this.name = name;
            this.path = path;
            this.direntType = direntType;
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
            return inodeTime(ino, false);
        }

        @Override
        public @NotNull Optional<Instant> modificationTime() {
            return inodeTime(ino, true);
        }

        @Override
        public @NotNull Optional<Instant> accessTime() {
            return Optional.empty();
        }

        @Override
        public @NotNull Map<String, Object> attributes() {
            return inodeAttributes(ino);
        }

        @Override
        public @NotNull FileSystemEntry.EntryType type() {
            return switch (direntType) {
                case Jffs2Node.DT_CHR -> FileSystemEntry.EntryType.CHARACTER_DEVICE;
                case Jffs2Node.DT_BLK -> FileSystemEntry.EntryType.BLOCK_DEVICE;
                case Jffs2Node.DT_FIFO -> FileSystemEntry.EntryType.FIFO;
                default -> FileSystemEntry.EntryType.SOCKET;
            };
        }

        @Override
        public @NotNull Optional<Integer> majorDevice() {
            return deviceNumbers().map(n -> n[0]);
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            return deviceNumbers().map(n -> n[1]);
        }

        private Optional<int[]> deviceNumbers() {
            try {
                Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
                if (meta == null) {
                    return Optional.empty();
                }
                // The device id is the first 2 or 4 bytes of the inode data.
                byte[] payload;
                try (InputStream in = openFileStream(ino)) {
                    payload = in.readNBytes(4);
                }
                long dev;
                if (payload.length == 4) {
                    dev = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0) & 0xffffffffL;
                    int major = (int) ((dev >> 8) & 0xfff);
                    int minor = (int) ((dev & 0xff) | ((dev >> 12) & 0xfff00));
                    return Optional.of(new int[] {major, minor});
                }
                if (payload.length == 2) {
                    dev = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getShort(0) & 0xffffL;
                    int major = (int) ((dev >> 8) & 0xff);
                    int minor = (int) ((dev & 0xff) | ((dev >> 8) & 0xff00));
                    return Optional.of(new int[] {major, minor});
                }
                return Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }

    private Optional<Instant> inodeTime(long ino, boolean modification) {
        Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
        if (meta == null || meta.mtime() == 0) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(meta.mtime()));
    }

    private Map<String, Object> inodeAttributes(long ino) {
        Jffs2Node.InodeMeta meta = inodeMeta.get(ino);
        if (meta == null) {
            return Map.of();
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("mode", String.format("%o", meta.mode() & 0xfff));
        attrs.put("uid", meta.uid());
        attrs.put("gid", meta.gid());
        attrs.put("ino", meta.ino());
        return Map.copyOf(attrs);
    }

    // ========================================================================
    // FileSystem API
    // ========================================================================

    @Override
    public @NotNull FileSystemEntry.Directory root() {
        return new Jffs2Directory(Jffs2Node.ROOT_INO, "/", "/");
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
                                                  Set<Long> visited) throws IOException {
        if (maxDepth <= 0) {
            return Stream.of(dir);
        }
        if (dir instanceof Jffs2Directory jd && !visited.add(jd.ino)) {
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
        return totalSize;
    }

    @Override
    public long usedSize() {
        return usedSize;
    }

    @Override
    public long freeSize() {
        // JFFS2 has no reliable free-space count without emulating the GC;
        // report unknown as 0.
        return 0;
    }

    @Override
    public @NotNull Optional<String> label() {
        // JFFS2 has no volume label.
        return Optional.empty();
    }

    @Override
    public @NotNull Optional<String> uuid() {
        // JFFS2 has no filesystem UUID.
        return Optional.empty();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("version", "jffs2");
        meta.put("inodeCount", String.valueOf(inodeMeta.size()));
        meta.put("nodeCount", String.valueOf(nodeCount));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public void close() {
        // Nothing to close; the region is managed externally.
    }
}
