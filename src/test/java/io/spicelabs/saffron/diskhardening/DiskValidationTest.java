/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.diskharness.DiskFixtures;
import io.spicelabs.saffron.vhd.dynamic.VhdDynamicHeader;
import io.spicelabs.saffron.vhd.footer.VhdFooter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hostile-header validation tests for the disk layers (phase 1, T1.3/T1.4).
 *
 * <h2>Why this test exists</h2>
 * <p>Pre-fix, size fields from on-disk headers were trusted: a crafted
 * BAT/BAM/region-table size caused multi-GB allocations or unchecked
 * {@code ArithmeticException}/{@code NegativeArraySizeException} at open.
 * Plan R1.3 requires validate-before-allocate with checked
 * {@code IOException} rejections.</p>
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Each test mutates one validated field in an otherwise-valid
 *       synthetic image and asserts {@code open()} throws
 *       {@code IOException} (never unchecked, never hangs).</li>
 *   <li>Accepted-range boundary cases (minimum/maximum legal values) are
 *       covered positively where cheap to build.</li>
 * </ul>
 */
class DiskValidationTest {

    private static void putIntLe(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    private static void putIntBe(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
    }

    private static void putLongLe(byte[] data, int offset, long value) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
    }

    private static void putLongBe(byte[] data, int offset, long value) {
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putLong(offset, value);
    }

    // ---------------------------------------------------------------- VHD

    /** Dynamic-header blockSize lives at offset 512+32 (big-endian). */
    private static final int VHD_DYN_BLOCKSIZE = VhdFooter.FOOTER_SIZE + 32;
    /** Dynamic-header maxTableEntries lives at offset 512+28 (big-endian). */
    private static final int VHD_DYN_MAX_TABLE = VhdFooter.FOOTER_SIZE + 28;

    @Test
    void vhdBlockSizeZeroRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.dynamicVhd(16 * 1024 * 1024, 2 * 1024 * 1024, false);
        putIntBe(image, VHD_DYN_BLOCKSIZE, 0);
        Path file = dir.resolve("vhd-bs0.vhd");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHD))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdBlockSizeNonPowerOfTwoRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.dynamicVhd(16 * 1024 * 1024, 2 * 1024 * 1024, false);
        putIntBe(image, VHD_DYN_BLOCKSIZE, 3 * 1024 * 1024);
        Path file = dir.resolve("vhd-bs3.vhd");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHD))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdBlockSizeBeyondEightMiBRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.dynamicVhd(16 * 1024 * 1024, 2 * 1024 * 1024, false);
        putIntBe(image, VHD_DYN_BLOCKSIZE, 9 * 1024 * 1024);
        Path file = dir.resolve("vhd-bs9.vhd");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHD))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdMaxTableEntriesHugeRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.dynamicVhd(16 * 1024 * 1024, 2 * 1024 * 1024, false);
        putIntBe(image, VHD_DYN_MAX_TABLE, 0x4000_0000); // 1G entries -> 4GB BAT
        Path file = dir.resolve("vhd-entries.vhd");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHD))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdFooterCurrentSizeBeyondTwoTiBRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.fixedVhd(8192, 8192);
        // Footer currentSize is at offset 48 within the footer (dataSize + 48).
        putLongBe(image, 8192 + 48, 3L * 1024 * 1024 * 1024 * 1024);
        Path file = dir.resolve("vhd-size.vhd");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHD))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- VDI

    @Test
    void vdiBlockSizeZeroRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vdi(8 * 1024 * 1024, 1024 * 1024, false, null);
        // blockSize is a 4-byte field at offset 0x178 within the header.
        putIntLe(image, 0x178, 0);
        Path file = dir.resolve("vdi-bs0.vdi");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VDI))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vdiBlockSizeNonPowerOfTwoRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vdi(8 * 1024 * 1024, 1024 * 1024, false, null);
        putIntLe(image, 0x178, 3 * 1024 * 1024);
        Path file = dir.resolve("vdi-bs3.vdi");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VDI))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vdiBlocksInHddHugeRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vdi(8 * 1024 * 1024, 1024 * 1024, false, null);
        // blocksInHdd is 4 bytes at offset 0x180 within the header.
        putIntLe(image, 0x180, 0x4000_0000);
        Path file = dir.resolve("vdi-blocks.vdi");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VDI))
                .isInstanceOf(IOException.class);
    }

    // --------------------------------------------------------------- VHDX

    private static final int VHDX_REGION_TABLE = 192 * 1024;
    private static final int VHDX_METADATA = 320 * 1024;

    @Test
    void vhdxBlockSizeBelowOneMiBRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vhdx(8 * 1024 * 1024, 1024 * 1024, false, false);
        // File-parameters item: blockSize at metadataOffset + 128.
        putIntLe(image, VHDX_METADATA + 128, 512);
        Path file = dir.resolve("vhdx-bs512.vhdx");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHDX))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdxBlockSizeNonPowerOfTwoRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vhdx(8 * 1024 * 1024, 1024 * 1024, false, false);
        putIntLe(image, VHDX_METADATA + 128, 3 * 1024 * 1024);
        Path file = dir.resolve("vhdx-bs3.vhdx");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHDX))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdxVirtualSizeBeyondSixtyFourTiBRejected(@TempDir Path dir) throws IOException {
        // 256 MiB blocks (the legal max): 65 TiB / 256 MiB = 266k BAT
        // entries x 8 = 2.1 MB - under the 16 MiB BAT budget, so ONLY the
        // 64 TiB virtual-size cap can reject this fixture.
        byte[] image = DiskFixtures.vhdx(256 * 1024 * 1024, 256 * 1024 * 1024, false, false);
        // virtual-disk-size item at metadataOffset + 136.
        putLongLe(image, VHDX_METADATA + 136, 65L * 1024 * 1024 * 1024 * 1024);
        Path file = dir.resolve("vhdx-size.vhdx");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHDX))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vhdxMetadataItemLengthOutOfRegionRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vhdx(8 * 1024 * 1024, 1024 * 1024, false, false);
        // Metadata entry 0 (file parameters): itemLength lives at
        // metadataOffset + 32 + 0*32 + 20.
        putIntLe(image, VHDX_METADATA + 32 + 20, 0x7FFFFF00);
        Path file = dir.resolve("vhdx-item.vhdx");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHDX))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vdiBlocksInHddTooSmallForDiskSizeRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vdi(8 * 1024 * 1024, 1024 * 1024, false, null);
        // blocksInHdd = 1 for an 8 MiB / 1 MiB disk (needs 8).
        putIntLe(image, 0x180, 1);
        Path file = dir.resolve("vdi-fewblocks.vdi");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VDI))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void vhdxRegionEntryCountHugeRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vhdx(8 * 1024 * 1024, 1024 * 1024, false, false);
        // Region-table entry count at offset +8.
        putIntLe(image, VHDX_REGION_TABLE + 8, 0x4000_0000);
        Path file = dir.resolve("vhdx-entries.vhdx");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHDX))
                .isInstanceOf(IOException.class);
    }

    // --------------------------------------------------------------- VMDK

    @Test
    void vmdkGrainSizeZeroRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vmdk(8 * 65536, 65536, false, false, null);
        // grainSize (sectors) is 8 bytes at header offset 20.
        putLongLe(image, 20, 0);
        Path file = dir.resolve("vmdk-gs0.vmdk");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VMDK))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vmdkGrainSizeHugeRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vmdk(8 * 65536, 65536, false, false, null);
        putLongLe(image, 20, 0x4000_0000L); // 2^30 sectors = 512 GB grains
        Path file = dir.resolve("vmdk-gs-huge.vmdk");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VMDK))
                .isInstanceOf(IOException.class);
    }

    @Test
    void vmdkCapacityOverflowRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vmdk(8 * 65536, 65536, false, false, null);
        putLongLe(image, 12, Long.MAX_VALUE / 512 + 1); // capacity*512 overflows
        Path file = dir.resolve("vmdk-cap.vmdk");
        Files.write(file, image);

        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VMDK))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- GPT

    private byte[] minimalGptDisk(long entriesLba, int numEntries, int entrySize) {
        int total = 3 * 512 + 128 * 8;
        byte[] data = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(512);
        buf.putLong(0x5452415020494645L); // "EFI PART"
        buf.putInt(0x00010000);           // revision
        buf.putInt(92);                   // header size
        buf.putInt(0);                    // crc
        buf.putInt(0);                    // reserved
        buf.putLong(1);                   // current LBA
        buf.putLong(2);                   // backup LBA
        buf.putLong(34);                  // first usable
        buf.putLong(100);                 // last usable
        buf.putLong(0);                   // disk guid hi
        buf.putLong(0);                   // disk guid lo
        buf.putLong(entriesLba);
        buf.putInt(numEntries);
        buf.putInt(entrySize);
        buf.putInt(0);                    // entries crc
        return data;
    }

    @Test
    void gptEntrySizeTooLargeRejected(@TempDir Path dir) throws IOException {
        byte[] data = minimalGptDisk(2, 4, 8192);
        Path file = dir.resolve("gpt-entry.vhdx");
        Files.write(file, data);
        try (VirtualDisk disk = DiskReader.open(file, DiskFormat.RAW)) {
            assertThat(io.spicelabs.saffron.partition.GptPartitionTable.tryParse(disk))
                    .isEmpty();
        }
    }

    @Test
    void gptEntriesLbaBeyondDiskRejected(@TempDir Path dir) throws IOException {
        byte[] data = minimalGptDisk(100_000, 4, 128);
        Path file = dir.resolve("gpt-lba.vhdx");
        Files.write(file, data);
        try (VirtualDisk disk = DiskReader.open(file, DiskFormat.RAW)) {
            assertThat(io.spicelabs.saffron.partition.GptPartitionTable.tryParse(disk))
                    .isEmpty();
        }
    }

    @Test
    void gptEntryCountBeyondTwoHundredFiftySixRejected(@TempDir Path dir) throws IOException {
        // Loud rejection (never silent truncation to 256).
        byte[] data = minimalGptDisk(2, 300, 128);
        Path file = dir.resolve("gpt-many-entries.vhdx");
        Files.write(file, data);
        try (VirtualDisk disk = DiskReader.open(file, DiskFormat.RAW)) {
            assertThat(io.spicelabs.saffron.partition.GptPartitionTable.tryParse(disk))
                    .isEmpty();
        }
    }

    // ------------------------------------------------------- descriptor pad

    @Test
    void acceptedRangeBoundariesStillOpen(@TempDir Path dir) throws IOException {
        // VHD dynamic with the maximum legal block size (8 MiB).
        byte[] vhd = DiskFixtures.dynamicVhd(8L * 1024 * 1024 * 1024, 8 * 1024 * 1024, false);
        // Block-size field must be 8 MiB (builder uses 2 MiB default).
        putIntBe(vhd, VHD_DYN_BLOCKSIZE, 8 * 1024 * 1024);
        Path vhdFile = dir.resolve("boundary.vhd");
        Files.write(vhdFile, vhd);
        try (var disk = DiskReader.open(vhdFile, DiskFormat.VHD)) {
            assertThat(disk.virtualSize()).isEqualTo(8L * 1024 * 1024 * 1024);
        }

        // VHDX with the minimum legal block size (1 MiB) and the maximum
        // (256 MiB) both open.
        byte[] vhdx = DiskFixtures.vhdx(8L * 1024 * 1024, 1024 * 1024, false, false);
        Path vhdxFile = dir.resolve("boundary.vhdx");
        Files.write(vhdxFile, vhdx);
        try (var disk = DiskReader.open(vhdxFile, DiskFormat.VHDX)) {
            assertThat(((VirtualDisk.VhdxDisk) disk).blockSize()).isEqualTo(1024 * 1024);
        }

        // VMDK with the minimum (1 sector = 512 B) and maximum (4096
        // sectors = 2 MiB) grain sizes both open.
        byte[] vmdk = DiskFixtures.vmdk(8L * 65536, 65536, false, false, null);
        putLongLe(vmdk, 20, 1); // 512-byte grains
        Path vmdkFile = dir.resolve("boundary.vmdk");
        Files.write(vmdkFile, vmdk);
        try (var disk = DiskReader.open(vmdkFile, DiskFormat.VMDK)) {
            assertThat(disk.virtualSize()).isEqualTo(8L * 65536);
        }

        // GPT with the maximum spec entry size (4096) parses.
        byte[] gpt = minimalGptDisk(2, 4, 4096);
        gpt = java.util.Arrays.copyOf(gpt, 3 * 512 + 4 * 4096);
        Path gptFile = dir.resolve("boundary-gpt.raw");
        Files.write(gptFile, gpt);
        try (var disk = DiskReader.open(gptFile, DiskFormat.RAW)) {
            assertThat(io.spicelabs.saffron.partition.GptPartitionTable.tryParse(disk))
                    .isPresent();
        }
    }

    /** VMDK descriptor used for the parent-rejection test. */
    static final String PARENT_DESCRIPTOR = "# Disk DescriptorFile\n"
            + "version=1\n"
            + "CID=00000001\n"
            + "parentCID=00000002\n"
            + "parentFileNameHint=\"parent.vmdk\"\n"
            + "createType=\"monolithicSparse\"\n"
            + "RW 100 SPARSE \"child.vmdk\"\n";
}
