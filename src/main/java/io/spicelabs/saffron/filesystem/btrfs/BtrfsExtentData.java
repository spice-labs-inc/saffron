/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a Btrfs EXTENT_DATA item for file content.
 */
public record BtrfsExtentData(
        long generation,
        long ramBytes,      // Uncompressed size
        int compression,    // 0=none, 1=zlib, 2=lzo, 3=zstd
        int encryption,     // 0=none
        int otherEncoding,  // Other encoding
        int type,           // 0=inline, 1=regular, 2=prealloc
        // For inline extents:
        byte[] inlineData,
        // For regular/prealloc extents:
        long diskBytenr,    // Logical address of extent on disk
        long diskNumBytes,  // Size on disk (may differ if compressed)
        long offset,        // Offset within the extent
        long numBytes       // Number of bytes from this extent
) {
    // Extent types
    public static final int TYPE_INLINE = 0;
    public static final int TYPE_REGULAR = 1;
    public static final int TYPE_PREALLOC = 2;

    // Compression types
    public static final int COMPRESS_NONE = 0;
    public static final int COMPRESS_ZLIB = 1;
    public static final int COMPRESS_LZO = 2;
    public static final int COMPRESS_ZSTD = 3;

    /**
     * Parses an EXTENT_DATA item from raw data.
     */
    public static BtrfsExtentData parse(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        long generation = buf.getLong();
        long ramBytes = buf.getLong();
        int compression = buf.get() & 0xFF;
        int encryption = buf.get() & 0xFF;
        int otherEncoding = buf.getShort() & 0xFFFF;
        int type = buf.get() & 0xFF;

        if (type == TYPE_INLINE) {
            // Inline data follows header
            if (data.length < 21) {
                throw new IllegalArgumentException(
                        "Btrfs inline extent data too short: " + data.length);
            }
            byte[] inlineData = new byte[data.length - 21];
            buf.get(inlineData);
            return new BtrfsExtentData(generation, ramBytes, compression, encryption,
                    otherEncoding, type, inlineData, 0, 0, 0, 0);
        } else {
            // Regular or prealloc extent
            long diskBytenr = buf.getLong();
            long diskNumBytes = buf.getLong();
            long offset = buf.getLong();
            long numBytes = buf.getLong();
            return new BtrfsExtentData(generation, ramBytes, compression, encryption,
                    otherEncoding, type, null, diskBytenr, diskNumBytes, offset, numBytes);
        }
    }

    /**
     * Returns true if this is an inline extent.
     */
    public boolean isInline() {
        return type == TYPE_INLINE;
    }

    /**
     * Returns true if this extent is compressed.
     */
    public boolean isCompressed() {
        return compression != COMPRESS_NONE;
    }

    /**
     * Returns a human-readable compression name.
     */
    public String compressionName() {
        return switch (compression) {
            case COMPRESS_NONE -> "none";
            case COMPRESS_ZLIB -> "zlib";
            case COMPRESS_LZO -> "lzo";
            case COMPRESS_ZSTD -> "zstd";
            default -> "unknown_" + compression;
        };
    }

    /**
     * Returns true if this extent is a hole (sparse).
     */
    public boolean isHole() {
        return type == TYPE_REGULAR && diskBytenr == 0;
    }
}
