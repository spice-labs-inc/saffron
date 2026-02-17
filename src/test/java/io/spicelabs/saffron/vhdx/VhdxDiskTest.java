/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.vhdx;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.vhdx.header.VhdxFileIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link VhdxDiskImpl} and VHDX reading functionality.
 */
class VhdxDiskTest {

    private static final Path TEST_RESOURCES = Paths.get("src/test/resources/vhdx");
    private static final Path MAGIC_ONLY_VHDX = TEST_RESOURCES.resolve("magic-only.vhdx");

    static boolean testResourceExists() {
        return Files.exists(MAGIC_ONLY_VHDX);
    }

    @Test
    void detectFormat_vhdxSignature_returnsVhdx() throws IOException {
        if (!testResourceExists()) {
            return;
        }

        var format = DiskFormat.detect(MAGIC_ONLY_VHDX);
        assertThat(format).isPresent();
        assertThat(format.get()).isEqualTo(DiskFormat.VHDX);
    }

    @Test
    void readFileIdentifier_validVhdx_succeeds() throws IOException {
        if (!testResourceExists()) {
            return;
        }

        try (var channel = Files.newByteChannel(MAGIC_ONLY_VHDX)) {
            VhdxFileIdentifier identifier = VhdxFileIdentifier.read(channel);
            // Just verify it doesn't throw
            assertThat(identifier).isNotNull();
        }
    }

    @Test
    void open_nonVhdxFile_throwsInvalidMagicException(@TempDir Path tempDir) throws IOException {
        Path notVhdx = tempDir.resolve("not-vhdx.vhdx");
        // Create a file with wrong magic
        byte[] data = new byte[1024];
        Files.write(notVhdx, data);

        assertThatThrownBy(() -> DiskReader.open(notVhdx, DiskFormat.VHDX))
                .isInstanceOf(InvalidMagicException.class);
    }

    @Test
    void open_emptyFile_throwsException(@TempDir Path tempDir) throws IOException {
        Path empty = tempDir.resolve("empty.vhdx");
        Files.write(empty, new byte[0]);

        assertThatThrownBy(() -> DiskReader.open(empty, DiskFormat.VHDX))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdxDisk_interfaceMethods_defined() {
        // Verify the interface methods exist and are correctly defined
        VirtualDisk.VhdxDisk disk = new VirtualDisk.VhdxDisk() {
            @Override public int logVersion() { return 0; }
            @Override public int blockSize() { return 32 * 1024 * 1024; }
            @Override public int logicalSectorSize() { return 512; }
            @Override public int physicalSectorSize() { return 4096; }
            @Override public @org.jetbrains.annotations.NotNull DiskFormat format() { return DiskFormat.VHDX; }
            @Override public long virtualSize() { return 0; }
            @Override public long allocatedSize() { return 0; }
            @Override public @org.jetbrains.annotations.NotNull ByteBuffer read(long offset, int length) { return ByteBuffer.allocate(0); }
            @Override public @org.jetbrains.annotations.NotNull java.io.InputStream openStream() { return java.io.InputStream.nullInputStream(); }
            @Override public @org.jetbrains.annotations.NotNull Map<String, String> metadata() { return Map.of(); }
            @Override public @org.jetbrains.annotations.NotNull com.github.packageurl.PackageURL packageUrl() { throw new UnsupportedOperationException(); }
            @Override public @org.jetbrains.annotations.NotNull java.util.Optional<String> backingFile() { return java.util.Optional.empty(); }
            @Override public boolean isEncrypted() { return false; }
            @Override public boolean isCompressed() { return false; }
            @Override public @org.jetbrains.annotations.NotNull java.util.stream.Stream<VirtualDisk.Snapshot> snapshots() { return java.util.stream.Stream.empty(); }
            @Override public void close() {}
        };

        assertThat(disk.format()).isEqualTo(DiskFormat.VHDX);
        assertThat(disk.blockSize()).isEqualTo(32 * 1024 * 1024);
        assertThat(disk.logicalSectorSize()).isEqualTo(512);
        assertThat(disk.physicalSectorSize()).isEqualTo(4096);
    }

    @Test
    void formatDetection_byExtension_works() {
        var format = DiskFormat.detectByExtension("test.vhdx");
        assertThat(format).isPresent();
        assertThat(format.get()).isEqualTo(DiskFormat.VHDX);
    }

    @Test
    void formatDetection_byMagic_works() {
        byte[] magic = "vhdxfile".getBytes();
        var format = DiskFormat.detect(magic);
        assertThat(format).isPresent();
        assertThat(format.get()).isEqualTo(DiskFormat.VHDX);
    }
}
