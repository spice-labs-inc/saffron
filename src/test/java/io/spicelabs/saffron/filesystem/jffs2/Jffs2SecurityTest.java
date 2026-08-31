/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.jffs2;

import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Defensive and semantic tests for {@link Jffs2FileSystemImpl}.
 *
 * <p>These tests exercise the log-structured semantics of JFFS2 (version
 * resolution, deletion by ino=0, hard links, sparse fragments) and the
 * hardening invariants (corrupt nodes are skipped, unsupported compression is
 * rejected with a checked exception, untrusted lengths never cause unbounded
 * allocation).
 */
class Jffs2SecurityTest {

    private static final byte[] A = "old-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] B = "newer".getBytes(StandardCharsets.UTF_8);

    /**
     * When two dirent versions exist for the same (pino, name), the highest
     * version wins.
     */
    @Test
    void direntHighestVersionWins() throws IOException {
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "f", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(2, 1, Jffs2NodeWriter.MODE_REG, A.length, 0, A),
                Jffs2NodeWriter.dirent(1, 2, 3, "f", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(3, 1, Jffs2NodeWriter.MODE_REG, B.length, 0, B));

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow()).readAllBytes();
            assertThat(content).containsExactly(B);
        }
    }

    /**
     * A dirent whose latest version has ino == 0 records a deletion: the
     * entry must disappear even though older versions exist.
     */
    @Test
    void deletedFileIsAbsent() throws IOException {
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "f", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(2, 1, Jffs2NodeWriter.MODE_REG, A.length, 0, A),
                Jffs2NodeWriter.dirent(1, 2, 0, "f", Jffs2NodeWriter.DT_REG));

        try (FileSystem fs = mount(image)) {
            assertThat(fs.resolve("/f")).isEmpty();
            List<String> names;
            try (Stream<FileSystemEntry> list = fs.root().list()) {
                names = list.map(FileSystemEntry::name).toList();
            }
            assertThat(names).doesNotContain("f");
        }
    }

    /**
     * Two dirents with the same ino are hard links: both names expose the
     * same content.
     */
    @Test
    void hardLinksExposeSameContent() throws IOException {
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "a", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.dirent(1, 2, 2, "b", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(2, 1, Jffs2NodeWriter.MODE_REG, A.length, 0, A));

        try (FileSystem fs = mount(image)) {
            byte[] viaA = ((FileSystemEntry.RegularFile) fs.resolve("/a").orElseThrow()).readAllBytes();
            byte[] viaB = ((FileSystemEntry.RegularFile) fs.resolve("/b").orElseThrow()).readAllBytes();
            assertThat(viaB).containsExactly(viaA);
        }
    }

    /**
     * A file whose fragments do not cover the whole logical size (a sparse
     * file) reads zeros for the unwritten ranges.
     */
    @Test
    void sparseFileReadsZeroGaps() throws IOException {
        byte[] first = "ABCD".getBytes(StandardCharsets.UTF_8);
        byte[] last = "WXYZ".getBytes(StandardCharsets.UTF_8);
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "sparse", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(2, 1, Jffs2NodeWriter.MODE_REG, 100, 0, first),
                Jffs2NodeWriter.inode(2, 2, Jffs2NodeWriter.MODE_REG, 100, 96, last));

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/sparse").orElseThrow()).readAllBytes();
            assertThat(content).hasSize(100);
            assertThat(java.util.Arrays.copyOfRange(content, 0, 4)).containsExactly(first);
            assertThat(java.util.Arrays.copyOfRange(content, 96, 100)).containsExactly(last);
            for (int i = 4; i < 96; i++) {
                assertThat(content[i]).isZero();
            }
        }
    }

    /**
     * When a fragment is overwritten by a newer version, only the newest
     * bytes are visible.
     */
    @Test
    void newerFragmentOverwritesOlder() throws IOException {
        byte[] oldFrag = "XXXX".getBytes(StandardCharsets.UTF_8);
        byte[] newFrag = "NEW!".getBytes(StandardCharsets.UTF_8);
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "f", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(2, 1, Jffs2NodeWriter.MODE_REG, 4, 0, oldFrag),
                Jffs2NodeWriter.inode(2, 2, Jffs2NodeWriter.MODE_REG, 4, 0, newFrag));

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow()).readAllBytes();
            assertThat(content).containsExactly(newFrag);
        }
    }

    /**
     * A node with a corrupted header CRC is skipped during the scan (matching
     * kernel behaviour), so the valid nodes around it still mount.
     */
    @Test
    void corruptNodeIsSkipped() throws IOException {
        byte[] corrupt = Jffs2NodeWriter.corruptHdrCrc(
                Jffs2NodeWriter.dirent(1, 1, 99, "ghost", Jffs2NodeWriter.DT_REG));
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                corrupt,
                Jffs2NodeWriter.dirent(1, 2, 2, "real", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(2, 1, Jffs2NodeWriter.MODE_REG, A.length, 0, A));

        try (FileSystem fs = mount(image)) {
            assertThat(fs.resolve("/real")).isPresent();
            assertThat(fs.resolve("/ghost")).isEmpty();
        }
    }

    /**
     * An inode whose node_crc is corrupted is skipped: the file it describes
     * does not appear.
     */
    @Test
    void inodeWithBadNodeCrcIsSkipped() throws IOException {
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "f", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inodeBadNodeCrc(2, 1, Jffs2NodeWriter.MODE_REG, A.length, 0, A));

        try (FileSystem fs = mount(image)) {
            assertThat(fs.resolve("/f")).isEmpty();
        }
    }

    /**
     * zlib-compressed data must be decompressed on read.
     */
    @Test
    void readsZlibCompressedData() throws IOException {
        byte[] plain = "zlib compressed payload".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflate(plain);
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "f", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inodeCompr(2, 1, Jffs2NodeWriter.MODE_REG, plain.length, 0,
                        plain.length, Jffs2Node.COMPR_ZLIB, compressed));

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow()).readAllBytes();
            assertThat(content).containsExactly(plain);
        }
    }

    /**
     * The ZERO compression id expands to the declared number of zero bytes
     * with no stored payload.
     */
    @Test
    void readsZeroCompressedData() throws IOException {
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "z", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inodeCompr(2, 1, Jffs2NodeWriter.MODE_REG, 64, 0,
                        64, Jffs2Node.COMPR_ZERO, new byte[0]));

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/z").orElseThrow()).readAllBytes();
            assertThat(content).hasSize(64).containsOnly((byte) 0);
        }
    }

    /**
     * The RTIME compression id is decoded with the kernel rtime algorithm.
     */
    @Test
    void readsRtimeCompressedData() throws IOException {
        byte[] plain = "AAAAABBBBB".getBytes(StandardCharsets.UTF_8);
        // Kernel rtime stream for "AAAAABBBBB" (see fs/jffs2/compr_rtime.c):
        // each pair is (verbatim byte, repeat count); the count copies from
        // the last-occurrence position of that byte value. 'B' first appears
        // with repeat 0, then 3 further copies from the position where the
        // first 'B' was written.
        byte[] compressed = new byte[] {'A', 4, 'B', 0, 'B', 3};
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "r", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inodeCompr(2, 1, Jffs2NodeWriter.MODE_REG, plain.length, 0,
                        plain.length, Jffs2Node.COMPR_RTIME, compressed));

        try (FileSystem fs = mount(image)) {
            byte[] content = ((FileSystemEntry.RegularFile) fs.resolve("/r").orElseThrow()).readAllBytes();
            assertThat(content).containsExactly(plain);
        }
    }

    /**
     * An unsupported compression id (RUBINMIPS, an obsolete algorithm) is
     * rejected with a checked IOException at read time, not silently
     * misread or allowed to crash.
     */
    @Test
    void unsupportedCompressionFailsCleanly() throws IOException {
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "f", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inodeCompr(2, 1, Jffs2NodeWriter.MODE_REG, 8, 0,
                        8, Jffs2Node.COMPR_RUBINMIPS, new byte[]{1, 2, 3, 4}));

        try (FileSystem fs = mount(image)) {
            FileSystemEntry.RegularFile file =
                    (FileSystemEntry.RegularFile) fs.resolve("/f").orElseThrow();
            assertThatThrownBy(file::readAllBytes).isInstanceOf(IOException.class);
        }
    }

    /**
     * A node whose totlen extends past the end of the region stops the scan;
     * if it is the first node, mount fails with a controlled IOException.
     */
    @Test
    void truncatedNodeFailsMountCleanly() {
        byte[] image = new byte[16];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) Jffs2Node.MAGIC);
        buf.putShort(2, (short) Jffs2Node.NODETYPE_CLEANMARKER);
        buf.putInt(4, 0x1000); // claims to be 4096 bytes long
        byte[] hdr = new byte[8];
        System.arraycopy(image, 0, hdr, 0, 8);
        int crc = (int) Jffs2NodeWriter.crc32(hdr);
        buf.putInt(8, crc);

        assertThatThrownBy(() -> Jffs2FileSystemImpl.mount(new Region(image)))
                .isInstanceOf(IOException.class);
    }

    /**
     * A dirent name containing path separators must be rejected (path
     * traversal hardening), mirroring the other Saffron filesystems.
     */
    @Test
    void pathTraversalNameIsSkipped() throws IOException {
        byte[] image = Jffs2NodeWriter.imageOf(
                Jffs2NodeWriter.cleanmarker(),
                Jffs2NodeWriter.dirent(1, 1, 2, "../evil", Jffs2NodeWriter.DT_REG),
                Jffs2NodeWriter.inode(2, 1, Jffs2NodeWriter.MODE_REG, A.length, 0, A));

        try (FileSystem fs = mount(image)) {
            assertThat(fs.resolve("/evil")).isEmpty();
            assertThat(fs.resolve("/../evil")).isEmpty();
        }
    }

    private static FileSystem mount(byte[] image) throws IOException {
        return Jffs2FileSystemImpl.mount(new Region(image));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buf = new byte[1024];
        int n = deflater.deflate(buf);
        deflater.end();
        return java.util.Arrays.copyOfRange(buf, 0, n);
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
