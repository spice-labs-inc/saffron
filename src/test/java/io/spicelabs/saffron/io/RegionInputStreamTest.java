/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RegionInputStream}.
 */
class RegionInputStreamTest {

    @Test
    void readsOnlyRegionLength() throws IOException {
        InputStream source = new ByteArrayInputStream("abcdefghijklmnopqrstuvwxyz".getBytes());
        try (InputStream region = new RegionInputStream(source, 5)) {
            assertThat(region.readAllBytes()).isEqualTo("abcde".getBytes());
        }
    }

    @Test
    void returnsMinusOneWhenRegionExhausted() throws IOException {
        InputStream source = new ByteArrayInputStream("abcdefghijklmnopqrstuvwxyz".getBytes());
        try (InputStream region = new RegionInputStream(source, 3)) {
            assertThat(region.readAllBytes()).isEqualTo("abc".getBytes());
            assertThat(region.read()).isEqualTo(-1);
        }
    }

    @Test
    void skipStopsAtRegionBoundary() throws IOException {
        InputStream source = new ByteArrayInputStream("abcdefghijklmnopqrstuvwxyz".getBytes());
        try (InputStream region = new RegionInputStream(source, 5)) {
            assertThat(region.skip(10)).isEqualTo(5);
            assertThat(region.read()).isEqualTo(-1);
        }
    }

    @Test
    void availableIsBoundedByRegion() throws IOException {
        InputStream source = new ByteArrayInputStream("abcdefghijklmnopqrstuvwxyz".getBytes());
        try (InputStream region = new RegionInputStream(source, 5)) {
            assertThat(region.available()).isEqualTo(5);
            region.read();
            assertThat(region.available()).isEqualTo(4);
        }
    }

    @Test
    void rejectsNegativeLength() {
        assertThatThrownBy(() -> new RegionInputStream(new ByteArrayInputStream(new byte[0]), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readAllBytesDoesNotThrowWhenSourceHasMoreData() throws IOException {
        InputStream source = new ByteArrayInputStream("abcdefghijklmnopqrstuvwxyz".getBytes());
        try (InputStream region = new RegionInputStream(source, 5)) {
            // This is the key difference from BoundedInputStream: readAllBytes on a region
            // in the middle of a larger source should return exactly the region bytes.
            assertThat(region.readAllBytes()).isEqualTo("abcde".getBytes());
        }
    }
}
