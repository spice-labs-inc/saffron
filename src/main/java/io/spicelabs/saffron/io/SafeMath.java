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
package io.spicelabs.saffron.io;

/**
 * Overflow-safe arithmetic operations for VM disk format processing.
 *
 * <p>VM disk formats frequently use 64-bit offsets and sizes that can easily
 * overflow when multiplied or added. This class provides checked arithmetic
 * that throws {@link ArithmeticException} on overflow instead of silently
 * wrapping around.
 *
 * <p>Example usage:
 * <pre>{@code
 * long clusterOffset = SafeMath.safeMultiply(clusterIndex, clusterSize);
 * long totalSize = SafeMath.safeAdd(headerSize, dataSize);
 * int arraySize = SafeMath.safeToInt(entryCount);
 * }</pre>
 */
public final class SafeMath {

    private SafeMath() {
        // Utility class - no instantiation
    }

    /**
     * Adds two long values with overflow checking.
     *
     * @param a first operand
     * @param b second operand
     * @return the sum
     * @throws ArithmeticException if the result overflows a long
     */
    public static long safeAdd(long a, long b) {
        return Math.addExact(a, b);
    }

    /**
     * Adds two int values with overflow checking.
     *
     * @param a first operand
     * @param b second operand
     * @return the sum
     * @throws ArithmeticException if the result overflows an int
     */
    public static int safeAdd(int a, int b) {
        return Math.addExact(a, b);
    }

    /**
     * Subtracts two long values with overflow checking.
     *
     * @param a first operand (minuend)
     * @param b second operand (subtrahend)
     * @return the difference (a - b)
     * @throws ArithmeticException if the result overflows a long
     */
    public static long safeSubtract(long a, long b) {
        return Math.subtractExact(a, b);
    }

    /**
     * Subtracts two int values with overflow checking.
     *
     * @param a first operand (minuend)
     * @param b second operand (subtrahend)
     * @return the difference (a - b)
     * @throws ArithmeticException if the result overflows an int
     */
    public static int safeSubtract(int a, int b) {
        return Math.subtractExact(a, b);
    }

    /**
     * Multiplies two long values with overflow checking.
     *
     * @param a first operand
     * @param b second operand
     * @return the product
     * @throws ArithmeticException if the result overflows a long
     */
    public static long safeMultiply(long a, long b) {
        return Math.multiplyExact(a, b);
    }

    /**
     * Multiplies two int values with overflow checking.
     *
     * @param a first operand
     * @param b second operand
     * @return the product
     * @throws ArithmeticException if the result overflows an int
     */
    public static int safeMultiply(int a, int b) {
        return Math.multiplyExact(a, b);
    }

    /**
     * Converts a long to an int with range checking.
     *
     * @param value the long value to convert
     * @return the int value
     * @throws ArithmeticException if the value doesn't fit in an int
     */
    public static int safeToInt(long value) {
        return Math.toIntExact(value);
    }

    /**
     * Negates an int value with overflow checking.
     *
     * @param a the value to negate
     * @return the negated value
     * @throws ArithmeticException if the result overflows (Integer.MIN_VALUE)
     */
    public static int safeNegate(int a) {
        return Math.negateExact(a);
    }

    /**
     * Negates a long value with overflow checking.
     *
     * @param a the value to negate
     * @return the negated value
     * @throws ArithmeticException if the result overflows (Long.MIN_VALUE)
     */
    public static long safeNegate(long a) {
        return Math.negateExact(a);
    }

    /**
     * Increments a long value with overflow checking.
     *
     * @param a the value to increment
     * @return a + 1
     * @throws ArithmeticException if the result overflows
     */
    public static long safeIncrement(long a) {
        return Math.incrementExact(a);
    }

    /**
     * Increments an int value with overflow checking.
     *
     * @param a the value to increment
     * @return a + 1
     * @throws ArithmeticException if the result overflows
     */
    public static int safeIncrement(int a) {
        return Math.incrementExact(a);
    }

    /**
     * Decrements a long value with overflow checking.
     *
     * @param a the value to decrement
     * @return a - 1
     * @throws ArithmeticException if the result overflows
     */
    public static long safeDecrement(long a) {
        return Math.decrementExact(a);
    }

    /**
     * Decrements an int value with overflow checking.
     *
     * @param a the value to decrement
     * @return a - 1
     * @throws ArithmeticException if the result overflows
     */
    public static int safeDecrement(int a) {
        return Math.decrementExact(a);
    }

    /**
     * Divides a by b, checking for division by zero and overflow.
     *
     * @param a dividend
     * @param b divisor
     * @return the quotient
     * @throws ArithmeticException if b is zero or if overflow occurs (Long.MIN_VALUE / -1)
     */
    public static long safeDivide(long a, long b) {
        if (b == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (a == Long.MIN_VALUE && b == -1) {
            throw new ArithmeticException("Long overflow");
        }
        return a / b;
    }

    /**
     * Divides a by b, checking for division by zero and overflow.
     *
     * @param a dividend
     * @param b divisor
     * @return the quotient
     * @throws ArithmeticException if b is zero or if overflow occurs (Integer.MIN_VALUE / -1)
     */
    public static int safeDivide(int a, int b) {
        if (b == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (a == Integer.MIN_VALUE && b == -1) {
            throw new ArithmeticException("Integer overflow");
        }
        return a / b;
    }

    /**
     * Computes the ceiling of a/b for positive numbers.
     *
     * <p>This is commonly needed for calculating how many clusters/blocks
     * are needed to store a given amount of data.
     *
     * @param dividend the numerator (must be non-negative)
     * @param divisor the denominator (must be positive)
     * @return ceiling(dividend / divisor)
     * @throws ArithmeticException if divisor is zero, dividend is negative,
     *         or if the result overflows
     */
    public static long safeCeilDiv(long dividend, long divisor) {
        if (divisor <= 0) {
            throw new ArithmeticException("Divisor must be positive");
        }
        if (dividend < 0) {
            throw new ArithmeticException("Dividend must be non-negative");
        }
        if (dividend == 0) {
            return 0;
        }
        // (dividend + divisor - 1) / divisor with overflow checking
        long sum = safeAdd(dividend, divisor - 1);
        return sum / divisor;
    }

    /**
     * Aligns a value up to the next multiple of alignment.
     *
     * <p>This is commonly needed for sector/block alignment in disk formats.
     *
     * @param value the value to align (must be non-negative)
     * @param alignment the alignment boundary (must be a power of 2)
     * @return the aligned value
     * @throws ArithmeticException if alignment is not a power of 2,
     *         value is negative, or result overflows
     */
    public static long alignUp(long value, long alignment) {
        if (value < 0) {
            throw new ArithmeticException("Value must be non-negative");
        }
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new ArithmeticException("Alignment must be a positive power of 2");
        }
        long mask = alignment - 1;
        if ((value & mask) == 0) {
            return value;
        }
        // (value & ~mask) + alignment
        long masked = value & ~mask;
        return safeAdd(masked, alignment);
    }

    /**
     * Aligns a value down to the previous multiple of alignment.
     *
     * @param value the value to align (must be non-negative)
     * @param alignment the alignment boundary (must be a power of 2)
     * @return the aligned value
     * @throws ArithmeticException if alignment is not a power of 2 or value is negative
     */
    public static long alignDown(long value, long alignment) {
        if (value < 0) {
            throw new ArithmeticException("Value must be non-negative");
        }
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new ArithmeticException("Alignment must be a positive power of 2");
        }
        return value & ~(alignment - 1);
    }

    /**
     * Checks if a value is aligned to the given boundary.
     *
     * @param value the value to check
     * @param alignment the alignment boundary (must be a power of 2)
     * @return true if value is aligned
     * @throws ArithmeticException if alignment is not a power of 2
     */
    public static boolean isAligned(long value, long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new ArithmeticException("Alignment must be a positive power of 2");
        }
        return (value & (alignment - 1)) == 0;
    }
}
