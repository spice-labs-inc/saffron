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
package io.spicelabs.saffron.common;

import org.jetbrains.annotations.NotNull;

/**
 * Utility methods for byte array manipulation and endianness conversion.
 *
 * <p>VM disk formats use a mix of big-endian (QCOW2) and little-endian (VHD, VMDK)
 * byte ordering. This class provides safe, checked methods for both.
 */
public final class ByteUtils {

    private ByteUtils() {
        // Utility class - no instantiation
    }

    // ========================================================================
    // Big-endian readers (QCOW2, network byte order)
    // ========================================================================

    /**
     * Reads an unsigned 16-bit big-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the unsigned 16-bit value as an int
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static int readU16BE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 2);
        return ((bytes[offset] & 0xFF) << 8) |
               (bytes[offset + 1] & 0xFF);
    }

    /**
     * Reads an unsigned 32-bit big-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the unsigned 32-bit value as a long
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static long readU32BE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 4);
        return ((long) (bytes[offset] & 0xFF) << 24) |
               ((long) (bytes[offset + 1] & 0xFF) << 16) |
               ((long) (bytes[offset + 2] & 0xFF) << 8) |
               (bytes[offset + 3] & 0xFF);
    }

    /**
     * Reads a signed 32-bit big-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the signed 32-bit value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static int readI32BE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 4);
        return (bytes[offset] << 24) |
               ((bytes[offset + 1] & 0xFF) << 16) |
               ((bytes[offset + 2] & 0xFF) << 8) |
               (bytes[offset + 3] & 0xFF);
    }

    /**
     * Reads a 64-bit big-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the 64-bit value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static long readI64BE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 8);
        return ((long) bytes[offset] << 56) |
               ((long) (bytes[offset + 1] & 0xFF) << 48) |
               ((long) (bytes[offset + 2] & 0xFF) << 40) |
               ((long) (bytes[offset + 3] & 0xFF) << 32) |
               ((long) (bytes[offset + 4] & 0xFF) << 24) |
               ((long) (bytes[offset + 5] & 0xFF) << 16) |
               ((long) (bytes[offset + 6] & 0xFF) << 8) |
               (bytes[offset + 7] & 0xFF);
    }

    // ========================================================================
    // Little-endian readers (VHD, VMDK, VDI, VHDX)
    // ========================================================================

    /**
     * Reads an unsigned 16-bit little-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the unsigned 16-bit value as an int
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static int readU16LE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 2);
        return (bytes[offset] & 0xFF) |
               ((bytes[offset + 1] & 0xFF) << 8);
    }

    /**
     * Reads an unsigned 32-bit little-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the unsigned 32-bit value as a long
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static long readU32LE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 4);
        return (bytes[offset] & 0xFFL) |
               ((bytes[offset + 1] & 0xFFL) << 8) |
               ((bytes[offset + 2] & 0xFFL) << 16) |
               ((bytes[offset + 3] & 0xFFL) << 24);
    }

    /**
     * Reads a signed 32-bit little-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the signed 32-bit value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static int readI32LE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 4);
        return (bytes[offset] & 0xFF) |
               ((bytes[offset + 1] & 0xFF) << 8) |
               ((bytes[offset + 2] & 0xFF) << 16) |
               (bytes[offset + 3] << 24);
    }

    /**
     * Reads a 64-bit little-endian integer.
     *
     * @param bytes the byte array
     * @param offset the offset to read from
     * @return the 64-bit value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public static long readI64LE(byte[] bytes, int offset) {
        checkBounds(bytes, offset, 8);
        return (bytes[offset] & 0xFFL) |
               ((bytes[offset + 1] & 0xFFL) << 8) |
               ((bytes[offset + 2] & 0xFFL) << 16) |
               ((bytes[offset + 3] & 0xFFL) << 24) |
               ((bytes[offset + 4] & 0xFFL) << 32) |
               ((bytes[offset + 5] & 0xFFL) << 40) |
               ((bytes[offset + 6] & 0xFFL) << 48) |
               ((long) bytes[offset + 7] << 56);
    }

    // ========================================================================
    // Checked arithmetic (overflow protection)
    // ========================================================================

    /**
     * Adds two longs with overflow checking.
     *
     * @param a first operand
     * @param b second operand
     * @return the sum
     * @throws ArithmeticException if overflow occurs
     */
    public static long addExact(long a, long b) {
        return Math.addExact(a, b);
    }

    /**
     * Multiplies two longs with overflow checking.
     *
     * @param a first operand
     * @param b second operand
     * @return the product
     * @throws ArithmeticException if overflow occurs
     */
    public static long multiplyExact(long a, long b) {
        return Math.multiplyExact(a, b);
    }

    /**
     * Converts a long to an int with range checking.
     *
     * @param value the long value
     * @return the int value
     * @throws ArithmeticException if value doesn't fit in an int
     */
    public static int toIntExact(long value) {
        return Math.toIntExact(value);
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Formats bytes as a hex string.
     *
     * @param bytes the byte array
     * @return hex string representation
     */
    public static @NotNull String toHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Formats bytes as a hex string with a prefix.
     *
     * @param bytes the byte array
     * @param maxBytes maximum bytes to include
     * @return hex string representation
     */
    public static @NotNull String toHexString(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int len = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(len * 2 + 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        if (bytes.length > maxBytes) {
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Checks if two byte arrays are equal.
     *
     * @param a first array
     * @param aOffset offset in first array
     * @param b second array
     * @param bOffset offset in second array
     * @param length number of bytes to compare
     * @return true if the ranges are equal
     */
    public static boolean equals(byte[] a, int aOffset, byte[] b, int bOffset, int length) {
        if (a == null || b == null) {
            return a == b;
        }
        if (aOffset + length > a.length || bOffset + length > b.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (a[aOffset + i] != b[bOffset + i]) {
                return false;
            }
        }
        return true;
    }

    private static void checkBounds(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            throw new NullPointerException("bytes array is null");
        }
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException(
                    String.format("offset=%d, length=%d, array length=%d",
                            offset, length, bytes.length));
        }
    }
}
