/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.jffs2;

import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.Random;

import static io.spicelabs.saffron.filesystem.jffs2.Jffs2NodeWriter.crcBytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JFFS2 filesystem detection.
 *
 * <p>JFFS2 has no superblock; a filesystem is recognized by a well-formed
 * node header at offset 0: magic {@code 0x1985}, a known node type, a total
 * length that is a non-negative multiple of 4, and a valid header CRC.
 */
class Jffs2DetectionTest {

    private static final String FIXTURE_DIR = "src/test/resources/jffs2/fixtures";

    /**
     * Images produced by the reference {@code mkfs.jffs2} tool must be
     * detected as JFFS2 regardless of the compression algorithm used for
     * the file data.
     */
    @ParameterizedTest(name = "fixture={0}")
    @ValueSource(strings = {"tree-none.jffs2", "tree-zlib.jffs2", "tree-lzo.jffs2",
            "tree-rtime.jffs2", "tree-none-noclean.jffs2"})
    void detectsReferenceFixtures(String name) throws IOException {
        byte[] image = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(FIXTURE_DIR, name));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).as("detected %s", name).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.JFFS2);
        assertThat(info.get().version()).isEqualTo("jffs2");
    }

    /**
     * An image with no nodes at all (mkfs.jffs2 on an empty directory produces
     * a zero-length image) must not be detected.
     */
    @Test
    void rejectsEmptyImage() throws IOException {
        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(new byte[0]));

        assertThat(info).isEmpty();
    }

    /**
     * Random bytes must not be detected as JFFS2.
     */
    @Test
    void rejectsRandomData() throws IOException {
        byte[] image = new byte[4096];
        new Random(42).nextBytes(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A prefix shorter than the 12-byte node header must not be detected.
     */
    @Test
    void rejectsTruncatedHeader() throws IOException {
        byte[] image = new byte[8];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) Jffs2Node.MAGIC);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A node with the legacy magic 0x1984 must not be detected as JFFS2.
     */
    @Test
    void rejectsOldMagic() throws IOException {
        byte[] image = Jffs2NodeWriter.cleanmarker();
        image[0] = (byte) 0x84;
        image[1] = 0x19;

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A node with the correct magic but an unknown node type must not be
     * detected: the magic alone is not enough evidence.
     */
    @Test
    void rejectsUnknownNodeType() throws IOException {
        byte[] image = Jffs2NodeWriter.cleanmarker();
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(2, (short) 0x7777);
        buf.put(8, crcBytes(image)[0]);
        // header CRC is now invalid, but the node type check runs first and
        // must reject regardless.

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A node whose declared total length is not a multiple of 4 IS valid
     * JFFS2: real mkfs.jffs2 images store the true node length and pad the
     * node body to the next 4-byte boundary with 0xFF. Detection must
     * accept such nodes (advancement rounds up, it does not reject).
     */
    @Test
    void acceptsUnalignedTotlen() throws IOException {
        byte[] image = new byte[20];
        ByteBuffer img = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        img.putShort(0, (short) Jffs2Node.MAGIC);
        img.putShort(2, (short) Jffs2Node.NODETYPE_PADDING);
        img.putInt(4, 17); // unaligned, like a real 5-byte-name dirent
        byte[] first8 = new byte[]{(byte) 0x85, 0x19, 0x04, 0x20, 17, 0, 0, 0};
        img.putInt(8, (int) Jffs2NodeWriter.crc32(first8));

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.JFFS2);
    }

    /**
     * A node whose header CRC does not match its header bytes must not be
     * detected.
     */
    @Test
    void rejectsBadHeaderCrc() throws IOException {
        byte[] image = Jffs2NodeWriter.corruptHdrCrc(Jffs2NodeWriter.cleanmarker());

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isEmpty();
    }

    /**
     * A well-formed cleanmarker node alone (the simplest possible image) is
     * detected: the cleanmarker is a valid JFFS2 node.
     */
    @Test
    void detectsLoneCleanmarker() throws IOException {
        byte[] image = Jffs2NodeWriter.cleanmarker();

        Optional<FilesystemInfo> info = FilesystemDetector.detect(new ByteArrayDiskRegion(image));

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.JFFS2);
    }

    static final class ByteArrayDiskRegion implements DiskRegion {
        private final byte[] data;

        ByteArrayDiskRegion(byte[] data) {
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
