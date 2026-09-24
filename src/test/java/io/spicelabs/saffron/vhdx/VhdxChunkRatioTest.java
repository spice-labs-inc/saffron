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
package io.spicelabs.saffron.vhdx;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.diskharness.DiskFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The VHDX BAT interleaves one sector-bitmap entry after every
 * {@code chunkRatio = 2^23 * logicalSectorSize / blockSize} payload entries
 * (spec section 2.5). With 512-byte sectors that is one extra entry every
 * 4 GiB of virtual disk, so a reader that indexes the BAT by block number
 * alone returns the wrong data for every block past the first 4 GiB.
 */
class VhdxChunkRatioTest {

    private static final int MIB = 1024 * 1024;
    private static final long GIB = 1024L * MIB;
    private static final Path QEMU_FIXTURE =
            Path.of("src/test/resources/vhdx/fixtures/chunk-ratio-512.vhdx");

    @Test
    void chunkRatio_matchesSpecFormula() {
        assertThat(VhdxDiskImpl.chunkRatio(512, MIB)).isEqualTo(4096);
        assertThat(VhdxDiskImpl.chunkRatio(512, 32 * MIB)).isEqualTo(128);
        assertThat(VhdxDiskImpl.chunkRatio(512, 256 * MIB)).isEqualTo(16);
        assertThat(VhdxDiskImpl.chunkRatio(4096, MIB)).isEqualTo(32768);
        assertThat(VhdxDiskImpl.chunkRatio(4096, 256 * MIB)).isEqualTo(128);
    }

    @Test
    void dynamic512_blocksAcrossFirstChunkBoundary_readCorrectly(@TempDir Path dir) throws IOException {
        int blockSize = MIB;
        long virtualSize = 5 * GIB;
        var allocated = new TreeMap<Long, Integer>();
        allocated.put(0L, 11);
        allocated.put(4095L, 12);   // last block of chunk 0
        allocated.put(4096L, 13);   // first block of chunk 1
        allocated.put(4097L, 14);
        Path file = dir.resolve("five-gib.vhdx");
        DiskFixtures.vhdxToFile(file, virtualSize, blockSize, 512, false, allocated);
        assertThat(Files.size(file)).isLessThan(8L * MIB);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertThat(disk.virtualSize()).isEqualTo(virtualSize);
            assertThat(disk.metadata()).containsEntry("vhdx.chunkRatio", "4096");
            for (var e : allocated.entrySet()) {
                assertBlockHasPattern(disk, e.getKey(), blockSize, e.getValue());
            }
            assertBlockIsZero(disk, 4094L, blockSize);
            assertBlockIsZero(disk, 4098L, blockSize);
            assertBlockIsZero(disk, 5L * 1024 - 1, blockSize);

            // A read straddling the chunk boundary
            ByteBuffer straddle = disk.read(4096L * blockSize - 16, 32);
            for (int i = 0; i < 16; i++) {
                assertThat(straddle.get(i)).isEqualTo(DiskFixtures.pattern(12L * blockSize + blockSize - 16 + i));
            }
            for (int i = 16; i < 32; i++) {
                assertThat(straddle.get(i)).isEqualTo(DiskFixtures.pattern(13L * blockSize + i - 16));
            }
        }
    }

    @Test
    void dynamic4kSectors_blockPast32Gib_readCorrectly(@TempDir Path dir) throws IOException {
        int blockSize = MIB;
        long virtualSize = 33 * GIB;
        var allocated = new TreeMap<Long, Integer>();
        allocated.put(32767L, 21);  // last block of chunk 0
        allocated.put(32768L, 22);  // first block of chunk 1
        allocated.put(32769L, 23);
        Path file = dir.resolve("thirty-three-gib-4k.vhdx");
        DiskFixtures.vhdxToFile(file, virtualSize, blockSize, 4096, false, allocated);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertThat(disk.metadata()).containsEntry("vhdx.logicalSectorSize", "4096");
            assertThat(disk.metadata()).containsEntry("vhdx.chunkRatio", "32768");
            for (var e : allocated.entrySet()) {
                assertBlockHasPattern(disk, e.getKey(), blockSize, e.getValue());
            }
            assertBlockIsZero(disk, 32766L, blockSize);
            assertBlockIsZero(disk, 32770L, blockSize);
        }
    }

    @Test
    void fixedImage_blockPastFirstChunk_readCorrectly(@TempDir Path dir) throws IOException {
        int blockSize = MIB;
        long virtualSize = 4 * GIB + 2 * MIB;
        var allocated = new TreeMap<Long, Integer>();
        allocated.put(4095L, 31);
        allocated.put(4097L, 32);
        Path file = dir.resolve("fixed.vhdx");
        DiskFixtures.vhdxToFile(file, virtualSize, blockSize, 512, true, allocated);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertBlockHasPattern(disk, 4095L, blockSize, 31);
            assertBlockHasPattern(disk, 4097L, blockSize, 32);
            assertBlockIsZero(disk, 4096L, blockSize);
            assertBlockIsZero(disk, 0L, blockSize);
        }
    }

    @Test
    void smallImage_underOneChunk_unchanged(@TempDir Path dir) throws IOException {
        int blockSize = MIB;
        var allocated = new TreeMap<Long, Integer>();
        allocated.put(3L, 41);
        Path file = dir.resolve("small.vhdx");
        DiskFixtures.vhdxToFile(file, 8 * MIB, blockSize, 512, false, allocated);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertBlockHasPattern(disk, 3L, blockSize, 41);
            assertBlockIsZero(disk, 2L, blockSize);
            assertBlockIsZero(disk, 7L, blockSize);
        }
    }

    @Test
    void batRegionTooSmallForBitmapEntries_rejectedAtOpen(@TempDir Path dir) throws IOException {
        // 8 MiB / 1 MiB = 8 payload entries + 1 bitmap entry = 72 bytes.
        byte[] image = DiskFixtures.vhdx(8 * MIB, MIB, true, false);
        // Region table entry 1 (BAT): 192 KiB + 16 header + 32 (entry 0) + 16 guid + 8 offset
        int batLengthField = 192 * 1024 + 16 + 32 + 16 + 8;
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(batLengthField, 64);
        Path file = dir.resolve("short-bat.vhdx");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("BAT region too small");
    }

    @Test
    void invalidLogicalSectorSize_rejectedAtOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad-sector.vhdx");
        DiskFixtures.vhdxToFile(file, 8 * MIB, MIB, 512, false, new TreeMap<>());
        byte[] image = Files.readAllBytes(file);
        // metadata items start at 320 KiB + 32 + 5 * 32; logical sector size is item 4 at +32
        int logicalSectorField = 320 * 1024 + 32 + 5 * 32 + 32;
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(logicalSectorField, 1024);
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("logical sector size");
    }

    @Test
    void qemuGeneratedFixture_blockPastFourGib_readCorrectly() throws IOException {
        if (!Files.exists(QEMU_FIXTURE)) {
            return;
        }
        try (VirtualDisk disk = DiskReader.open(QEMU_FIXTURE)) {
            assertThat(disk.virtualSize()).isEqualTo(5 * GIB);
            assertThat(disk.metadata()).containsEntry("vhdx.blockSize", String.valueOf(MIB));
            assertThat(disk.metadata()).containsEntry("vhdx.chunkRatio", "4096");

            assertBlockIsFilledWith(disk, 1L, MIB, (byte) 0xCD);
            assertBlockIsFilledWith(disk, 4608L, MIB, (byte) 0xAB);
            assertBlockIsZero(disk, 0L, MIB);
            assertBlockIsZero(disk, 4607L, MIB);
            assertBlockIsZero(disk, 4609L, MIB);
            assertBlockIsZero(disk, 5L * 1024 - 1, MIB);
        }
    }

    private static void assertBlockHasPattern(VirtualDisk disk, long block, int blockSize, int seed)
            throws IOException {
        ByteBuffer data = disk.read(block * blockSize, blockSize);
        assertThat(data.remaining()).isEqualTo(blockSize);
        for (int i = 0; i < blockSize; i += 4093) {
            assertThat(data.get(i))
                    .as("block %d byte %d", block, i)
                    .isEqualTo(DiskFixtures.pattern((long) seed * blockSize + i));
        }
        assertThat(data.get(blockSize - 1))
                .isEqualTo(DiskFixtures.pattern((long) seed * blockSize + blockSize - 1));
    }

    private static void assertBlockIsFilledWith(VirtualDisk disk, long block, int blockSize, byte value)
            throws IOException {
        ByteBuffer data = disk.read(block * blockSize, blockSize);
        assertThat(data.remaining()).isEqualTo(blockSize);
        for (int i = 0; i < blockSize; i++) {
            if (data.get(i) != value) {
                throw new AssertionError(String.format("block %d byte %d: expected 0x%02x got 0x%02x",
                        block, i, value, data.get(i)));
            }
        }
    }

    private static void assertBlockIsZero(VirtualDisk disk, long block, int blockSize)
            throws IOException {
        assertBlockIsFilledWith(disk, block, blockSize, (byte) 0);
    }
}
