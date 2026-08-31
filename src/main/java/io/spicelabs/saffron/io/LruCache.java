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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small synchronized LRU cache with optional per-entry byte accounting.
 *
 * <p>Entries are evicted least-recently-used first; when a
 * {@code maxBytes} budget is set, entries are additionally evicted by
 * aggregate weight (LRU order) until the budget holds. Thread-safe
 * (uncontended cost is one monitor acquire).</p>
 */
public final class LruCache<K, V> {

    /** Weighs a cached value in bytes (byte-budget accounting). */
    @FunctionalInterface
    public interface Weigher<V> {
        long weight(V value);
    }

    private final int maxEntries;
    private final long maxBytes;
    private final Weigher<V> weigher;
    private final Map<K, V> map;
    private long totalBytes;

    public LruCache(int maxEntries) {
        this(maxEntries, Long.MAX_VALUE, null);
    }

    public LruCache(int maxEntries, long maxBytes, Weigher<V> weigher) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1: " + maxEntries);
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be >= 1: " + maxBytes);
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.weigher = weigher;
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        V previous = map.put(key, value);
        if (previous != null && weigher != null) {
            totalBytes -= weigher.weight(previous);
        }
        if (weigher != null) {
            totalBytes += weigher.weight(value);
        }
        evict();
    }

    public synchronized boolean containsKey(K key) {
        return map.containsKey(key);
    }

    /** Current entry count (test/observation seam). */
    public synchronized int size() {
        return map.size();
    }

    /** Current aggregate weight in bytes (test/observation seam). */
    public synchronized long totalBytes() {
        return totalBytes;
    }

    public synchronized void clear() {
        map.clear();
        totalBytes = 0;
    }

    private void evict() {
        while (map.size() > maxEntries || (weigher != null && totalBytes > maxBytes)) {
            var it = map.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            Map.Entry<K, V> eldest = it.next();
            it.remove();
            if (weigher != null) {
                totalBytes -= weigher.weight(eldest.getValue());
            }
        }
    }
}
