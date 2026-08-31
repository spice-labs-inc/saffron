/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.cramfs;

import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Defensive and semantic tests for {@link CramfsFileSystemImpl}, using
 * hand-crafted images built by {@link CramfsImageWriter}.
 *
 * <p>Crafted layout: superblock (0..76), the root directory's single entry
 * "f" (76..92), a block-pointer table, then block data.
 */
class CramfsSecurityTest {

    private static final byte[] PLAIN = "block data".getBytes(StandardCharsets.UTF_8);

    /**
     * A file whose data is one zlib-compressed block reads back verbatim.
     */
    @Test
    void readsSingleCompressedBlock() throws IOException {
        byte[] compressed = zlib(PLAIN);
        byte[] image = fileImage(PLAIN.length, compressed, 0);

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow()).readAllBytes();
            assertThat(content).containsExactly(PLAIN);
        }
    }

    /**
     * A hole (consecutive equal block pointers) reads as zeros.
     */
    @Test
    void holeReadsAsZeros() throws IOException {
        // File of 8192 bytes: block 0 = data, block 1 = hole.
        byte[] compressed = zlib(new byte[4096]);
        byte[] image = fileImageTwoBlocks(8192, compressed, false);

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow()).readAllBytes();
            assertThat(content).hasSize(8192).containsOnly((byte) 0);
        }
    }

    /**
     * An uncompressed block (BLK_UNCOMPRESSED flag) is copied raw.
     */
    @Test
    void readsUncompressedBlock() throws IOException {
        byte[] raw = new byte[4096];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i & 0xff);
        }
        byte[] image = fileImage(4096, raw, CramfsImageWriter.BLK_UNCOMPRESSED);

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow()).readAllBytes();
            assertThat(content).containsExactly(raw);
        }
    }

    /**
     * A block pointer beyond the image size must fail with a checked
     * IOException, never an unchecked exception or an out-of-bounds read.
     */
    @Test
    void pointerBeyondImageFailsCleanly() throws IOException {
        byte[] image = fileImageExplicitPtr(4096, 0x7fffff00, new byte[0]);

        try (FileSystem fs = mount(image)) {
            FileSystemEntry.RegularFile file =
                    (FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow();
            assertThatThrownBy(file::readAllBytes).isInstanceOf(IOException.class);
        }
    }

    /**
     * A stored block longer than 2*4096 bytes (the kernel's sanity limit) is
     * rejected: a 4096-byte block cannot plausibly occupy > 8192 bytes of
     * compressed data.
     */
    @Test
    void oversizedBlockIsRejected() throws IOException {
        byte[] incompressible = new byte[9000];
        new Random(7).nextBytes(incompressible);
        byte[] compressed = zlib(incompressible);
        assertThat(compressed.length).isGreaterThan(8192);
        byte[] image = fileImage(4096, compressed, 0);

        try (FileSystem fs = mount(image)) {
            FileSystemEntry.RegularFile file =
                    (FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow();
            assertThatThrownBy(file::readAllBytes).isInstanceOf(IOException.class);
        }
    }

    /**
     * Unsupported superblock feature flags are rejected at mount time.
     */
    @Test
    void unsupportedFlagsRejectMount() throws IOException {
        CramfsImageWriter w = new CramfsImageWriter();
        w.superblock(CramfsImageWriter.FLAG_UNSUPPORTED, "test");
        w.rootDir(0, 0);
        byte[] image = w.finish(0, 0);

        assertThatThrownBy(() -> mount(image)).isInstanceOf(IOException.class);
    }

    /**
     * Entry names containing path separators are dropped (path traversal
     * hardening), mirroring the other Saffron filesystems.
     */
    @Test
    void pathTraversalNameIsSkipped() throws IOException {
        CramfsImageWriter w = new CramfsImageWriter();
        w.superblock(CramfsImageWriter.FLAG_FSID_V2, "test");
        w.rootDir(36, 19); // two entries: "../evil" (20 bytes) and "ok" (16)
        w.dirent(CramfsImageWriter.MODE_REG, 0, "../evil", 0);
        w.dirent(CramfsImageWriter.MODE_REG, 0, "ok", 0);
        byte[] image = w.finish(2, 0);

        try (FileSystem fs = mount(image)) {
            List<String> names;
            try (Stream<FileSystemEntry> list = fs.root().list()) {
                names = list.map(FileSystemEntry::name).toList();
            }
            assertThat(names).containsExactly("ok");
        }
    }

    /**
     * A FIFO entry is exposed as a SpecialFile.
     */
    @Test
    void fifoEntryIsExposed() throws IOException {
        CramfsImageWriter w = new CramfsImageWriter();
        w.superblock(CramfsImageWriter.FLAG_FSID_V2, "test");
        w.rootDir(16, 19);
        w.dirent(CramfsImageWriter.MODE_FIFO, 0x0101, "pipe", 0);
        byte[] image = w.finish(1, 0);

        try (FileSystem fs = mount(image)) {
            Optional<FileSystemEntry> entry = fs.root().find("pipe");
            assertThat(entry).isPresent();
            assertThat(entry.get().type()).isEqualTo(FileSystemEntry.EntryType.FIFO);
        }
    }

    // ========================================================================
    // Image construction helpers
    // ========================================================================

    /**
     * Builds an image with root entry "f" (16 bytes at offset 76), a pointer
     * table at offset 92 with {@code ptrValues} entries, and a data region.
     */
    private static byte[] buildImage(long fileSize, int[] ptrValues, byte[] dataRegion) {
        CramfsImageWriter w = new CramfsImageWriter();
        w.superblock(CramfsImageWriter.FLAG_FSID_V2, "test");
        w.rootDir(16, 19);
        w.dirent(CramfsImageWriter.MODE_REG, fileSize, "f", 23); // 23*4 = 92
        byte[] base = w.finish(1, 1);
        int tableOff = base.length; // 92

        byte[] image = new byte[base.length + ptrValues.length * 4 + dataRegion.length];
        System.arraycopy(base, 0, image, 0, base.length);
        ByteBuffer b = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4, image.length); // re-patch the superblock size field
        for (int i = 0; i < ptrValues.length; i++) {
            b.putInt(tableOff + i * 4, ptrValues[i]);
        }
        System.arraycopy(dataRegion, 0, image, tableOff + ptrValues.length * 4,
                dataRegion.length);
        return image;
    }

    /** Single-block file: pointer = end of the data region. */
    private static byte[] fileImage(long fileSize, byte[] data, int flags) {
        return buildImage(fileSize,
                new int[] {(92 + 4 + data.length) | flags}, data);
    }

    /** Two-block file: block 0 = data, block 1 = same end pointer (hole). */
    private static byte[] fileImageTwoBlocks(long fileSize, byte[] data, boolean uncompressed) {
        int end0 = 92 + 8 + data.length;
        if (uncompressed) {
            end0 |= CramfsImageWriter.BLK_UNCOMPRESSED;
        }
        return buildImage(fileSize, new int[] {end0, 92 + 8 + data.length}, data);
    }

    /** Two-block file with an explicit first pointer. */
    private static byte[] fileImageExplicitPtr(long fileSize, int ptr0, byte[] data) {
        return buildImage(fileSize, new int[] {ptr0}, data);
    }

    private static byte[] zlib(byte[] data) {
        CramfsImageWriter w = new CramfsImageWriter();
        return w.zlibBlock(data);
    }

    private static FileSystem mount(byte[] image) throws IOException {
        return CramfsFileSystemImpl.mount(new Region(image));
    }

    static final class Region implements DiskRegion {
        private final byte[] data;

        Region(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IOException("Read out of bounds");
            }
            byte[] copy = new byte[length];
            System.arraycopy(data, (int) offset, copy, 0, length);
            return ByteBuffer.wrap(copy);
        }

        @Override
        public long size() {
            return data.length;
        }
    }
}
