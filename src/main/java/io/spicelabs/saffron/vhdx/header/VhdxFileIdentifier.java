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
package io.spicelabs.saffron.vhdx.header;

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
 * Represents the VHDX file type identifier.
 *
 * <p>The file type identifier is the first structure in a VHDX file,
 * occupying the first 1 MB. It contains the signature and optional
 * creator information.
 *
 * <p>Structure (at offset 0):
 * <pre>
 * Offset  Size  Description
 * 0       8     Signature ("vhdxfile")
 * 8       256   Creator (UTF-16LE, optional)
 * </pre>
 */
public record VhdxFileIdentifier(
        @Nullable String creator
) {

    /** Magic signature for VHDX files */
    public static final byte[] MAGIC = "vhdxfile".getBytes(StandardCharsets.US_ASCII);

    /** Size of the file identifier region */
    public static final int FILE_IDENTIFIER_SIZE = 1024 * 1024; // 1 MB

    /** Offset where Header 1 begins */
    public static final long HEADER1_OFFSET = 64 * 1024; // 64 KB

    /** Offset where Header 2 begins */
    public static final long HEADER2_OFFSET = 128 * 1024; // 128 KB

    /**
     * Reads the file identifier from the beginning of a VHDX file.
     *
     * @param channel the channel to read from
     * @return the parsed file identifier
     * @throws IOException if an I/O error occurs
     * @throws InvalidMagicException if the magic signature is invalid
     */
    public static @NotNull VhdxFileIdentifier read(@NotNull SeekableByteChannel channel)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(264); // Signature + Creator
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(0);
        int read = channel.read(buffer);
        if (read < 8) {
            throw new IOException("Failed to read VHDX file identifier");
        }
        buffer.flip();

        // Validate signature
        byte[] signature = new byte[8];
        buffer.get(signature);
        if (!SecurityUtils.constantTimeEquals(signature, MAGIC)) {
            throw new InvalidMagicException(
                    "Invalid VHDX signature: expected 'vhdxfile'",
                    MAGIC, signature, 0, DiskFormat.VHDX);
        }

        // Read creator (optional, UTF-16LE)
        String creator = null;
        if (read >= 264) {
            byte[] creatorBytes = new byte[256];
            buffer.get(creatorBytes);
            creator = parseUtf16Le(creatorBytes);
        }

        return new VhdxFileIdentifier(creator);
    }

    private static @Nullable String parseUtf16Le(byte[] bytes) {
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
        return new String(bytes, 0, length, StandardCharsets.UTF_16LE);
    }
}
