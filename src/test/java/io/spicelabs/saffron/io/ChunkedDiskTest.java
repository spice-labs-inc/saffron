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

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.github.packageurl.PackageURL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ChunkedDisk} never issues a single read larger than
 * {@value ChunkedDisk#CHUNK_SIZE} bytes and keeps resident memory bounded.
 *
 * <h2>LLM section</h2>
 * <p>Each test below documents which requirement it enforces. The requirement
 * under test everywhere is the bounded-read guarantee: chunk size 256 KiB,
 * at most 4 resident chunks, out-of-bounds guarded. These tests are the
 * boundary tests for the chunk logic (offsets exactly at chunk edges).</p>
 */
class ChunkedDiskTest {

    /** A single chunk worth of bytes, patterned so offsets are verifiable. */
    private static final int SIZE = 3 * ChunkedDisk.CHUNK_SIZE + 1234;

    /** Records every read against the underlying disk. */
    static final class RecordingRawDisk implements VirtualDisk.RawDisk {
        final byte[] content;
        long maxRead;
        long readCount;

        RecordingRawDisk(byte[] content) {
            this.content = content.clone();
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            maxRead = Math.max(maxRead, length);
            readCount++;
            if (offset < 0 || offset >= content.length) {
                return ByteBuffer.allocate(length);
            }
            int available = (int) Math.min(length, content.length - offset);
            byte[] result = new byte[length];
            System.arraycopy(content, (int) offset, result, 0, available);
            return ByteBuffer.wrap(result);
        }

        @Override
        public long virtualSize() {
            return content.length;
        }

        @Override
        public Optional<String> backingFile() {
            return Optional.empty();
        }

        @Override
        public long allocatedSize() {
            return content.length;
        }

        @Override
        public boolean isEncrypted() {
            return false;
        }

        @Override
        public boolean isCompressed() {
            return false;
        }

        @Override
        public DiskFormat format() {
            return DiskFormat.RAW;
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of();
        }

        @Override
        public Stream<VirtualDisk.Snapshot> snapshots() {
            return Stream.empty();
        }

        @Override
        public int sectorSize() {
            return 512;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public PackageURL packageUrl() {
            try {
                return new PackageURL("pkg:vmdisk/raw/test@1.0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private static byte[] patternedContent() {
        byte[] content = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            content[i] = (byte) (i * 31 + 7);
        }
        return content;
    }

    private static ChunkedDisk chunked(RecordingRawDisk disk) {
        return new ChunkedDisk(disk);
    }

    /**
     * Requirement: get() returns correct bytes at offsets before, at, and
     * after chunk boundaries. This catches off-by-one errors in the
     * offset-to-chunk mapping.
     */
    @Test
    void getIsCorrectAcrossChunkBoundaries() throws IOException {
        byte[] content = patternedContent();
        RecordingRawDisk disk = new RecordingRawDisk(content);
        ChunkedDisk chunked = chunked(disk);

        long[] offsets = {
                0, 1, ChunkedDisk.CHUNK_SIZE - 1, ChunkedDisk.CHUNK_SIZE,
                ChunkedDisk.CHUNK_SIZE + 1, 2L * ChunkedDisk.CHUNK_SIZE - 1,
                2L * ChunkedDisk.CHUNK_SIZE, 2L * ChunkedDisk.CHUNK_SIZE + 5,
                SIZE - 1
        };
        for (long offset : offsets) {
            assertEquals(content[(int) offset] & 0xff, chunked.get(offset),
                    "byte at offset " + offset);
        }
    }

    /**
     * Requirement: multi-byte reads crossing a chunk boundary must still
     * return the correct bytes (assembled across chunks), because real
     * artifacts place multi-byte fields at arbitrary offsets.
     */
    @Test
    void multiByteReadAtChunkBoundaryIsCorrect() throws IOException {
        byte[] content = patternedContent();
        RecordingRawDisk disk = new RecordingRawDisk(content);
        ChunkedDisk chunked = chunked(disk);

        long boundary = ChunkedDisk.CHUNK_SIZE;
        // 4- and 8-byte values straddling the boundary assemble correctly.
        int straddle4 = chunked.getInt(boundary - 2, ByteOrder.BIG_ENDIAN);
        ByteBuffer expected4 = ByteBuffer.wrap(content).order(ByteOrder.BIG_ENDIAN);
        assertEquals(expected4.getInt((int) boundary - 2), straddle4);

        long straddle8 = chunked.getLong(boundary - 4, ByteOrder.LITTLE_ENDIAN);
        ByteBuffer expected8 = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(expected8.getLong((int) boundary - 4), straddle8);

        // Values fully inside a chunk work, including right at the boundary.
        int before = chunked.getInt(boundary - 4, ByteOrder.BIG_ENDIAN);
        ByteBuffer expected = ByteBuffer.wrap(content).order(ByteOrder.BIG_ENDIAN);
        assertEquals(expected.getInt((int) boundary - 4), before);

        // Reads straddling the start of the final (partial) chunk work too.
        long nearEnd = chunked.getInt(3L * ChunkedDisk.CHUNK_SIZE - 2, ByteOrder.BIG_ENDIAN);
        assertEquals(expected4.getInt(3 * ChunkedDisk.CHUNK_SIZE - 2), nearEnd);
    }

    /**
     * Requirement: endianness is honored by getInt/getLong on the chunked
     * path, matching ByteBuffer semantics of the in-memory path.
     */
    @Test
    void getIntHonorsByteOrder() throws IOException {
        byte[] content = new byte[ChunkedDisk.CHUNK_SIZE + 16];
        ByteBuffer put = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        put.putInt(ChunkedDisk.CHUNK_SIZE - 4, 0x7856_3412);
        put.putLong(ChunkedDisk.CHUNK_SIZE + 8, 0x0123_4567_89ab_cdefL);
        RecordingRawDisk disk = new RecordingRawDisk(content);
        ChunkedDisk chunked = chunked(disk);

        assertEquals(0x7856_3412, chunked.getInt(ChunkedDisk.CHUNK_SIZE - 4, ByteOrder.LITTLE_ENDIAN));
        assertEquals(0x1234_5678, chunked.getInt(ChunkedDisk.CHUNK_SIZE - 4, ByteOrder.BIG_ENDIAN));
        assertEquals(0x0123_4567_89ab_cdefL,
                chunked.getLong(ChunkedDisk.CHUNK_SIZE + 8, ByteOrder.LITTLE_ENDIAN));
    }

    /**
     * Requirement: copyRange produces the exact source bytes across spans of
     * multiple chunks, and the underlying reads are each bounded by the chunk
     * size.
     */
    @Test
    void copyRangeSpansChunksAndReadsAreBounded() throws IOException {
        byte[] content = patternedContent();
        RecordingRawDisk disk = new RecordingRawDisk(content);
        ChunkedDisk chunked = chunked(disk);

        int start = ChunkedDisk.CHUNK_SIZE / 2;
        int length = 2 * ChunkedDisk.CHUNK_SIZE + 500;
        byte[] copied = chunked.copyRange(start, length);

        assertTrue(Arrays.equals(
                Arrays.copyOfRange(content, start, start + length), copied));
        assertTrue(disk.maxRead <= ChunkedDisk.CHUNK_SIZE,
                "max single read was " + disk.maxRead);
    }

    /**
     * Requirement: streams over a range return identical bytes and issue only
     * bounded reads.
     */
    @Test
    void streamReturnsExactBytesWithBoundedReads() throws IOException {
        byte[] content = patternedContent();
        RecordingRawDisk disk = new RecordingRawDisk(content);
        ChunkedDisk chunked = chunked(disk);

        int start = 100;
        long length = SIZE - 200L;
        ByteArrayInputStream expected = new ByteArrayInputStream(content, start, (int) length);
        try (InputStream in = chunked.stream(start, length)) {
            byte[] actual = in.readAllBytes();
            assertTrue(Arrays.equals(expected.readAllBytes(), actual));
        }
        assertTrue(disk.maxRead <= ChunkedDisk.CHUNK_SIZE,
                "max single read was " + disk.maxRead);
    }

    /**
     * Requirement: at most {@value ChunkedDisk#MAX_RESIDENT_CHUNKS} chunks
     * stay resident regardless of access pattern (LRU eviction).
     */
    @Test
    void residentChunkCountIsBounded() throws IOException {
        byte[] content = patternedContent();
        RecordingRawDisk disk = new RecordingRawDisk(content);
        ChunkedDisk chunked = chunked(disk);

        for (long offset = 0; offset < SIZE; offset += ChunkedDisk.CHUNK_SIZE / 2) {
            chunked.get(offset);
        }
        assertTrue(chunked.residentChunkCount() <= ChunkedDisk.MAX_RESIDENT_CHUNKS,
                "resident chunks: " + chunked.residentChunkCount());
    }

    /**
     * Requirement: out-of-range access fails fast with
     * IndexOutOfBoundsException instead of silently returning garbage.
     */
    @Test
    void outOfBoundsAccessIsRejected() {
        byte[] content = patternedContent();
        RecordingRawDisk disk = new RecordingRawDisk(content);
        ChunkedDisk chunked = chunked(disk);

        assertThrows(IndexOutOfBoundsException.class, () -> chunked.get(SIZE));
        assertThrows(IndexOutOfBoundsException.class, () -> chunked.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> chunked.getInt(SIZE - 2, ByteOrder.BIG_ENDIAN));
        assertThrows(IndexOutOfBoundsException.class,
                () -> chunked.copyRange(SIZE - 10, new byte[20], 0, 20));
        assertThrows(IndexOutOfBoundsException.class, () -> chunked.stream(SIZE - 10, 20));
        assertThrows(IndexOutOfBoundsException.class, () -> chunked.stream(0, SIZE + 1));
    }
}
