/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.squashfs;

import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for rejecting superblocks with impossible size/count values.
 */
class SquashfsOverflowTest {

    /**
     * A superblock whose inode count exceeds the declared filesystem size must
     * not be accepted as mountable.
     */
    @Test
    void rejectsBadInodeCount() throws IOException {
        byte[] image = createSuperblockWithBadInodeCount();
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(region);

        assertThat(info).isEmpty();
    }

    private byte[] createSuperblockWithBadInodeCount() {
        byte[] image = new byte[4096];
        ByteBuffer buf = ByteBuffer.wrap(image);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, SquashfsSuperblock.SQUASHFS_MAGIC);
        buf.putInt(4, Integer.MAX_VALUE);
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
        buf.putLong(0x28, image.length);
        buf.putLong(0x30, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x38, 0xffffffffffffffffL);
        buf.putLong(0x40, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x48, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x50, SquashfsSuperblock.SUPERBLOCK_SIZE);
        buf.putLong(0x58, 0xffffffffffffffffL);
        return image;
    }

    /**
     * A superblock whose major/minor version is not 4.0 must not be accepted as
     * mountable.
     */
    @Test
    void rejectsBadVersion() throws IOException {
        byte[] image = createSuperblockWithBadVersion();
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<FilesystemInfo> info = FilesystemDetector.detect(region);

        assertThat(info).isEmpty();
    }

    private byte[] createSuperblockWithBadVersion() {
        byte[] image = new byte[4096];
        ByteBuffer buf = ByteBuffer.wrap(image);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, SquashfsSuperblock.SQUASHFS_MAGIC);
        buf.putInt(4, 1); // inode count
        buf.putInt(8, 0); // creation time
        buf.putInt(0x0c, 4096); // block size
        buf.putInt(0x10, 0); // fragment count
        buf.putShort(0x14, (short) 3); // major version != 4
        buf.putShort(0x16, (short) 0); // minor version
        buf.putShort(0x18, (short) 0);
        buf.putShort(0x1a, (short) 1);
        buf.putShort(0x1c, (short) 4);
        buf.putShort(0x1e, (short) 0);
        buf.putLong(0x20, 0L);
        buf.putLong(0x28, image.length);
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
