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
package io.spicelabs.saffron.container.compressed;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Supported compression formats for a single compressed non-archive payload.
 */
public enum CompressedSingleFormat {
    GZIP("gzip", new byte[]{(byte) 0x1f, (byte) 0x8b}),
    XZ("xz", new byte[]{(byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00}),
    BZIP2("bzip2", new byte[]{'B', 'Z', 'h'});

    private final String name;
    private final byte[] magic;

    CompressedSingleFormat(@NotNull String name, byte @NotNull [] magic) {
        this.name = name;
        this.magic = magic.clone();
    }

    public @NotNull String getName() {
        return name;
    }

    public byte @NotNull [] getMagic() {
        return magic.clone();
    }

    /**
     * Checks whether the supplied buffer begins with this format's magic bytes.
     *
     * @param buffer the buffer to inspect; position must be 0
     * @return true if the buffer starts with the magic bytes
     */
    public boolean isMagic(@NotNull ByteBuffer buffer) {
        if (buffer.remaining() < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (buffer.get(i) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Opens a decompressing input stream for this format.
     *
     * @param compressed the compressed input stream
     * @param memoryLimitInKb maximum memory in KiB for XZ dictionary; ignored for other formats
     * @return a stream that yields decompressed bytes
     * @throws IOException if the stream cannot be created or the bzip2 block size is invalid
     */
    public @NotNull InputStream openDecompressor(@NotNull InputStream compressed, int memoryLimitInKb)
            throws IOException {
        return switch (this) {
            case GZIP -> new GZIPInputStream(compressed);
            case XZ -> new XZCompressorInputStream(compressed, true, memoryLimitInKb);
            case BZIP2 -> {
                // BufferedInputStream is required for the block-size validation peek,
                // and it also improves streaming performance for file-backed sources.
                java.io.BufferedInputStream buffered = new java.io.BufferedInputStream(compressed);
                validateBzip2BlockSize(buffered);
                yield new BZip2CompressorInputStream(buffered);
            }
        };
    }

    /**
     * Returns the format detected from the buffer, or empty if none.
     *
     * @param buffer the buffer to inspect; position must be 0
     * @return the detected format, or empty
     */
    public static @NotNull java.util.Optional<CompressedSingleFormat> detect(@NotNull ByteBuffer buffer) {
        for (CompressedSingleFormat format : values()) {
            if (format.isMagic(buffer)) {
                return java.util.Optional.of(format);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Checks whether a path should be excluded from compressed-single detection.
     * Excludes tar-in-compression archives and compressed raw disk images.
     *
     * @param path the path to inspect
     * @return true if the path should not be detected as a compressed single payload
     */
    public static boolean isExcludedPath(@NotNull java.nio.file.Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".tar.gz") || name.endsWith(".tgz")
                || name.endsWith(".tar.xz") || name.endsWith(".txz")
                || name.endsWith(".tar.bz2") || name.endsWith(".tbz2")
                || name.endsWith(".tar.lzma") || name.endsWith(".tlzma")
                || name.endsWith(".img.gz") || name.endsWith(".raw.gz");
    }

    /**
     * Validates that a bzip2 stream declares a supported block size (1-9).
     * Malformed or corrupted block size fields are rejected before decompression
     * starts to bound memory usage.
     */
    private static void validateBzip2BlockSize(@NotNull InputStream compressed) throws IOException {
        compressed.mark(4);
        try {
            byte[] header = new byte[4];
            int read = compressed.read(header);
            if (read < 4) {
                throw new IOException("Truncated bzip2 header");
            }
            // Magic is "BZh", fourth byte is ASCII '1'..'9' for 100..900 KB block size.
            if (header[0] != 'B' || header[1] != 'Z' || header[2] != 'h') {
                throw new IOException("Invalid bzip2 magic");
            }
            int blockDigit = header[3] - '0';
            if (blockDigit < 1 || blockDigit > 9) {
                throw new IOException("Invalid bzip2 block size: " + (char) header[3]);
            }
        } finally {
            compressed.reset();
        }
    }
}
