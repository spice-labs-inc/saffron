/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.io;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LruCache} (phase 6, T6.1/T6.2).
 *
 * <h2>LLM section</h2>
 * <p>Entry-count eviction, LRU order, byte-budget eviction (a single
 * heavy entry is evicted even under the count cap), and knob
 * validation.</p>
 */
class LruCacheTest {

    @Test
    void entryCountEvictionIsLru() {
        LruCache<Integer, String> cache = new LruCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        cache.get(1);          // 1 is now most recent
        cache.put(4, "d");     // evicts 2 (least recent)
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.containsKey(2)).isFalse();
        assertThat(cache.containsKey(1)).isTrue();
        assertThat(cache.containsKey(3)).isTrue();
        assertThat(cache.containsKey(4)).isTrue();
    }

    @Test
    void byteBudgetEvictsEvenBelowCountCap() {
        LruCache<Integer, String> cache = new LruCache<>(100, 10, String::length);
        cache.put(1, "abcdefghij"); // 10 bytes
        cache.put(2, "x");          // 11 > 10 -> evict entry 1
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.containsKey(1)).isFalse();
        assertThat(cache.containsKey(2)).isTrue();
        assertThat(cache.totalBytes()).isEqualTo(1);
    }

    @Test
    void replacementUpdatesByteAccounting() {
        LruCache<Integer, String> cache = new LruCache<>(10, 10, String::length);
        cache.put(1, "hello");   // 5
        cache.put(1, "world!");  // 6 (replace)
        assertThat(cache.totalBytes()).isEqualTo(6);
    }

    @Test
    void zeroOrNegativeKnobsRejected() {
        assertThatThrownBy(() -> new LruCache<Integer, String>(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LruCache<Integer, String>(10, 0, String::length))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearResetsAccounting() {
        LruCache<Integer, String> cache = new LruCache<>(10, 100, String::length);
        cache.put(1, "data");
        cache.clear();
        assertThat(cache.size()).isZero();
        assertThat(cache.totalBytes()).isZero();
    }
}
