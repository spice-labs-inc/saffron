/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.apfs;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Generic B-tree reader for APFS.
 *
 * <p>APFS uses B-trees for object maps, filesystem trees, and other indexes.
 * Each node starts with obj_phys_t (32 bytes) followed by btree_node_phys_t (24 bytes),
 * then the table of contents (TOC), keys area, and values area.
 *
 * <p>Node layout:
 * <pre>
 * [obj_phys_t: 32 bytes]
 * [btn_flags: 2] [btn_level: 2] [btn_nkeys: 4]
 * [btn_table_space offset: 2] [btn_table_space len: 2]
 * [btn_free_space offset: 2] [btn_free_space len: 2]
 * [btn_key_free_list offset: 2] [btn_key_free_list len: 2]
 * [btn_val_free_list offset: 2] [btn_val_free_list len: 2]
 * [TOC entries...]
 * [Key data...]
 * [Free space...]
 * [Value data...] (grows from end of node towards keys)
 * </pre>
 */
public class ApfsBTreeReader {

    // Node flags
    public static final int BTNODE_ROOT = 0x0001;
    public static final int BTNODE_LEAF = 0x0002;
    public static final int BTNODE_FIXED_KV_SIZE = 0x0004;

    private static final int BTREE_NODE_PHYS_OFFSET = ApfsObjectHeader.SIZE; // 32
    private static final int BTREE_NODE_PHYS_SIZE = 24;

    private final DiskRegion region;
    private final int blockSize;

    public ApfsBTreeReader(@NotNull DiskRegion region, int blockSize) {
        this.region = region;
        this.blockSize = blockSize;
    }

    /**
     * Reads a B-tree node from a physical block address.
     */
    public @NotNull BTreeNode readNode(long physicalBlock) throws IOException {
        long offset = physicalBlock * blockSize;
        ByteBuffer buf = region.read(offset, blockSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return parseNode(buf);
    }

    /**
     * Reads a B-tree node from raw buffer data.
     */
    public static @NotNull BTreeNode parseNode(ByteBuffer buf) {
        buf.order(ByteOrder.LITTLE_ENDIAN);

        ApfsObjectHeader objHeader = ApfsObjectHeader.parse(buf);

        // btree_node_phys_t
        int flags = buf.getShort(32) & 0xFFFF;
        int level = buf.getShort(34) & 0xFFFF;
        int nkeys = buf.getInt(36);
        int tableSpaceOffset = buf.getShort(40) & 0xFFFF;
        int tableSpaceLen = buf.getShort(42) & 0xFFFF;
        int freeSpaceOffset = buf.getShort(44) & 0xFFFF;
        int freeSpaceLen = buf.getShort(46) & 0xFFFF;

        boolean isLeaf = (flags & BTNODE_LEAF) != 0;
        boolean isRoot = (flags & BTNODE_ROOT) != 0;
        boolean fixedKV = (flags & BTNODE_FIXED_KV_SIZE) != 0;

        // TOC starts at offset 56 + tableSpaceOffset
        int tocStart = BTREE_NODE_PHYS_OFFSET + BTREE_NODE_PHYS_SIZE + tableSpaceOffset;

        // Keys area starts after TOC
        int keysAreaStart = BTREE_NODE_PHYS_OFFSET + BTREE_NODE_PHYS_SIZE + tableSpaceOffset + tableSpaceLen;

        // Copy buf data for later access
        byte[] data = new byte[buf.capacity()];
        buf.position(0);
        buf.get(data);

        List<KVEntry> entries = new ArrayList<>();
        int nodeSize = data.length;

        for (int i = 0; i < nkeys; i++) {
            int keyOff, keyLen, valOff, valLen;

            if (fixedKV) {
                // Fixed-size entries: TOC has (key_offset:2, val_offset:2)
                int tocEntryOffset = tocStart + i * 4;
                if (tocEntryOffset + 4 > data.length) break;
                keyOff = ((data[tocEntryOffset] & 0xFF) | ((data[tocEntryOffset + 1] & 0xFF) << 8));
                valOff = ((data[tocEntryOffset + 2] & 0xFF) | ((data[tocEntryOffset + 3] & 0xFF) << 8));
                // For fixed-size, we need to infer sizes from the B-tree info
                // Omap trees use 16-byte keys and 16-byte values (leaf) or 8-byte values (index)
                keyLen = 16;
                valLen = isLeaf ? 16 : 8;
            } else {
                // Variable-size entries: TOC has (key_offset:2, key_len:2, val_offset:2, val_len:2)
                int tocEntryOffset = tocStart + i * 8;
                if (tocEntryOffset + 8 > data.length) break;
                keyOff = (data[tocEntryOffset] & 0xFF) | ((data[tocEntryOffset + 1] & 0xFF) << 8);
                keyLen = (data[tocEntryOffset + 2] & 0xFF) | ((data[tocEntryOffset + 3] & 0xFF) << 8);
                valOff = (data[tocEntryOffset + 4] & 0xFF) | ((data[tocEntryOffset + 5] & 0xFF) << 8);
                valLen = (data[tocEntryOffset + 6] & 0xFF) | ((data[tocEntryOffset + 7] & 0xFF) << 8);
            }

            // Keys are at keysAreaStart + keyOff
            int absoluteKeyOff = keysAreaStart + keyOff;

            // Values grow from end of node backwards (both leaf and non-leaf).
            // For root nodes, there's a btree_info_t (40 bytes) at the very end.
            int valEnd = isRoot ? nodeSize - 40 : nodeSize;
            int absoluteValOff = valEnd - valOff;

            byte[] key = safeSlice(data, absoluteKeyOff, keyLen);
            byte[] val = safeSlice(data, absoluteValOff, valLen);

            entries.add(new KVEntry(key, val));
        }

        return new BTreeNode(objHeader, flags, level, nkeys, isLeaf, isRoot, fixedKV, entries);
    }

    private static byte[] safeSlice(byte[] data, int offset, int length) {
        if (offset < 0 || offset + length > data.length || length < 0) {
            return new byte[0];
        }
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }

    /**
     * Searches a B-tree starting from the given root physical block.
     * Uses the key comparator to find matching entries.
     *
     * @param rootBlock physical block of the root node
     * @param keyComparator compares a node's key to the search key; returns negative/zero/positive
     * @param resolveVirtual function to resolve virtual OID to physical block (for non-leaf nodes)
     * @return list of matching leaf entries
     */
    public @NotNull List<KVEntry> search(long rootBlock,
                                           @NotNull BiFunction<byte[], Void, Integer> keyComparator,
                                           @Nullable BiFunction<Long, Long, Long> resolveVirtual) throws IOException {
        BTreeNode node = readNode(rootBlock);
        return searchNode(node, keyComparator, resolveVirtual);
    }

    private List<KVEntry> searchNode(BTreeNode node,
                                       BiFunction<byte[], Void, Integer> keyComparator,
                                       BiFunction<Long, Long, Long> resolveVirtual) throws IOException {
        List<KVEntry> results = new ArrayList<>();

        if (node.isLeaf()) {
            for (KVEntry entry : node.entries()) {
                int cmp = keyComparator.apply(entry.key(), null);
                if (cmp == 0) {
                    results.add(entry);
                }
            }
        } else {
            // Index node: find the child to descend into
            for (int i = node.entries().size() - 1; i >= 0; i--) {
                KVEntry entry = node.entries().get(i);
                int cmp = keyComparator.apply(entry.key(), null);
                if (cmp >= 0) {
                    // Descend into this child
                    long childOid = readOidFromValue(entry.val());
                    long childBlock;
                    if (resolveVirtual != null) {
                        Long resolved = resolveVirtual.apply(childOid, node.header().xid());
                        if (resolved == null) continue;
                        childBlock = resolved;
                    } else {
                        childBlock = childOid; // Physical
                    }
                    BTreeNode child = readNode(childBlock);
                    results.addAll(searchNode(child, keyComparator, resolveVirtual));
                    break;
                }
            }
        }

        return results;
    }

    /**
     * Collects all leaf entries from a B-tree that match a prefix.
     *
     * @param rootBlock physical block of root node
     * @param prefixMatcher returns true if a key matches the prefix
     * @param resolveVirtual OID resolver
     * @return all matching leaf entries
     */
    public @NotNull List<KVEntry> collectPrefix(long rootBlock,
                                                  @NotNull BiFunction<byte[], Void, Boolean> prefixMatcher,
                                                  @Nullable BiFunction<Long, Long, Long> resolveVirtual) throws IOException {
        BTreeNode node = readNode(rootBlock);
        List<KVEntry> results = new ArrayList<>();
        collectPrefixFromNode(node, prefixMatcher, resolveVirtual, results, 0);
        return results;
    }

    private void collectPrefixFromNode(BTreeNode node,
                                         BiFunction<byte[], Void, Boolean> prefixMatcher,
                                         BiFunction<Long, Long, Long> resolveVirtual,
                                         List<KVEntry> results,
                                         int depth) throws IOException {
        if (depth > 20) return; // Safety limit

        if (node.isLeaf()) {
            for (KVEntry entry : node.entries()) {
                if (prefixMatcher.apply(entry.key(), null)) {
                    results.add(entry);
                }
            }
        } else {
            // Need to descend into all children that might contain matching keys
            for (KVEntry entry : node.entries()) {
                long childOid = readOidFromValue(entry.val());
                long childBlock;
                if (resolveVirtual != null) {
                    Long resolved = resolveVirtual.apply(childOid, node.header().xid());
                    if (resolved == null) continue;
                    childBlock = resolved;
                } else {
                    childBlock = childOid;
                }
                BTreeNode child = readNode(childBlock);
                collectPrefixFromNode(child, prefixMatcher, resolveVirtual, results, depth + 1);
            }
        }
    }

    private static long readOidFromValue(byte[] val) {
        if (val.length < 8) return 0;
        ByteBuffer buf = ByteBuffer.wrap(val);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return buf.getLong(0);
    }

    /**
     * A parsed B-tree node.
     */
    public record BTreeNode(
            ApfsObjectHeader header,
            int flags,
            int level,
            int nkeys,
            boolean isLeaf,
            boolean isRoot,
            boolean fixedKV,
            List<KVEntry> entries
    ) {}

    /**
     * A key-value entry from a B-tree node.
     */
    public record KVEntry(byte[] key, byte[] val) {}
}
