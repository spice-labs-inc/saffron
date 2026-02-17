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
package io.spicelabs.saffron.io;

import org.jetbrains.annotations.NotNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Endian-aware binary reader for stream-based I/O.
 *
 * <p>This class provides convenient methods for reading binary data from an
 * {@link InputStream} with configurable byte ordering. It supports both
 * big-endian (used by QCOW2) and little-endian (used by VHD, VMDK, VDI, VHDX)
 * formats.
 *
 * <p>Unlike {@code DataInputStream}, this class:
 * <ul>
 *   <li>Supports both byte orderings</li>
 *   <li>Provides unsigned reading methods that return wider types</li>
 *   <li>Tracks the current position for debugging</li>
 *   <li>Does not implement {@code DataInput} to avoid confusion</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try (InputStream is = Files.newInputStream(path);
 *      BinaryReader reader = new BinaryReader(is, ByteOrder.LITTLE_ENDIAN)) {
 *     int magic = reader.readInt32();
 *     long size = reader.readInt64();
 *     String name = reader.readString(32, StandardCharsets.UTF_8);
 * }
 * }</pre>
 *
 * <p>This class is NOT thread-safe.
 */
public class BinaryReader implements AutoCloseable {

    private final InputStream in;
    private final ByteOrder byteOrder;
    private long position;
    private final byte[] buffer8 = new byte[8];

    /**
     * Creates a new BinaryReader with the specified byte order.
     *
     * @param in the underlying input stream
     * @param byteOrder the byte order for multi-byte reads
     */
    public BinaryReader(@NotNull InputStream in, @NotNull ByteOrder byteOrder) {
        this.in = in;
        this.byteOrder = byteOrder;
        this.position = 0;
    }

    /**
     * Creates a new BinaryReader with big-endian byte order.
     *
     * @param in the underlying input stream
     * @return a new big-endian reader
     */
    public static @NotNull BinaryReader bigEndian(@NotNull InputStream in) {
        return new BinaryReader(in, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Creates a new BinaryReader with little-endian byte order.
     *
     * @param in the underlying input stream
     * @return a new little-endian reader
     */
    public static @NotNull BinaryReader littleEndian(@NotNull InputStream in) {
        return new BinaryReader(in, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Returns the byte order used by this reader.
     *
     * @return the byte order
     */
    public @NotNull ByteOrder getByteOrder() {
        return byteOrder;
    }

    /**
     * Returns the current position in the stream.
     *
     * <p>This is the number of bytes read since construction.
     *
     * @return the current position
     */
    public long getPosition() {
        return position;
    }

    /**
     * Reads a single byte as an unsigned value.
     *
     * @return the byte value (0-255)
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public int readUInt8() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Unexpected end of stream at position " + position);
        }
        position++;
        return b;
    }

    /**
     * Reads a single byte as a signed value.
     *
     * @return the signed byte value (-128 to 127)
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public byte readInt8() throws IOException {
        return (byte) readUInt8();
    }

    /**
     * Reads a 16-bit unsigned integer.
     *
     * @return the unsigned 16-bit value (0-65535)
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public int readUInt16() throws IOException {
        readFully(buffer8, 0, 2);
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            return ((buffer8[0] & 0xFF) << 8) |
                   (buffer8[1] & 0xFF);
        } else {
            return (buffer8[0] & 0xFF) |
                   ((buffer8[1] & 0xFF) << 8);
        }
    }

    /**
     * Reads a 16-bit signed integer.
     *
     * @return the signed 16-bit value
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public short readInt16() throws IOException {
        return (short) readUInt16();
    }

    /**
     * Reads a 32-bit signed integer.
     *
     * @return the 32-bit value
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public int readInt32() throws IOException {
        readFully(buffer8, 0, 4);
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            return (buffer8[0] << 24) |
                   ((buffer8[1] & 0xFF) << 16) |
                   ((buffer8[2] & 0xFF) << 8) |
                   (buffer8[3] & 0xFF);
        } else {
            return (buffer8[0] & 0xFF) |
                   ((buffer8[1] & 0xFF) << 8) |
                   ((buffer8[2] & 0xFF) << 16) |
                   (buffer8[3] << 24);
        }
    }

    /**
     * Reads a 32-bit unsigned integer.
     *
     * @return the unsigned 32-bit value as a long (0 to 4294967295)
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public long readUInt32() throws IOException {
        return readInt32() & 0xFFFFFFFFL;
    }

    /**
     * Reads a 64-bit signed integer.
     *
     * @return the 64-bit value
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public long readInt64() throws IOException {
        readFully(buffer8, 0, 8);
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            return ((long) buffer8[0] << 56) |
                   ((long) (buffer8[1] & 0xFF) << 48) |
                   ((long) (buffer8[2] & 0xFF) << 40) |
                   ((long) (buffer8[3] & 0xFF) << 32) |
                   ((long) (buffer8[4] & 0xFF) << 24) |
                   ((long) (buffer8[5] & 0xFF) << 16) |
                   ((long) (buffer8[6] & 0xFF) << 8) |
                   (buffer8[7] & 0xFF);
        } else {
            return (buffer8[0] & 0xFFL) |
                   ((buffer8[1] & 0xFFL) << 8) |
                   ((buffer8[2] & 0xFFL) << 16) |
                   ((buffer8[3] & 0xFFL) << 24) |
                   ((buffer8[4] & 0xFFL) << 32) |
                   ((buffer8[5] & 0xFFL) << 40) |
                   ((buffer8[6] & 0xFFL) << 48) |
                   ((long) buffer8[7] << 56);
        }
    }

    /**
     * Reads exact number of bytes into the buffer.
     *
     * @param buffer the buffer to read into
     * @throws EOFException if end of stream is reached before buffer is filled
     * @throws IOException if an I/O error occurs
     */
    public void readFully(byte @NotNull [] buffer) throws IOException {
        readFully(buffer, 0, buffer.length);
    }

    /**
     * Reads exact number of bytes into the buffer.
     *
     * @param buffer the buffer to read into
     * @param offset the offset in the buffer
     * @param length the number of bytes to read
     * @throws EOFException if end of stream is reached before length bytes are read
     * @throws IOException if an I/O error occurs
     */
    public void readFully(byte @NotNull [] buffer, int offset, int length) throws IOException {
        int remaining = length;
        int off = offset;
        while (remaining > 0) {
            int read = in.read(buffer, off, remaining);
            if (read < 0) {
                throw new EOFException(String.format(
                        "Unexpected end of stream at position %d (needed %d more bytes)",
                        position, remaining));
            }
            off += read;
            remaining -= read;
            position += read;
        }
    }

    /**
     * Reads a byte array of the specified length.
     *
     * @param length the number of bytes to read
     * @return the read bytes
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public byte @NotNull [] readBytes(int length) throws IOException {
        byte[] buffer = new byte[length];
        readFully(buffer);
        return buffer;
    }

    /**
     * Reads a null-terminated string using the specified charset.
     *
     * <p>Reads up to maxLength bytes, stopping at the first null byte.
     *
     * @param maxLength the maximum number of bytes to read
     * @param charset the character set to use for decoding
     * @return the decoded string (without the null terminator)
     * @throws IOException if an I/O error occurs
     */
    public @NotNull String readNullTerminatedString(int maxLength, @NotNull Charset charset) throws IOException {
        byte[] buffer = new byte[maxLength];
        readFully(buffer);
        int end = 0;
        while (end < buffer.length && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, 0, end, charset);
    }

    /**
     * Reads a fixed-length string using the specified charset.
     *
     * <p>Reads exactly length bytes and trims trailing nulls.
     *
     * @param length the number of bytes to read
     * @param charset the character set to use for decoding
     * @return the decoded string (trimmed of trailing nulls)
     * @throws IOException if an I/O error occurs
     */
    public @NotNull String readString(int length, @NotNull Charset charset) throws IOException {
        byte[] buffer = new byte[length];
        readFully(buffer);
        int end = length;
        while (end > 0 && buffer[end - 1] == 0) {
            end--;
        }
        return new String(buffer, 0, end, charset);
    }

    /**
     * Reads a fixed-length ASCII string.
     *
     * @param length the number of bytes to read
     * @return the decoded string
     * @throws IOException if an I/O error occurs
     */
    public @NotNull String readAsciiString(int length) throws IOException {
        return readString(length, StandardCharsets.US_ASCII);
    }

    /**
     * Skips the specified number of bytes.
     *
     * @param n the number of bytes to skip
     * @throws EOFException if end of stream is reached before all bytes are skipped
     * @throws IOException if an I/O error occurs
     */
    public void skip(long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // Fallback: read and discard
                int toRead = (int) Math.min(remaining, buffer8.length);
                int read = in.read(buffer8, 0, toRead);
                if (read < 0) {
                    throw new EOFException(String.format(
                            "Unexpected end of stream at position %d while skipping (needed %d more bytes)",
                            position, remaining));
                }
                skipped = read;
            }
            remaining -= skipped;
            position += skipped;
        }
    }

    /**
     * Reads bytes until a specific position is reached.
     *
     * <p>If already at or past the target position, does nothing.
     *
     * @param targetPosition the target position
     * @throws EOFException if end of stream is reached
     * @throws IOException if an I/O error occurs
     */
    public void skipTo(long targetPosition) throws IOException {
        if (targetPosition > position) {
            skip(targetPosition - position);
        }
    }

    /**
     * Creates a new BinaryReader with the opposite byte order.
     *
     * <p>This shares the same underlying stream, so reads affect both.
     *
     * @return a new reader with opposite byte order
     */
    public @NotNull BinaryReader withOppositeByteOrder() {
        ByteOrder opposite = (byteOrder == ByteOrder.BIG_ENDIAN)
                ? ByteOrder.LITTLE_ENDIAN
                : ByteOrder.BIG_ENDIAN;
        BinaryReader other = new BinaryReader(in, opposite);
        other.position = this.position;
        return other;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
