/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.io;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SafeMath}.
 */
class SafeMathTest {

    @Test
    void safeAdd_normalValues_returnsSum() {
        assertThat(SafeMath.safeAdd(100L, 200L)).isEqualTo(300L);
    }

    @Test
    void safeAdd_maxLongPlusOne_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeAdd(Long.MAX_VALUE, 1L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeAdd_minLongMinusOne_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeAdd(Long.MIN_VALUE, -1L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeMultiply_normalValues_returnsProduct() {
        assertThat(SafeMath.safeMultiply(100L, 200L)).isEqualTo(20000L);
    }

    @Test
    void safeMultiply_overflow_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeMultiply(Long.MAX_VALUE, 2L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeMultiply_largeValues_throwsArithmeticException() {
        // Two values that multiply to overflow
        long a = Long.MAX_VALUE / 2 + 1;
        long b = 3;
        assertThatThrownBy(() -> SafeMath.safeMultiply(a, b))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeToInt_withinRange_returnsInt() {
        assertThat(SafeMath.safeToInt(1000L)).isEqualTo(1000);
    }

    @Test
    void safeToInt_exceedsIntMax_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeToInt((long) Integer.MAX_VALUE + 1))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeToInt_belowIntMin_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeToInt((long) Integer.MIN_VALUE - 1))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeSubtract_normalValues_returnsDifference() {
        assertThat(SafeMath.safeSubtract(100L, 30L)).isEqualTo(70L);
    }

    @Test
    void safeSubtract_overflow_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeSubtract(Long.MIN_VALUE, 1L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeNegate_normalValue_returnsNegated() {
        assertThat(SafeMath.safeNegate(100L)).isEqualTo(-100L);
    }

    @Test
    void safeNegate_minValue_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeNegate(Long.MIN_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeIncrement_normalValue_returnsIncremented() {
        assertThat(SafeMath.safeIncrement(100L)).isEqualTo(101L);
    }

    @Test
    void safeIncrement_maxValue_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeIncrement(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeDecrement_normalValue_returnsDecremented() {
        assertThat(SafeMath.safeDecrement(100L)).isEqualTo(99L);
    }

    @Test
    void safeDecrement_minValue_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeDecrement(Long.MIN_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeDivide_normalValues_returnsQuotient() {
        assertThat(SafeMath.safeDivide(100L, 3L)).isEqualTo(33L);
    }

    @Test
    void safeDivide_byZero_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeDivide(100L, 0L))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void safeDivide_minValueByNegativeOne_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeDivide(Long.MIN_VALUE, -1L))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("overflow");
    }

    @Test
    void safeCeilDiv_normalValues_returnsCeiling() {
        assertThat(SafeMath.safeCeilDiv(10L, 3L)).isEqualTo(4L);
        assertThat(SafeMath.safeCeilDiv(9L, 3L)).isEqualTo(3L);
        assertThat(SafeMath.safeCeilDiv(0L, 5L)).isEqualTo(0L);
    }

    @Test
    void safeCeilDiv_negativeValues_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeCeilDiv(-1L, 3L))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> SafeMath.safeCeilDiv(10L, -3L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void alignUp_normalValue_alignsCorrectly() {
        assertThat(SafeMath.alignUp(100L, 64L)).isEqualTo(128L);
        assertThat(SafeMath.alignUp(128L, 64L)).isEqualTo(128L);
        assertThat(SafeMath.alignUp(0L, 64L)).isEqualTo(0L);
    }

    @Test
    void alignUp_notPowerOfTwo_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.alignUp(100L, 63L))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("power of 2");
    }

    @Test
    void alignDown_normalValue_alignsCorrectly() {
        assertThat(SafeMath.alignDown(100L, 64L)).isEqualTo(64L);
        assertThat(SafeMath.alignDown(128L, 64L)).isEqualTo(128L);
        assertThat(SafeMath.alignDown(63L, 64L)).isEqualTo(0L);
    }

    @Test
    void isAligned_returnsTrueForAlignedValues() {
        assertThat(SafeMath.isAligned(0L, 512L)).isTrue();
        assertThat(SafeMath.isAligned(512L, 512L)).isTrue();
        assertThat(SafeMath.isAligned(1024L, 512L)).isTrue();
    }

    @Test
    void isAligned_returnsFalseForUnalignedValues() {
        assertThat(SafeMath.isAligned(1L, 512L)).isFalse();
        assertThat(SafeMath.isAligned(513L, 512L)).isFalse();
        assertThat(SafeMath.isAligned(100L, 64L)).isFalse();
    }

    // Int overload tests
    @Test
    void safeAddInt_normalValues_returnsSum() {
        assertThat(SafeMath.safeAdd(100, 200)).isEqualTo(300);
    }

    @Test
    void safeAddInt_overflow_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeAdd(Integer.MAX_VALUE, 1))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void safeMultiplyInt_normalValues_returnsProduct() {
        assertThat(SafeMath.safeMultiply(100, 200)).isEqualTo(20000);
    }

    @Test
    void safeMultiplyInt_overflow_throwsArithmeticException() {
        assertThatThrownBy(() -> SafeMath.safeMultiply(Integer.MAX_VALUE, 2))
                .isInstanceOf(ArithmeticException.class);
    }
}
