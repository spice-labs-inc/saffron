/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ext4;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Defensive parsing tests for {@link Ext4FileSystemImpl}.
 *
 * <p>These tests guard the invariant that malformed, truncated, or misdetected
 * data (for example a WAR/ZIP that merely happens to look like an ext4
 * filesystem) must never crash the parser or propagate an exception to the
 * Saffron API caller. Instead, {@link Ext4FileSystemImpl#tryMount(DiskRegion)}
 * reports such input as "not mountable" via {@link Optional#empty()}, and the
 * legacy {@link Ext4FileSystemImpl#mount(DiskRegion)} surfaces a controlled
 * {@link IOException} rather than an {@link NullPointerException}.
 */
class Ext4DefensiveParsingTest {

    /** ext4 superblock magic 0xEF53. */
    private static final short EXT4_MAGIC = (short) 0xEF53;

    /** Byte offset of the ext4 superblock within the filesystem. */
    private static final int SB = Ext4Superblock.SUPERBLOCK_OFFSET; // 1024

    /**
     * Random bytes (no magic) must not mount and must yield an empty Optional
     * rather than throwing.
     */
    @Test
    void tryMountRejectsGarbageData() {
        byte[] image = new byte[4096];
        new Random(42).nextBytes(image);
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<Ext4FileSystemImpl> mounted = Ext4FileSystemImpl.tryMount(region);

        assertThat(mounted).isEmpty();
    }

    /**
     * A region that is too small to even hold a full superblock must not mount.
     * This covers the case where detection matched magic bytes at a location
     * that is not a real superblock.
     */
    @Test
    void tryMountRejectsRegionSmallerThanSuperblock() {
        byte[] image = new byte[512];
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<Ext4FileSystemImpl> mounted = Ext4FileSystemImpl.tryMount(region);

        assertThat(mounted).isEmpty();
    }

    /**
     * A valid superblock magic but zero/implausible geometry (e.g. a WAR/ZIP
     * containing bytes that coincide with 0xEF53) must be rejected rather than
     * mounting a nonsense filesystem that would crash during walk.
     */
    @Test
    void tryMountRejectsAbsurdBlockGroupGeometry() {
        // inodes_per_group = 0 and blocks_per_group = 0 make the block-group
        // count degenerate and the inode addressing arithmetic undefined.
        byte[] image = new byte[4096];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        writeSuperblockHeader(buf);
        buf.putInt(SB + 32, 0);   // blocks_per_group = 0
        buf.putInt(SB + 40, 0);   // inodes_per_group = 0
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<Ext4FileSystemImpl> mounted = Ext4FileSystemImpl.tryMount(region);

        assertThat(mounted).isEmpty();
    }

    /**
     * A superblock whose block-group descriptor table extends past the end of
     * the region (truncated data) must be rejected up front instead of
     * producing null descriptors that later NPE.
     */
    @Test
    void tryMountRejectsDescriptorTableBeyondRegion() {
        // Large block_count with small blocks_per_group means a huge number of
        // block groups, whose descriptor table cannot fit in the small region.
        byte[] image = new byte[4096];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        writeSuperblockHeader(buf);
        buf.putInt(SB + 4, 1_000_000_000); // block_count -> enormous group count
        buf.putInt(SB + 32, 32);           // blocks_per_group (small -> many groups)
        buf.putInt(SB + 40, 32);           // inodes_per_group
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<Ext4FileSystemImpl> mounted = Ext4FileSystemImpl.tryMount(region);

        assertThat(mounted).isEmpty();
    }

    /**
     * A superblock whose root inode (inode 2) table address points beyond the
     * end of the region must be rejected eagerly, preventing a crash during
     * {@code root()}/{@code walk()}. This is the exact failure mode observed
     * for the misdetected WAR.
     */
    @Test
    void tryMountRejectsRootInodeOutOfBounds() {
        byte[] image = new byte[4096];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        writeSuperblockHeader(buf);
        buf.putInt(SB + 4, 8);    // block_count = 8
        buf.putInt(SB + 32, 8);   // blocks_per_group = 8 -> 1 block group
        buf.putInt(SB + 40, 32);  // inodes_per_group = 32
        buf.putShort(SB + 88, (short) 128); // inode_size

        // Block group descriptor table is at block 2 (offset 2048) for a 1024
        // byte block size. Point the group 0 inode table at block 1000, far
        // beyond the 4096-byte region (1000*1024 = 1024000 > 4096).
        buf.putInt(2 * 1024 + 8, 1000); // bg_inode_table_lo = 1000
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<Ext4FileSystemImpl> mounted = Ext4FileSystemImpl.tryMount(region);

        assertThat(mounted).isEmpty();
    }

    /**
     * The legacy {@code mount(DiskRegion)} must throw a controlled, documented
     * {@link IOException} for invalid data rather than propagating an
     * {@link NullPointerException} or other unchecked exception.
     */
    @Test
    void mountThrowsControlledIOExceptionForGarbage() {
        byte[] image = new byte[4096];
        new Random(7).nextBytes(image);
        DiskRegion region = new ByteArrayDiskRegion(image);

        assertThatThrownBy(() -> Ext4FileSystemImpl.mount(region))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ext4");
    }

    /**
     * A minimal but self-consistent ext4 layout must still mount successfully
     * via {@code tryMount}, proving the defensive validation does not reject
     * genuine filesystems.
     */
    @Test
    void tryMountAcceptsCoherentMinimalLayout() {
        int blockSize = 1024;
        int blocksPerGroup = 8;
        int inodesPerGroup = 32;
        int inodeSize = 128;
        int descTableOffset = 2 * blockSize;    // block 2 for 1024-byte blocks
        int inodeTableBlock = 3;                 // group 0 inode table at block 3
        int regionSize = 4096;                   // 4 blocks

        byte[] image = new byte[regionSize];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        writeSuperblockHeader(buf);
        buf.putInt(SB + 4, blocksPerGroup);    // block_count = 8
        buf.putInt(SB + 32, blocksPerGroup);   // blocks_per_group
        buf.putInt(SB + 40, inodesPerGroup);   // inodes_per_group
        buf.putShort(SB + 88, (short) inodeSize); // inode_size

        // Block group 0 descriptor: block_bitmap=1, inode_bitmap=2, inode_table=3
        buf.putInt(descTableOffset + 0, 1);
        buf.putInt(descTableOffset + 4, 2);
        buf.putInt(descTableOffset + 8, inodeTableBlock);
        DiskRegion region = new ByteArrayDiskRegion(image);

        Optional<Ext4FileSystemImpl> mounted = Ext4FileSystemImpl.tryMount(region);

        assertThat(mounted).isPresent();
    }

    /**
     * Writes the fields shared by every test superblock: magic and the sizes
     * used by the geometry computations. Callers then override geometry.
     */
    private static void writeSuperblockHeader(ByteBuffer buf) {
        buf.putShort(SB + 56, EXT4_MAGIC);
        // block_size = 1024 << shift; leave shift (offset 24) at default 0.
        buf.putInt(SB + 4, 8);    // block_count (overridden by callers as needed)
        buf.putInt(SB + 32, 8);   // blocks_per_group
        buf.putInt(SB + 40, 32);  // inodes_per_group
        buf.putShort(SB + 88, (short) 128); // inode_size
    }

    /** In-memory DiskRegion backed by a byte array (mirrors the squashfs tests). */
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