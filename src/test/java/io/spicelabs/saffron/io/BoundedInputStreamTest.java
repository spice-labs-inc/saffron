/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link BoundedInputStream}.
 */
class BoundedInputStreamTest {

    @Test
    void read_withinLimit_succeeds() throws IOException {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 100);

        assertThatCode(() -> bis.readAllBytes()).doesNotThrowAnyException();
    }

    @Test
    void read_exceedsLimit_throwsIOException() {
        byte[] data = new byte[200];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 100);

        assertThatThrownBy(bis::readAllBytes)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limit exceeded");
    }

    @Test
    void read_exactlyAtLimit_succeeds() throws IOException {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 100);
        byte[] result = bis.readAllBytes();

        assertThat(result).hasSize(100);
    }

    @Test
    void getBytesRead_tracksCorrectly() throws IOException {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 200);

        assertThat(bis.getBytesRead()).isEqualTo(0);

        byte[] buf = new byte[30];
        bis.read(buf);
        assertThat(bis.getBytesRead()).isEqualTo(30);

        bis.read(buf);
        assertThat(bis.getBytesRead()).isEqualTo(60);
    }

    @Test
    void getRemaining_decreasesAsReads() throws IOException {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 100);

        assertThat(bis.getRemaining()).isEqualTo(100);

        byte[] buf = new byte[30];
        bis.read(buf);
        assertThat(bis.getRemaining()).isEqualTo(70);
    }

    @Test
    void getLimit_returnsConfiguredLimit() {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 50);

        assertThat(bis.getLimit()).isEqualTo(50);
    }

    @Test
    void skip_countsTowardLimit() throws IOException {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 50);

        bis.skip(30);
        assertThat(bis.getBytesRead()).isEqualTo(30);
        assertThat(bis.getRemaining()).isEqualTo(20);
    }

    @Test
    void skip_beyondLimit_throwsIOException() {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 50);

        // First skip 40 bytes
        assertThatCode(() -> bis.skip(40)).doesNotThrowAnyException();

        // Then try to skip 20 more (would exceed limit)
        assertThatThrownBy(() -> bis.skip(20))
                .isInstanceOf(IOException.class);
    }

    @Test
    void available_respectsLimit() throws IOException {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 50);

        // Available should be limited by our bound
        assertThat(bis.available()).isLessThanOrEqualTo(50);
    }

    @Test
    void isLimitExceeded_falseBeforeExceeding() throws IOException {
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 100);

        bis.read(new byte[50]);
        assertThat(bis.isLimitExceeded()).isFalse();
    }

    @Test
    void isLimitExceeded_trueAfterExceeding() {
        byte[] data = new byte[200];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 100);

        try {
            bis.readAllBytes();
        } catch (IOException e) {
            // Expected
        }

        assertThat(bis.isLimitExceeded()).isTrue();
    }

    @Test
    void constructor_negativeLimit_throwsException() {
        byte[] data = new byte[100];
        assertThatThrownBy(() -> new BoundedInputStream(new ByteArrayInputStream(data), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_zeroLimit_succeeds() {
        byte[] data = new byte[100];
        assertThatCode(() -> new BoundedInputStream(new ByteArrayInputStream(data), 0))
                .doesNotThrowAnyException();
    }

    @Test
    void readSingleByte_exceedsLimit_throwsIOException() {
        byte[] data = new byte[10];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 5);

        // Read 5 bytes (at limit)
        for (int i = 0; i < 5; i++) {
            try {
                bis.read();
            } catch (IOException e) {
                fail("Should not throw before limit");
            }
        }

        // 6th read should fail
        assertThatThrownBy(bis::read)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limit exceeded");
    }

    // Anti-fake test
    @Test
    void boundedInputStream_actuallyCountsBytes() throws IOException {
        // Create stream with exactly 50 bytes limit
        byte[] data = new byte[100];
        BoundedInputStream bis = new BoundedInputStream(new ByteArrayInputStream(data), 50);

        // Read 30 bytes - should succeed
        byte[] first = new byte[30];
        bis.read(first);

        // Read 30 more - should fail (total would be 60 > 50)
        byte[] second = new byte[30];
        assertThatThrownBy(() -> bis.read(second))
                .isInstanceOf(IOException.class);
    }
}
