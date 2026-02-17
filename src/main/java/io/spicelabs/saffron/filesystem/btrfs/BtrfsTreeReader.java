/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides B-tree traversal and search utilities for Btrfs.
 */
public class BtrfsTreeReader {

    private final BtrfsChunkTree chunkTree;
    private final int nodeSize;

    /**
     * Represents a node header in a Btrfs B-tree.
     */
    public record NodeHeader(
            byte[] csum,         // 32 bytes
            byte[] fsid,         // 16 bytes
            long bytenr,         // Logical address of this node
            long flags,
            byte[] chunkTreeUuid, // 16 bytes
            long generation,
            long owner,          // Tree that owns this node
            int nrItems,
            int level            // 0 = leaf
    ) {
        public static final int SIZE = 101;

        public boolean isLeaf() {
            return level == 0;
        }
    }

    /**
     * Represents an item pointer in a leaf node.
     */
    public record LeafItem(
            BtrfsKey key,
            int dataOffset,  // Offset from end of leaf
            int dataSize
    ) {
        public static final int SIZE = BtrfsKey.SIZE + 4 + 4;  // key + offset + size
    }

    /**
     * Represents a key pointer in an internal node.
     */
    public record KeyPtr(
            BtrfsKey key,
            long blockPtr,   // Logical address of child
            long generation
    ) {
        public static final int SIZE = BtrfsKey.SIZE + 8 + 8;  // key + ptr + gen
    }

    public BtrfsTreeReader(BtrfsChunkTree chunkTree, int nodeSize) {
        this.chunkTree = chunkTree;
        this.nodeSize = nodeSize;
    }

    /**
     * Reads a node header from a logical address.
     */
    public NodeHeader readHeader(long logicalAddr) throws IOException {
        ByteBuffer buf = chunkTree.readLogical(logicalAddr, NodeHeader.SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        byte[] csum = new byte[32];
        buf.get(csum);

        byte[] fsid = new byte[16];
        buf.get(fsid);

        long bytenr = buf.getLong();
        long flags = buf.getLong();

        byte[] chunkTreeUuid = new byte[16];
        buf.get(chunkTreeUuid);

        long generation = buf.getLong();
        long owner = buf.getLong();
        int nrItems = buf.getInt();
        int level = buf.get() & 0xFF;

        return new NodeHeader(csum, fsid, bytenr, flags, chunkTreeUuid, generation, owner, nrItems, level);
    }

    /**
     * Reads all items from a leaf node.
     */
    public List<LeafItem> readLeafItems(long logicalAddr) throws IOException {
        ByteBuffer buf = chunkTree.readLogical(logicalAddr, nodeSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Read header to get item count
        buf.position(0);
        byte[] csum = new byte[32];
        buf.get(csum);
        buf.position(32 + 16 + 8 + 8 + 16 + 8 + 8);  // Skip to nritems
        int nrItems = buf.getInt();

        // Read items starting after header
        buf.position(NodeHeader.SIZE);
        List<LeafItem> items = new ArrayList<>(nrItems);
        for (int i = 0; i < nrItems; i++) {
            BtrfsKey key = BtrfsKey.read(buf);
            int dataOffset = buf.getInt();
            int dataSize = buf.getInt();
            items.add(new LeafItem(key, dataOffset, dataSize));
        }
        return items;
    }

    /**
     * Reads item data from a leaf node.
     *
     * @param logicalAddr logical address of the leaf
     * @param item the item whose data to read
     * @return the item data
     */
    public byte[] readItemData(long logicalAddr, LeafItem item) throws IOException {
        ByteBuffer buf = chunkTree.readLogical(logicalAddr, nodeSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Item data offset is relative to the leaf data area start (right after the header)
        int dataStart = NodeHeader.SIZE + item.dataOffset();
        byte[] data = new byte[item.dataSize()];
        buf.position(dataStart);
        buf.get(data);
        return data;
    }

    /**
     * Reads key pointers from an internal node.
     */
    public List<KeyPtr> readKeyPtrs(long logicalAddr) throws IOException {
        ByteBuffer buf = chunkTree.readLogical(logicalAddr, nodeSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Read header to get item count
        buf.position(32 + 16 + 8 + 8 + 16 + 8 + 8);  // Skip to nritems
        int nrItems = buf.getInt();

        // Read key pointers after header
        buf.position(NodeHeader.SIZE);
        List<KeyPtr> ptrs = new ArrayList<>(nrItems);
        for (int i = 0; i < nrItems; i++) {
            BtrfsKey key = BtrfsKey.read(buf);
            long blockPtr = buf.getLong();
            long generation = buf.getLong();
            ptrs.add(new KeyPtr(key, blockPtr, generation));
        }
        return ptrs;
    }

    /**
     * Searches for items matching the given object ID and type.
     *
     * @param rootAddr logical address of tree root
     * @param objectId the object ID to find
     * @param type the item type to find
     * @return list of matching items with their leaf addresses
     */
    public List<SearchResult> search(long rootAddr, long objectId, int type) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        BtrfsKey searchKey = new BtrfsKey(objectId, type, 0);
        searchRecursive(rootAddr, searchKey, objectId, type, results);
        return results;
    }

    /**
     * Result of a tree search.
     */
    public record SearchResult(
            long leafAddr,
            LeafItem item,
            byte[] data
    ) {}

    private void searchRecursive(long nodeAddr, BtrfsKey searchKey, long targetObjId, int targetType,
                                  List<SearchResult> results) throws IOException {
        NodeHeader header = readHeader(nodeAddr);

        if (header.isLeaf()) {
            List<LeafItem> items = readLeafItems(nodeAddr);
            for (LeafItem item : items) {
                if (item.key().objectId() == targetObjId && item.key().type() == targetType) {
                    byte[] data = readItemData(nodeAddr, item);
                    results.add(new SearchResult(nodeAddr, item, data));
                }
            }
        } else {
            // Descend into child i only if target objectId is in range
            // [ptr[i].objectId, ptr[i+1].objectId] (inclusive both ends,
            // since items with targetObjId may straddle the boundary)
            List<KeyPtr> ptrs = readKeyPtrs(nodeAddr);
            for (int i = 0; i < ptrs.size(); i++) {
                long ptrObjId = ptrs.get(i).key().objectId();
                if (targetObjId < ptrObjId) {
                    break; // All remaining children have keys > target
                }
                boolean lastChild = (i == ptrs.size() - 1);
                if (lastChild || targetObjId <= ptrs.get(i + 1).key().objectId()) {
                    searchRecursive(ptrs.get(i).blockPtr(), searchKey, targetObjId, targetType, results);
                }
            }
        }
    }

    /**
     * Finds all items in a tree for a given object ID.
     */
    public List<SearchResult> findAllForObject(long rootAddr, long objectId) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        findAllRecursive(rootAddr, objectId, results);
        return results;
    }

    private void findAllRecursive(long nodeAddr, long targetObjId, List<SearchResult> results) throws IOException {
        NodeHeader header = readHeader(nodeAddr);

        if (header.isLeaf()) {
            List<LeafItem> items = readLeafItems(nodeAddr);
            for (LeafItem item : items) {
                if (item.key().objectId() == targetObjId) {
                    byte[] data = readItemData(nodeAddr, item);
                    results.add(new SearchResult(nodeAddr, item, data));
                }
            }
        } else {
            List<KeyPtr> ptrs = readKeyPtrs(nodeAddr);
            for (int i = 0; i < ptrs.size(); i++) {
                long ptrObjId = ptrs.get(i).key().objectId();
                if (targetObjId < ptrObjId) {
                    break;
                }
                boolean lastChild = (i == ptrs.size() - 1);
                if (lastChild || targetObjId <= ptrs.get(i + 1).key().objectId()) {
                    findAllRecursive(ptrs.get(i).blockPtr(), targetObjId, results);
                }
            }
        }
    }

    /**
     * Scans a tree for all items of a given type.
     */
    public List<SearchResult> scanForType(long rootAddr, int type, int limit) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        scanForTypeRecursive(rootAddr, type, results, limit);
        return results;
    }

    private void scanForTypeRecursive(long nodeAddr, int targetType, List<SearchResult> results, int limit) throws IOException {
        if (results.size() >= limit) return;

        NodeHeader header = readHeader(nodeAddr);

        if (header.isLeaf()) {
            List<LeafItem> items = readLeafItems(nodeAddr);
            for (LeafItem item : items) {
                if (results.size() >= limit) return;
                if (item.key().type() == targetType) {
                    byte[] data = readItemData(nodeAddr, item);
                    results.add(new SearchResult(nodeAddr, item, data));
                }
            }
        } else {
            List<KeyPtr> ptrs = readKeyPtrs(nodeAddr);
            for (KeyPtr ptr : ptrs) {
                if (results.size() >= limit) return;
                scanForTypeRecursive(ptr.blockPtr(), targetType, results, limit);
            }
        }
    }
}
