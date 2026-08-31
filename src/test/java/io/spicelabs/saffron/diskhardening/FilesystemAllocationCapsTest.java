/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.filesystem.apfs.ApfsContainerSuperblock;
import io.spicelabs.saffron.filesystem.btrfs.BtrfsSuperblock;
import io.spicelabs.saffron.filesystem.exfat.ExFatBootSector;
import io.spicelabs.saffron.filesystem.fat32.FatBootSector;
import io.spicelabs.saffron.filesystem.hfsplus.HfsPlusBTreeNode;
import io.spicelabs.saffron.filesystem.hfsplus.HfsPlusVolumeHeader;
import io.spicelabs.saffron.filesystem.ntfs.NtfsBootSector;
import io.spicelabs.saffron.filesystem.xfs.XfsSuperblock;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Metadata-validation tests for the filesystem drivers (phase 3, T3.1/T3.2).
 *
 * <h2>Why this test exists</h2>
 * <p>Pre-fix, superblock/BPB size fields (btrfs nodeSize, APFS blockSize,
 * XFS blockSize, HFS+ blockSize, FAT/exFAT/NTFS BPB fields) were trusted:
 * a crafted image could drive huge or overflowed allocations at mount.
 * Plan R3.1–R3.4/R3.6/R3.9 require validate-before-allocate with checked
 * {@code IOException} rejections and accepted-range boundaries.</p>
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Each test crafts the minimal valid on-disk structure for one
 *       parser, mutates one field, and asserts the parser rejects it —
 *       and that the boundary values still parse.</li>
 *   <li>Parsers are exercised directly over an in-memory
 *       {@code DiskRegion}, so no full image fixtures are needed.</li>
 * </ul>
 */
class FilesystemAllocationCapsTest {

    /** In-memory region over a byte array. */
    private static DiskRegion region(byte[] data) {
        return new DiskRegion() {
            @Override
            public ByteBuffer read(long offset, int length) throws IOException {
                if (offset < 0 || offset + length > data.length) {
                    throw new IOException("region read out of bounds");
                }
                byte[] out = new byte[length];
                System.arraycopy(data, (int) offset, out, 0, length);
                return ByteBuffer.wrap(out);
            }

            @Override
            public long size() {
                return data.length;
            }
        };
    }

    // -------------------------------------------------------------- btrfs

    /** Btrfs superblock lives at offset 65536; magic at +64, nodeSize at +148. */
    private static byte[] btrfsSuperblock(int nodeSize) {
        byte[] data = new byte[65536 + 4096];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(65536 + 64);
        buf.put("_BHRfS_M".getBytes());
        buf.position(65536 + 144);
        buf.putInt(4096); // sectorSize
        buf.putInt(nodeSize);
        return data;
    }

    @Test
    void btrfsNodeSizeValidation() throws IOException {
        assertThat(BtrfsSuperblock.read(region(btrfsSuperblock(16384)), 0)).isNotNull();
        assertThat(BtrfsSuperblock.read(region(btrfsSuperblock(4096)), 0)).isNotNull();
        assertThat(BtrfsSuperblock.read(region(btrfsSuperblock(1024 * 1024)), 0)).isNotNull();

        assertThatThrownBy(() -> BtrfsSuperblock.read(region(btrfsSuperblock(3 * 1024 * 1024)), 0))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> BtrfsSuperblock.read(region(btrfsSuperblock(4095)), 0))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> BtrfsSuperblock.read(region(btrfsSuperblock(1024 * 1024 + 1)), 0))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- apfs

    /** APFS container superblock: magic at 32, blockSize at 36. */
    private static byte[] apfsSuperblock(int blockSize) {
        byte[] data = new byte[4096];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(32, 0x4253584E);
        buf.putInt(36, blockSize);
        return data;
    }

    @Test
    void apfsBlockSizeValidatedAtMount() throws IOException {
        assertThat(ApfsContainerSuperblock.read(region(apfsSuperblock(4096)))).isNotNull();
        assertThat(ApfsContainerSuperblock.read(region(apfsSuperblock(512)))).isNotNull();

        assertThatThrownBy(() -> ApfsContainerSuperblock.read(region(apfsSuperblock(0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ApfsContainerSuperblock.read(region(apfsSuperblock(3 * 1024))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ApfsContainerSuperblock.read(region(apfsSuperblock(128 * 1024 * 1024))))
                .isInstanceOf(IOException.class);
    }

    // ----------------------------------------------------------------- xfs

    /** XFS superblock: magic at 0 (BE), blockSize at 4. */
    private static byte[] xfsSuperblock(int blockSize) {
        byte[] data = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0, 0x58465342);
        buf.putInt(4, blockSize);
        return data;
    }

    @Test
    void xfsBlockSizeValidation() throws IOException {
        assertThat(XfsSuperblock.read(region(xfsSuperblock(4096)))).isNotNull();
        assertThat(XfsSuperblock.read(region(xfsSuperblock(65536)))).isNotNull();

        assertThatThrownBy(() -> XfsSuperblock.read(region(xfsSuperblock(0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> XfsSuperblock.read(region(xfsSuperblock(3 * 1024))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> XfsSuperblock.read(region(xfsSuperblock(128 * 1024))))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- hfs+

    /** HFS+ volume header at offset 1024: signature at 0, blockSize at 40. */
    private static byte[] hfsPlusVolumeHeader(int blockSize) {
        byte[] data = new byte[1024 + 512];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(1024, (short) 0x482B);
        buf.putInt(1024 + 40, blockSize);
        return data;
    }

    @Test
    void hfsPlusBlockSizeValidation() throws IOException {
        assertThat(HfsPlusVolumeHeader.read(region(hfsPlusVolumeHeader(4096)))).isNotNull();
        assertThat(HfsPlusVolumeHeader.read(region(hfsPlusVolumeHeader(4 * 1024 * 1024)))).isNotNull();

        assertThatThrownBy(() -> HfsPlusVolumeHeader.read(region(hfsPlusVolumeHeader(0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> HfsPlusVolumeHeader.read(region(hfsPlusVolumeHeader(3 * 1024))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> HfsPlusVolumeHeader.read(region(hfsPlusVolumeHeader(8 * 1024 * 1024))))
                .isInstanceOf(IOException.class);
    }

    @Test
    void hfsPlusBTreeNodeImplausibleRecordCountRejected() {
        // 512-byte node declaring 400 records cannot fit its offset table.
        byte[] node = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(node).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(10, (short) 400);
        assertThatThrownBy(() -> HfsPlusBTreeNode.parse(node, 512))
                .isInstanceOf(IOException.class);
    }

    // ----------------------------------------------------------------- fat

    /** FAT boot sector: jump at 0, BPB at 11/13, signature at 510. */
    private static byte[] fatBootSector(int bytesPerSector, int sectorsPerCluster) {
        byte[] data = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0xEB);
        buf.putShort(11, (short) bytesPerSector);
        buf.put(13, (byte) sectorsPerCluster);
        buf.putShort(510, (short) 0xAA55);
        return data;
    }

    @Test
    void fatBpbValidation() throws IOException {
        assertThat(FatBootSector.read(region(fatBootSector(512, 8)))).isNotNull();
        assertThat(FatBootSector.read(region(fatBootSector(4096, 128)))).isNotNull();

        assertThatThrownBy(() -> FatBootSector.read(region(fatBootSector(0, 8))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> FatBootSector.read(region(fatBootSector(1000, 8))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> FatBootSector.read(region(fatBootSector(512, 0))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> FatBootSector.read(region(fatBootSector(512, 3))))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- ntfs

    /** NTFS boot sector: OEM at 3, BPB at 11/13, clustersPerMftRecord at 64. */
    private static byte[] ntfsBootSector(int bytesPerSector, int sectorsPerCluster,
                                         int clustersPerMftRecord) {
        byte[] data = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] oem = "NTFS    ".getBytes();
        System.arraycopy(oem, 0, data, 3, 8);
        buf.putShort(11, (short) bytesPerSector);
        buf.put(13, (byte) sectorsPerCluster);
        buf.put(64, (byte) clustersPerMftRecord);
        return data;
    }

    @Test
    void ntfsBpbAndMftRecordSizeValidation() throws IOException {
        assertThat(NtfsBootSector.read(region(ntfsBootSector(512, 8, -10)))).isNotNull();

        assertThatThrownBy(() -> NtfsBootSector.read(region(ntfsBootSector(0, 8, -10))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NtfsBootSector.read(region(ntfsBootSector(1000, 8, -10))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NtfsBootSector.read(region(ntfsBootSector(512, 0, -10))))
                .isInstanceOf(IOException.class);
        // clustersPerMftRecord = -127 -> 2^127 byte records: implausible.
        assertThatThrownBy(() -> NtfsBootSector.read(region(ntfsBootSector(512, 8, -127))))
                .isInstanceOf(IOException.class);
    }

    // --------------------------------------------------------------- exfat

    /** exFAT boot sector: jump at 0, "EXFAT   " at 3, shifts at 108/109. */
    private static byte[] exfatBootSector(int bytesPerSectorShift, int sectorsPerClusterShift) {
        byte[] data = new byte[512];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0xEB);
        byte[] name = "EXFAT   ".getBytes();
        System.arraycopy(name, 0, data, 3, 8);
        buf.put(108, (byte) bytesPerSectorShift);
        buf.put(109, (byte) sectorsPerClusterShift);
        buf.putShort(510, (short) 0xAA55);
        return data;
    }

    @Test
    void exfatBpbValidation() throws IOException {
        assertThat(ExFatBootSector.read(region(exfatBootSector(9, 4)))).isNotNull();

        assertThatThrownBy(() -> ExFatBootSector.read(region(exfatBootSector(8, 4))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ExFatBootSector.read(region(exfatBootSector(13, 4))))
                .isInstanceOf(IOException.class);
        // 2^9 bytes/sector * 2^15 sectors/cluster = 16 MiB + beyond budget.
        assertThatThrownBy(() -> ExFatBootSector.read(region(exfatBootSector(9, 16))))
                .isInstanceOf(IOException.class);
    }
}
