/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.yaffs2;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Test-side builder for hand-crafted YAFFS2 images (2048-byte pages, 64-byte
 * spare, little-endian tags and headers — the most common layout).
 *
 * <p>Each chunk is 2048 data bytes + 64 spare bytes; the first 16 spare
 * bytes carry the tags {@code (seq_number, obj_id, chunk_id, n_bytes)} in
 * little-endian order and the remainder is 0xFF. Chunk 0 of each object
 * holds the 512-byte object header.
 */
final class Yaffs2ImageWriter {

    static final int TYPE_FILE = 1;
    static final int TYPE_SYMLINK = 2;
    static final int TYPE_DIRECTORY = 3;
    static final int TYPE_HARDLINK = 4;
    static final int TYPE_SPECIAL = 5;

    static final int PAGE = 2048;
    static final int SPARE = 64;
    static final int CHUNK = PAGE + SPARE;

    static final int MODE_REG = 0x81a4;  // 0100644
    static final int MODE_DIR = 0x41ed;  // 040755
    static final int MODE_LNK = 0xa1ff;  // 0120777

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private long seq = 100;

    Yaffs2ImageWriter chunk(long objId, long chunkId, long nBytes, byte[] data) {
        byte[] full = new byte[PAGE];
        Arrays.fill(full, (byte) 0xff);
        int n = (int) Math.min(data.length, PAGE);
        System.arraycopy(data, 0, full, 0, n);
        out.writeBytes(full);
        byte[] spare = new byte[SPARE];
        Arrays.fill(spare, (byte) 0xff);
        ByteBuffer tags = ByteBuffer.wrap(spare).order(ByteOrder.LITTLE_ENDIAN);
        tags.putInt((int) seq++);
        tags.putInt((int) objId);
        tags.putInt((int) chunkId);
        tags.putInt((int) nBytes);
        out.writeBytes(spare);
        return this;
    }

    /** Writes an object header chunk (chunk id 0). */
    Yaffs2ImageWriter header(long objId, int type, long parent, String name,
                             int mode, long fileSize, String alias, long equivId) {
        byte[] data = new byte[PAGE];
        Arrays.fill(data, (byte) 0xff);
        ByteBuffer h = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(type);
        h.putInt((int) parent);
        h.putShort((short) 0xffff); // sum_no_longer_used
        byte[] nameBytes = new byte[Yaffs2Node.HDR_NAME_LEN];
        Arrays.fill(nameBytes, (byte) 0xff);
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(raw.length, Yaffs2Node.HDR_NAME_LEN - 1);
        System.arraycopy(raw, 0, nameBytes, 0, n);
        nameBytes[n] = 0; // NUL-terminate (strcpy semantics in the real tool)
        h.position(Yaffs2Node.HDR_NAME);
        h.put(nameBytes);
        h.position(Yaffs2Node.HDR_MODE);
        h.putInt(mode);
        h.putInt(0); // uid
        h.putInt(0); // gid
        h.putInt(0); // atime
        h.putInt(0); // mtime
        h.putInt(0); // ctime
        h.putInt((int) fileSize);
        h.putInt((int) equivId);
        if (alias != null && !alias.isEmpty()) {
            byte[] aliasBytes = new byte[Yaffs2Node.HDR_ALIAS_LEN];
            Arrays.fill(aliasBytes, (byte) 0xff);
            byte[] rawAlias = alias.getBytes(StandardCharsets.UTF_8);
            int an = Math.min(rawAlias.length, Yaffs2Node.HDR_ALIAS_LEN - 1);
            System.arraycopy(rawAlias, 0, aliasBytes, 0, an);
            aliasBytes[an] = 0;
            h.position(Yaffs2Node.HDR_ALIAS);
            h.put(aliasBytes);
        }
        chunk(objId, 0, 0xffffL, data);
        return this;
    }

    /** Writes a data chunk with explicit content (n_bytes = content length). */
    Yaffs2ImageWriter dataChunk(long objId, long chunkId, byte[] content) {
        chunk(objId, chunkId, content.length, content);
        return this;
    }

    byte[] bytes() {
        return out.toByteArray();
    }
}
