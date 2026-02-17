/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.hfsplus;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads HFS+ B-tree structures from disk.
 *
 * <p>HFS+ uses B-trees for the catalog file, extents overflow file, and attributes file.
 * Each B-tree is stored as a special file with its own extent records. The first node
 * (node 0) is always the header node containing the B-tree header record.
 */
public class HfsPlusBTreeReader {

    private final DiskRegion region;
    private final List<HfsPlusExtent> extents;
    private final int blockSize;
    private final HfsPlusBTreeNode.HeaderRecord header;
    private final int nodeSize;

    private HfsPlusBTreeReader(DiskRegion region, List<HfsPlusExtent> extents, int blockSize,
                                HfsPlusBTreeNode.HeaderRecord header) {
        this.region = region;
        this.extents = extents;
        this.blockSize = blockSize;
        this.header = header;
        this.nodeSize = header.nodeSize();
    }

    /**
     * Opens a B-tree reader for the given fork extents.
     */
    public static @NotNull HfsPlusBTreeReader open(@NotNull DiskRegion region,
                                                     @NotNull List<HfsPlusExtent> extents,
                                                     int blockSize) throws IOException {
        // Read a small chunk from the start of the B-tree to determine the node size.
        // The header node (node 0) starts at byte offset 0 of the fork.
        // Layout: 14-byte node descriptor, then header record immediately after.
        // The nodeSize field is at offset 18 within the header record = byte offset 32.
        byte[] probe = readNodeData(region, extents, blockSize, 0, 256);

        ByteBuffer probeBuf = ByteBuffer.wrap(probe);
        probeBuf.order(java.nio.ByteOrder.BIG_ENDIAN);

        int kind = probeBuf.get(8);
        if (kind != HfsPlusBTreeNode.KIND_HEADER) {
            throw new IOException("Invalid B-tree header node (kind=" + kind + ")");
        }

        // nodeSize is at header record offset 18, which is byte 14 + 18 = 32 in the node
        int nodeSize = probeBuf.getShort(32) & 0xFFFF;
        if (nodeSize < 512 || nodeSize > 65536) {
            throw new IOException("Invalid B-tree node size: " + nodeSize);
        }

        // Now read the full header node with the correct size
        byte[] headerNodeData = readNodeData(region, extents, blockSize, 0, nodeSize);
        HfsPlusBTreeNode headerNode = HfsPlusBTreeNode.parse(headerNodeData, nodeSize);
        if (headerNode.numRecords() < 1) {
            throw new IOException("Invalid B-tree header node: no records");
        }

        byte[] headerRecordData = headerNode.getRecordData(0);
        HfsPlusBTreeNode.HeaderRecord header = HfsPlusBTreeNode.HeaderRecord.parse(headerRecordData);

        return new HfsPlusBTreeReader(region, extents, blockSize, header);
    }

    /**
     * Returns the header record.
     */
    public HfsPlusBTreeNode.HeaderRecord header() {
        return header;
    }

    /**
     * Reads a B-tree node by number.
     */
    public @NotNull HfsPlusBTreeNode readNode(int nodeNumber) throws IOException {
        byte[] data = readNodeData(region, extents, blockSize, nodeNumber, nodeSize);
        return HfsPlusBTreeNode.parse(data, nodeSize);
    }

    /**
     * Finds all leaf records matching the given parent CNID in the catalog B-tree.
     *
     * <p>This traverses leaf nodes via fLink pointers starting from firstLeafNode,
     * collecting all records whose key starts with the given parentID.
     *
     * @param parentId the parent catalog node ID
     * @return list of raw record data for matching entries
     */
    public @NotNull List<byte[]> findRecordsByParentId(int parentId) throws IOException {
        List<byte[]> results = new ArrayList<>();
        if (header.firstLeafNode() == 0) {
            return results;
        }

        int nodeNum = header.firstLeafNode();
        int maxNodes = header.totalNodes();
        int visited = 0;

        while (nodeNum != 0 && visited < maxNodes) {
            HfsPlusBTreeNode node = readNode(nodeNum);
            if (!node.isLeaf()) break;

            boolean foundInNode = false;
            for (int i = 0; i < node.numRecords(); i++) {
                byte[] record = node.getRecordData(i);
                if (record.length < 6) continue;

                // Catalog key: keyLength(2) + parentID(4) + nameLength(2) + name(variable)
                ByteBuffer keyBuf = ByteBuffer.wrap(record);
                keyBuf.order(ByteOrder.BIG_ENDIAN);
                int keyLength = keyBuf.getShort(0) & 0xFFFF;
                int recordParentId = keyBuf.getInt(2);

                if (recordParentId == parentId) {
                    results.add(record);
                    foundInNode = true;
                } else if (recordParentId > parentId && foundInNode) {
                    // Past the parent ID range, done
                    return results;
                }
            }

            nodeNum = node.fLink();
            visited++;
        }

        return results;
    }

    /**
     * Finds a specific record by parent ID and name.
     *
     * @param parentId the parent catalog node ID
     * @param name the entry name to find
     * @return the raw record data, or null if not found
     */
    public byte[] findRecord(int parentId, @NotNull String name) throws IOException {
        // Search from root node
        if (header.rootNode() == 0) {
            return null;
        }

        int nodeNum = header.rootNode();
        int maxDepth = header.treeDepth() + 1;

        for (int depth = 0; depth < maxDepth; depth++) {
            HfsPlusBTreeNode node = readNode(nodeNum);

            if (node.isLeaf()) {
                // Search leaf for exact match
                for (int i = 0; i < node.numRecords(); i++) {
                    byte[] record = node.getRecordData(i);
                    if (record.length < 8) continue;

                    ByteBuffer keyBuf = ByteBuffer.wrap(record);
                    keyBuf.order(ByteOrder.BIG_ENDIAN);
                    int keyLength = keyBuf.getShort(0) & 0xFFFF;
                    int recordParentId = keyBuf.getInt(2);

                    if (recordParentId == parentId) {
                        String recordName = readUnicodeName(record, 6);
                        if (recordName.equalsIgnoreCase(name)) {
                            return record;
                        }
                    } else if (recordParentId > parentId) {
                        return null;
                    }
                }
                return null;
            } else if (node.isIndex()) {
                // Find child pointer to descend
                int childNode = findChildPointer(node, parentId, name);
                if (childNode == 0) {
                    return null;
                }
                nodeNum = childNode;
            } else {
                return null;
            }
        }

        return null;
    }

    /**
     * Finds the thread record for a given CNID.
     * Thread records have parentID = CNID and empty name.
     */
    public byte[] findThreadRecord(int cnid) throws IOException {
        if (header.rootNode() == 0) {
            return null;
        }

        int nodeNum = header.rootNode();
        int maxDepth = header.treeDepth() + 1;

        for (int depth = 0; depth < maxDepth; depth++) {
            HfsPlusBTreeNode node = readNode(nodeNum);

            if (node.isLeaf()) {
                for (int i = 0; i < node.numRecords(); i++) {
                    byte[] record = node.getRecordData(i);
                    if (record.length < 8) continue;

                    ByteBuffer keyBuf = ByteBuffer.wrap(record);
                    keyBuf.order(ByteOrder.BIG_ENDIAN);
                    int keyLength = keyBuf.getShort(0) & 0xFFFF;
                    int recordParentId = keyBuf.getInt(2);

                    if (recordParentId == cnid) {
                        // Check for empty name (thread records have nameLength == 0)
                        int nameLength = keyBuf.getShort(6) & 0xFFFF;
                        if (nameLength == 0) {
                            return record;
                        }
                    }
                }
                return null;
            } else if (node.isIndex()) {
                int childNode = findChildPointerForThread(node, cnid);
                if (childNode == 0) return null;
                nodeNum = childNode;
            } else {
                return null;
            }
        }
        return null;
    }

    private int findChildPointer(HfsPlusBTreeNode node, int parentId, String name) {
        int bestChild = 0;
        for (int i = 0; i < node.numRecords(); i++) {
            byte[] record = node.getRecordData(i);
            if (record.length < 8) continue;

            ByteBuffer keyBuf = ByteBuffer.wrap(record);
            keyBuf.order(ByteOrder.BIG_ENDIAN);
            int keyLength = keyBuf.getShort(0) & 0xFFFF;
            int recordParentId = keyBuf.getInt(2);

            int cmp;
            if (recordParentId != parentId) {
                cmp = Integer.compare(recordParentId, parentId);
            } else {
                String recordName = readUnicodeName(record, 6);
                cmp = recordName.compareToIgnoreCase(name);
            }

            if (cmp <= 0) {
                // Read child pointer at end of record (after key)
                int pointerOffset = 2 + keyLength;
                // Align to even offset
                if (pointerOffset % 2 != 0) pointerOffset++;
                if (pointerOffset + 4 <= record.length) {
                    bestChild = ((record[pointerOffset] & 0xFF) << 24) |
                                ((record[pointerOffset + 1] & 0xFF) << 16) |
                                ((record[pointerOffset + 2] & 0xFF) << 8) |
                                (record[pointerOffset + 3] & 0xFF);
                }
            } else {
                break;
            }
        }
        return bestChild;
    }

    private int findChildPointerForThread(HfsPlusBTreeNode node, int cnid) {
        int bestChild = 0;
        for (int i = 0; i < node.numRecords(); i++) {
            byte[] record = node.getRecordData(i);
            if (record.length < 8) continue;

            ByteBuffer keyBuf = ByteBuffer.wrap(record);
            keyBuf.order(ByteOrder.BIG_ENDIAN);
            int keyLength = keyBuf.getShort(0) & 0xFFFF;
            int recordParentId = keyBuf.getInt(2);

            if (recordParentId <= cnid) {
                int pointerOffset = 2 + keyLength;
                if (pointerOffset % 2 != 0) pointerOffset++;
                if (pointerOffset + 4 <= record.length) {
                    bestChild = ((record[pointerOffset] & 0xFF) << 24) |
                                ((record[pointerOffset + 1] & 0xFF) << 16) |
                                ((record[pointerOffset + 2] & 0xFF) << 8) |
                                (record[pointerOffset + 3] & 0xFF);
                }
            } else {
                break;
            }
        }
        return bestChild;
    }

    /**
     * Reads a Unicode name from a catalog key.
     * Format: nameLength (2 bytes, big-endian) + UTF-16BE chars.
     */
    static String readUnicodeName(byte[] data, int offset) {
        if (offset + 2 > data.length) return "";
        int nameLength = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        if (nameLength == 0) return "";
        int charBytes = nameLength * 2;
        if (offset + 2 + charBytes > data.length) {
            charBytes = data.length - offset - 2;
            nameLength = charBytes / 2;
        }
        char[] chars = new char[nameLength];
        for (int i = 0; i < nameLength; i++) {
            chars[i] = (char) (((data[offset + 2 + i * 2] & 0xFF) << 8) |
                               (data[offset + 2 + i * 2 + 1] & 0xFF));
        }
        return new String(chars);
    }

    /**
     * Searches the extents overflow B-tree for additional extents belonging to the
     * given file (by CNID) and fork type, starting from the given allocation block offset.
     *
     * <p>The extents overflow B-tree key format is:
     * <pre>
     * offset 0: uint16 keyLength (always 10)
     * offset 2: uint8  forkType (0=data, 0xFF=resource)
     * offset 3: uint8  pad (0)
     * offset 4: uint32 cnid
     * offset 8: uint32 startBlock
     * </pre>
     *
     * <p>Each record value contains 8 extent descriptors (startBlock:4 + blockCount:4 each).
     *
     * @param cnid the catalog node ID of the file
     * @param forkType 0 for data fork, 0xFF for resource fork
     * @param startBlock the first allocation block number to search from
     * @return list of additional extents found in the overflow file
     */
    public @NotNull List<HfsPlusExtent> findOverflowExtents(int cnid, int forkType, long startBlock)
            throws IOException {
        List<HfsPlusExtent> result = new ArrayList<>();
        if (header.rootNode() == 0) {
            return result;
        }

        // First, navigate to the correct leaf node using B-tree traversal
        long currentStartBlock = startBlock;

        while (true) {
            List<HfsPlusExtent> batch = findOverflowExtentRecord(cnid, forkType, currentStartBlock);
            if (batch.isEmpty()) {
                break;
            }
            long batchBlocks = 0;
            for (HfsPlusExtent ext : batch) {
                batchBlocks += ext.blockCount();
            }
            result.addAll(batch);
            currentStartBlock += batchBlocks;
        }

        return result;
    }

    /**
     * Finds a single overflow extent record (8 extents) for the given key.
     */
    private @NotNull List<HfsPlusExtent> findOverflowExtentRecord(int cnid, int forkType, long startBlock)
            throws IOException {
        int nodeNum = header.rootNode();
        int maxDepth = header.treeDepth() + 1;

        for (int depth = 0; depth < maxDepth; depth++) {
            HfsPlusBTreeNode node = readNode(nodeNum);

            if (node.isLeaf()) {
                // Search leaf for exact match on (forkType, cnid, startBlock)
                for (int i = 0; i < node.numRecords(); i++) {
                    byte[] record = node.getRecordData(i);
                    if (record.length < 12) continue;

                    ByteBuffer keyBuf = ByteBuffer.wrap(record);
                    keyBuf.order(ByteOrder.BIG_ENDIAN);

                    int keyLength = keyBuf.getShort(0) & 0xFFFF;
                    int recForkType = record[2] & 0xFF;
                    int recCnid = keyBuf.getInt(4);
                    long recStartBlock = keyBuf.getInt(8) & 0xFFFFFFFFL;

                    if (recForkType == forkType && recCnid == cnid && recStartBlock == startBlock) {
                        // Found matching record; parse 8 extents from value
                        int valueOffset = 2 + keyLength;
                        if (valueOffset % 2 != 0) valueOffset++;
                        return parseExtentRecord(record, valueOffset);
                    }
                }
                return List.of();
            } else if (node.isIndex()) {
                int childNode = findOverflowChildPointer(node, cnid, forkType, startBlock);
                if (childNode == 0) {
                    return List.of();
                }
                nodeNum = childNode;
            } else {
                return List.of();
            }
        }
        return List.of();
    }

    /**
     * Finds the child pointer in an index node for the extents overflow B-tree.
     * Key comparison order: forkType, then cnid, then startBlock (all big-endian unsigned).
     */
    private int findOverflowChildPointer(HfsPlusBTreeNode node, int cnid, int forkType, long startBlock) {
        int bestChild = 0;
        for (int i = 0; i < node.numRecords(); i++) {
            byte[] record = node.getRecordData(i);
            if (record.length < 12) continue;

            ByteBuffer keyBuf = ByteBuffer.wrap(record);
            keyBuf.order(ByteOrder.BIG_ENDIAN);

            int keyLength = keyBuf.getShort(0) & 0xFFFF;
            int recForkType = record[2] & 0xFF;
            int recCnid = keyBuf.getInt(4);
            long recStartBlock = keyBuf.getInt(8) & 0xFFFFFFFFL;

            // Compare: forkType, cnid, startBlock
            int cmp = Integer.compare(recForkType, forkType);
            if (cmp == 0) {
                cmp = Integer.compareUnsigned(recCnid, cnid);
            }
            if (cmp == 0) {
                cmp = Long.compare(recStartBlock, startBlock);
            }

            if (cmp <= 0) {
                int pointerOffset = 2 + keyLength;
                if (pointerOffset % 2 != 0) pointerOffset++;
                if (pointerOffset + 4 <= record.length) {
                    bestChild = ((record[pointerOffset] & 0xFF) << 24) |
                                ((record[pointerOffset + 1] & 0xFF) << 16) |
                                ((record[pointerOffset + 2] & 0xFF) << 8) |
                                (record[pointerOffset + 3] & 0xFF);
                }
            } else {
                break;
            }
        }
        return bestChild;
    }

    /**
     * Parses 8 extent descriptors from the value portion of an overflow extent record.
     */
    private static List<HfsPlusExtent> parseExtentRecord(byte[] record, int valueOffset) {
        List<HfsPlusExtent> extents = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int off = valueOffset + i * 8;
            if (off + 8 > record.length) break;
            ByteBuffer buf = ByteBuffer.wrap(record, off, 8);
            buf.order(ByteOrder.BIG_ENDIAN);
            long sb = buf.getInt(0) & 0xFFFFFFFFL;
            long bc = buf.getInt(4) & 0xFFFFFFFFL;
            if (bc > 0) {
                extents.add(new HfsPlusExtent(sb, bc));
            }
        }
        return extents;
    }

    /**
     * Reads node data from disk, resolving fork extents to physical locations.
     */
    private static byte[] readNodeData(DiskRegion region, List<HfsPlusExtent> extents,
                                         int blockSize, int nodeNumber, int nodeSize) throws IOException {
        long byteOffset = (long) nodeNumber * nodeSize;
        byte[] data = new byte[nodeSize];
        int bytesRead = 0;

        while (bytesRead < nodeSize) {
            long currentOffset = byteOffset + bytesRead;
            long logicalBlock = currentOffset / blockSize;
            int blockOffset = (int) (currentOffset % blockSize);

            long physicalBlock = HfsPlusExtent.resolveLogicalBlock(extents, logicalBlock);
            if (physicalBlock < 0) {
                break;
            }

            long diskOffset = physicalBlock * blockSize + blockOffset;
            int toRead = Math.min(nodeSize - bytesRead, blockSize - blockOffset);

            ByteBuffer buf = region.read(diskOffset, toRead);
            buf.get(data, bytesRead, toRead);
            bytesRead += toRead;
        }

        return data;
    }
}
