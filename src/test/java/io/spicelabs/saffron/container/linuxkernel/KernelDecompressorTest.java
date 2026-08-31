/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.linuxkernel;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.anarres.lzo.LzoCompressor1x_1;
import org.anarres.lzo.lzo_uintp;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link KernelDecompressor} can detect and decompress every kernel
 * payload compression format it claims to support.
 *
 * <p>Each test case compresses a known payload with a real compressor, hands the
 * bytes to {@link KernelDecompressor}, and verifies the original bytes are
 * restored.</p>
 */
class KernelDecompressorTest {

    private static final byte[] PAYLOAD = "KernelDecompressor test payload".getBytes();

    @ParameterizedTest
    @MethodSource("compressionCases")
    void decompressesAllSupportedFormats(String name, byte[] compressed) {
        Optional<byte[]> result = KernelDecompressor.decompress(compressed, 0, compressed.length);
        assertThat(result).as("decompress %s", name).isPresent();
        assertThat(result.get()).as("%s output", name).containsExactly(PAYLOAD);
    }

    static Stream<Arguments> compressionCases() throws IOException {
        return Stream.of(
                Arguments.of("gzip", gzip(PAYLOAD)),
                Arguments.of("bzip2", bzip2(PAYLOAD)),
                Arguments.of("lzma", lzma(PAYLOAD)),
                Arguments.of("xz", xz(PAYLOAD)),
                Arguments.of("lzo", lzo(PAYLOAD)),
                Arguments.of("lz4-legacy", lz4Legacy(PAYLOAD)),
                Arguments.of("lz4-frame", lz4Frame(PAYLOAD)),
                Arguments.of("zstd", zstd(PAYLOAD))
        );
    }

    @Test
    void decompressUImageUsesHeaderCompressionType() throws IOException {
        byte[] compressed = gzip(PAYLOAD);
        // uImage compression type 1 = gzip (IH_COMP_GZIP)
        Optional<byte[]> result = KernelDecompressor.decompressUImage(compressed, 0, compressed.length, 1);
        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(PAYLOAD);
    }

    @Test
    void returnsEmptyForUncompressedBytes() {
        byte[] uncompressed = PAYLOAD.clone();
        Optional<byte[]> result = KernelDecompressor.decompress(uncompressed, 0, uncompressed.length);
        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForZeroLengthInput() {
        Optional<byte[]> result = KernelDecompressor.decompress(new byte[0], 0, 0);
        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForTruncatedGzip() throws IOException {
        byte[] compressed = gzip(PAYLOAD);
        Optional<byte[]> result = KernelDecompressor.decompress(compressed, 0, 4);
        assertThat(result).isEmpty();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
            gz.finish();
            return out.toByteArray();
        }
    }

    private static byte[] bzip2(byte[] data) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             BZip2CompressorOutputStream bz = new BZip2CompressorOutputStream(out)) {
            bz.write(data);
            // close() finishes the stream; an explicit finish() before
            // close() double-finishes (commons-compress 1.28 nulls its
            // state after finish and close() re-writes).
            bz.close();
            return out.toByteArray();
        }
    }

    private static byte[] lzma(byte[] data) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             LZMACompressorOutputStream lz = new LZMACompressorOutputStream(out)) {
            lz.write(data);
            lz.close(); // close() finishes; explicit finish()+close() double-finishes
            return out.toByteArray();
        }
    }

    private static byte[] xz(byte[] data) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             XZCompressorOutputStream xz = new XZCompressorOutputStream(out)) {
            xz.write(data);
            xz.close(); // close() finishes; explicit finish()+close() double-finishes
            return out.toByteArray();
        }
    }

    private static byte[] lzo(byte[] data) throws IOException {
        byte[] compressed = new byte[data.length + data.length / 16 + 64 + 3];
        lzo_uintp written = new lzo_uintp(compressed.length);
        int result = LzoCompressor1x_1.compress(data, 0, data.length, compressed, 0, written, null);
        if (result != 0) {
            throw new IOException("LZO compression failed: " + result);
        }
        // The Linux kernel LZO payload format expects the standard lzo1x magic header.
        byte[] lzoMagic = {0x00, (byte) 0x09, 0x20, (byte) 0x89};
        byte[] trimmed = new byte[lzoMagic.length + written.value];
        System.arraycopy(lzoMagic, 0, trimmed, 0, lzoMagic.length);
        System.arraycopy(compressed, 0, trimmed, lzoMagic.length, written.value);
        return trimmed;
    }

    private static byte[] lz4Legacy(byte[] data) {
        LZ4Factory factory = LZ4Factory.fastestInstance();
        LZ4Compressor compressor = factory.fastCompressor();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Linux kernel LZ4 legacy magic.
        out.write(0x04);
        out.write(0x22);
        out.write(0x4d);
        out.write(0x18);
        // Write a single block: 4-byte little-endian compressed size + compressed bytes.
        int maxCompressed = compressor.maxCompressedLength(data.length);
        byte[] compressed = new byte[maxCompressed];
        int compressedLen = compressor.compress(data, 0, data.length, compressed, 0, maxCompressed);
        byte[] size = new byte[4];
        ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).putInt(compressedLen);
        out.write(size, 0, 4);
        out.write(compressed, 0, compressedLen);
        // Terminator block with size 0.
        out.write(new byte[]{0, 0, 0, 0}, 0, 4);
        return out.toByteArray();
    }

    private static byte[] lz4Frame(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LZ4FrameOutputStream frame = new LZ4FrameOutputStream(out);
        try {
            frame.write(data);
            frame.flush();
        } finally {
            frame.close();
        }
        return out.toByteArray();
    }

    private static byte[] zstd(byte[] data) {
        return com.github.luben.zstd.Zstd.compress(data, 3);
    }
}
