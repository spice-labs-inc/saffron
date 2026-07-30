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
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.io.BoundedInputStream;
import net.jpountz.lz4.LZ4Factory;
import org.anarres.lzo.LzoAlgorithm;
import org.anarres.lzo.LzoDecompressor1x;
import org.anarres.lzo.lzo_uintp;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public interface SquashfsCompressor {

    byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) throws IOException;

    static @NotNull SquashfsCompressor forId(int id) throws IOException {
        return switch (id) {
            case 0 -> new NoneCompressor();
            case 1 -> new GzipCompressor();
            case 2 -> new LzmaCompressor();
            case 3 -> new LzoCompressor();
            case 4 -> new XzCompressor();
            case 5 -> new Lz4Compressor();
            case 6 -> new ZstdCompressor();
            default -> throw new IOException("Unsupported squashfs compression id: " + id);
        };
    }

    final class NoneCompressor implements SquashfsCompressor {
        @Override
        public byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) {
            if (input.length > maxOutputSize) {
                byte[] copy = new byte[maxOutputSize];
                System.arraycopy(input, 0, copy, 0, maxOutputSize);
                return copy;
            }
            return input.clone();
        }
    }

    final class XzCompressor implements SquashfsCompressor {
        @Override
        public byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) throws IOException {
            try (InputStream in = new XZCompressorInputStream(new ByteArrayInputStream(input));
                 BoundedInputStream bounded = new BoundedInputStream(in, maxOutputSize)) {
                return bounded.readAllBytes();
            }
        }
    }

    final class GzipCompressor implements SquashfsCompressor {
        @Override
        public byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) throws IOException {
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(input), new Inflater());
                 BoundedInputStream bounded = new BoundedInputStream(in, maxOutputSize)) {
                return bounded.readAllBytes();
            }
        }
    }

    final class LzmaCompressor implements SquashfsCompressor {
        @Override
        public byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) throws IOException {
            try (InputStream in = new LZMACompressorInputStream(new ByteArrayInputStream(input));
                 BoundedInputStream bounded = new BoundedInputStream(in, maxOutputSize)) {
                return bounded.readAllBytes();
            }
        }
    }

    final class LzoCompressor implements SquashfsCompressor {
        @Override
        public byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) throws IOException {
            byte[] output = new byte[maxOutputSize];
            lzo_uintp written = new lzo_uintp(maxOutputSize);
            int result = LzoDecompressor1x.decompress(input, 0, input.length, output, 0, written, null);
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
    }

    final class Lz4Compressor implements SquashfsCompressor {
        private final LZ4Factory factory = LZ4Factory.fastestInstance();

        @Override
        public byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) throws IOException {
            byte[] output = new byte[maxOutputSize];
            int written = factory.unknownSizeDecompressor().decompress(input, 0, input.length, output, 0, maxOutputSize);
            if (written == output.length) {
                return output;
            }
            byte[] trimmed = new byte[written];
            System.arraycopy(output, 0, trimmed, 0, written);
            return trimmed;
        }
    }

    final class ZstdCompressor implements SquashfsCompressor {
        @Override
        public byte @NotNull [] decompress(byte @NotNull [] input, int maxOutputSize) throws IOException {
            byte[] output = new byte[maxOutputSize];
            long written = com.github.luben.zstd.Zstd.decompress(output, input);
            if (com.github.luben.zstd.Zstd.isError(written)) {
                throw new IOException("Zstd decompression failed: " + com.github.luben.zstd.Zstd.getErrorName(written));
            }
            if (written == output.length) {
                return output;
            }
            byte[] trimmed = new byte[(int) written];
            System.arraycopy(output, 0, trimmed, 0, (int) written);
            return trimmed;
        }
    }
}
