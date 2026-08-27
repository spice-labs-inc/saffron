/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.cramfs;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

/**
 * Test-side builder for hand-crafted cramfs images.
 *
 * <p>cramfs is simple enough to assemble by hand: a 64-byte superblock, a
 * root inode, sequential directory entries, block-pointer tables, and
 * per-block zlib data. This helper writes little-endian images so tests can
 * exercise holes, corrupt pointers, unsupported flags, and path traversal
 * without shelling out to external tools.
 */
final class CramfsImageWriter {

    static final int MODE_DIR = 0x41ed;   // 040755
    static final int MODE_REG = 0x81a4;   // 0100644
    static final int MODE_LNK = 0xa1ff;   // 0120777
    static final int MODE_FIFO = 0x11a4;  // 0010644

    static final int FLAG_FSID_V2 = 0x1;
    static final int FLAG_UNSUPPORTED = 0x10000000;
    static final int BLK_UNCOMPRESSED = 0x80000000;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    CramfsImageWriter superblock(int flags, String name) {
        byte[] nameBytes = new byte[16];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, nameBytes, 0, Math.min(n.length, 16));
        ByteBuffer sb = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN);
        sb.putInt(0x28cd3d45); // magic
        sb.putInt(0);          // size, patched by finish()
        sb.putInt(flags);
        sb.putInt(0);
        sb.put("Compressed ROMFS".getBytes(StandardCharsets.US_ASCII));
        sb.putInt(0); // fsid crc
        sb.putInt(0); // edition
        sb.putInt(0); // blocks
        sb.putInt(0); // files
        sb.put(nameBytes);
        sb.putInt(0); // root inode placeholder words, patched by root()
        sb.putInt(0);
        sb.putInt(0);
        out.writeBytes(sb.array());
        return this;
    }

    CramfsImageWriter rootDir(int entriesSize, int entryOffset) {
        int rootIno = 64;
        byte[] current = out.toByteArray();
        ByteBuffer buf = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(rootIno, MODE_DIR | 0x03e80000); // uid 1000 in high bits? mode|uid packed
        buf.putInt(rootIno + 4, entriesSize | 0x00000000); // size | gid<<24
        buf.putInt(rootIno + 8, entryOffset << 6);
        out.reset();
        out.writeBytes(current);
        return this;
    }

    CramfsImageWriter dirent(int mode, long size, String name, int offset) {
        int namelen = (name.length() + 3) / 4;
        ByteBuffer e = ByteBuffer.allocate(12 + namelen * 4).order(ByteOrder.LITTLE_ENDIAN);
        e.putInt(mode | 0x03e80000);
        e.putInt((int) size | 0x00000000);
        e.putInt((namelen & 0x3f) | (offset << 6));
        byte[] nameBytes = new byte[namelen * 4];
        System.arraycopy(name.getBytes(StandardCharsets.UTF_8), 0, nameBytes, 0, name.length());
        e.put(nameBytes);
        out.writeBytes(e.array());
        return this;
    }

    /** Appends a block-pointer table for a file at the current offset. */
    int blockPointerTable(long[] blockEnds, int... uncompressedBlocks) {
        int tableOffset = out.size();
        for (long end : blockEnds) {
            out.writeBytes(intLe((int) end));
        }
        return tableOffset;
    }

    static void patchBlockFlag(byte[] image, int ptrOffset, int flag) {
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        int v = buf.getInt(ptrOffset);
        buf.putInt(ptrOffset, v | flag);
    }

    byte[] zlibBlock(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        byte[] buf = new byte[data.length + 512];
        int n = d.deflate(buf);
        d.end();
        out.write(buf, 0, n);
        return java.util.Arrays.copyOfRange(buf, 0, n);
    }

    void raw(byte[] data) {
        out.writeBytes(data);
    }

    byte[] finish(int fileCount, int blockCount) {
        byte[] current = out.toByteArray();
        ByteBuffer buf = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(4, current.length); // size
        buf.putInt(40, blockCount);    // fsid.blocks
        buf.putInt(44, fileCount);     // fsid.files
        return current;
    }

    static byte[] intLe(int v) {
        return new byte[] {
                (byte) (v & 0xff), (byte) ((v >>> 8) & 0xff),
                (byte) ((v >>> 16) & 0xff), (byte) ((v >>> 24) & 0xff) };
    }
}
