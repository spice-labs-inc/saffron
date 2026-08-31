/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.apfs;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * APFS Object Map (omap).
 *
 * <p>The object map is a B-tree that maps (OID, XID) pairs to physical block addresses.
 * It is the core indirection layer in APFS - virtual objects are resolved through
 * the omap to find their physical location on disk.
 *
 * <p>Omap B-tree uses fixed-size keys:
 * <pre>
 * Key:   oid(8) + xid(8) = 16 bytes
 * Value: flags(4) + size(4) + paddr(8) = 16 bytes (leaf)
 *        or oid(8) = 8 bytes (index)
 * </pre>
 */
public class ApfsObjectMap {

    private final ApfsBTreeReader btreeReader;
    private final long rootBlock;
    private final io.spicelabs.saffron.io.LruCache<Long, Long> cache =
            new io.spicelabs.saffron.io.LruCache<>(MAX_CACHE_ENTRIES);

    /** Test/observation seam for the bounded cache (package scope). */
    int cacheSize() {
        return cache.size();
    }

    /** Object-map resolution cache: bounded LRU (hostile walks). */
    private static final int MAX_CACHE_ENTRIES = 4096;

    /** Maximum omap descent depth (real omaps are shallow). */
    private static final int MAX_OMAP_DEPTH = 64;

    private ApfsObjectMap(ApfsBTreeReader btreeReader, long rootBlock) {
        this.btreeReader = btreeReader;
        this.rootBlock = rootBlock;
    }

    /**
     * Opens the object map from its physical block.
     *
     * <p>The omap object at the given block contains a pointer to the B-tree root.
     */
    public static @NotNull ApfsObjectMap open(@NotNull DiskRegion region, int blockSize,
                                                long omapBlock) throws IOException {
        // Read the omap object
        ByteBuffer buf = region.read(omapBlock * blockSize, blockSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // obj_phys_t header (32 bytes), then omap_phys_t:
        // om_flags(4) + om_snap_count(4) + om_tree_type(4) + om_snapshot_tree_type(4)
        // + om_tree_oid(8) + om_snapshot_tree_oid(8) + ...
        // om_tree_oid at offset 32 + 16 = 48
        long treeOid = buf.getLong(48);

        ApfsBTreeReader reader = new ApfsBTreeReader(region, blockSize);
        return new ApfsObjectMap(reader, treeOid);
    }

    /**
     * Resolves a virtual OID to a physical block address.
     *
     * @param oid the virtual object identifier
     * @param maxXid the maximum transaction ID to consider
     * @return the physical block address, or -1 if not found
     */
    public long resolve(long oid, long maxXid) throws IOException {
        // Check cache first
        Long cached = cache.get(oid);
        if (cached != null) {
            return cached;
        }

        long result = searchOmap(oid, maxXid);
        if (result >= 0) {
            cache.put(oid, result);
        }
        return result;
    }

    /**
     * Resolves a virtual OID to a physical block, searching the omap B-tree.
     */
    private long searchOmap(long oid, long maxXid) throws IOException {
        ApfsBTreeReader.BTreeNode node = btreeReader.readNode(rootBlock);
        return searchOmapNode(node, oid, maxXid, 0, new java.util.HashSet<>());
    }

    /**
     * Depth-capped, cycle-guarded omap descent: a hostile omap index that
     * references an ancestor must fail checked, never
     * {@link StackOverflowError}.
     */
    private long searchOmapNode(ApfsBTreeReader.BTreeNode node, long oid, long maxXid,
                                int depth, java.util.Set<Long> visited) throws IOException {
        if (depth > MAX_OMAP_DEPTH) {
            throw new IOException("apfs object map too deep");
        }
        if (node.isLeaf()) {
            // Search for the entry with matching OID and highest XID <= maxXid
            long bestPaddr = -1;
            long bestXid = -1;

            for (ApfsBTreeReader.KVEntry entry : node.entries()) {
                if (entry.key().length < 16 || entry.val().length < 16) continue;

                ByteBuffer keyBuf = ByteBuffer.wrap(entry.key());
                keyBuf.order(ByteOrder.LITTLE_ENDIAN);
                long entryOid = keyBuf.getLong(0);
                long entryXid = keyBuf.getLong(8);

                if (entryOid == oid && entryXid <= maxXid && entryXid > bestXid) {
                    ByteBuffer valBuf = ByteBuffer.wrap(entry.val());
                    valBuf.order(ByteOrder.LITTLE_ENDIAN);
                    // flags(4) + size(4) + paddr(8)
                    bestPaddr = valBuf.getLong(8);
                    bestXid = entryXid;
                }
            }

            return bestPaddr;
        } else {
            // Index node: find the right child
            long bestChildOid = -1;

            for (int i = node.entries().size() - 1; i >= 0; i--) {
                ApfsBTreeReader.KVEntry entry = node.entries().get(i);
                if (entry.key().length < 16) continue;

                ByteBuffer keyBuf = ByteBuffer.wrap(entry.key());
                keyBuf.order(ByteOrder.LITTLE_ENDIAN);
                long entryOid = keyBuf.getLong(0);

                if (entryOid <= oid) {
                    // Descend into this child
                    long childBlock = readOidFromValue(entry.val());
                    if (!visited.add(childBlock)) {
                        throw new IOException("apfs object map cycle at block " + childBlock);
                    }
                    ApfsBTreeReader.BTreeNode child = btreeReader.readNode(childBlock);
                    long result = searchOmapNode(child, oid, maxXid, depth + 1, visited);
                    if (result >= 0) return result;
                    break;
                }
            }

            return -1;
        }
    }

    /**
     * Creates a resolver function suitable for use with ApfsBTreeReader.
     */
    public java.util.function.BiFunction<Long, Long, Long> resolver() {
        return (oid, xid) -> {
            try {
                long result = resolve(oid, xid);
                return result >= 0 ? result : null;
            } catch (IOException e) {
                return null;
            }
        };
    }

    private static long readOidFromValue(byte[] val) {
        if (val.length < 8) return 0;
        ByteBuffer buf = ByteBuffer.wrap(val);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return buf.getLong(0);
    }
}
