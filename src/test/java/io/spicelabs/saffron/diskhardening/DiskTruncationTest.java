/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.diskharness.DiskFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Truncation-matrix tests for the disk-image layers (phase 1, T1.1/T1.2).
 *
 * <h2>Why this test exists</h2>
 * <p>Pre-fix, reads of ALLOCATED regions past the end of a truncated file
 * either spun forever (qcow2/VHD/VHDX: the outer block loop retries a
 * zero-progress channel read) or silently returned zero-padded data
 * (VDI/VMDK/raw). The requirement (plan R1.1/R1.2) is: such reads throw a
 * checked {@code IOException}; reads wholly below the truncation point
 * stay byte-exact; UNALLOCATED (sparse) regions keep returning zeros
 * without touching the channel (the sparse seam).</p>
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Each format opens a synthetic image with one allocated region of
 *       pattern bytes at virtual offset 0, then truncates the file
 *       mid-region and asserts the behaviors above.</li>
 *   <li>Hang-prone tests carry {@code @Timeout} and run in the dedicated
 *       hardening surefire fork (single-threaded, no reuse).</li>
 *   <li>Positive controls (untruncated image, exact-size read) pin that
 *       the fixtures themselves are valid.</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class DiskTruncationTest {

    private static void write(Path file, byte[] data) throws IOException {
        Files.write(file, data);
    }

    private static ByteBuffer readBytes(VirtualDisk disk, long offset, int length)
            throws IOException {
        return disk.read(offset, length);
    }

    private static void assertPattern(ByteBuffer buf, long startOffset, int length) {
        byte[] bytes = new byte[length];
        buf.get(bytes);
        for (int i = 0; i < length; i++) {
            assertThat(bytes[i])
                    .as("byte at %d", startOffset + i)
                    .isEqualTo(DiskFixtures.pattern(startOffset + i));
        }
    }

    private static void assertZeros(ByteBuffer buf, int length) {
        byte[] bytes = new byte[length];
        buf.get(bytes);
        for (int i = 0; i < length; i++) {
            assertThat(bytes[i]).as("byte at %d", i).isEqualTo((byte) 0);
        }
    }

    // ---------------------------------------------------------------- raw

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void rawTruncatedReadThrows(@TempDir Path dir) throws IOException {
        int size = 8192;
        byte[] data = new byte[size];
        DiskFixtures.fill(data, 0, size);
        Path file = dir.resolve("trunc.raw");
        write(file, data);

        try (VirtualDisk disk = DiskReader.open(file)) {
            // Positive control: full-size read succeeds with pattern.
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            DiskFixtures.truncate(file, size / 2);

            // Read wholly below the truncation point: byte-exact.
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            // Read touching the truncation point: checked IOException.
            assertThatThrownBy(() -> disk.read(size / 2 - 32, 64))
                    .isInstanceOf(IOException.class);
        }
    }

    // -------------------------------------------------------------- qcow2

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void qcow2TruncatedAllocatedReadThrows(@TempDir Path dir) throws IOException {
        int clusterSize = 65536;
        byte[] cluster = new byte[clusterSize];
        DiskFixtures.fill(cluster, 0, clusterSize);
        byte[] image = DiskFixtures.qcow2AllocatedCluster(3, 16, 4L * clusterSize, cluster);
        Path file = dir.resolve("trunc.qcow2");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            DiskFixtures.truncate(file, 5L * clusterSize + clusterSize / 2);

            // Wholly below truncation: exact.
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            // Touching truncation: IOException (pre-fix: infinite spin).
            assertThatThrownBy(() -> disk.read(clusterSize / 2 - 32, 64))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void qcow2UnallocatedRegionStillZerosWhenTruncated(@TempDir Path dir) throws IOException {
        int clusterSize = 65536;
        byte[] cluster = new byte[clusterSize];
        DiskFixtures.fill(cluster, 0, clusterSize);
        byte[] image = DiskFixtures.qcow2AllocatedCluster(3, 16, 8L * clusterSize, cluster);
        Path file = dir.resolve("sparse.qcow2");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            // Truncate away the allocated cluster entirely.
            DiskFixtures.truncate(file, 5L * clusterSize);

            // Allocated region read -> IOException.
            assertThatThrownBy(() -> disk.read(0, 512)).isInstanceOf(IOException.class);

            // Unallocated region beyond the first cluster: zeros, no throw.
            assertZeros(readBytes(disk, 2L * clusterSize, 512), 512);
        }
    }

    // ---------------------------------------------------------------- VHD

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void vhdFixedTruncatedReadThrows(@TempDir Path dir) throws IOException {
        int dataSize = 8192;
        byte[] image = DiskFixtures.fixedVhd(dataSize, dataSize);
        Path file = dir.resolve("trunc.vhd");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            DiskFixtures.truncate(file, dataSize / 2);

            assertPattern(readBytes(disk, 0, 512), 0, 512);
            assertThatThrownBy(() -> disk.read(dataSize / 2 - 32, 64))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void vhdDynamicTruncatedAllocatedReadThrows(@TempDir Path dir) throws IOException {
        int blockSize = 2 * 1024 * 1024;
        byte[] image = DiskFixtures.dynamicVhd(8L * blockSize, blockSize, true);
        Path file = dir.resolve("trunc-dyn.vhd");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            long dataStart = DiskFixtures.vhdDynamicDataStart(blockSize, 4);
            DiskFixtures.truncate(file, dataStart + blockSize / 2);

            assertPattern(readBytes(disk, 0, 512), 0, 512);
            assertThatThrownBy(() -> disk.read(blockSize / 2 - 32, 64))
                    .isInstanceOf(IOException.class);

            // Unallocated block: zeros, no throw (sparse seam).
            assertZeros(readBytes(disk, 2L * blockSize, 512), 512);
        }
    }

    // --------------------------------------------------------------- VHDX

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void vhdxTruncatedAllocatedReadThrows(@TempDir Path dir) throws IOException {
        int blockSize = 1024 * 1024;
        byte[] image = DiskFixtures.vhdx(8L * blockSize, blockSize, true, false);
        Path file = dir.resolve("trunc.vhdx");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            long dataStart = DiskFixtures.vhdxDataStart(8L * blockSize, blockSize, true);
            DiskFixtures.truncate(file, dataStart + blockSize / 2);

            assertPattern(readBytes(disk, 0, 512), 0, 512);
            assertThatThrownBy(() -> disk.read(blockSize / 2 - 32, 64))
                    .isInstanceOf(IOException.class);

            assertZeros(readBytes(disk, 2L * blockSize, 512), 512);
        }
    }

    // ---------------------------------------------------------------- VDI

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void vdiTruncatedAllocatedReadThrows(@TempDir Path dir) throws IOException {
        int blockSize = 1024 * 1024;
        byte[] image = DiskFixtures.vdi(8L * blockSize, blockSize, true, null);
        Path file = dir.resolve("trunc.vdi");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            long dataOffset = DiskFixtures.vdiDataOffset(8L * blockSize, blockSize);
            DiskFixtures.truncate(file, dataOffset + blockSize / 2);

            assertPattern(readBytes(disk, 0, 512), 0, 512);
            assertThatThrownBy(() -> disk.read(blockSize / 2 - 32, 64))
                    .isInstanceOf(IOException.class);

            assertZeros(readBytes(disk, 2L * blockSize, 512), 512);
        }
    }

    // --------------------------------------------------------------- VMDK

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void vmdkTruncatedAllocatedReadThrows(@TempDir Path dir) throws IOException {
        int grainSize = 65536;
        byte[] image = DiskFixtures.vmdk(8L * grainSize, grainSize, true, false, null);
        Path file = dir.resolve("trunc.vmdk");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            long grainData = DiskFixtures.vmdkGrainDataStart(grainSize);
            DiskFixtures.truncate(file, grainData + grainSize / 2);

            assertPattern(readBytes(disk, 0, 512), 0, 512);
            assertThatThrownBy(() -> disk.read(grainSize / 2 - 32, 64))
                    .isInstanceOf(IOException.class);

            assertZeros(readBytes(disk, 2L * grainSize, 512), 512);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void vmdkCompressedTruncatedReadThrows(@TempDir Path dir) throws IOException {
        int grainSize = 65536;
        byte[] image = DiskFixtures.vmdk(8L * grainSize, grainSize, true, true, null);
        Path file = dir.resolve("trunc-comp.vmdk");
        write(file, image);

        try (VirtualDisk disk = DiskReader.open(file)) {
            assertPattern(readBytes(disk, 0, 512), 0, 512);

            long grainData = DiskFixtures.vmdkGrainDataStart(grainSize);
            DiskFixtures.truncate(file, grainData + 64);

            assertThatThrownBy(() -> disk.read(0, 512)).isInstanceOf(IOException.class);
        }
    }
}
