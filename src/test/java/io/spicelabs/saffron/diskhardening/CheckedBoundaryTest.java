/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.partition.GptPartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checked-exception boundary tests (phase 1, R1.8 / T1.7 / T1.9).
 *
 * <h2>Why this test exists</h2>
 * <p>Pre-fix, out-of-range reads from hostile metadata escaped the
 * driver APIs as unchecked {@code IllegalArgumentException} /
 * {@code IndexOutOfBoundsException} (and GPT entry arithmetic overflowed
 * silently). R1.8 requires the {@code DiskRegion} boundary to convert
 * unchecked bounds errors into checked {@code IOException}, and detectors
 * probing truncated media to return empty rather than throw.</p>
 *
 * <h2>LLM section</h2>
 * <p>A fake disk throwing {@code IllegalArgumentException} on out-of-range
 * reads stands in for the real disk layer; assertions verify the checked
 * conversion and that detector probes on truncated files stay quiet.</p>
 */
class CheckedBoundaryTest {

    static class BoundsThrowingDisk implements VirtualDisk.RawDisk {
        private final long size;

        BoundsThrowingDisk(long size) {
            this.size = size;
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            if (offset < 0 || length < 0 || offset + length > size) {
                throw new IllegalArgumentException("bounds");
            }
            return ByteBuffer.allocate(length);
        }

        @Override
        public long virtualSize() {
            return size;
        }

        @Override
        public long allocatedSize() {
            return size;
        }

        @Override
        public DiskFormat format() {
            return DiskFormat.RAW;
        }

        @Override
        public java.util.Map<String, String> metadata() {
            return java.util.Map.of();
        }

        @Override
        public java.util.stream.Stream<Snapshot> snapshots() {
            return java.util.stream.Stream.empty();
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
        public java.util.Optional<String> backingFile() {
            return java.util.Optional.empty();
        }

        @Override
        public int sectorSize() {
            return 512;
        }

        @Override
        public java.io.InputStream openStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public com.github.packageurl.PackageURL packageUrl() {
            try {
                return new com.github.packageurl.PackageURL("pkg:vmdisk/raw/test@1.0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
        }
    }

    @Test
    void diskRegionConvertsBoundsErrorsToCheckedIOException() throws IOException {
        DiskRegion region = DiskRegion.fromPartition(new BoundsThrowingDisk(4096), 0, 4096);

        // In-range read works.
        assertThat(region.read(0, 512).remaining()).isEqualTo(512);

        // Out-of-range reads: checked IOException, not IAE.
        assertThatThrownBy(() -> region.read(4000, 512)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> region.read(-1, 16)).isInstanceOf(IOException.class);
    }

    @Test
    void diskRegionConvertsCorruptionExceptionsToIOException() throws IOException {
        // A disk whose read() throws the library's UNCHECKED corruption
        // exception must surface as checked IOException at the boundary.
        VirtualDisk.RawDisk corrupt = new CorruptThrowingDisk(4096);
        DiskRegion region = DiskRegion.fromPartition(corrupt, 0, 4096);
        assertThatThrownBy(() -> region.read(0, 16)).isInstanceOf(IOException.class);
    }

    @Test
    void diskRegionOverflowingOffsetIsChecked() throws IOException {
        DiskRegion region = DiskRegion.fromPartition(new BoundsThrowingDisk(4096),
                Long.MAX_VALUE - 10, 4096);

        assertThatThrownBy(() -> region.read(20, 16)).isInstanceOf(IOException.class);
    }

    @Test
    void gptProbeOnTruncatedDiskReturnsEmpty(@TempDir Path dir) throws IOException {
        // GPT header present, entries LBA beyond the file: pre-fix this
        // escaped as unchecked IAE; post-fix tryParse returns empty.
        byte[] data = new byte[3 * 512];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.position(512);
        buf.putLong(0x5452415020494645L);
        buf.putInt(0x00010000);
        buf.putInt(92);
        buf.putInt(0);
        buf.putInt(0);
        buf.putLong(1);
        buf.putLong(2);
        buf.putLong(34);
        buf.putLong(100);
        buf.putLong(0);
        buf.putLong(0);
        buf.putLong(100_000); // entries LBA far beyond the file
        buf.putInt(4);
        buf.putInt(128);
        buf.putInt(0);

        Path file = dir.resolve("trunc-gpt.raw");
        Files.write(file, data);
        try (VirtualDisk disk = DiskReader.open(file, DiskFormat.RAW)) {
            assertThat(GptPartitionTable.tryParse(disk)).isEmpty();
        }
    }

    private static final class CorruptThrowingDisk extends BoundsThrowingDisk {
        CorruptThrowingDisk(long size) {
            super(size);
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            throw new io.spicelabs.saffron.exception.CorruptedDiskException(
                    "simulated corruption", offset, "region", DiskFormat.RAW);
        }
    }

    @Test
    void filesystemDetectorOnTinyTruncatedDiskReturnsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("tiny.raw");
        Files.write(file, new byte[64]);

        try (VirtualDisk disk = DiskReader.open(file, DiskFormat.RAW)) {
            var result = io.spicelabs.saffron.filesystem.FilesystemDetector.detect(disk, 0);
            assertThat(result).isEmpty();
        }
    }

    @Test
    void containerDetectorOnTruncatedFileReturnsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("trunc.bin");
        Files.write(file, new byte[512]);

        assertThat(io.spicelabs.saffron.container.ContainerDetector.detect(file)).isEmpty();
    }
}
