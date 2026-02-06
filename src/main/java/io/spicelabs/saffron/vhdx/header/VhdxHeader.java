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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Represents a VHDX header structure.
 *
 * <p>VHDX files have two copies of the header at offsets 64 KB and 128 KB.
 * The header with the higher sequence number is the current one.
 *
 * <p>Header structure (4 KB):
 * <pre>
 * Offset  Size  Description
 * 0       4     Signature ("head")
 * 4       4     Checksum (CRC-32C)
 * 8       8     Sequence Number
 * 16      16    File Write GUID
 * 32      16    Data Write GUID
 * 48      16    Log GUID
 * 64      2     Log Version
 * 66      2     Version (1)
 * 68      4     Log Length
 * 72      8     Log Offset
 * </pre>
 */
public record VhdxHeader(
        int checksum,
        long sequenceNumber,
        @NotNull UUID fileWriteGuid,
        @NotNull UUID dataWriteGuid,
        @NotNull UUID logGuid,
        int logVersion,
        int version,
        int logLength,
        long logOffset
) {

    /** Magic signature for VHDX header */
    public static final byte[] MAGIC = "head".getBytes(StandardCharsets.US_ASCII);

    /** Header size in bytes */
    public static final int HEADER_SIZE = 4096;

    /** Expected version */
    public static final int VERSION_1 = 1;

    /**
     * Reads both headers and returns the one with the higher sequence number.
     *
     * @param channel the channel to read from
     * @return the current (active) header
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VhdxHeader readCurrent(@NotNull SeekableByteChannel channel)
            throws IOException {
        VhdxHeader header1 = null;
        VhdxHeader header2 = null;

        try {
            header1 = read(channel, VhdxFileIdentifier.HEADER1_OFFSET);
        } catch (InvalidMagicException e) {
            // Header 1 is invalid
        }

        try {
            header2 = read(channel, VhdxFileIdentifier.HEADER2_OFFSET);
        } catch (InvalidMagicException e) {
            // Header 2 is invalid
        }

        if (header1 == null && header2 == null) {
            throw new IOException("Both VHDX headers are invalid");
        }

        if (header1 == null) {
            return header2;
        }
        if (header2 == null) {
            return header1;
        }

        // Return header with higher sequence number
        return header1.sequenceNumber() >= header2.sequenceNumber() ? header1 : header2;
    }

    /**
     * Reads a header from the specified offset.
     *
     * @param channel the channel to read from
     * @param offset the offset where the header begins
     * @return the parsed header
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VhdxHeader read(@NotNull SeekableByteChannel channel, long offset)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        int read = channel.read(buffer);
        if (read < 80) {
            throw new IOException("Failed to read VHDX header: got " + read + " bytes");
        }
        buffer.flip();

        // Validate signature
        byte[] signature = new byte[4];
        buffer.get(signature);
        if (!SecurityUtils.constantTimeEquals(signature, MAGIC)) {
            throw new InvalidMagicException(
                    "Invalid VHDX header signature: expected 'head'",
                    MAGIC, signature, offset, DiskFormat.VHDX);
        }

        // Checksum
        int checksum = buffer.getInt();

        // Sequence number
        long sequenceNumber = buffer.getLong();

        // File write GUID
        UUID fileWriteGuid = readUuid(buffer);

        // Data write GUID
        UUID dataWriteGuid = readUuid(buffer);

        // Log GUID
        UUID logGuid = readUuid(buffer);

        // Log version
        int logVersion = buffer.getShort() & 0xFFFF;

        // Version
        int version = buffer.getShort() & 0xFFFF;

        // Log length
        int logLength = buffer.getInt();

        // Log offset
        long logOffset = buffer.getLong();

        return new VhdxHeader(
                checksum,
                sequenceNumber,
                fileWriteGuid,
                dataWriteGuid,
                logGuid,
                logVersion,
                version,
                logLength,
                logOffset
        );
    }

    private static UUID readUuid(ByteBuffer buffer) {
        // VHDX uses Microsoft GUID format (little-endian for first 3 components)
        int data1 = buffer.getInt();
        short data2 = buffer.getShort();
        short data3 = buffer.getShort();
        byte[] data4 = new byte[8];
        buffer.get(data4);

        long msb = ((long) data1 << 32) | ((long) (data2 & 0xFFFF) << 16) | (data3 & 0xFFFF);
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            lsb = (lsb << 8) | (data4[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }
}
