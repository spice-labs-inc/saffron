/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for squashfs filesystem detection.
 */
class SquashfsDetectionTest {

    /**
     * A buffer that starts with the squashfs magic {@code hsqs} and has a valid
     * superblock should be detected as SQUASHFS.
     */
    @Test
    void detectsMagic() throws IOException {
        byte[] image = createValidSuperblock(4096);
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(region);

        assertThat(info).isPresent();
        assertThat(info.get().type()).isEqualTo(FileSystemType.SQUASHFS);
        assertThat(info.get().version()).isEqualTo("4.0");
    }

    /**
     * Random bytes should not be detected as a squashfs filesystem.
     */
    @Test
    void rejectsRandomData() throws IOException {
        byte[] image = new byte[4096];
        new Random(42).nextBytes(image);
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(region);

        assertThat(info).isEmpty();
    }

    /**
     * A buffer that only contains the magic bytes is too small to hold a
     * superblock and must not be detected as mountable.
     */
    @Test
    void rejectsMagicOnlyBuffer() throws IOException {
        byte[] image = new byte[] {0x68, 0x73, 0x71, 0x73};
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(region);

        assertThat(info).isEmpty();
    }

    private byte[] createValidSuperblock(int size) {
        byte[] image = new byte[size];
        ByteBuffer buf = ByteBuffer.wrap(image);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, SquashfsSuperblock.SQUASHFS_MAGIC);
        buf.putInt(4, 1);
        buf.putInt(8, 0);
        buf.putInt(0x0c, 4096);
        buf.putInt(0x10, 0);
        buf.putShort(0x14, (short) 4);
        buf.putShort(0x16, (short) 12);
        buf.putShort(0x18, (short) 0);
        buf.putShort(0x1a, (short) 1);
        buf.putShort(0x1c, (short) 4);
        buf.putShort(0x1e, (short) 0);
        buf.putLong(0x20, 0L);
        buf.putLong(0x28, size);
        buf.putLong(0x30, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x38, 0xffffffffffffffffL);
        buf.putLong(0x40, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x48, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x50, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x58, 0xffffffffffffffffL);
        return image;
    }

    private static final class ByteArrayDiskRegion implements DiskRegion {
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
