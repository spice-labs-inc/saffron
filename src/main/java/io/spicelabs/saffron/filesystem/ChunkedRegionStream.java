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
package io.spicelabs.saffron.filesystem;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A lazy, bounded stream over an ordered list of contiguous data runs.
 *
 * <p>Filesystem drivers build a list of {@link Segment}s (physical offset
 * + length, in file order) from their extent/run/cluster metadata; this
 * stream serves the file by reading at most {@value #WINDOW} bytes per
 * underlying {@code DiskRegion.read}. Gaps between segments (sparse
 * holes) are served as zeros WITHOUT touching the region.</p>
 *
 * <h2>LLM section</h2>
 * <p>This is the phase-5 memory-budget enforcement point: no single read
 * over 1 MiB, sparse regions never read, streams single-threaded per
 * instance. Segment metadata is capped at {@value #MAX_SEGMENTS}.</p>
 */
public final class ChunkedRegionStream extends InputStream {

    /** Window size per underlying read (memory budget: ≤ 1 MiB). */
    public static final int WINDOW = 256 * 1024;

    /** Sanity cap on segment metadata (hostile run lists). */
    public static final int MAX_SEGMENTS = 1_000_000;

    /**
     * One contiguous run of data: logical position in the file, physical
     * offset in the region, and length. Gaps between segments (sparse
     * holes) are served as zeros without touching the region.
     */
    public record Segment(long logicalStart, long offset, long length) {
        public Segment {
            if (logicalStart < 0 || offset < 0 || length < 0) {
                throw new IllegalArgumentException("Invalid segment: logicalStart="
                        + logicalStart + ", offset=" + offset + ", length=" + length);
            }
        }
    }

    private final DiskRegion region;
    private final List<Segment> segments;
    private final long size;
    private long pos;
    private int segmentIndex;
    private final byte[] window = new byte[WINDOW];
    private int windowPos;
    private int windowLen;

    public ChunkedRegionStream(@NotNull DiskRegion region, @NotNull List<Segment> segments,
                               long size) throws IOException {
        if (segments.size() > MAX_SEGMENTS) {
            throw new IOException("Too many stream segments: " + segments.size());
        }
        this.region = region;
        this.segments = List.copyOf(segments);
        this.size = size;
        this.pos = 0;
        this.segmentIndex = 0;
    }

    /** True when the stream has served all {@code size} bytes. */
    public boolean atEnd() {
        return pos >= size;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte @NotNull [] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (pos >= size) {
            return -1;
        }

        int total = 0;
        while (total < len && pos < size) {
            if (windowPos >= windowLen) {
                fillWindow();
            }
            int n = Math.min(Math.min(len - total, windowLen - windowPos),
                    (int) Math.min(Integer.MAX_VALUE, size - pos));
            System.arraycopy(window, windowPos, buf, off + total, n);
            windowPos += n;
            pos += n;
            total += n;
        }
        return total;
    }

    /**
     * Loads the next window: either the current segment's data (bounded
     * read) or zeros for a sparse gap.
     */
    private void fillWindow() throws IOException {
        // Advance to the segment containing pos (or past all segments).
        while (segmentIndex < segments.size()) {
            Segment seg = segments.get(segmentIndex);
            if (pos < seg.logicalStart()) {
                // Sparse gap before this segment: zeros without reading.
                int toFill = (int) Math.min(Math.min(WINDOW, seg.logicalStart() - pos),
                        Math.min(Integer.MAX_VALUE, size - pos));
                java.util.Arrays.fill(window, 0, toFill, (byte) 0);
                windowLen = toFill;
                windowPos = 0;
                return;
            }
            long segEnd = seg.logicalStart() + seg.length();
            if (pos < segEnd) {
                long within = pos - seg.logicalStart();
                long available = segEnd - pos;
                int toRead = (int) Math.min(Math.min(WINDOW, available),
                        Math.min(Integer.MAX_VALUE, size - pos));
                ByteBuffer data = region.read(seg.offset() + within, toRead);
                data.get(window, 0, toRead);
                windowLen = toRead;
                windowPos = 0;
                return;
            }
            segmentIndex++;
        }
        // Sparse gap after the last segment: zeros without touching the region.
        int toFill = (int) Math.min(Math.min(WINDOW, size - pos), Integer.MAX_VALUE);
        java.util.Arrays.fill(window, 0, toFill, (byte) 0);
        windowLen = toFill;
        windowPos = 0;
    }
}
