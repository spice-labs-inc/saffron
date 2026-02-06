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
package io.spicelabs.saffron.vhd.dynamic;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.common.SecurityUtils;
import io.spicelabs.saffron.exception.InvalidMagicException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Represents the VHD dynamic disk header.
 *
 * <p>The dynamic disk header follows the copy of the footer at the beginning
 * of dynamic and differencing VHD files.
 *
 * <p>Dynamic disk header structure (1024 bytes, big-endian):
 * <pre>
 * Offset  Size  Description
 * 0       8     Cookie ("cxsparse")
 * 8       8     Data Offset (unused, 0xFFFFFFFFFFFFFFFF)
 * 16      8     Table Offset (BAT location)
 * 24      4     Header Version
 * 28      4     Max Table Entries
 * 32      4     Block Size (typically 2 MB)
 * 36      4     Checksum
 * 40      16    Parent Unique ID (for differencing)
 * 56      4     Parent Time Stamp
 * 60      4     Reserved
 * 64      512   Parent Unicode Name
 * 576     24*8  Parent Locator Entries
 * 768     256   Reserved
 * </pre>
 */
public record VhdDynamicHeader(
        long tableOffset,
        int headerVersion,
        int maxTableEntries,
        int blockSize,
        int checksum,
        @Nullable String parentUnicodeName
) {

    /** Magic cookie identifying a VHD dynamic header */
    public static final byte[] MAGIC = "cxsparse".getBytes(StandardCharsets.US_ASCII);

    /** Dynamic header size in bytes */
    public static final int HEADER_SIZE = 1024;

    /** Default block size (2 MB) */
    public static final int DEFAULT_BLOCK_SIZE = 2 * 1024 * 1024;

    /** Marker for unallocated BAT entry */
    public static final int BAT_ENTRY_UNUSED = 0xFFFFFFFF;

    /**
     * Reads the dynamic header from the specified offset.
     *
     * @param channel the channel to read from
     * @param offset the offset where the dynamic header starts
     * @return the parsed dynamic header
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VhdDynamicHeader read(@NotNull SeekableByteChannel channel, long offset)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        channel.position(offset);
        int read = channel.read(buffer);
        if (read < HEADER_SIZE) {
            throw new IOException("Failed to read VHD dynamic header: got " + read + " bytes");
        }
        buffer.flip();

        // Validate magic
        byte[] magic = new byte[8];
        buffer.get(magic);
        if (!SecurityUtils.constantTimeEquals(magic, MAGIC)) {
            throw new InvalidMagicException(
                    "Invalid VHD dynamic header magic: expected 'cxsparse'",
                    MAGIC, magic, offset, DiskFormat.VHD);
        }

        // Data offset (unused)
        buffer.getLong();

        // Table offset (BAT location)
        long tableOffset = buffer.getLong();

        // Header version
        int headerVersion = buffer.getInt();

        // Max table entries
        int maxTableEntries = buffer.getInt();

        // Block size
        int blockSize = buffer.getInt();

        // Checksum
        int checksum = buffer.getInt();

        // Parent unique ID (16 bytes) - skip for now
        buffer.position(buffer.position() + 16);

        // Parent time stamp
        buffer.getInt();

        // Reserved
        buffer.getInt();

        // Parent unicode name (512 bytes, UTF-16BE)
        byte[] parentNameBytes = new byte[512];
        buffer.get(parentNameBytes);
        String parentUnicodeName = parseParentName(parentNameBytes);

        return new VhdDynamicHeader(
                tableOffset,
                headerVersion,
                maxTableEntries,
                blockSize,
                checksum,
                parentUnicodeName
        );
    }

    /**
     * Parses the parent unicode name from UTF-16BE bytes.
     */
    private static @Nullable String parseParentName(byte[] bytes) {
        // Find null terminator
        int length = 0;
        for (int i = 0; i < bytes.length - 1; i += 2) {
            if (bytes[i] == 0 && bytes[i + 1] == 0) {
                break;
            }
            length += 2;
        }
        if (length == 0) {
            return null;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_16BE);
    }

    /**
     * Returns the number of sectors per block.
     */
    public int sectorsPerBlock() {
        return blockSize / 512;
    }

    /**
     * Returns the bitmap size for a block (in bytes).
     * Each bit in the bitmap represents one sector.
     */
    public int blockBitmapSize() {
        // Round up to sector boundary
        int bitmapBits = sectorsPerBlock();
        int bitmapBytes = (bitmapBits + 7) / 8;
        // Round up to 512-byte sector
        return ((bitmapBytes + 511) / 512) * 512;
    }
}
