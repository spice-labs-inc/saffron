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
package io.spicelabs.saffron.filesystem.ubifs;

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
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import io.spicelabs.saffron.exception.ResourceLimitException;

/**
 * Read-only UBIFS implementation (cleanly-committed images).
 *
 * <p>Mount reads the superblock node (LEB 0), the newest valid master node
 * (LEB 1/2), then walks the index B-tree from the master's root reference,
 * collecting inode, directory-entry, and data nodes. Dirty (uncleanly
 * rebooted) images and encrypted/authenticated images are rejected with a
 * clear {@link IOException} (no journal replay, no key material).
 *
 * <p>Hardening: every node read is bounds-checked; index fanouts and node
 * lengths are capped; decompression output is bounded by the UBIFS block
 * size; names with path separators are dropped; walks are cycle-guarded.
 */
public final class UbifsFileSystemImpl implements FileSystem.UbifsFileSystem {

    /** Maximum default walk depth (hostile trees must not overflow the stack). */
    private static final int MAX_WALK_DEPTH = 512;

    /** Memory budget: no single file read > 16 MiB. */
    private static final long MAX_READABLE_SIZE = 16 * 1024 * 1024;

    private static final int MAX_SYMLINK_DEPTH = 40;
    private static final long MAX_NODE_LEN = 1024 * 1024;
    /** Offset of inline data within an inode node. */
    private static final int INLINE_DATA_OFFSET = 160;

    private final DiskRegion region;
    private final UbifsSuperblock superblock;
    private final int lebSize;
    private final long lebCnt;
    private final Map<Long, Inode> inodes;           // ino -> inode
    private final Map<Long, List<Dent>> dents;       // pino -> dents
    private final Map<Long, TreeMap<Long, DataNode>> data; // ino -> block -> node
    private final Map<Long, byte[]> inlineData;       // ino -> raw inline payload

    private record Inode(int mode, long uid, long gid, long size, long nlink,
                         int dataLen, int comprType) {
    }

    private record Dent(long pino, long inum, int type, String name) {
    }

    private record DataNode(long block, long size, int comprType, long payloadOffset,
                            long payloadLen) {
    }

    private UbifsFileSystemImpl(DiskRegion region, UbifsSuperblock sb,
                                Map<Long, Inode> inodes,
                                Map<Long, List<Dent>> dents,
                                Map<Long, TreeMap<Long, DataNode>> data,
                                Map<Long, byte[]> inlineData) {
        this.region = region;
        this.superblock = sb;
        this.lebSize = sb.sb().lebSize();
        this.lebCnt = sb.sb().lebCnt();
        this.inodes = inodes;
        this.dents = dents;
        this.data = data;
        this.inlineData = inlineData;
    }

    /**
     * Mounts a UBIFS filesystem from a virtual disk at a partition offset.
     */
    public static @NotNull UbifsFileSystemImpl mount(@NotNull VirtualDisk disk,
                                                      long partitionOffset) throws IOException {
        return mount(DiskRegion.fromPartition(disk, partitionOffset, 0));
    }

    /**
     * Mounts a UBIFS filesystem from a disk region (a bare volume or a UBI
     * volume region).
     */
    public static @NotNull UbifsFileSystemImpl mount(@NotNull DiskRegion region)
            throws IOException {
        UbifsSuperblock sb = UbifsSuperblock.read(region)
                .orElseThrow(() -> new IOException("Not a valid ubifs filesystem"));

        long flags = sb.sb().flags();
        if ((flags & UbifsNode.FLG_ENCRYPTION) != 0
                || (flags & UbifsNode.FLG_AUTHENTICATION) != 0) {
            throw new IOException("ubifs encryption/authentication is not supported");
        }

        // Master node: LEB 1 and 2 hold one copy each; the newest wins.
        UbifsNode.Master master = null;
        for (long mleb : new long[] {1, 2}) {
            if (mleb >= sb.sb().lebCnt()) {
                continue;
            }
            UbifsNode.Master m = readMaster(region, mleb * (long) sb.sb().lebSize());
            if (m != null && (master == null || m.cmtNo() > master.cmtNo())) {
                master = m;
            }
        }
        if (master == null) {
            throw new IOException("ubifs master node not found");
        }
        if ((master.flags() & UbifsNode.MST_DIRTY) != 0) {
            throw new IOException("ubifs image was not cleanly unmounted "
                    + "(dirty master node); journal replay is not supported");
        }

        Scan scan = new Scan();
        walkIndex(region, sb.sb().lebSize(), master.rootLnum(), master.rootOffs(),
                master.rootLen(), 0, scan);
        return new UbifsFileSystemImpl(region, sb, scan.inodes, scan.dents,
                scan.data, scan.inlineData);
    }

    private static final class Scan {
        final Map<Long, Inode> inodes = new HashMap<>();
        final Map<Long, List<Dent>> dents = new HashMap<>();
        final Map<Long, TreeMap<Long, DataNode>> data = new HashMap<>();
        final Map<Long, byte[]> inlineData = new HashMap<>();
    }

    private static UbifsNode.Master readMaster(DiskRegion region, long offset)
            throws IOException {
        if (region.size() < offset + UbifsNode.CH_SIZE) {
            return null;
        }
        byte[] buf = new byte[4096];
        ByteBuffer bb = region.read(offset, buf.length);
        bb.get(buf);
        UbifsNode.Header header = UbifsNode.parseHeader(buf, 0, buf.length);
        if (header == null || header.nodeType() != UbifsNode.MST_NODE) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        return new UbifsNode.Master(
                b.getLong(UbifsNode.MST_CMT_NO),
                b.getInt(UbifsNode.MST_FLAGS) & 0xffffffffL,
                b.getInt(UbifsNode.MST_LOG_LNUM) & 0xffffffffL,
                b.getInt(UbifsNode.MST_ROOT_LNUM) & 0xffffffffL,
                b.getInt(UbifsNode.MST_ROOT_OFFS) & 0xffffffffL,
                b.getInt(UbifsNode.MST_ROOT_LEN) & 0xffffffffL);
    }

    private static void walkIndex(DiskRegion region, int lebSize, long lnum,
                                  long offs, long len, int depth, Scan scan)
            throws IOException {
        if (depth > 512) {
            throw new IOException("ubifs index tree too deep");
        }
        if (len <= 0 || len > MAX_NODE_LEN) {
            throw new IOException("ubifs index node length implausible: " + len);
        }
        long offset = SafeMath.safeAdd(SafeMath.safeMultiply(lnum, lebSize), offs);
        if (offset < 0 || offset + len > region.size()) {
            throw new IOException("ubifs index node out of bounds");
        }
        byte[] buf = new byte[(int) len];
        ByteBuffer bb = region.read(offset, buf.length);
        bb.get(buf);
        UbifsNode.Header header = UbifsNode.parseHeader(buf, 0, buf.length);
        if (header == null) {
            throw new IOException("ubifs corrupt index node header");
        }

        switch (header.nodeType()) {
            case UbifsNode.IDX_NODE -> {
                ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                int childCnt = b.getShort(UbifsNode.IDX_CHILD_CNT) & 0xffff;
                if (childCnt < 1 || childCnt > 4096 || childCnt * 20 + UbifsNode.IDX_BRANCHES > buf.length) {
                    throw new IOException("ubifs index fanout implausible: " + childCnt);
                }
                for (int i = 0; i < childCnt; i++) {
                    int bo = UbifsNode.IDX_BRANCHES + i * 20;
                    long cLnum = b.getInt(bo) & 0xffffffffL;
                    long cOffs = b.getInt(bo + 4) & 0xffffffffL;
                    long cLen = b.getInt(bo + 8) & 0xffffffffL;
                    walkIndex(region, lebSize, cLnum, cOffs, cLen, depth + 1, scan);
                }
            }
            case UbifsNode.INO_NODE -> parseInoNode(buf, scan);
            case UbifsNode.DENT_NODE -> parseDentNode(buf, scan);
            case UbifsNode.DATA_NODE -> parseDataNode(buf, scan, offset);
            case UbifsNode.PAD_NODE, UbifsNode.TRUN_NODE, UbifsNode.REF_NODE,
                 UbifsNode.ORPH_NODE, UbifsNode.XENT_NODE -> {
                // Skip.
            }
            default -> throw new IOException("ubifs unexpected node type in index: "
                    + header.nodeType());
        }
    }

    private static void parseInoNode(byte[] buf, Scan scan) {
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        long ino = b.getInt(UbifsNode.INO_KEY) & 0xffffffffL;
        long size = b.getLong(UbifsNode.INO_SIZE);
        long nlink = b.getInt(UbifsNode.INO_NLINK) & 0xffffffffL;
        long uid = b.getInt(UbifsNode.INO_UID) & 0xffffffffL;
        long gid = b.getInt(UbifsNode.INO_GID) & 0xffffffffL;
        int mode = b.getInt(UbifsNode.INO_MODE);
        int dataLen = b.getInt(UbifsNode.INO_DATA_LEN);
        int comprType = b.getShort(UbifsNode.INO_COMPR_TYPE) & 0xffff;
        if (dataLen > 0 && dataLen <= buf.length - INLINE_DATA_OFFSET) {
            byte[] payload = new byte[dataLen];
            System.arraycopy(buf, INLINE_DATA_OFFSET, payload, 0, dataLen);
            scan.inlineData.put(ino, payload);
        }
        scan.inodes.put(ino, new Inode(mode, uid, gid, size, nlink, dataLen, comprType));
    }

    private static void parseDentNode(byte[] buf, Scan scan) {
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        long pino = b.getInt(UbifsNode.DENT_KEY) & 0xffffffffL;
        long inum = b.getLong(UbifsNode.DENT_INUM);
        int type = b.get(UbifsNode.DENT_TYPE) & 0xff;
        int nlen = b.getShort(UbifsNode.DENT_NLEN) & 0xffff;
        if (nlen == 0 || nlen > UbifsNode.MAX_NAME_LEN
                || UbifsNode.DENT_NAME + nlen > buf.length) {
            return;
        }
        String name = new String(buf, UbifsNode.DENT_NAME, nlen, StandardCharsets.UTF_8);
        scan.dents.computeIfAbsent(pino, k -> new ArrayList<>())
                .add(new Dent(pino, inum, type, name));
    }

    private static void parseDataNode(byte[] buf, Scan scan, long nodeOffset) {
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        long ino = b.getInt(UbifsNode.DATA_KEY) & 0xffffffffL;
        long keyWord1 = b.getInt(UbifsNode.DATA_KEY + 4) & 0xffffffffL;
        long block = UbifsNode.keyBlock(keyWord1);
        long size = b.getInt(UbifsNode.DATA_SIZE) & 0xffffffffL;
        int comprType = b.getShort(UbifsNode.DATA_COMPR_TYPE) & 0xffff;
        long payloadLen = buf.length - UbifsNode.DATA_OFFSET;
        scan.data.computeIfAbsent(ino, k -> new TreeMap<>())
                .put(block, new DataNode(block, size, comprType,
                        nodeOffset + UbifsNode.DATA_OFFSET, payloadLen));
    }

    // ========================================================================
    // Entry construction
    // ========================================================================

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

    private Optional<FileSystemEntry> entryFor(long ino, String path) throws IOException {
        Inode inode = inodes.get(ino);
        if (inode == null) {
            return Optional.empty();
        }
        Dent dent = findDent(ino);
        String name = dent == null ? "" : dent.name();
        if (dent != null && !isSafeName(name)) {
            return Optional.empty();
        }
        return switch (inode.mode() & 0xf000) {
            case 0x4000 -> Optional.of(new UbifsDirectory(inode, ino, name, path));
            case 0x8000 -> Optional.of(new UbifsRegularFile(inode, ino, name, path));
            case 0xa000 -> Optional.of(new UbifsSymlink(inode, ino, name, path));
            case 0x1000, 0x2000, 0x6000, 0xc000 ->
                    Optional.of(new UbifsSpecialFile(inode, ino, name, path));
            default -> Optional.empty();
        };
    }

    /** Finds the directory entry that names an inode (for name/path display). */
    private Dent findDent(long ino) {
        for (List<Dent> list : dents.values()) {
            for (Dent d : list) {
                if (d.inum() == ino) {
                    return d;
                }
            }
        }
        return null;
    }

    // ========================================================================
    // File content
    // ========================================================================

    private InputStream openFileStream(Inode inode, long ino) throws IOException {
        long size = inode.size();
        TreeMap<Long, DataNode> blocks = data.getOrDefault(ino, new TreeMap<>());
        byte[] inlineRaw = inlineData.get(ino);
        List<DataNode> nodes = new ArrayList<>(blocks.values());

        final byte[] inlineBytes;
        if (inlineRaw != null) {
            inlineBytes = readInline(inode, inlineRaw);
        } else {
            inlineBytes = null;
        }

        return new InputStream() {
            private long pos = 0;
            private int nodeIdx = 0;
            private long inlineRemaining = inlineBytes == null ? 0 : inlineBytes.length;
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
                    if (pos < inlineRemaining) {
                        int n = (int) Math.min(inlineRemaining - pos,
                                Math.min(len - total, size - pos));
                        System.arraycopy(inlineBytes, (int) pos, b, off + total, n);
                        pos += n;
                        total += n;
                        continue;
                    }
                    if (nodeIdx >= nodes.size()) {
                        // Trailing zeros (sparse tail).
                        int n = (int) Math.min(size - pos, len - total);
                        Arrays.fill(b, off + total, off + total + n, (byte) 0);
                        pos += n;
                        total += n;
                        continue;
                    }
                    DataNode node = nodes.get(nodeIdx);
                    long logicalStart = inlineRemaining + node.block() * (long) UbifsNode.BLOCK_SIZE;
                    if (pos < logicalStart) {
                        int n = (int) Math.min(logicalStart - pos, len - total);
                        Arrays.fill(b, off + total, off + total + n, (byte) 0);
                        pos += n;
                        total += n;
                        continue;
                    }
                    if (current == null) {
                        current = decompress(node);
                    }
                    int within = (int) (pos - logicalStart);
                    int n = (int) Math.min(current.length - within,
                            Math.min(len - total, size - pos));
                    System.arraycopy(current, within, b, off + total, n);
                    pos += n;
                    total += n;
                    if (pos >= logicalStart + current.length) {
                        nodeIdx++;
                        current = null;
                    }
                }
                return total == 0 ? -1 : total;
            }
        };
    }

    private byte[] readInline(Inode inode, byte[] inline) throws IOException {
        // Inline data is stored compressed in the inode node (compr_type).
        int comprType = inode.comprType();
        long size = inode.size();
        if (size > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("ubifs inline data too large: " + size
                    + " bytes (limit: 16 MB).",
                    "allocation_size", MAX_READABLE_SIZE, size);
        }
        int expected = (int) size;
        byte[] out = new byte[expected];
        if (inline.length == expected) {
            // Compression did not help (or was not applied): stored raw.
            System.arraycopy(inline, 0, out, 0, expected);
            return out;
        }
        switch (comprType) {
            case UbifsNode.COMPR_NONE -> {
                if (inline.length != expected) {
                    throw new IOException("ubifs inline size mismatch");
                }
                System.arraycopy(inline, 0, out, 0, expected);
            }
            case UbifsNode.COMPR_ZLIB -> {
                Inflater inflater = new Inflater();
                inflater.setInput(inline);
                int written;
                try {
                    written = inflater.inflate(out);
                } catch (DataFormatException e) {
                    inflater.end();
                    throw new IOException("ubifs inline zlib decompression failed", e);
                }
                inflater.end();
                if (written != expected) {
                    throw new IOException("ubifs inline zlib size mismatch");
                }
            }
            case UbifsNode.COMPR_LZO -> {
                lzo_uintp written = new lzo_uintp(expected);
                int result = LzoDecompressor1x.decompress(inline, 0, inline.length,
                        out, 0, written, null);
                if (result != 0) {
                    throw new IOException("ubifs inline LZO decompression failed: " + result);
                }
                if (written.value != expected) {
                    throw new IOException("ubifs inline LZO size mismatch");
                }
            }
            case UbifsNode.COMPR_ZSTD -> {
                long written = com.github.luben.zstd.Zstd.decompress(out, inline);
                if (com.github.luben.zstd.Zstd.isError(written)) {
                    throw new IOException("ubifs inline zstd decompression failed: "
                            + com.github.luben.zstd.Zstd.getErrorName(written));
                }
                if (written != expected) {
                    throw new IOException("ubifs inline zstd size mismatch");
                }
            }
            default -> throw new IOException("unsupported ubifs inline compression id: "
                    + comprType);
        }
        return out;
    }

    private byte[] decompress(DataNode node) throws IOException {
        if (node.size() > UbifsNode.BLOCK_SIZE) {
            throw new IOException("ubifs data node exceeds block size: " + node.size());
        }
        byte[] payload = new byte[(int) node.payloadLen()];
        ByteBuffer buf = region.read(node.payloadOffset(), payload.length);
        buf.get(payload);
        byte[] out = new byte[(int) node.size()];
        switch (node.comprType()) {
            case UbifsNode.COMPR_NONE -> {
                if (payload.length != out.length) {
                    throw new IOException("ubifs uncompressed size mismatch");
                }
                System.arraycopy(payload, 0, out, 0, out.length);
            }
            case UbifsNode.COMPR_ZLIB -> {
                Inflater inflater = new Inflater();
                inflater.setInput(payload);
                int written;
                try {
                    written = inflater.inflate(out);
                } catch (DataFormatException e) {
                    inflater.end();
                    throw new IOException("ubifs zlib decompression failed", e);
                }
                inflater.end();
                if (written != out.length) {
                    throw new IOException("ubifs zlib size mismatch: expected "
                            + out.length + " got " + written);
                }
            }
            case UbifsNode.COMPR_LZO -> {
                lzo_uintp written = new lzo_uintp(out.length);
                int result = LzoDecompressor1x.decompress(payload, 0, payload.length,
                        out, 0, written, null);
                if (result != 0) {
                    throw new IOException("ubifs LZO decompression failed: " + result);
                }
                if (written.value != out.length) {
                    throw new IOException("ubifs LZO size mismatch: expected "
                            + out.length + " got " + written.value);
                }
            }
            case UbifsNode.COMPR_ZSTD -> {
                long written = com.github.luben.zstd.Zstd.decompress(out, payload);
                if (com.github.luben.zstd.Zstd.isError(written)) {
                    throw new IOException("ubifs zstd decompression failed: "
                            + com.github.luben.zstd.Zstd.getErrorName(written));
                }
                if (written != out.length) {
                    throw new IOException("ubifs zstd size mismatch: expected "
                            + out.length + " got " + written);
                }
            }
            default -> throw new IOException("unsupported ubifs compression id: "
                    + node.comprType());
        }
        return out;
    }

    private byte[] readAllContent(Inode inode, long ino) throws IOException {
        long size = inode.size();
        if (size > MAX_READABLE_SIZE) {
            throw new ResourceLimitException("ubifs file too large to read into memory: "
                    + size + " bytes (limit: 16 MB). Use openStream() for large files.",
                    "allocation_size", MAX_READABLE_SIZE, size);
        }
        byte[] content = new byte[(int) size];
        try (InputStream in = openFileStream(inode, ino)) {
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

    private String readSymlinkTarget(Inode inode, long ino) throws IOException {
        if (inode.size() > 4096) {
            throw new IOException("ubifs symlink target too large: " + inode.size());
        }
        return new String(readAllContent(inode, ino), StandardCharsets.UTF_8);
    }

    // ========================================================================
    // Entry implementations
    // ========================================================================

    private final class UbifsDirectory implements FileSystemEntry.Directory {
        private final Inode inode;
        private final long ino;
        private final String name;
        private final String path;

        UbifsDirectory(Inode inode, long ino, String name, String path) {
            this.inode = inode;
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
            return headerAttributes(inode);
        }

        @Override
        public @NotNull Stream<FileSystemEntry> list() throws IOException {
            List<Dent> entries = dents.getOrDefault(ino, List.of());
            List<FileSystemEntry> result = new ArrayList<>(entries.size());
            for (Dent d : entries) {
                if (!isSafeName(d.name())) {
                    continue;
                }
                String childPath = path.equals("/") ? "/" + d.name() : path + "/" + d.name();
                entryFor(d.inum(), childPath).ifPresent(result::add);
            }
            result.sort(Comparator.comparing(FileSystemEntry::name));
            return result.stream();
        }

        @Override
        public @NotNull Optional<FileSystemEntry> find(@NotNull String name) throws IOException {
            for (Dent d : dents.getOrDefault(ino, List.of())) {
                if (name.equals(d.name())) {
                    String childPath = path.equals("/") ? "/" + name : path + "/" + name;
                    return entryFor(d.inum(), childPath);
                }
            }
            return Optional.empty();
        }
    }

    private final class UbifsRegularFile implements FileSystemEntry.RegularFile {
        final Inode inode;
        final long ino;
        private final String name;
        private final String path;

        UbifsRegularFile(Inode inode, long ino, String name, String path) {
            this.inode = inode;
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
            return headerAttributes(inode);
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            return openFileStream(inode, ino);
        }

        @Override
        public byte @NotNull [] readAllBytes() throws IOException {
            return readAllContent(inode, ino);
        }
    }

    private final class UbifsSymlink implements FileSystemEntry.SymbolicLink {
        final Inode inode;
        final long ino;
        private final String name;
        private final String path;
        private String target;

        UbifsSymlink(Inode inode, long ino, String name, String path) {
            this.inode = inode;
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
            return headerAttributes(inode);
        }

        @Override
        public @NotNull String target() {
            if (target == null) {
                try {
                    target = readSymlinkTarget(inode, ino);
                } catch (IOException e) {
                    target = "";
                }
            }
            return target;
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
            return UbifsFileSystemImpl.this.resolve(t);
        }
    }

    private final class UbifsSpecialFile implements FileSystemEntry.SpecialFile {
        private final Inode inode;
        private final String name;
        private final String path;

        UbifsSpecialFile(Inode inode, long ino, String name, String path) {
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
            return headerAttributes(inode);
        }

        @Override
        public @NotNull FileSystemEntry.EntryType type() {
            return switch (inode.mode() & 0xf000) {
                case 0x2000 -> FileSystemEntry.EntryType.CHARACTER_DEVICE;
                case 0x6000 -> FileSystemEntry.EntryType.BLOCK_DEVICE;
                case 0x1000 -> FileSystemEntry.EntryType.FIFO;
                default -> FileSystemEntry.EntryType.SOCKET;
            };
        }

        @Override
        public @NotNull Optional<Integer> majorDevice() {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Integer> minorDevice() {
            return Optional.empty();
        }
    }

    private static Map<String, Object> headerAttributes(Inode inode) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("mode", String.format("%o", inode.mode() & 0xfff));
        attrs.put("uid", inode.uid());
        attrs.put("gid", inode.gid());
        return Map.copyOf(attrs);
    }

    // ========================================================================
    // FileSystem API
    // ========================================================================

    @Override
    public @NotNull FileSystemEntry.Directory root() {
        Inode rootInode = inodes.getOrDefault(UbifsNode.ROOT_INO,
                new Inode(0x41ed, 0, 0, 0, 1, 0, 0));
        return new UbifsDirectory(rootInode, UbifsNode.ROOT_INO, "/", "/");
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
                String t = symlink.target();
                String resolvedTarget;
                if (t.startsWith("/")) {
                    resolvedTarget = t;
                } else {
                    String parentPath = dir.path();
                    if (!parentPath.endsWith("/")) {
                        parentPath = parentPath + "/";
                    }
                    resolvedTarget = parentPath + t;
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
        if (dir instanceof UbifsDirectory ud && !visited.add(ud.ino)) {
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
        return SafeMath.safeMultiply(lebCnt, lebSize);
    }

    @Override
    public long usedSize() {
        return totalSize();
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
        byte[] uuid = superblock.sb().uuid();
        boolean allZero = true;
        for (byte b : uuid) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            return Optional.empty();
        }
        return Optional.of(String.format(
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                uuid[0] & 0xff, uuid[1] & 0xff, uuid[2] & 0xff, uuid[3] & 0xff,
                uuid[4] & 0xff, uuid[5] & 0xff,
                uuid[6] & 0xff, uuid[7] & 0xff,
                uuid[8] & 0xff, uuid[9] & 0xff,
                uuid[10] & 0xff, uuid[11] & 0xff, uuid[12] & 0xff, uuid[13] & 0xff,
                uuid[14] & 0xff, uuid[15] & 0xff));
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("version", "ubifs");
        meta.put("fmtVersion", String.valueOf(superblock.sb().fmtVersion()));
        meta.put("lebSize", String.valueOf(superblock.sb().lebSize()));
        meta.put("lebCount", String.valueOf(superblock.sb().lebCnt()));
        meta.put("fanout", String.valueOf(superblock.sb().fanout()));
        meta.put("compression", compressionName(superblock.sb().defaultCompr()));
        meta.put("inodeCount", String.valueOf(inodes.size()));
        return Collections.unmodifiableMap(meta);
    }

    private static String compressionName(int id) {
        return switch (id) {
            case UbifsNode.COMPR_NONE -> "none";
            case UbifsNode.COMPR_LZO -> "lzo";
            case UbifsNode.COMPR_ZSTD -> "zstd";
            default -> "zlib";
        };
    }

    @Override
    public void close() {
        // Nothing to close; the region is managed externally.
    }
}
