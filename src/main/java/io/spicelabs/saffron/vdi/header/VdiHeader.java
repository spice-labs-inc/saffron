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
package io.spicelabs.saffron.vdi.header;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.exception.InvalidMagicException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Represents a VDI (VirtualBox Disk Image) header.
 *
 * <p>VDI is Oracle VirtualBox's native disk image format. The file structure is:
 * <pre>{@code
 * Offset  Size    Description
 * 0x00    64      Text description "<<< Oracle VM VirtualBox Disk Image >>>\n"
 * 0x40    4       Signature (0x7f10dabe)
 * 0x44    4       Version (major.minor as two 16-bit values)
 * 0x48    4       Header size (from 0x48)
 * 0x4C    4       Image type (1=dynamic, 2=fixed, 3=undo, 4=differencing)
 * 0x50    4       Image flags
 * 0x54    256     Image comment (null-terminated)
 * 0x154   4       Offset of blocks (BAM offset)
 * 0x158   4       Offset of data
 * 0x15C   4       Legacy cylinders
 * 0x160   4       Legacy heads
 * 0x164   4       Legacy sectors per track
 * 0x168   4       Sector size
 * 0x16C   4       Unused
 * 0x170   8       Disk size (virtual size in bytes)
 * 0x178   4       Block size
 * 0x17C   4       Block extra data size
 * 0x180   4       Blocks in HDD (total)
 * 0x184   4       Blocks allocated
 * 0x188   16      UUID of image
 * 0x198   16      UUID of last snapshot
 * 0x1A8   16      UUID of link
 * 0x1B8   16      UUID of parent
 * }</pre>
 */
public record VdiHeader(
        int versionMajor,
        int versionMinor,
        int headerSize,
        @NotNull ImageType imageType,
        int imageFlags,
        @Nullable String comment,
        int blocksOffset,
        int dataOffset,
        int sectorSize,
        long diskSize,
        int blockSize,
        int blockExtraSize,
        int blocksInHdd,
        int blocksAllocated,
        @NotNull UUID imageUuid,
        @Nullable UUID lastSnapUuid,
        @Nullable UUID linkUuid,
        @Nullable UUID parentUuid
) {

    /** VDI magic signature at offset 0x40 */
    public static final int MAGIC = 0xBEDA107F;

    /** Offset where the magic signature is located */
    public static final int MAGIC_OFFSET = 0x40;

    /** Text preamble before magic */
    public static final String TEXT_PREAMBLE = "<<< Oracle VM VirtualBox Disk Image >>>";

    /** Minimum header size to read */
    public static final int MIN_HEADER_SIZE = 0x1C8;

    /** Default block size (1 MB) */
    public static final int DEFAULT_BLOCK_SIZE = 1024 * 1024;

    /** Marker for unallocated block in BAM */
    public static final int BLOCK_FREE = 0xFFFFFFFF;

    /** Marker for zero block in BAM */
    public static final int BLOCK_ZERO = 0xFFFFFFFE;

    /**
     * VDI image types.
     */
    public enum ImageType {
        DYNAMIC(1, "dynamic"),
        FIXED(2, "fixed"),
        UNDO(3, "undo"),
        DIFFERENCING(4, "differencing");

        private final int value;
        private final String name;

        ImageType(int value, String name) {
            this.value = value;
            this.name = name;
        }

        public int value() {
            return value;
        }

        public String typeName() {
            return name;
        }

        public static ImageType fromValue(int value) {
            for (ImageType type : values()) {
                if (type.value == value) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown VDI image type: " + value);
        }
    }

    /**
     * Reads the VDI header from the beginning of the file.
     *
     * @param channel the channel to read from
     * @return the parsed VDI header
     * @throws IOException if an I/O error occurs
     * @throws InvalidMagicException if the magic signature is invalid
     */
    public static @NotNull VdiHeader read(@NotNull SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(MIN_HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(0);
        int read = channel.read(buffer);
        if (read < MIN_HEADER_SIZE) {
            throw new IOException("Failed to read VDI header: got " + read + " bytes, need " + MIN_HEADER_SIZE);
        }
        buffer.flip();

        // Skip text preamble (64 bytes)
        buffer.position(MAGIC_OFFSET);

        // Read and validate magic signature
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            byte[] expected = new byte[]{(byte) 0x7F, 0x10, (byte) 0xDA, (byte) 0xBE};
            byte[] actual = new byte[]{
                    (byte) (magic & 0xFF),
                    (byte) ((magic >> 8) & 0xFF),
                    (byte) ((magic >> 16) & 0xFF),
                    (byte) ((magic >> 24) & 0xFF)
            };
            throw new InvalidMagicException(
                    "Invalid VDI signature",
                    expected, actual, MAGIC_OFFSET, DiskFormat.VDI);
        }

        // Version (two 16-bit values)
        int versionMinor = buffer.getShort() & 0xFFFF;
        int versionMajor = buffer.getShort() & 0xFFFF;

        // Header size (from offset 0x48)
        int headerSize = buffer.getInt();

        // Image type
        int imageTypeValue = buffer.getInt();
        ImageType imageType;
        try {
            imageType = ImageType.fromValue(imageTypeValue);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid VDI image type: " + imageTypeValue);
        }

        // Image flags
        int imageFlags = buffer.getInt();

        // Comment (256 bytes, null-terminated)
        byte[] commentBytes = new byte[256];
        buffer.get(commentBytes);
        String comment = parseNullTerminatedString(commentBytes);

        // Offset of blocks (BAM)
        int blocksOffset = buffer.getInt();

        // Offset of data
        int dataOffset = buffer.getInt();

        // Legacy geometry (skip cylinders, heads, sectors)
        buffer.getInt(); // cylinders
        buffer.getInt(); // heads
        buffer.getInt(); // sectors per track

        // Sector size
        int sectorSize = buffer.getInt();

        // Unused
        buffer.getInt();

        // Disk size (virtual size)
        long diskSize = buffer.getLong();

        // Block size
        int blockSize = buffer.getInt();

        // Block extra data size
        int blockExtraSize = buffer.getInt();

        // Blocks in HDD
        int blocksInHdd = buffer.getInt();

        // Blocks allocated
        int blocksAllocated = buffer.getInt();

        // Image UUID
        UUID imageUuid = readUuid(buffer);

        // Last snapshot UUID
        UUID lastSnapUuid = readUuid(buffer);

        // Link UUID
        UUID linkUuid = readUuid(buffer);

        // Parent UUID
        UUID parentUuid = readUuid(buffer);

        return new VdiHeader(
                versionMajor,
                versionMinor,
                headerSize,
                imageType,
                imageFlags,
                comment,
                blocksOffset,
                dataOffset,
                sectorSize,
                diskSize,
                blockSize,
                blockExtraSize,
                blocksInHdd,
                blocksAllocated,
                imageUuid,
                lastSnapUuid,
                linkUuid,
                parentUuid
        );
    }

    /**
     * Parses a null-terminated string from a byte array.
     */
    private static @Nullable String parseNullTerminatedString(byte[] bytes) {
        int length = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                break;
            }
            length++;
        }
        if (length == 0) {
            return null;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /**
     * Reads a UUID from the buffer in little-endian Microsoft GUID format.
     */
    private static UUID readUuid(ByteBuffer buffer) {
        // VDI uses Microsoft GUID format (little-endian for first 3 components)
        int data1 = buffer.getInt();
        short data2 = buffer.getShort();
        short data3 = buffer.getShort();
        byte[] data4 = new byte[8];
        buffer.get(data4);

        long msb = ((long) data1 << 32) | ((long) (data2 & 0xFFFF) << 16) | (data3 & 0xFFFF);
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            lsb = (lsb << 8) | (data4[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }

    /**
     * Returns the version as a string (e.g., "1.1").
     */
    public String versionString() {
        return versionMajor + "." + versionMinor;
    }

    /**
     * Returns whether this is a dynamic (sparse) disk.
     */
    public boolean isDynamic() {
        return imageType == ImageType.DYNAMIC;
    }

    /**
     * Returns whether this is a fixed (preallocated) disk.
     */
    public boolean isFixed() {
        return imageType == ImageType.FIXED;
    }

    /**
     * Returns whether this disk has a parent (differencing image).
     */
    public boolean hasParent() {
        return imageType == ImageType.DIFFERENCING ||
               (parentUuid != null && !isZeroUuid(parentUuid));
    }

    /**
     * Checks if a UUID is the zero UUID.
     */
    private static boolean isZeroUuid(UUID uuid) {
        return uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0;
    }
}
