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
package io.spicelabs.saffron.vhd.footer;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.common.SecurityUtils;
import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.exception.UnsupportedVersionException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Represents the VHD footer structure.
 *
 * <p>The footer is located at the last 512 bytes of a VHD file. For dynamic
 * and differencing disks, a copy is also placed at the beginning of the file.
 *
 * <p>VHD footer structure (512 bytes, big-endian):
 * <pre>
 * Offset  Size  Description
 * 0       8     Cookie ("conectix")
 * 8       4     Features
 * 12      4     File Format Version (0x00010000)
 * 16      8     Data Offset (for dynamic disks)
 * 24      4     Time Stamp (seconds since Jan 1, 2000)
 * 28      4     Creator Application
 * 32      4     Creator Version
 * 36      4     Creator Host OS
 * 40      8     Original Size
 * 48      8     Current Size
 * 56      4     Disk Geometry
 * 60      4     Disk Type
 * 64      4     Checksum
 * 68      16    Unique ID (UUID)
 * 84      1     Saved State
 * 85      427   Reserved
 * </pre>
 */
public record VhdFooter(
        int features,
        int fileFormatVersion,
        long dataOffset,
        int timeStamp,
        @NotNull String creatorApplication,
        int creatorVersion,
        @NotNull String creatorHostOs,
        long originalSize,
        long currentSize,
        int cylinders,
        int heads,
        int sectorsPerTrack,
        @NotNull DiskType diskType,
        int checksum,
        @NotNull UUID uniqueId,
        boolean savedState
) {

    /** Magic cookie identifying a VHD file */
    public static final byte[] MAGIC = "conectix".getBytes(StandardCharsets.US_ASCII);

    /** Expected file format version */
    public static final int VERSION_1_0 = 0x00010000;

    /** Footer size in bytes */
    public static final int FOOTER_SIZE = 512;

    /** Offset of data offset field for fixed disks (no dynamic header) */
    public static final long DATA_OFFSET_NONE = 0xFFFFFFFFFFFFFFFFL;

    /**
     * VHD disk type enumeration.
     */
    public enum DiskType {
        /** Fixed size disk - all space allocated at creation */
        FIXED(2),
        /** Dynamic disk - grows as data is written */
        DYNAMIC(3),
        /** Differencing disk - stores changes from parent */
        DIFFERENCING(4);

        private final int value;

        DiskType(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        public static DiskType fromValue(int value) {
            return switch (value) {
                case 2 -> FIXED;
                case 3 -> DYNAMIC;
                case 4 -> DIFFERENCING;
                default -> throw new IllegalArgumentException("Unknown VHD disk type: " + value);
            };
        }
    }

    /**
     * Reads the VHD footer from the end of a file.
     *
     * @param channel the channel to read from
     * @return the parsed footer
     * @throws IOException if an I/O error occurs
     * @throws InvalidMagicException if the magic signature is invalid
     */
    public static @NotNull VhdFooter read(@NotNull SeekableByteChannel channel) throws IOException {
        long fileSize = channel.size();
        if (fileSize < FOOTER_SIZE) {
            throw new IOException("File too small to be a VHD: " + fileSize + " bytes");
        }

        // Read footer from end of file
        ByteBuffer buffer = ByteBuffer.allocate(FOOTER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        channel.position(fileSize - FOOTER_SIZE);
        int read = channel.read(buffer);
        if (read < FOOTER_SIZE) {
            throw new IOException("Failed to read VHD footer: got " + read + " bytes");
        }
        buffer.flip();

        return parse(buffer);
    }

    /**
     * Reads the VHD header (copy of footer) from the beginning of a file.
     * This is used for dynamic and differencing disks.
     *
     * @param channel the channel to read from
     * @return the parsed header/footer
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VhdFooter readHeader(@NotNull SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(FOOTER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        channel.position(0);
        int read = channel.read(buffer);
        if (read < FOOTER_SIZE) {
            throw new IOException("Failed to read VHD header: got " + read + " bytes");
        }
        buffer.flip();

        return parse(buffer);
    }

    private static VhdFooter parse(ByteBuffer buffer) throws IOException {
        // Validate magic
        byte[] magic = new byte[8];
        buffer.get(magic);
        if (!SecurityUtils.constantTimeEquals(magic, MAGIC)) {
            throw new InvalidMagicException(
                    "Invalid VHD magic: expected 'conectix'",
                    MAGIC, magic, 0, DiskFormat.VHD);
        }

        // Features
        int features = buffer.getInt();

        // File format version
        int version = buffer.getInt();
        if (version != VERSION_1_0) {
            throw new UnsupportedVersionException(
                    "Unsupported VHD version: " + String.format("0x%08x", version),
                    version >> 16, 1, 1, DiskFormat.VHD);
        }

        // Data offset
        long dataOffset = buffer.getLong();

        // Time stamp
        int timeStamp = buffer.getInt();

        // Creator application
        byte[] creatorAppBytes = new byte[4];
        buffer.get(creatorAppBytes);
        String creatorApplication = new String(creatorAppBytes, StandardCharsets.US_ASCII).trim();

        // Creator version
        int creatorVersion = buffer.getInt();

        // Creator host OS
        byte[] creatorOsBytes = new byte[4];
        buffer.get(creatorOsBytes);
        String creatorHostOs = new String(creatorOsBytes, StandardCharsets.US_ASCII).trim();

        // Original size
        long originalSize = buffer.getLong();

        // Current size
        long currentSize = buffer.getLong();

        // Disk geometry
        int geometry = buffer.getInt();
        int cylinders = (geometry >> 16) & 0xFFFF;
        int heads = (geometry >> 8) & 0xFF;
        int sectorsPerTrack = geometry & 0xFF;

        // Disk type
        int diskTypeValue = buffer.getInt();
        DiskType diskType;
        try {
            diskType = DiskType.fromValue(diskTypeValue);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown VHD disk type: " + diskTypeValue);
        }

        // Checksum
        int checksum = buffer.getInt();

        // Unique ID (UUID)
        byte[] uuidBytes = new byte[16];
        buffer.get(uuidBytes);
        UUID uniqueId = uuidFromBytes(uuidBytes);

        // Saved state
        boolean savedState = buffer.get() != 0;

        return new VhdFooter(
                features,
                version,
                dataOffset,
                timeStamp,
                creatorApplication,
                creatorVersion,
                creatorHostOs,
                originalSize,
                currentSize,
                cylinders,
                heads,
                sectorsPerTrack,
                diskType,
                checksum,
                uniqueId,
                savedState
        );
    }

    /**
     * Returns whether this is a fixed disk.
     */
    public boolean isFixed() {
        return diskType == DiskType.FIXED;
    }

    /**
     * Returns whether this is a dynamic disk.
     */
    public boolean isDynamic() {
        return diskType == DiskType.DYNAMIC;
    }

    /**
     * Returns whether this is a differencing disk.
     */
    public boolean isDifferencing() {
        return diskType == DiskType.DIFFERENCING;
    }

    /**
     * Returns the virtual size of the disk.
     */
    public long virtualSize() {
        return currentSize;
    }

    /**
     * Converts VHD-format UUID bytes to a UUID object.
     * VHD stores the first three components in little-endian order.
     */
    private static UUID uuidFromBytes(byte[] bytes) {
        // VHD UUID is stored with first 3 components in little-endian
        ByteBuffer bb = ByteBuffer.wrap(bytes);

        // First 4 bytes (time_low) - little-endian
        int timeLow = Integer.reverseBytes(bb.getInt());
        // Next 2 bytes (time_mid) - little-endian
        short timeMid = Short.reverseBytes(bb.getShort());
        // Next 2 bytes (time_hi_and_version) - little-endian
        short timeHi = Short.reverseBytes(bb.getShort());
        // Remaining 8 bytes - big-endian
        long lsb = bb.getLong();

        long msb = ((long) timeLow << 32) | ((long) (timeMid & 0xFFFF) << 16) | (timeHi & 0xFFFF);
        return new UUID(msb, lsb);
    }
}
