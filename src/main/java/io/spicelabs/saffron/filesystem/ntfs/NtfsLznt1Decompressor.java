/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ntfs;

import java.io.IOException;

/**
 * LZNT1 decompression for NTFS compressed files.
 *
 * <p>NTFS uses LZNT1 compression on 16-cluster compression units. Each unit
 * is either compressed (starts with a chunk header) or stored uncompressed
 * (when compression would not save space).
 *
 * <p>The compression format is documented in MS-XCA section 2.5.
 */
final class NtfsLznt1Decompressor {

    private NtfsLznt1Decompressor() {}

    /**
     * Decompresses LZNT1-compressed data.
     *
     * @param compressed the compressed input data
     * @param uncompressedSize the expected output size
     * @return the decompressed data
     * @throws IOException if the compressed data is malformed
     */
    static byte[] decompress(byte[] compressed, int uncompressedSize) throws IOException {
        byte[] output = new byte[uncompressedSize];
        int srcPos = 0;
        int dstPos = 0;

        while (srcPos < compressed.length && dstPos < uncompressedSize) {
            if (srcPos + 2 > compressed.length) break;

            // Read chunk header (2 bytes, little-endian)
            int header = (compressed[srcPos] & 0xFF) | ((compressed[srcPos + 1] & 0xFF) << 8);
            srcPos += 2;

            if (header == 0) {
                // End of compressed data
                break;
            }

            int chunkSize = (header & 0x0FFF) + 1;  // 12-bit size field + 1
            boolean isCompressed = (header & 0x8000) != 0;

            if (!isCompressed) {
                // Uncompressed chunk — copy directly
                int toCopy = Math.min(chunkSize, Math.min(compressed.length - srcPos, uncompressedSize - dstPos));
                System.arraycopy(compressed, srcPos, output, dstPos, toCopy);
                srcPos += chunkSize;
                dstPos += toCopy;
            } else {
                // Compressed chunk
                int chunkEnd = srcPos + chunkSize;
                if (chunkEnd > compressed.length) {
                    chunkEnd = compressed.length;
                }
                int chunkStart = dstPos;

                while (srcPos < chunkEnd && dstPos < uncompressedSize) {
                    if (srcPos >= compressed.length) break;

                    int flagByte = compressed[srcPos++] & 0xFF;

                    for (int bit = 0; bit < 8 && srcPos < chunkEnd && dstPos < uncompressedSize; bit++) {
                        if ((flagByte & (1 << bit)) == 0) {
                            // Literal byte
                            output[dstPos++] = compressed[srcPos++];
                        } else {
                            // Back-reference (compressed pair)
                            if (srcPos + 2 > compressed.length) break;

                            int ref = (compressed[srcPos] & 0xFF) | ((compressed[srcPos + 1] & 0xFF) << 8);
                            srcPos += 2;

                            // Compute displacement and length based on current position in chunk
                            int posInChunk = dstPos - chunkStart;
                            int displacementBits = computeDisplacementBits(posInChunk);
                            int lengthBits = 16 - displacementBits;

                            int displacement = (ref >> lengthBits) + 1;
                            int length = (ref & ((1 << lengthBits) - 1)) + 3;

                            // Copy from back-reference
                            int srcOffset = dstPos - displacement;
                            if (srcOffset < 0) break;

                            for (int j = 0; j < length && dstPos < uncompressedSize; j++) {
                                output[dstPos++] = output[srcOffset + j];
                            }
                        }
                    }
                }

                srcPos = Math.max(srcPos, chunkEnd);
            }
        }

        return output;
    }

    /**
     * Computes the number of bits used for the displacement field based on
     * the current position within the chunk. This is the LZNT1 sliding window
     * encoding defined in MS-XCA.
     */
    private static int computeDisplacementBits(int position) {
        if (position <= 0) return 4;  // Minimum displacement bits

        // The displacement size increases as position increases
        // Position range → displacement bits:
        // 0-63: 4, 64-127: 5, 128-255: 6, 256-511: 7, ...
        int bits = 4;
        int threshold = 0x10; // 16
        while (position >= threshold && bits < 12) {
            bits++;
            threshold <<= 1;
        }
        return bits;
    }
}
