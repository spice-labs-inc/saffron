/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import io.spicelabs.saffron.lvm.DiskRegion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages the Btrfs chunk tree for logical-to-physical address translation.
 *
 * <p>Btrfs uses logical addresses in most metadata. The chunk tree maps
 * logical address ranges to physical device addresses.
 */
public class BtrfsChunkTree {

    private final DiskRegion region;
    private final long partitionOffset;
    private final List<Chunk> chunks;

    /**
     * Represents a single chunk mapping from logical to physical address.
     */
    public record Chunk(
            long logicalAddress,
            long length,
            long stripeLen,
            long type,
            int numStripes,
            List<Stripe> stripes
    ) {}

    /**
     * Represents a stripe within a chunk (physical location).
     */
    public record Stripe(
            long devId,
            long offset
    ) {}

    private BtrfsChunkTree(DiskRegion region, long partitionOffset, List<Chunk> chunks) {
        this.region = region;
        this.partitionOffset = partitionOffset;
        this.chunks = chunks;
    }

    /**
     * Parses a chunk item from a ByteBuffer at the current position.
     *
     * @param logicalAddress the logical address for this chunk (from the key's offset)
     * @param buf the buffer positioned at the start of the chunk item data
     * @return the parsed Chunk
     */
    private static Chunk parseChunkItem(long logicalAddress, ByteBuffer buf) {
        long length = buf.getLong();
        long owner = buf.getLong();
        long stripeLen = buf.getLong();
        long type = buf.getLong();  // u64, not u32
        int ioAlign = buf.getInt();
        int ioWidth = buf.getInt();
        int sectorSize = buf.getInt();
        int numStripes = buf.getShort() & 0xFFFF;
        int subStripes = buf.getShort() & 0xFFFF;

        List<Stripe> stripes = new ArrayList<>();
        for (int i = 0; i < numStripes; i++) {
            if (buf.remaining() < 32) break;
            long devId = buf.getLong();
            long offset = buf.getLong();
            // Skip dev_uuid (16 bytes)
            buf.position(buf.position() + 16);
            stripes.add(new Stripe(devId, offset));
        }

        return new Chunk(logicalAddress, length, stripeLen, type, numStripes, stripes);
    }

    /**
     * Parses the chunk tree from the superblock's embedded system chunk array,
     * then loads the full chunk tree from disk for complete logical-to-physical mappings.
     */
    public static BtrfsChunkTree parse(DiskRegion region, long partitionOffset, BtrfsSuperblock sb) throws IOException {
        List<Chunk> chunks = new ArrayList<>();

        // Phase 1: Parse bootstrap (system) chunks from superblock's sys_chunk_array
        ByteBuffer buf = ByteBuffer.wrap(sb.sysChunkArray());
        buf.order(ByteOrder.LITTLE_ENDIAN);

        while (buf.remaining() >= BtrfsKey.SIZE + 48) {
            BtrfsKey key = BtrfsKey.read(buf);
            if (key.type() != BtrfsKey.CHUNK_ITEM) {
                break;
            }
            chunks.add(parseChunkItem(key.offset(), buf));
        }

        // Phase 2: Load the full chunk tree from disk using bootstrap chunks
        Set<Long> knownLogicalAddresses = new HashSet<>();
        for (Chunk c : chunks) {
            knownLogicalAddresses.add(c.logicalAddress());
        }

        BtrfsChunkTree bootstrapTree = new BtrfsChunkTree(region, partitionOffset,
                List.copyOf(chunks));
        BtrfsTreeReader treeReader = new BtrfsTreeReader(bootstrapTree, sb.nodeSize());

        try {
            List<BtrfsTreeReader.SearchResult> chunkItems =
                    treeReader.scanForType(sb.chunkTreeRoot(), BtrfsKey.CHUNK_ITEM, Integer.MAX_VALUE);

            for (BtrfsTreeReader.SearchResult result : chunkItems) {
                long logicalAddr = result.item().key().offset();
                if (knownLogicalAddresses.contains(logicalAddr)) {
                    continue; // Already have this from bootstrap
                }
                ByteBuffer itemBuf = ByteBuffer.wrap(result.data());
                itemBuf.order(ByteOrder.LITTLE_ENDIAN);
                chunks.add(parseChunkItem(logicalAddr, itemBuf));
                knownLogicalAddresses.add(logicalAddr);
            }
        } catch (IOException | IllegalArgumentException e) {
            // If full chunk tree loading fails, fall back to bootstrap-only chunks.
            // This can happen if the chunk tree root itself is not mappable via bootstrap,
            // or if the underlying disk reader rejects the translated physical address.
        }

        // Sort chunks by logical address for binary search
        chunks.sort(Comparator.comparingLong(Chunk::logicalAddress));

        return new BtrfsChunkTree(region, partitionOffset, chunks);
    }

    /**
     * Translates a logical address to a physical address.
     *
     * @param logicalAddr the logical address to translate
     * @return the physical address
     * @throws IOException if the logical address is not mapped
     */
    public long translateLogical(long logicalAddr) throws IOException {
        for (Chunk chunk : chunks) {
            if (logicalAddr >= chunk.logicalAddress &&
                    logicalAddr < chunk.logicalAddress + chunk.length) {

                long offsetInChunk = logicalAddr - chunk.logicalAddress;

                // For simple single-device case, use first stripe
                if (!chunk.stripes.isEmpty()) {
                    Stripe stripe = chunk.stripes.get(0);
                    return partitionOffset + stripe.offset + offsetInChunk;
                }
            }
        }
        throw new IOException("Logical address not mapped: " + logicalAddr);
    }

    /**
     * Reads data at a logical address.
     *
     * @param logicalAddr the logical address to read from
     * @param length the number of bytes to read
     * @throws IOException if an I/O error occurs
     */
    public ByteBuffer readLogical(long logicalAddr, int length) throws IOException {
        long physicalAddr = translateLogical(logicalAddr);
        return region.read(physicalAddr, length);
    }

    /**
     * Returns the number of chunks in the tree.
     */
    public int chunkCount() {
        return chunks.size();
    }

    /**
     * Returns all chunks for debugging.
     */
    public List<Chunk> chunks() {
        return List.copyOf(chunks);
    }
}
