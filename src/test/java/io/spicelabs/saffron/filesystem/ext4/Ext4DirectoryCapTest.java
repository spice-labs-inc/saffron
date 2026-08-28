/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ext4;

import io.spicelabs.saffron.exception.ResourceLimitException;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R5.2 boundary test for the ext4 directory-read cap: a directory inode's
 * claimed size of at most 16 MiB is accepted (and reads its — here empty —
 * data), while a size of 16 MiB + 1 is rejected with a checked
 * {@link ResourceLimitException} at list time, after the filesystem has
 * mounted.
 *
 * <h2>LLM section</h2>
 * <p>Builds a synthetic 8-block ext4 image (block size 1024): superblock at
 * 1024 with magic 0xEF53, one block group whose descriptor at block 2 points
 * to an inode table at block 3; inode 2 (the root directory) sits at
 * 3*1024 + 1*128. Only the low 32 bits of the inode size are set, which is
 * sufficient because the hostile value fits in 32 bits and the driver reads
 * the low word. All block pointers are zero, so readInodeData() zero-fills
 * without I/O and parseBlock() sees rec_len=0 and yields an empty listing.
 * If the {@code dirInode.size() > MAX_READABLE_SIZE} guard were removed,
 * the cap+1 case would read 16 MiB + 1 of zeros and NOT throw, making this
 * test fail — i.e. it is red against the guard's removal.</p>
 */
class Ext4DirectoryCapTest {

    private static final int BLOCK = 1024;
    private static final long CAP = 16L * 1024 * 1024;

    private static final class ByteArrayRegion implements DiskRegion {
        private final byte[] image;

        ByteArrayRegion(byte[] image) {
            this.image = image;
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            byte[] out = new byte[length];
            System.arraycopy(image, (int) offset, out, 0, length);
            return ByteBuffer.wrap(out);
        }

        @Override
        public long size() {
            return image.length;
        }
    }

    /** Synthetic 8-block ext4 image whose root directory (inode 2) claims {@code rootDirSize} bytes. */
    private static byte[] ext4Image(long rootDirSize) {
        byte[] img = new byte[8 * BLOCK];
        ByteBuffer b = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN);
        int sb = 1024;
        b.putInt(sb, 64);                        // inodeCount
        b.putInt(sb + 4, 64);                    // blockCount
        b.putInt(sb + 24, 0);                    // blockSizeShift -> 1024-byte blocks
        b.putInt(sb + 32, 64);                   // blocksPerGroup
        b.putInt(sb + 40, 64);                   // inodesPerGroup
        b.putShort(sb + 56, (short) 0xEF53);     // magic
        b.putShort(sb + 88, (short) 128);        // inodeSize
        b.putInt(2048 + 8, 3);                   // bgd block 2: inodeTable = block 3
        int ino = 3 * BLOCK + 1 * 128;           // inode 2 -> index 1
        b.putShort(ino, (short) 0x41ED);         // mode: directory
        b.putInt(ino + 4, (int) rootDirSize);    // sizeLo
        return img;
    }

    private static Ext4FileSystemImpl mount(long rootDirSize) throws IOException {
        return Ext4FileSystemImpl.mount(new ByteArrayRegion(ext4Image(rootDirSize)));
    }

    @Test
    void dirSizesUpToTheCapAreAccepted() throws IOException {
        for (long size : new long[]{0, CAP - 1, CAP}) {
            Ext4FileSystemImpl fs = mount(size);
            assertThatCode(() -> fs.root().list()).doesNotThrowAnyException();
        }
    }

    @Test
    void dirSizeAboveTheCapIsRejectedChecked() throws IOException {
        Ext4FileSystemImpl fs = mount(CAP + 1);
        assertThat(fs.root()).isNotNull();
        assertThatThrownBy(() -> fs.root().list())
                .isInstanceOf(ResourceLimitException.class)
                .hasMessageContaining("16 MB");
    }
}
