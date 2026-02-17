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
package io.spicelabs.saffron.qcow2.cluster;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.common.SecurityUtils;
import io.spicelabs.saffron.exception.CorruptedDiskException;
import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.qcow2.header.Qcow2Header;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Handles QCOW2 cluster lookup and data reading.
 *
 * <p>QCOW2 uses a two-level table structure for mapping virtual addresses to
 * physical cluster locations:
 * <ul>
 *   <li><b>L1 Table</b>: Top-level table with entries pointing to L2 tables</li>
 *   <li><b>L2 Table</b>: Second-level table with entries pointing to data clusters</li>
 * </ul>
 *
 * <h2>Address Translation</h2>
 * <pre>
 * Virtual Offset:
 * +------------------+------------------+------------------+
 * | L1 index         | L2 index         | Cluster offset   |
 * | (l2_bits bits)   | (cluster_bits-3) | (cluster_bits)   |
 * +------------------+------------------+------------------+
 * </pre>
 *
 * <h2>L1/L2 Entry Format</h2>
 * <pre>
 * Bit  63: Reserved (must be 0 for L1, copied to L2 for compressed)
 * Bit  62: Compressed flag (L2 only)
 * Bits 9-55: Cluster offset (shifted right by 9)
 * Bits 0-8: Reserved
 * </pre>
 */
public class ClusterReader {

    /** Mask for extracting cluster offset from L1/L2 entry */
    private static final long OFFSET_MASK = 0x00fffffffffffe00L;

    /** Flag indicating a compressed cluster */
    private static final long COMPRESSED_FLAG = 1L << 62;

    /** Flag indicating cluster is all zeros (standard ZERO flag in v3) */
    private static final long ZERO_FLAG = 1L << 0;

    private final SeekableByteChannel channel;
    private final Qcow2Header header;
    private final int clusterSize;
    private final int l2Bits;
    private final int l2Size;
    private final long[] l1Table;
    private final @Nullable VirtualDisk backingDisk;

    // L2 cache (simple single-entry cache)
    private long cachedL2Index = -1;
    private long[] cachedL2Table;

    /**
     * Creates a new cluster reader without a backing disk.
     *
     * @param channel the channel to read from
     * @param header the parsed QCOW2 header
     * @throws IOException if an I/O error occurs during L1 table load
     */
    public ClusterReader(@NotNull SeekableByteChannel channel, @NotNull Qcow2Header header)
            throws IOException {
        this(channel, header, null);
    }

    /**
     * Creates a new cluster reader with an optional backing disk.
     *
     * <p>When a cluster is unallocated in this image, the read will be delegated
     * to the backing disk instead of returning zeros. If the backing disk is null,
     * unallocated clusters read as zeros (the default QCOW2 behavior).
     *
     * @param channel the channel to read from
     * @param header the parsed QCOW2 header
     * @param backingDisk the backing disk to read from for unallocated clusters, or null
     * @throws IOException if an I/O error occurs during L1 table load
     */
    public ClusterReader(@NotNull SeekableByteChannel channel, @NotNull Qcow2Header header,
                         @Nullable VirtualDisk backingDisk)
            throws IOException {
        this.channel = channel;
        this.header = header;
        this.clusterSize = header.clusterSize();
        this.backingDisk = backingDisk;

        // Calculate L2 parameters
        // Each L2 table entry is 8 bytes, so entries per cluster = clusterSize / 8
        this.l2Bits = header.clusterBits() - 3;
        this.l2Size = 1 << l2Bits;

        // Load L1 table
        this.l1Table = loadL1Table();
    }

    /**
     * Reads data from the virtual disk at the specified offset.
     *
     * @param virtualOffset the virtual offset to read from
     * @param length the number of bytes to read
     * @return a buffer containing the read data
     * @throws IOException if an I/O error occurs
     */
    public @NotNull ByteBuffer read(long virtualOffset, int length) throws IOException {
        if (virtualOffset < 0 || virtualOffset >= header.virtualSize()) {
            throw new IllegalArgumentException(
                    "Virtual offset out of range: " + virtualOffset);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive: " + length);
        }

        // Clamp to virtual size
        long maxLength = header.virtualSize() - virtualOffset;
        if (length > maxLength) {
            length = SafeMath.safeToInt(maxLength);
        }

        ByteBuffer result = ByteBuffer.allocate(length);

        while (result.hasRemaining()) {
            long currentOffset = virtualOffset + result.position();
            int remaining = result.remaining();

            // Calculate cluster-relative offset
            int clusterOffset = (int) (currentOffset % clusterSize);
            int bytesInCluster = Math.min(remaining, clusterSize - clusterOffset);

            // Look up physical location
            ClusterMapping mapping = lookupCluster(currentOffset);

            if (mapping.isZero()) {
                // Explicitly zeroed cluster (v3 feature) - always return zeros
                byte[] zeros = new byte[bytesInCluster];
                result.put(zeros);
            } else if (mapping.isUnallocated()) {
                // Unallocated cluster - read from backing disk if available, else zeros
                if (backingDisk != null && currentOffset < backingDisk.virtualSize()) {
                    int backingLength = (int) Math.min(bytesInCluster,
                            backingDisk.virtualSize() - currentOffset);
                    ByteBuffer backingData = backingDisk.read(currentOffset, backingLength);
                    result.put(backingData);
                    // If backing disk is smaller, fill remaining with zeros
                    int remaining2 = bytesInCluster - backingLength;
                    if (remaining2 > 0) {
                        result.put(new byte[remaining2]);
                    }
                } else {
                    byte[] zeros = new byte[bytesInCluster];
                    result.put(zeros);
                }
            } else if (mapping.isCompressed()) {
                // Decompress the cluster and extract the needed portion
                ClusterMapping.Compressed compressed = (ClusterMapping.Compressed) mapping;
                byte[] decompressed = readCompressedCluster(compressed);
                result.put(decompressed, clusterOffset, bytesInCluster);
            } else {
                // Normal cluster - read from file
                long physicalOffset = mapping.physicalOffset() + clusterOffset;
                readFromChannel(physicalOffset, result, bytesInCluster);
            }
        }

        result.flip();
        return result;
    }

    /**
     * Checks if a region of the virtual disk is allocated.
     *
     * @param virtualOffset the virtual offset to check
     * @param length the length of the region to check
     * @return true if any part of the region is allocated
     * @throws IOException if an I/O error occurs
     */
    public boolean isAllocated(long virtualOffset, long length) throws IOException {
        if (virtualOffset < 0 || virtualOffset >= header.virtualSize()) {
            return false;
        }

        long endOffset = Math.min(virtualOffset + length, header.virtualSize());
        long currentOffset = virtualOffset;

        while (currentOffset < endOffset) {
            ClusterMapping mapping = lookupCluster(currentOffset);
            if (!mapping.isUnallocated() && !mapping.isZero()) {
                return true;
            }
            // Move to next cluster
            currentOffset = ((currentOffset / clusterSize) + 1) * clusterSize;
        }

        return false;
    }

    /**
     * Looks up the physical cluster mapping for a virtual offset.
     *
     * @param virtualOffset the virtual offset
     * @return the cluster mapping
     * @throws IOException if an I/O error occurs
     */
    public @NotNull ClusterMapping lookupCluster(long virtualOffset) throws IOException {
        // Calculate L1 and L2 indices
        int l1Index = (int) (virtualOffset >> (header.clusterBits() + l2Bits));
        int l2Index = (int) ((virtualOffset >> header.clusterBits()) & (l2Size - 1));

        // Check L1 bounds
        if (l1Index >= l1Table.length) {
            return ClusterMapping.unallocated();
        }

        // Get L2 table offset from L1
        long l1Entry = l1Table[l1Index];
        if (l1Entry == 0) {
            return ClusterMapping.unallocated();
        }

        long l2TableOffset = l1Entry & OFFSET_MASK;
        if (l2TableOffset == 0) {
            return ClusterMapping.unallocated();
        }

        // Load L2 table (with caching)
        long[] l2Table = loadL2Table(l1Index, l2TableOffset);

        // Get cluster descriptor from L2
        long l2Entry = l2Table[l2Index];
        return parseL2Entry(l2Entry);
    }

    private long[] loadL1Table() throws IOException {
        int l1Size = header.l1Size();
        if (l1Size == 0) {
            return new long[0];
        }

        // Validate L1 table size
        long tableBytes = SafeMath.safeMultiply(l1Size, 8L);
        SecurityUtils.validateAllocationSize(tableBytes, 256 * 1024 * 1024, "L1 table");

        long[] table = new long[l1Size];
        ByteBuffer buffer = ByteBuffer.allocate(l1Size * 8);
        buffer.order(ByteOrder.BIG_ENDIAN);

        channel.position(header.l1TableOffset());
        int read = channel.read(buffer);
        if (read < l1Size * 8) {
            throw new CorruptedDiskException(
                    "Truncated L1 table: expected " + (l1Size * 8) + " bytes, got " + read,
                    header.l1TableOffset(), "L1 table", DiskFormat.QCOW2);
        }

        buffer.flip();
        for (int i = 0; i < l1Size; i++) {
            table[i] = buffer.getLong();
        }

        return table;
    }

    private long[] loadL2Table(long l1Index, long l2TableOffset) throws IOException {
        // Check cache
        if (l1Index == cachedL2Index && cachedL2Table != null) {
            return cachedL2Table;
        }

        // Validate offset
        if (l2TableOffset < 0) {
            throw new CorruptedDiskException(
                    "Invalid L2 table offset: " + l2TableOffset,
                    -1, "L2 table", DiskFormat.QCOW2);
        }

        long[] table = new long[l2Size];
        ByteBuffer buffer = ByteBuffer.allocate(l2Size * 8);
        buffer.order(ByteOrder.BIG_ENDIAN);

        channel.position(l2TableOffset);
        int read = channel.read(buffer);
        if (read < l2Size * 8) {
            throw new CorruptedDiskException(
                    "Truncated L2 table: expected " + (l2Size * 8) + " bytes, got " + read,
                    l2TableOffset, "L2 table", DiskFormat.QCOW2);
        }

        buffer.flip();
        for (int i = 0; i < l2Size; i++) {
            table[i] = buffer.getLong();
        }

        // Update cache
        cachedL2Index = l1Index;
        cachedL2Table = table;

        return table;
    }

    private ClusterMapping parseL2Entry(long entry) {
        if (entry == 0) {
            return ClusterMapping.unallocated();
        }

        // Check for standard cluster type indicators
        boolean isCompressed = (entry & COMPRESSED_FLAG) != 0;
        boolean isZero = (entry & ZERO_FLAG) != 0 && (entry & OFFSET_MASK) == 0;

        if (isZero) {
            return ClusterMapping.zero();
        }

        if (isCompressed) {
            // For compressed clusters, clear the compressed flag and use the rest as descriptor
            // The descriptor encodes both the host offset and compressed size
            long descriptor = entry & ~COMPRESSED_FLAG;
            return ClusterMapping.compressed(descriptor);
        }

        long offset = entry & OFFSET_MASK;
        if (offset == 0) {
            return ClusterMapping.unallocated();
        }

        return ClusterMapping.allocated(offset);
    }

    private void readFromChannel(long position, ByteBuffer dest, int length) throws IOException {
        ByteBuffer temp = ByteBuffer.allocate(length);
        channel.position(position);

        int totalRead = 0;
        while (totalRead < length) {
            int read = channel.read(temp);
            if (read < 0) {
                // EOF - fill remaining with zeros
                break;
            }
            totalRead += read;
        }

        temp.flip();
        dest.put(temp);
    }

    /**
     * Reads and decompresses a compressed cluster.
     *
     * <p>QCOW2 compressed cluster format (following QEMU's implementation):
     * <pre>
     * For cluster_bits = n:
     *   x = 62 - (n - 8) = 70 - n
     *   nb_csectors = ((descriptor >> x) & ((1 << (n - 8)) - 1)) + 1
     *   coffset = descriptor & ((1ULL << x) - 1)
     *   compressed_size = nb_csectors * 512 - (coffset & 511)
     * </pre>
     *
     * @param compressed the compressed cluster mapping
     * @return the decompressed cluster data (always clusterSize bytes)
     * @throws IOException if an I/O error occurs or decompression fails
     */
    private byte[] readCompressedCluster(ClusterMapping.Compressed compressed) throws IOException {
        // Extract host offset and compressed size from the descriptor
        // Using QEMU's formula for compatibility
        long descriptor = compressed.descriptor();
        int clusterBits = header.clusterBits();

        // x = 62 - (cluster_bits - 8) = 70 - cluster_bits
        int x = 70 - clusterBits;

        // csize_mask = (1 << (cluster_bits - 8)) - 1
        int csizeMask = (1 << (clusterBits - 8)) - 1;

        // Offset mask covers lower x bits
        long clusterOffsetMask = (1L << x) - 1;

        // Extract compressed data offset
        long coffset = descriptor & clusterOffsetMask;

        // Extract number of 512-byte sectors containing compressed data
        int nbCsectors = (int) (((descriptor >> x) & csizeMask) + 1);

        // Calculate actual compressed size, accounting for alignment within sector
        int compressedSize = nbCsectors * 512 - (int) (coffset & 511);

        // Validate the offset
        if (coffset < 0 || coffset > channel.size()) {
            throw new CorruptedDiskException(
                    "Invalid compressed cluster offset: " + coffset + " (channel size: " + channel.size() +
                    ", descriptor: 0x" + Long.toHexString(descriptor) + ")",
                    coffset, "compressed cluster", DiskFormat.QCOW2);
        }

        // Sanity check - compressed data shouldn't be larger than uncompressed
        // But allow some margin for compression overhead
        if (compressedSize <= 0 || compressedSize > clusterSize * 2) {
            throw new CorruptedDiskException(
                    "Invalid compressed cluster size: " + compressedSize + " (nbCsectors: " + nbCsectors + ")",
                    coffset, "compressed cluster", DiskFormat.QCOW2);
        }

        // Read the compressed data
        ByteBuffer compressedData = ByteBuffer.allocate(compressedSize);
        channel.position(coffset);
        int read = 0;
        while (read < compressedSize) {
            int n = channel.read(compressedData);
            if (n < 0) {
                break;
            }
            read += n;
        }

        // Decompress using raw DEFLATE (QCOW2 doesn't use zlib header)
        byte[] decompressed = new byte[clusterSize];
        Inflater inflater = new Inflater(true); // true = raw DEFLATE, no zlib header
        try {
            inflater.setInput(compressedData.array(), 0, read);

            // Inflater.inflate() may need to be called multiple times
            int totalDecompressed = 0;
            while (!inflater.finished() && totalDecompressed < clusterSize) {
                int remaining = clusterSize - totalDecompressed;
                int decompressedThisCall = inflater.inflate(decompressed, totalDecompressed, remaining);
                if (decompressedThisCall == 0) {
                    // No progress - check if we need more input or if we're done
                    if (inflater.needsInput()) {
                        // We've exhausted the input but haven't filled the cluster
                        // This is OK for sparse data - remaining bytes are zeros
                        break;
                    }
                    if (inflater.needsDictionary()) {
                        throw new CorruptedDiskException(
                                "Decompression requires dictionary (unexpected)",
                                coffset, "compressed cluster", DiskFormat.QCOW2);
                    }
                }
                totalDecompressed += decompressedThisCall;
            }

            // Verify we got some data
            if (totalDecompressed == 0) {
                throw new CorruptedDiskException(
                        "Decompression produced no data",
                        coffset, "compressed cluster", DiskFormat.QCOW2);
            }

            // Remaining bytes (if any) are already zero-initialized
            return decompressed;
        } catch (DataFormatException e) {
            throw new CorruptedDiskException(
                    "Failed to decompress cluster: " + e.getMessage(),
                    coffset, "compressed cluster", DiskFormat.QCOW2);
        } finally {
            inflater.end();
        }
    }

    /**
     * Represents the mapping of a virtual cluster to its physical location.
     */
    public sealed interface ClusterMapping {
        /** Returns true if the cluster is not allocated (reads as zeros). */
        boolean isUnallocated();

        /** Returns true if the cluster is explicitly zero (v3 feature). */
        boolean isZero();

        /** Returns true if the cluster is compressed. */
        boolean isCompressed();

        /** Returns the physical offset, or 0 if not applicable. */
        long physicalOffset();

        static ClusterMapping unallocated() {
            return Unallocated.INSTANCE;
        }

        static ClusterMapping zero() {
            return Zero.INSTANCE;
        }

        static ClusterMapping allocated(long offset) {
            return new Allocated(offset);
        }

        static ClusterMapping compressed(long descriptor) {
            return new Compressed(descriptor);
        }

        record Unallocated() implements ClusterMapping {
            static final Unallocated INSTANCE = new Unallocated();

            @Override public boolean isUnallocated() { return true; }
            @Override public boolean isZero() { return false; }
            @Override public boolean isCompressed() { return false; }
            @Override public long physicalOffset() { return 0; }
        }

        record Zero() implements ClusterMapping {
            static final Zero INSTANCE = new Zero();

            @Override public boolean isUnallocated() { return false; }
            @Override public boolean isZero() { return true; }
            @Override public boolean isCompressed() { return false; }
            @Override public long physicalOffset() { return 0; }
        }

        record Allocated(long offset) implements ClusterMapping {
            @Override public boolean isUnallocated() { return false; }
            @Override public boolean isZero() { return false; }
            @Override public boolean isCompressed() { return false; }
            @Override public long physicalOffset() { return offset; }
        }

        record Compressed(long descriptor) implements ClusterMapping {
            @Override public boolean isUnallocated() { return false; }
            @Override public boolean isZero() { return false; }
            @Override public boolean isCompressed() { return true; }
            @Override public long physicalOffset() { return descriptor; }
        }
    }
}
