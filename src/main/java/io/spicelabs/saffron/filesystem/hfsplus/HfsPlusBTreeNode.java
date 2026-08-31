/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.hfsplus;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.ByteOrder;

/**
 * Represents an HFS+ B-tree node.
 *
 * <p>Each B-tree node has a 14-byte descriptor followed by records. The offsets to each
 * record are stored in an array at the end of the node, growing backwards.
 *
 * <p>Node descriptor layout:
 * <pre>
 * Offset  Size  Description
 * 0       4     fLink (next node in this level)
 * 4       4     bLink (previous node in this level)
 * 8       1     kind (-1=leaf, 0=index, 1=header, 2=map)
 * 9       1     height
 * 10      2     numRecords
 * 12      2     reserved
 * </pre>
 */
public record HfsPlusBTreeNode(
        int fLink,
        int bLink,
        int kind,
        int height,
        int numRecords,
        byte[] data,
        int nodeSize
) {
    public static final int NODE_DESCRIPTOR_SIZE = 14;

    public static final int KIND_LEAF = -1;
    public static final int KIND_INDEX = 0;
    public static final int KIND_HEADER = 1;
    public static final int KIND_MAP = 2;

    /**
     * Parses a B-tree node from raw data.
     *
     * @throws IOException on implausible record counts (checked - the
     *         filesystem API must never leak unchecked exceptions).
     */
    public static HfsPlusBTreeNode parse(byte[] nodeData, int nodeSize) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(nodeData);
        buf.order(ByteOrder.BIG_ENDIAN);

        int fLink = buf.getInt(0);
        int bLink = buf.getInt(4);
        int kind = buf.get(8);
        int height = buf.get(9) & 0xFF;
        int numRecords = buf.getShort(10) & 0xFFFF;
        if (numRecords > nodeSize / 2) {
            // More records than the node's offset table can hold: every
            // record needs a 2-byte offset entry plus at least 1 byte of
            // data, so numRecords > nodeSize/2 indexes negative offsets.
            throw new IOException("HFS+ B-tree node record count implausible: "
                    + numRecords + " for node size " + nodeSize);
        }

        return new HfsPlusBTreeNode(fLink, bLink, kind, height, numRecords, nodeData, nodeSize);
    }

    public boolean isLeaf() {
        return kind == KIND_LEAF;
    }

    public boolean isIndex() {
        return kind == KIND_INDEX;
    }

    public boolean isHeader() {
        return kind == KIND_HEADER;
    }

    /**
     * Gets the offset of record i within the node data.
     * Record offsets are stored at the end of the node, as 16-bit values growing backwards.
     */
    public int getRecordOffset(int i) throws IOException {
        if (i < 0 || i > numRecords) {
            throw new IOException("Record index: " + i + ", numRecords: " + numRecords);
        }
        // Record offsets are at the end of the node, 2 bytes each, in reverse order
        int offsetPos = nodeSize - 2 * (i + 1);
        if (offsetPos < 0 || offsetPos + 1 >= nodeSize) {
            throw new IOException("Record offset table out of node bounds");
        }
        return ((data[offsetPos] & 0xFF) << 8) | (data[offsetPos + 1] & 0xFF);
    }

    /**
     * Gets the length of record i.
     */
    public int getRecordLength(int i) throws IOException {
        return getRecordOffset(i + 1) - getRecordOffset(i);
    }

    /**
     * Gets the raw bytes for record i.
     */
    public byte[] getRecordData(int i) throws IOException {
        int start = getRecordOffset(i);
        int length = getRecordLength(i);
        if (length < 0 || start < 0 || start + (long) length > data.length) {
            throw new IOException("HFS+ B-tree record out of bounds: start="
                    + start + ", length=" + length);
        }
        byte[] record = new byte[length];
        System.arraycopy(data, start, record, 0, length);
        return record;
    }

    /**
     * B-tree header record, found in the header node (node 0, record 0).
     */
    public record HeaderRecord(
            int treeDepth,
            int rootNode,
            int leafRecords,
            int firstLeafNode,
            int lastLeafNode,
            int nodeSize,
            int maxKeyLength,
            int totalNodes,
            int freeNodes,
            int keyCompareType
    ) {
        /**
         * Parses the header record from the header node.
         */
        public static HeaderRecord parse(byte[] recordData) {
            ByteBuffer buf = ByteBuffer.wrap(recordData);
            buf.order(ByteOrder.BIG_ENDIAN);

            int treeDepth = buf.getShort(0) & 0xFFFF;
            int rootNode = buf.getInt(2);
            int leafRecords = buf.getInt(6);
            int firstLeafNode = buf.getInt(10);
            int lastLeafNode = buf.getInt(14);
            int nodeSize = buf.getShort(18) & 0xFFFF;
            int maxKeyLength = buf.getShort(20) & 0xFFFF;
            int totalNodes = buf.getInt(22);
            int freeNodes = buf.getInt(26);
            // keyCompareType at offset 44
            int keyCompareType = recordData.length > 44 ? buf.get(44) & 0xFF : 0;

            return new HeaderRecord(treeDepth, rootNode, leafRecords, firstLeafNode,
                    lastLeafNode, nodeSize, maxKeyLength, totalNodes, freeNodes, keyCompareType);
        }
    }
}
