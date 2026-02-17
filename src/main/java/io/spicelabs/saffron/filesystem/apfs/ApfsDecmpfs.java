/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.apfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Handles APFS decmpfs (com.apple.decmpfs) compressed file decompression.
 *
 * <p>macOS uses a proprietary compression scheme stored in the {@code com.apple.decmpfs}
 * extended attribute. The decmpfs header format:
 * <pre>
 * offset 0:  uint32 magic = 0x636D7066 ('cmpf' in LE)
 * offset 4:  uint32 compression_type
 * offset 8:  uint64 uncompressed_size
 * </pre>
 *
 * <p>Supported compression types:
 * <ul>
 *   <li>Type 3: zlib compressed, data stored in the xattr (after 16-byte header)</li>
 *   <li>Type 4: zlib compressed, data stored in the file's resource fork</li>
 *   <li>Type 7: LZVN compressed, data in xattr (not yet supported)</li>
 *   <li>Type 8: LZVN compressed, data in resource fork (not yet supported)</li>
 *   <li>Type 9: uncompressed, data stored in xattr (for files &lt; ~3802 bytes)</li>
 * </ul>
 */
public final class ApfsDecmpfs {

    /** decmpfs magic: 'cmpf' in little-endian = 0x636D7066 */
    public static final int DECMPFS_MAGIC = 0x636D7066;

    /** Size of the decmpfs header: magic(4) + type(4) + uncompressed_size(8) = 16 bytes */
    public static final int HEADER_SIZE = 16;

    /** Size of each 64KB decompression block for resource fork compression */
    public static final int RESOURCE_FORK_BLOCK_SIZE = 65536;

    /** Resource fork header size to skip */
    public static final int RESOURCE_FORK_HEADER_SIZE = 256;

    // Compression types
    public static final int TYPE_ZLIB_XATTR = 3;
    public static final int TYPE_ZLIB_RESOURCE_FORK = 4;
    public static final int TYPE_LZVN_XATTR = 7;
    public static final int TYPE_LZVN_RESOURCE_FORK = 8;
    public static final int TYPE_UNCOMPRESSED_XATTR = 9;

    private ApfsDecmpfs() {}

    /**
     * Parsed decmpfs header.
     */
    public record DecmpfsHeader(
            int compressionType,
            long uncompressedSize
    ) {}

    /**
     * Parses the decmpfs header from xattr data.
     *
     * @param xattrData the raw data from the com.apple.decmpfs xattr
     * @return the parsed header, or null if the data is invalid
     */
    public static DecmpfsHeader parseHeader(byte[] xattrData) {
        if (xattrData == null || xattrData.length < HEADER_SIZE) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(xattrData);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt(0);
        if (magic != DECMPFS_MAGIC) {
            return null;
        }

        int compressionType = buf.getInt(4);
        long uncompressedSize = buf.getLong(8);

        return new DecmpfsHeader(compressionType, uncompressedSize);
    }

    /**
     * Decompresses data based on the decmpfs compression type.
     *
     * @param header the parsed decmpfs header
     * @param xattrData the full xattr data (including header)
     * @param resourceForkData the resource fork data (for types 4 and 8), may be null
     * @return the decompressed file data
     * @throws IOException if decompression fails or the type is unsupported
     */
    public static byte[] decompress(DecmpfsHeader header, byte[] xattrData, byte[] resourceForkData)
            throws IOException {
        return switch (header.compressionType()) {
            case TYPE_ZLIB_XATTR -> decompressZlibXattr(header, xattrData);
            case TYPE_ZLIB_RESOURCE_FORK -> decompressZlibResourceFork(header, resourceForkData);
            case TYPE_UNCOMPRESSED_XATTR -> decompressInlineXattr(header, xattrData);
            case TYPE_LZVN_XATTR, TYPE_LZVN_RESOURCE_FORK ->
                    throw new IOException("LZVN compression (type " + header.compressionType()
                            + ") is not yet supported");
            default -> throw new IOException("Unknown decmpfs compression type: " + header.compressionType());
        };
    }

    /**
     * Type 3: zlib compressed data in the xattr, immediately after the 16-byte header.
     */
    private static byte[] decompressZlibXattr(DecmpfsHeader header, byte[] xattrData) throws IOException {
        if (xattrData.length <= HEADER_SIZE) {
            // No compressed data after the header — file is empty
            return new byte[0];
        }

        int compressedOffset = HEADER_SIZE;
        int compressedLen = xattrData.length - HEADER_SIZE;

        // Check if the first byte after header indicates uncompressed inline data.
        // In some macOS versions, type 3 with a 0xFF marker byte means the data is
        // stored uncompressed after the marker.
        if (compressedLen > 0 && (xattrData[compressedOffset] & 0xFF) == 0xFF) {
            int dataLen = compressedLen - 1;
            byte[] result = new byte[dataLen];
            System.arraycopy(xattrData, compressedOffset + 1, result, 0, dataLen);
            return result;
        }

        return inflateData(xattrData, compressedOffset, compressedLen, (int) header.uncompressedSize());
    }

    /**
     * Type 4: zlib compressed data in the resource fork.
     *
     * <p>The resource fork layout for compressed files:
     * <pre>
     * Bytes 0-3:   data offset (uint32 BE) — offset to compressed data from start
     * Bytes 4-7:   management offset (uint32 BE)
     * Bytes 8-11:  data length (uint32 BE)
     * ...
     * At data offset:
     *   Bytes 0-3:   block table offset from data start (uint32 LE) = number of blocks * 8 + 4
     *   Then: compressed block data
     * At (data offset + block table offset):
     *   uint32 LE: number of blocks
     *   For each block: uint32 LE offset, uint32 LE size
     * </pre>
     *
     * <p>Each block is independently zlib-compressed and represents up to 64KB of uncompressed data.
     */
    private static byte[] decompressZlibResourceFork(DecmpfsHeader header, byte[] resourceForkData)
            throws IOException {
        if (resourceForkData == null || resourceForkData.length < 4) {
            throw new IOException("No resource fork data available for decmpfs type 4 decompression");
        }

        int uncompressedSize = (int) header.uncompressedSize();
        byte[] result = new byte[uncompressedSize];

        ByteBuffer rfBuf = ByteBuffer.wrap(resourceForkData);
        rfBuf.order(ByteOrder.BIG_ENDIAN);

        // Resource fork header: data offset at bytes 0-3 (big-endian)
        int dataOffset = rfBuf.getInt(0);

        if (dataOffset + 4 > resourceForkData.length) {
            throw new IOException("Invalid resource fork data offset: " + dataOffset);
        }

        // At the data offset, skip the 4-byte data-area length field
        int dataAreaStart = dataOffset + 4;

        // Read the block table: at the start of the data area there's a uint32 LE giving
        // the offset from dataAreaStart to the block table
        ByteBuffer leBuf = ByteBuffer.wrap(resourceForkData);
        leBuf.order(ByteOrder.LITTLE_ENDIAN);

        if (dataAreaStart + 4 > resourceForkData.length) {
            throw new IOException("Resource fork data area too small");
        }

        int blockTableRelOffset = leBuf.getInt(dataAreaStart);
        int blockTableStart = dataAreaStart + blockTableRelOffset;

        if (blockTableStart + 4 > resourceForkData.length) {
            throw new IOException("Invalid block table offset in resource fork");
        }

        int numBlocks = leBuf.getInt(blockTableStart);
        if (numBlocks <= 0 || numBlocks > 100_000) {
            throw new IOException("Invalid block count in resource fork: " + numBlocks);
        }

        int blockEntryStart = blockTableStart + 4;
        int resultOffset = 0;

        for (int i = 0; i < numBlocks; i++) {
            int entryOffset = blockEntryStart + i * 8;
            if (entryOffset + 8 > resourceForkData.length) break;

            int blockOffset = leBuf.getInt(entryOffset);
            int blockSize = leBuf.getInt(entryOffset + 4);

            // Block offsets are relative to dataAreaStart + 4 (after the block table offset field)
            int absoluteBlockOffset = dataAreaStart + 4 + blockOffset;

            if (absoluteBlockOffset + blockSize > resourceForkData.length) {
                // Truncated block — decompress what we can
                blockSize = resourceForkData.length - absoluteBlockOffset;
                if (blockSize <= 0) break;
            }

            int expectedDecompressed = Math.min(RESOURCE_FORK_BLOCK_SIZE, uncompressedSize - resultOffset);

            // Check if the block is stored uncompressed (first byte 0xFF marker)
            if (blockSize > 0 && (resourceForkData[absoluteBlockOffset] & 0xFF) == 0xFF) {
                int copyLen = Math.min(blockSize - 1, expectedDecompressed);
                System.arraycopy(resourceForkData, absoluteBlockOffset + 1, result, resultOffset, copyLen);
                resultOffset += copyLen;
            } else if (blockSize == expectedDecompressed) {
                // Block stored uncompressed (size matches expected)
                System.arraycopy(resourceForkData, absoluteBlockOffset, result, resultOffset, blockSize);
                resultOffset += blockSize;
            } else {
                // zlib compressed block
                byte[] decompressed = inflateData(resourceForkData, absoluteBlockOffset, blockSize,
                        expectedDecompressed);
                int copyLen = Math.min(decompressed.length, uncompressedSize - resultOffset);
                System.arraycopy(decompressed, 0, result, resultOffset, copyLen);
                resultOffset += copyLen;
            }
        }

        return result;
    }

    /**
     * Type 9: uncompressed data stored directly in the xattr after the 16-byte header.
     */
    private static byte[] decompressInlineXattr(DecmpfsHeader header, byte[] xattrData) throws IOException {
        int dataLen = xattrData.length - HEADER_SIZE;
        if (dataLen < 0) dataLen = 0;

        long expectedSize = header.uncompressedSize();
        int actualLen = (int) Math.min(dataLen, expectedSize);

        byte[] result = new byte[actualLen];
        if (actualLen > 0) {
            System.arraycopy(xattrData, HEADER_SIZE, result, 0, actualLen);
        }
        return result;
    }

    /**
     * Inflates zlib-compressed data.
     *
     * @param input the input buffer containing compressed data
     * @param offset offset into the input buffer
     * @param length length of compressed data
     * @param expectedSize expected uncompressed size
     * @return the decompressed data
     * @throws IOException if decompression fails
     */
    private static byte[] inflateData(byte[] input, int offset, int length, int expectedSize) throws IOException {
        // Try with zlib header first, fall back to raw deflate
        byte[] result = tryInflate(input, offset, length, expectedSize, false);
        if (result != null) return result;

        result = tryInflate(input, offset, length, expectedSize, true);
        if (result != null) return result;

        throw new IOException("Failed to decompress zlib data (tried both zlib and raw deflate modes)");
    }

    private static byte[] tryInflate(byte[] input, int offset, int length, int expectedSize, boolean rawDeflate)
            throws IOException {
        Inflater inflater = new Inflater(rawDeflate);
        try {
            inflater.setInput(input, offset, length);
            byte[] output = new byte[expectedSize];
            int totalDecompressed = 0;

            while (!inflater.finished() && totalDecompressed < expectedSize) {
                int n = inflater.inflate(output, totalDecompressed, expectedSize - totalDecompressed);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                totalDecompressed += n;
            }

            if (totalDecompressed > 0) {
                if (totalDecompressed < expectedSize) {
                    // Return what we got — partial decompression
                    byte[] partial = new byte[totalDecompressed];
                    System.arraycopy(output, 0, partial, 0, totalDecompressed);
                    return partial;
                }
                return output;
            }
            return null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /**
     * Returns true if the given compression type is supported by this implementation.
     */
    public static boolean isSupported(int compressionType) {
        return compressionType == TYPE_ZLIB_XATTR
                || compressionType == TYPE_ZLIB_RESOURCE_FORK
                || compressionType == TYPE_UNCOMPRESSED_XATTR;
    }
}
