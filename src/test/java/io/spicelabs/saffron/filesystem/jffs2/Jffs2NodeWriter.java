/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.jffs2;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Test-side builder for hand-crafted JFFS2 images.
 *
 * <p>JFFS2 has no superblock; a full image can be assembled from individual
 * nodes. This helper writes well-formed nodes (correct magic, node types,
 * lengths, and JFFS2 CRCs) so tests can exercise version resolution, deletion,
 * sparse files, and unsupported compression without shelling out to external
 * tools.
 *
 * <p>The on-disk layout mirrors the Linux kernel {@code include/uapi/linux/jffs2.h}
 * structs:
 * <ul>
 *   <li>Common node header: magic (2), nodetype (2), totlen (4), hdr_crc (4)</li>
 *   <li>Inode node: common header + ino, version, mode, uid, gid, isize,
 *       atime, mtime, ctime, offset, csize, dsize, compr, usercompr, flags,
 *       data_crc, node_crc, then the compressed data</li>
 *   <li>Dirent node: common header + pino, version, ino, mctime, nsize, type,
 *       unused[2], node_crc, name_crc, then the name bytes</li>
 * </ul>
 */
final class Jffs2NodeWriter {

    /** S_IFREG | 0644, as stored in the inode mode field. */
    static final int MODE_REG = 0x81a4;
    /** S_IFDIR | 0755. */
    static final int MODE_DIR = 0x41ed;
    /** S_IFLNK | 0777. */
    static final int MODE_LNK = 0xa1ff;
    /** S_IFCHR | 0600. */
    static final int MODE_CHR = 0x2180;

    /** Directory entry types (Linux DT_*). */
    static final int DT_REG = 8;
    static final int DT_DIR = 4;
    static final int DT_LNK = 10;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    static byte[] imageOf(byte[]... nodes) {
        Jffs2NodeWriter w = new Jffs2NodeWriter();
        for (byte[] n : nodes) {
            w.raw(n);
        }
        return w.bytes();
    }

    Jffs2NodeWriter raw(byte[] node) {
        out.writeBytes(node);
        return this;
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    /**
     * Writes a cleanmarker node (nodetype 0x2003, totlen 12).
     */
    static byte[] cleanmarker() {
        return common(Jffs2Node.NODETYPE_CLEANMARKER, new byte[0]);
    }

    /**
     * Writes a dirent node. A zero {@code ino} records a deletion (unlink).
     */
    static byte[] dirent(long pino, long version, long ino, String name, int type) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer body = ByteBuffer.allocate(28 + nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt((int) pino);
        body.putInt((int) version);
        body.putInt((int) ino);
        body.putInt(0); // mctime
        body.put((byte) nameBytes.length);
        body.put((byte) type);
        body.putShort((short) 0);
        // node_crc placeholder at body[20..24), name_crc at body[24..28)
        body.putInt(0);
        body.putInt(0);
        body.put(nameBytes);
        byte[] withNames = body.array();
        // dirent node_crc covers the node bytes 0..31: the common header (12,
        // which is CRC-zero) plus pino..unused (20). Because the common
        // header is a CRC-zero prefix, computing over the 20 body bytes is
        // equivalent.
        byte[] nodeCrcTarget = new byte[20];
        System.arraycopy(withNames, 0, nodeCrcTarget, 0, 20);
        byte[] nodeCrc = crcBytes(nodeCrcTarget);
        int nodeCrcVal = ByteBuffer.wrap(nodeCrc).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        int nameCrcVal = (int) crc32(nameBytes);
        withNames[20] = (byte) (nodeCrcVal & 0xff);
        withNames[21] = (byte) ((nodeCrcVal >>> 8) & 0xff);
        withNames[22] = (byte) ((nodeCrcVal >>> 16) & 0xff);
        withNames[23] = (byte) ((nodeCrcVal >>> 24) & 0xff);
        withNames[24] = (byte) (nameCrcVal & 0xff);
        withNames[25] = (byte) ((nameCrcVal >>> 8) & 0xff);
        withNames[26] = (byte) ((nameCrcVal >>> 16) & 0xff);
        withNames[27] = (byte) ((nameCrcVal >>> 24) & 0xff);
        return common(Jffs2Node.NODETYPE_DIRENT, withNames);
    }

    /**
     * Writes an inode node whose data is stored uncompressed ({@code compr}
     * = JFFS2_COMPR_NONE).
     */
    static byte[] inode(long ino, long version, int mode, long isize, long offset, byte[] data) {
        return inodeCompr(ino, version, mode, isize, offset, data.length, Jffs2Node.COMPR_NONE, data);
    }

    /**
     * Writes an inode node with an explicit compression id and compressed payload.
     */
    static byte[] inodeCompr(long ino, long version, int mode, long isize, long offset,
                             long dsize, int compr, byte[] cdata) {
        // 56 bytes of inode fields after the common header (ino..node_crc),
        // followed by the compressed data payload.
        ByteBuffer body = ByteBuffer.allocate(56 + cdata.length).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt((int) ino);
        body.putInt((int) version);
        body.putInt(mode);
        body.putShort((short) 0); // uid
        body.putShort((short) 0); // gid
        body.putInt((int) isize);
        body.putInt(0); // atime
        body.putInt(0); // mtime
        body.putInt(0); // ctime
        body.putInt((int) offset);
        body.putInt(cdata.length);
        body.putInt((int) dsize);
        body.put((byte) compr);
        body.put((byte) 0); // usercompr
        body.putShort((short) 0); // flags
        body.putInt((int) crc32(cdata)); // data_crc
        // node_crc placeholder at body[52..56)
        body.putInt(0);
        body.put(cdata);
        byte[] full = body.array();
        // node_crc covers bytes 0..59 of the whole node = common header (12)
        // + inode fields through flags (48).
        byte[] nodeCrcTarget = new byte[48];
        System.arraycopy(full, 0, nodeCrcTarget, 0, 48);
        int nodeCrcVal = (int) crc32(nodeCrcTarget);
        full[52] = (byte) (nodeCrcVal & 0xff);
        full[53] = (byte) ((nodeCrcVal >>> 8) & 0xff);
        full[54] = (byte) ((nodeCrcVal >>> 16) & 0xff);
        full[55] = (byte) ((nodeCrcVal >>> 24) & 0xff);
        return common(Jffs2Node.NODETYPE_INODE, full);
    }

    /**
     * Writes an inode node with a corrupted node_crc (to test CRC rejection).
     */
    static byte[] inodeBadNodeCrc(long ino, long version, int mode, long isize, long offset, byte[] data) {
        byte[] node = inode(ino, version, mode, isize, offset, data);
        // node_crc sits at node offset 64 (common header 12 + body offset 52).
        node[64] ^= 0x01;
        return node;
    }

    /**
     * Writes an arbitrary node with a deliberately corrupted hdr_crc.
     */
    static byte[] corruptHdrCrc(byte[] node) {
        byte[] copy = node.clone();
        copy[8] ^= 0x01;
        return copy;
    }

    /**
     * Builds a common node header (magic + nodetype + totlen + hdr_crc) around
     * a node body and pads the total length to a 4-byte boundary.
     */
    private static byte[] common(int nodetype, byte[] body) {
        int totlen = 12 + body.length;
        if ((totlen & 3) != 0) {
            byte[] padded = new byte[body.length + (4 - (totlen & 3))];
            System.arraycopy(body, 0, padded, 0, body.length);
            body = padded;
            totlen = 12 + body.length;
        }
        ByteBuffer node = ByteBuffer.allocate(totlen).order(ByteOrder.LITTLE_ENDIAN);
        node.putShort((short) Jffs2Node.MAGIC);
        node.putShort((short) nodetype);
        node.putInt(totlen);
        byte[] first8 = new byte[8];
        first8[0] = (byte) (Jffs2Node.MAGIC & 0xff);
        first8[1] = (byte) ((Jffs2Node.MAGIC >>> 8) & 0xff);
        first8[2] = (byte) (nodetype & 0xff);
        first8[3] = (byte) ((nodetype >>> 8) & 0xff);
        first8[4] = (byte) (totlen & 0xff);
        first8[5] = (byte) ((totlen >>> 8) & 0xff);
        first8[6] = (byte) ((totlen >>> 16) & 0xff);
        first8[7] = (byte) ((totlen >>> 24) & 0xff);
        node.putInt((int) crc32(first8));
        node.put(body);
        return node.array();
    }

    static byte[] crcBytes(byte[] data) {
        return new byte[] {
                (byte) (crc32(data) & 0xff),
                (byte) ((crc32(data) >>> 8) & 0xff),
                (byte) ((crc32(data) >>> 16) & 0xff),
                (byte) ((crc32(data) >>> 24) & 0xff) };
    }

    /**
     * JFFS2's CRC32 variant: standard reflected CRC-32 (poly 0xedb88320) but
     * with initial value 0 and no final XOR.
     */
    static long crc32(byte[] data) {
        long crc = 0;
        for (byte b : data) {
            crc ^= (b & 0xff);
            for (int i = 0; i < 8; i++) {
                crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xedb88320L : 0);
            }
        }
        return crc & 0xffffffffL;
    }
}
