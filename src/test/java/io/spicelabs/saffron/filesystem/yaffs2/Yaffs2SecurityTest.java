/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.yaffs2;

import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Semantic and defensive tests for {@link Yaffs2FileSystemImpl}, using
 * hand-crafted images from {@link Yaffs2ImageWriter}.
 */
class Yaffs2SecurityTest {

    private static final byte[] A = "first version".getBytes(StandardCharsets.UTF_8);
    private static final byte[] B = "second".getBytes(StandardCharsets.UTF_8);

    /**
     * A directory tree with files, a symlink, a hardlink, a special file,
     * and a holey file round-trips with correct content.
     */
    @Test
    void roundTripTree() throws IOException {
        byte[] fileData = "file content".getBytes(StandardCharsets.UTF_8);
        Yaffs2ImageWriter w = new Yaffs2ImageWriter();
        w.header(1, Yaffs2ImageWriter.TYPE_DIRECTORY, 1, "/", Yaffs2ImageWriter.MODE_DIR, 0, null, 0);
        w.header(2, Yaffs2ImageWriter.TYPE_DIRECTORY, 1, "dir", Yaffs2ImageWriter.MODE_DIR, 0, null, 0);
        w.header(3, Yaffs2ImageWriter.TYPE_FILE, 1, "file.txt", Yaffs2ImageWriter.MODE_REG, fileData.length, null, 0);
        w.dataChunk(3, 1, fileData);
        w.header(4, Yaffs2ImageWriter.TYPE_FILE, 2, "nested.txt", Yaffs2ImageWriter.MODE_REG, 4, null, 0);
        w.dataChunk(4, 1, "deep".getBytes(StandardCharsets.UTF_8));
        w.header(5, Yaffs2ImageWriter.TYPE_SYMLINK, 1, "link", Yaffs2ImageWriter.MODE_LNK, 0, "dir/nested.txt", 0);
        w.header(6, Yaffs2ImageWriter.TYPE_HARDLINK, 1, "hard", Yaffs2ImageWriter.MODE_REG, 0, null, 3);
        w.header(7, Yaffs2ImageWriter.TYPE_SPECIAL, 1, "fifo", 0x11a4, 0, null, 0);
        // holey file: chunk 1 and chunk 3 with a gap at chunk 2
        w.header(8, Yaffs2ImageWriter.TYPE_FILE, 1, "holey.bin", Yaffs2ImageWriter.MODE_REG, 0, null, 0);
        w.dataChunk(8, 1, "AAAA".getBytes(StandardCharsets.UTF_8));
        w.dataChunk(8, 3, "ZZZZ".getBytes(StandardCharsets.UTF_8));

        try (FileSystem fs = mount(w.bytes())) {
            assertContent(fs, "/file.txt", fileData);
            assertContent(fs, "/dir/nested.txt", "deep".getBytes(StandardCharsets.UTF_8));

            // symlink
            Optional<FileSystemEntry> link = fs.root().find("link");
            assertThat(link).isPresent();
            assertThat(link.get().type()).isEqualTo(FileSystemEntry.EntryType.SYMBOLIC_LINK);
            assertThat(((FileSystemEntry.SymbolicLink) link.get()).target()).isEqualTo("dir/nested.txt");

            // hardlink resolves to the same content as file.txt
            Optional<FileSystemEntry> hard = fs.root().find("hard");
            assertThat(hard).isPresent();
            assertThat(hard.get().type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
            assertThat(((FileSystemEntry.RegularFile) hard.get()).readAllBytes())
                    .containsExactly(fileData);

            // special file
            Optional<FileSystemEntry> fifo = fs.root().find("fifo");
            assertThat(fifo).isPresent();
            assertThat(fifo.get().type()).isEqualTo(FileSystemEntry.EntryType.FIFO);

            // holey file: AAAA + zero gap + ZZZZ
            byte[] holey = ((FileSystemEntry.RegularFile) fs.resolve("/holey.bin").orElseThrow())
                    .readAllBytes();
            assertThat(holey).hasSize(Yaffs2ImageWriter.PAGE * 2 + 4);
            assertThat(java.util.Arrays.copyOfRange(holey, 0, 4))
                    .containsExactly("AAAA".getBytes(StandardCharsets.UTF_8));
            assertThat(java.util.Arrays.copyOfRange(holey, holey.length - 4, holey.length))
                    .containsExactly("ZZZZ".getBytes(StandardCharsets.UTF_8));
            for (int i = 4; i < holey.length - 4; i++) {
                assertThat(holey[i]).isZero();
            }
        }
    }

    /**
     * When two versions of the same object header exist, the highest
     * sequence number wins.
     */
    @Test
    void headerHighestSeqWins() throws IOException {
        Yaffs2ImageWriter w = new Yaffs2ImageWriter();
        w.header(2, Yaffs2ImageWriter.TYPE_FILE, 1, "f", Yaffs2ImageWriter.MODE_REG, 0, null, 0);
        w.dataChunk(2, 1, A);
        // Newer header for obj 2 renames it to "g" (seq increases automatically).
        w.header(2, Yaffs2ImageWriter.TYPE_FILE, 1, "g", Yaffs2ImageWriter.MODE_REG, 0, null, 0);

        try (FileSystem fs = mount(w.bytes())) {
            assertThat(fs.resolve("/g")).isPresent();
            assertThat(fs.resolve("/f")).isEmpty();
        }
    }

    /**
     * Objects whose parent is the deleted sentinel (3) are hidden.
     */
    @Test
    void deletedObjectIsHidden() throws IOException {
        Yaffs2ImageWriter w = new Yaffs2ImageWriter();
        w.header(2, Yaffs2ImageWriter.TYPE_FILE, Yaffs2Node.OBJID_UNLINKED, "gone",
                Yaffs2ImageWriter.MODE_REG, 0, null, 0);
        w.dataChunk(2, 1, A);
        w.header(3, Yaffs2ImageWriter.TYPE_FILE, 1, "present", Yaffs2ImageWriter.MODE_REG, 0, null, 0);
        w.dataChunk(3, 1, B);

        try (FileSystem fs = mount(w.bytes())) {
            assertThat(fs.resolve("/present")).isPresent();
            assertThat(fs.resolve("/gone")).isEmpty();
        }
    }

    /**
     * Entry names containing path separators are dropped.
     */
    @Test
    void pathTraversalNameIsSkipped() throws IOException {
        Yaffs2ImageWriter w = new Yaffs2ImageWriter();
        w.header(2, Yaffs2ImageWriter.TYPE_FILE, 1, "../evil", Yaffs2ImageWriter.MODE_REG, 0, null, 0);
        w.dataChunk(2, 1, A);

        try (FileSystem fs = mount(w.bytes())) {
            assertThat(fs.resolve("/evil")).isEmpty();
            List<String> names;
            try (Stream<FileSystemEntry> list = fs.root().list()) {
                names = list.map(FileSystemEntry::name).toList();
            }
            assertThat(names).isEmpty();
        }
    }

    /**
     * A region that is not a valid YAFFS2 image fails mount with a checked
     * IOException.
     */
    @Test
    void garbageFailsMountCleanly() {
        byte[] garbage = new byte[2112 * 4];
        new java.util.Random(3).nextBytes(garbage);

        assertThatThrownBy(() -> mount(garbage)).isInstanceOf(IOException.class);
    }

    /**
     * The root object id (1) is implied when its header is absent: entries
     * whose parent is 1 still list under the root.
     */
    @Test
    void rootIsImpliedWhenAbsent() throws IOException {
        Yaffs2ImageWriter w = new Yaffs2ImageWriter();
        w.header(2, Yaffs2ImageWriter.TYPE_FILE, 1, "orphan.txt", Yaffs2ImageWriter.MODE_REG, 0, null, 0);
        w.dataChunk(2, 1, "data".getBytes(StandardCharsets.UTF_8));

        try (FileSystem fs = mount(w.bytes())) {
            assertContent(fs, "/orphan.txt", "data".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void assertContent(FileSystem fs, String path, byte[] expected)
            throws IOException {
        Optional<FileSystemEntry> found = fs.resolve(path);
        assertThat(found).as("resolving %s", path).isPresent();
        assertThat(found.get().type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE);
        byte[] content = ((FileSystemEntry.RegularFile) found.get()).readAllBytes();
        assertThat(content).as("content of %s", path).containsExactly(expected);
    }

    private static FileSystem mount(byte[] image) throws IOException {
        return Yaffs2FileSystemImpl.mount(new Region(image));
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
