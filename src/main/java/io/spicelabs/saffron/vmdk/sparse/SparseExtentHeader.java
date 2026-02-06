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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.vmdk.sparse;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.exception.InvalidMagicException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;

/**
 * Represents the sparse extent header of a VMDK file.
 *
 * <p>The sparse extent header is the first structure in a sparse VMDK file.
 * It contains information about the disk layout, grain directories, and
 * optional embedded descriptor.
 *
 * <p>Sparse extent header structure (512 bytes, little-endian):
 * <pre>
 * Offset  Size    Description
 * 0       4       Magic number (KDMV = 0x564D444B little-endian)
 * 4       4       Version (1, 2, or 3)
 * 8       4       Flags
 * 12      8       Capacity (in sectors)
 * 20      8       Grain size (in sectors)
 * 28      8       Descriptor offset (in sectors)
 * 36      8       Descriptor size (in sectors)
 * 44      4       Number of grain table entries per grain table
 * 48      8       RGDE offset (redundant grain directory)
 * 56      8       GDE offset (grain directory)
 * 64      8       Overhead (sectors before first grain)
 * 72      1       Unclean shutdown flag
 * 73      4       Single end-of-line character
 * 77      4       Non end-of-line character
 * 81      4       Double end-of-line char 1
 * 85      4       Double end-of-line char 2
 * 89      2       Compression algorithm
 * 91      421     Padding to 512 bytes
 * </pre>
 */
public record SparseExtentHeader(
        int version,
        int flags,
        long capacity,
        long grainSize,
        long descriptorOffset,
        long descriptorSize,
        int numGTEsPerGT,
        long rgdOffset,
        long gdOffset,
        long overhead,
        boolean uncleanShutdown,
        int compressAlgorithm
) {

    /** Magic signature for sparse VMDK (little-endian "VMDK") */
    public static final int MAGIC = 0x564D444B;

    /** Magic bytes as they appear in the file */
    public static final byte[] MAGIC_BYTES = {0x4B, 0x44, 0x4D, 0x56};

    /** Sparse extent header size */
    public static final int HEADER_SIZE = 512;

    /** Sector size in bytes */
    public static final int SECTOR_SIZE = 512;

    /** Flag: new line detection */
    public static final int FLAG_NEW_LINE_DETECTION = 0x01;

    /** Flag: redundant grain directory used */
    public static final int FLAG_USE_REDUNDANT_GRAIN_DIR = 0x02;

    /** Flag: compressed grains */
    public static final int FLAG_COMPRESSED = 0x10000;

    /** Flag: grains have markers */
    public static final int FLAG_HAS_MARKERS = 0x20000;

    /** Compression: none */
    public static final int COMPRESSION_NONE = 0;

    /** Compression: deflate */
    public static final int COMPRESSION_DEFLATE = 1;

    /** Version 1 sparse extent */
    public static final int VERSION_1 = 1;

    /** Version 2 sparse extent */
    public static final int VERSION_2 = 2;

    /** Version 3 sparse extent (stream optimized) */
    public static final int VERSION_3 = 3;

    /**
     * Reads the sparse extent header from the beginning of a VMDK file.
     *
     * @param channel the channel to read from
     * @return the parsed sparse extent header
     * @throws IOException if an I/O error occurs
     * @throws InvalidMagicException if the magic signature is invalid
     */
    public static @NotNull SparseExtentHeader read(@NotNull SeekableByteChannel channel) throws IOException {
        return read(channel, 0);
    }

    /**
     * Reads the sparse extent header from the specified offset.
     *
     * @param channel the channel to read from
     * @param offset the offset to read from
     * @return the parsed sparse extent header
     * @throws IOException if an I/O error occurs
     * @throws InvalidMagicException if the magic signature is invalid
     */
    public static @NotNull SparseExtentHeader read(@NotNull SeekableByteChannel channel, long offset)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        int read = channel.read(buffer);
        if (read < HEADER_SIZE) {
            throw new IOException("Failed to read VMDK sparse header: got " + read + " bytes");
        }
        buffer.flip();

        // Validate magic
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            byte[] expected = MAGIC_BYTES;
            byte[] actual = new byte[]{
                    (byte) (magic & 0xFF),
                    (byte) ((magic >> 8) & 0xFF),
                    (byte) ((magic >> 16) & 0xFF),
                    (byte) ((magic >> 24) & 0xFF)
            };
            throw new InvalidMagicException(
                    "Invalid VMDK sparse header signature",
                    expected, actual, offset, DiskFormat.VMDK);
        }

        // Version
        int version = buffer.getInt();

        // Flags
        int flags = buffer.getInt();

        // Capacity (in sectors)
        long capacity = buffer.getLong();

        // Grain size (in sectors)
        long grainSize = buffer.getLong();

        // Descriptor offset (in sectors)
        long descriptorOffset = buffer.getLong();

        // Descriptor size (in sectors)
        long descriptorSize = buffer.getLong();

        // Number of grain table entries per grain table
        int numGTEsPerGT = buffer.getInt();

        // RGDE offset (redundant grain directory)
        long rgdOffset = buffer.getLong();

        // GDE offset (grain directory)
        long gdOffset = buffer.getLong();

        // Overhead (sectors before first grain)
        long overhead = buffer.getLong();

        // Unclean shutdown flag
        boolean uncleanShutdown = buffer.get() != 0;

        // Skip newline characters (13 bytes)
        buffer.position(buffer.position() + 16);

        // Compression algorithm
        int compressAlgorithm = buffer.getShort() & 0xFFFF;

        return new SparseExtentHeader(
                version,
                flags,
                capacity,
                grainSize,
                descriptorOffset,
                descriptorSize,
                numGTEsPerGT,
                rgdOffset,
                gdOffset,
                overhead,
                uncleanShutdown,
                compressAlgorithm
        );
    }

    /**
     * Returns the virtual disk size in bytes.
     */
    public long virtualSizeBytes() {
        return capacity * SECTOR_SIZE;
    }

    /**
     * Returns the grain size in bytes.
     */
    public int grainSizeBytes() {
        return (int) (grainSize * SECTOR_SIZE);
    }

    /**
     * Returns whether this extent has an embedded descriptor.
     */
    public boolean hasEmbeddedDescriptor() {
        return descriptorOffset > 0 && descriptorSize > 0;
    }

    /**
     * Returns the embedded descriptor offset in bytes.
     */
    public long descriptorOffsetBytes() {
        return descriptorOffset * SECTOR_SIZE;
    }

    /**
     * Returns the embedded descriptor size in bytes.
     */
    public long descriptorSizeBytes() {
        return descriptorSize * SECTOR_SIZE;
    }

    /**
     * Returns whether this extent uses compression.
     */
    public boolean isCompressed() {
        return (flags & FLAG_COMPRESSED) != 0 || compressAlgorithm != COMPRESSION_NONE;
    }

    /**
     * Returns whether this is a stream-optimized extent.
     */
    public boolean isStreamOptimized() {
        return version == VERSION_3;
    }

    /**
     * Returns whether this extent has markers (stream-optimized).
     */
    public boolean hasMarkers() {
        return (flags & FLAG_HAS_MARKERS) != 0;
    }

    /**
     * Returns the grain directory offset in bytes.
     */
    public long grainDirectoryOffsetBytes() {
        return gdOffset * SECTOR_SIZE;
    }

    /**
     * Returns the overhead in bytes (data starts after this).
     */
    public long overheadBytes() {
        return overhead * SECTOR_SIZE;
    }
}
