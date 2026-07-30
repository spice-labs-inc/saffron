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
package io.spicelabs.saffron.container.linuxkernel;

import io.spicelabs.saffron.io.BoundedInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4UnknownSizeDecompressor;
import org.anarres.lzo.LzoAlgorithm;
import org.anarres.lzo.LzoDecompressor1x;
import org.anarres.lzo.LzoDecompressor1y;
import org.anarres.lzo.lzo_uintp;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Decompresses Linux kernel payloads using the compression algorithms declared
 * in the kernel or U-Boot image headers.
 *
 * <p>All decompression is bounded by a maximum output size derived from the
 * compressed input size, avoiding arbitrary fixed caps. The bound is computed
 * as the compressed size plus a generous headroom (up to {@link Integer#MAX_VALUE})
 * so that typical kernels decompress completely while malicious streams cannot
 * exhaust memory.</p>
 */
final class KernelDecompressor {

    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;
    private static final byte[] BZIP2_MAGIC = {'B', 'Z', 'h'};
    private static final int LZMA_MAGIC_0 = 0x5d;
    private static final byte[] XZ_MAGIC = {(byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00};
    private static final byte[] LZO_MAGIC_1X = {0x00, (byte) 0x09, 0x20, (byte) 0x89};
    // Both the Linux kernel LZ4 wrapper and the official LZ4 frame format use the
    // same magic value 0x184D2204 stored little-endian as these four bytes.
    private static final byte[] LZ4_MAGIC = {0x04, 0x22, 0x4d, 0x18};
    private static final byte[] ZSTD_MAGIC = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd};

    private static final long MAX_OUTPUT_SIZE = Integer.MAX_VALUE;

    private KernelDecompressor() {
        // Static utility class
    }

    /**
     * Attempts to decompress a kernel payload.
     *
     * @param data   the raw bytes starting at the payload offset
     * @param offset the start of the compressed payload within {@code data}
     * @param length the length of the compressed payload
     * @return the decompressed bytes, or empty if the payload is not compressed
     * or decompression fails
     */
    static @NotNull Optional<byte[]> decompress(@NotNull byte[] data, int offset, int length) {
        if (length < 2) {
            return Optional.empty();
        }
        CompressionType type = detectCompression(data, offset, length);
        if (type == CompressionType.NONE) {
            return Optional.empty();
        }
        try {
            int maxOutput = maxOutputSize(length);
            byte[] output = decompressWithType(type, data, offset, length, maxOutput);
            return Optional.of(output);
        } catch (IOException | IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    /**
     * Decompresses a U-Boot uImage payload using the compression type declared
     * in the uImage header.
     *
     * @param data        the raw bytes starting at the payload offset
     * @param offset      the start of the payload within {@code data}
     * @param length      the length of the payload
     * @param compression the uImage compression type (IH_COMP_*)
     * @return the decompressed bytes, or empty if uncompressed or decompression fails
     */
    static @NotNull Optional<byte[]> decompressUImage(@NotNull byte[] data, int offset, int length, int compression) {
        CompressionType type = CompressionType.fromUImage(compression);
        if (type == CompressionType.NONE) {
            return Optional.empty();
        }
        try {
            int maxOutput = maxOutputSize(length);
            byte[] output = decompressWithType(type, data, offset, length, maxOutput);
            return Optional.of(output);
        } catch (IOException | IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static @NotNull CompressionType detectCompression(@NotNull byte[] data, int offset, int length) {
        if (length >= 2 && (data[offset] & 0xff) == GZIP_MAGIC_0 && (data[offset + 1] & 0xff) == GZIP_MAGIC_1) {
            return CompressionType.GZIP;
        }
        if (length >= 3 && matches(data, offset, BZIP2_MAGIC)) {
            return CompressionType.BZIP2;
        }
        if (length >= 4 && (data[offset] & 0xff) == LZMA_MAGIC_0) {
            return CompressionType.LZMA;
        }
        if (length >= 6 && matches(data, offset, XZ_MAGIC)) {
            return CompressionType.XZ;
        }
        if (length >= 4 && matches(data, offset, LZ4_MAGIC)) {
            return CompressionType.LZ4;
        }
        if (length >= 4 && matches(data, offset, ZSTD_MAGIC)) {
            return CompressionType.ZSTD;
        }
        if (length >= 4 && matches(data, offset, LZO_MAGIC_1X)) {
            return CompressionType.LZO1X;
        }
        return CompressionType.NONE;
    }

    private static int maxOutputSize(int compressedLength) {
        long estimate = Math.max((long) compressedLength * 16L, 64L * 1024);
        return (int) Math.min(estimate, MAX_OUTPUT_SIZE);
    }

    private static byte[] decompressWithType(@NotNull CompressionType type, @NotNull byte[] data, int offset, int length, int maxOutput) throws IOException {
        return switch (type) {
            case GZIP -> decompressGzip(data, offset, length, maxOutput);
            case BZIP2 -> decompressBzip2(data, offset, length, maxOutput);
            case LZMA -> decompressLzma(data, offset, length, maxOutput);
            case XZ -> decompressXz(data, offset, length, maxOutput);
            case LZO1X -> decompressLzo(data, offset, length, maxOutput, LzoAlgorithm.LZO1X);
            case LZO1Y -> decompressLzo(data, offset, length, maxOutput, LzoAlgorithm.LZO1Y);
            case LZ4 -> decompressLz4(data, offset, length, maxOutput);
            case ZSTD -> decompressZstd(data, offset, length, maxOutput);
            case NONE -> throw new IllegalArgumentException("No compression");
        };
    }

    private static byte[] decompressGzip(byte[] data, int offset, int length, int maxOutput) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data, offset, length));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length * 4, 64 * 1024))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gz.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > maxOutput) {
                    throw new IOException("Decompressed output exceeds derived limit");
                }
            }
            return out.toByteArray();
        }
    }

    private static byte[] decompressBzip2(byte[] data, int offset, int length, int maxOutput) throws IOException {
        try (InputStream in = new BZip2CompressorInputStream(new ByteArrayInputStream(data, offset, length));
             BoundedInputStream bounded = new BoundedInputStream(in, maxOutput);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = bounded.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] decompressLzma(byte[] data, int offset, int length, int maxOutput) throws IOException {
        try (InputStream in = new LZMACompressorInputStream(new ByteArrayInputStream(data, offset, length));
             BoundedInputStream bounded = new BoundedInputStream(in, maxOutput);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = bounded.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] decompressXz(byte[] data, int offset, int length, int maxOutput) throws IOException {
        try (InputStream in = new XZCompressorInputStream(new ByteArrayInputStream(data, offset, length));
             BoundedInputStream bounded = new BoundedInputStream(in, maxOutput);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = bounded.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] decompressLzo(byte[] data, int offset, int length, int maxOutput, LzoAlgorithm algorithm) throws IOException {
        // The kernel LZO format begins with a 4-byte magic header that is not part
        // of the compressed stream expected by the lzo decompressors.
        int magicLen = LZO_MAGIC_1X.length;
        if (length < magicLen) {
            throw new IOException("LZO payload too short for magic header");
        }
        int payloadOffset = offset + magicLen;
        int payloadLength = length - magicLen;
        byte[] output = new byte[maxOutput];
        lzo_uintp written = new lzo_uintp(maxOutput);
        int result;
        if (algorithm == LzoAlgorithm.LZO1Y) {
            result = LzoDecompressor1y.decompress(data, payloadOffset, payloadLength, output, 0, written, null);
        } else {
            result = LzoDecompressor1x.decompress(data, payloadOffset, payloadLength, output, 0, written, null);
        }
        if (result != 0) {
            throw new IOException("LZO decompression failed: " + result);
        }
        if (written.value == output.length) {
            return output;
        }
        byte[] trimmed = new byte[written.value];
        System.arraycopy(output, 0, trimmed, 0, written.value);
        return trimmed;
    }

    private static byte[] decompressLz4(byte[] data, int offset, int length, int maxOutput) throws IOException {
        // The LZ4 magic is shared by the official frame format and the kernel's
        // legacy wrapper. Try the official frame format first; if the header is not
        // a valid frame, fall back to the kernel legacy block format.
        try {
            byte[] frame = decompressLz4Frame(data, offset, length, maxOutput);
            if (frame.length > 0) {
                return frame;
            }
        } catch (IOException | RuntimeException e) {
            // Fall back to legacy parsing below.
        }
        return decompressLz4Legacy(data, offset, length, maxOutput);
    }

    private static byte[] decompressLz4Legacy(byte[] data, int offset, int length, int maxOutput) throws IOException {
        LZ4Factory factory = LZ4Factory.fastestInstance();
        LZ4UnknownSizeDecompressor decompressor = factory.unknownSizeDecompressor();
        int pos = offset + 4; // skip legacy magic
        int end = offset + length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (pos < end) {
            if (pos + 4 > end) {
                throw new IOException("Truncated LZ4 legacy block header");
            }
            int compressedSize = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(pos);
            pos += 4;
            if (compressedSize == 0) {
                break;
            }
            if (compressedSize < 0 || pos + compressedSize > end) {
                throw new IOException("Invalid LZ4 legacy block size: " + compressedSize);
            }
            int remainingOutput = maxOutput - out.size();
            if (remainingOutput <= 0) {
                throw new IOException("Decompressed output exceeds derived limit");
            }
            // unknownSizeDecompressor needs a destination buffer; cap per-block to avoid
            // allocating the whole remaining allowance for every block.
            int blockBufferSize = Math.min(remainingOutput, 64 * 1024);
            byte[] blockOutput = new byte[Math.max(blockBufferSize, 1)];
            int written = decompressor.decompress(data, pos, compressedSize, blockOutput, 0, blockOutput.length);
            out.write(blockOutput, 0, written);
            pos += compressedSize;
        }
        return out.toByteArray();
    }

    private static byte[] decompressLz4Frame(byte[] data, int offset, int length, int maxOutput) throws IOException {
        try (LZ4FrameInputStream frame = new LZ4FrameInputStream(new ByteArrayInputStream(data, offset, length));
             BoundedInputStream bounded = new BoundedInputStream(frame, maxOutput);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = bounded.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] decompressZstd(byte[] data, int offset, int length, int maxOutput) throws IOException {
        byte[] compressed = Arrays.copyOfRange(data, offset, offset + length);
        try {
            byte[] output = com.github.luben.zstd.Zstd.decompress(compressed, maxOutput);
            if (output.length > maxOutput) {
                throw new IOException("Decompressed output exceeds derived limit");
            }
            return output;
        } catch (RuntimeException e) {
            throw new IOException("Zstd decompression failed", e);
        }
    }

    private static boolean matches(byte[] data, int offset, byte[] pattern) {
        if (offset + pattern.length > data.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private enum CompressionType {
        NONE, GZIP, BZIP2, LZMA, XZ, LZO1X, LZO1Y, LZ4, ZSTD;

        static CompressionType fromUImage(int compression) {
            return switch (compression) {
                case 0 -> NONE;     // IH_COMP_NONE
                case 1 -> GZIP;     // IH_COMP_GZIP
                case 2 -> BZIP2;    // IH_COMP_BZIP2
                case 3 -> LZMA;     // IH_COMP_LZMA
                case 4, 5 -> LZO1X; // IH_COMP_LZO
                case 6 -> LZ4;      // IH_COMP_LZ4
                case 7 -> ZSTD;     // IH_COMP_ZSTD
                default -> NONE;
            };
        }
    }
}
