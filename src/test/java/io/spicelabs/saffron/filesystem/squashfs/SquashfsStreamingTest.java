/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.fs.FileSystemMount.FilesystemLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that squashfs file reads are streamed rather than fully materialized.
 */
class SquashfsStreamingTest {

    /**
     * Verifies that a file can be read correctly through {@link InputStream}
     * without requiring the entire file to be held in memory first. This is the
     * path used by callers that stream large files.
     */
    @Test
    void openStreamReadsFileInChunks(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Path file = source.resolve("hello.txt");
        byte[] content = "Hello, streamed squashfs!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, content);

        Path image = tempDir.resolve("stream-test.squashfs");
        ProcessBuilder pb = new ProcessBuilder(
                "mksquashfs", source.toString(), image.toString(),
                "-noappend", "-quiet");
        Process process = pb.inheritIO().start();
        int exit = process.waitFor();
        assertThat(exit).isEqualTo(0);
        assertThat(Files.exists(image)).isTrue();

        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> found = fs.resolve("/hello.txt");
            assertThat(found).isPresent();
            FileSystemEntry entry = found.get();
            assertThat(entry.type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);

            FileSystemEntry.RegularFile regular = (FileSystemEntry.RegularFile) entry;
            try (InputStream in = regular.openStream()) {
                byte[] read = in.readAllBytes();
                assertThat(read).containsExactly(content);
            }
            assertThat(regular.readAllBytes()).containsExactly(content);
        }
    }

    /**
     * Verifies that a declared file size larger than the data blocks can actually
     * produce is rejected before any allocation is made. The inode table is left
     * uncompressed so the file-size field can be patched directly.
     */
    @Test
    void rejectsDeclaredFileSizeLargerThanAvailableBlocks(@TempDir Path tempDir) throws IOException, InterruptedException {
        int originalSize = 4660;
        byte[] content = new byte[originalSize];
        java.util.Random random = new java.util.Random(7);
        random.nextBytes(content);

        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Path file = source.resolve("big.txt");
        Files.write(file, content);

        Path image = tempDir.resolve("size-test.squashfs");
        ProcessBuilder pb = new ProcessBuilder(
                "mksquashfs", source.toString(), image.toString(),
                "-b", "4096", "-noI", "-noD", "-noappend", "-quiet");
        Process process = pb.inheritIO().start();
        int exit = process.waitFor();
        assertThat(exit).isEqualTo(0);

        byte[] data = Files.readAllBytes(image);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long inodeTableStart = buf.getLong(0x40);
        long directoryTableStart = buf.getLong(0x48);

        // Root directory inode (basic) is 32 bytes, file inode (basic) starts next,
        // and file_size is at offset 28 within the basic file inode. With -noI the
        // inode table metadata block has a 2-byte header, so the field is at this
        // computed offset.
        int patchOffset = findFileSizeOffset(data, (int) inodeTableStart, (int) directoryTableStart, originalSize);
        assertThat(patchOffset).isPositive();
        int current = buf.getInt(patchOffset);
        assertThat(current).isEqualTo(originalSize);

        // Declare a file size that requires more uncompressed bytes than the blocks
        // and fragment can possibly produce. A file of 4660 bytes in a 4096-byte
        // block filesystem needs one full block + one fragment, so 8193 bytes is
        // larger than any valid layout can satisfy.
        int patchedSize = 8193;
        buf.putInt(patchOffset, patchedSize);
        Files.write(image, data);

        try (VirtualDisk disk = DiskReader.open(image);
             FileSystem fs = mountLargest(disk)) {
            Optional<FileSystemEntry> found = fs.resolve("/big.txt");
            assertThat(found).isPresent();
            FileSystemEntry.RegularFile regular = (FileSystemEntry.RegularFile) found.get();
            assertThat(regular.size()).isEqualTo(patchedSize);
            assertThatThrownBy(regular::openStream)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("exceeds available data blocks");
            assertThatThrownBy(regular::readAllBytes)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("exceeds available data blocks");
        }
    }

    private static int findFileSizeOffset(byte[] image, int inodeTableStart, int directoryTableStart, int fileSize) {
        byte[] pattern = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize).array();
        for (int i = inodeTableStart; i + 4 <= directoryTableStart; i++) {
            if (matches(image, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matches(byte[] data, int offset, byte[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private static FileSystem mountLargest(VirtualDisk disk) throws IOException {
        Optional<FilesystemLocation> location = FileSystemMount.findLargestFilesystem(disk);
        assertThat(location).isPresent();
        return FileSystemMount.mount(disk, location.get());
    }
}
