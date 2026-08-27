/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskharness;

import io.spicelabs.saffron.vhd.dynamic.VhdDynamicHeader;
import io.spicelabs.saffron.vhd.footer.VhdFooter;
import io.spicelabs.saffron.vdi.header.VdiHeader;
import io.spicelabs.saffron.vmdk.sparse.SparseExtentHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Builders for minimal synthetic disk images used by the disk-hardening
 * tests (phase 1). Each image is crafted so its layout is understood by
 * the real parsers; allocated regions carry a per-offset pattern so
 * wrong-offset reads are detectable.
 *
 * <h2>LLM section</h2>
 * <p>Layouts mirror the on-disk specs (VHD/VHDX/VDI/VMDK/QCOW2). Data
 * regions are filled with {@code pattern(offset)} bytes so any read from
 * the wrong physical location fails content comparison.</p>
 */
public final class DiskFixtures {

    public static final int SECTOR = 512;

    private DiskFixtures() {
    }

    /** Deterministic per-offset byte pattern. */
    public static byte pattern(long offset) {
        return (byte) (0x21 ^ (offset * 31 + (offset >> 8)));
    }

    public static void fill(byte[] target, int start, int length) {
        for (int i = 0; i < length; i++) {
            target[start + i] = pattern(i);
        }
    }

    /** Truncates an existing file (open a write channel independently). */
    public static void truncate(Path file, long newSize) throws java.io.IOException {
        try (var ch = java.nio.file.Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            ch.truncate(newSize);
        }
    }

    // ---------------------------------------------------------------- VHD

    /**
     * Minimal fixed VHD: {@code dataSize} data bytes + 512-byte footer.
     */
    public static byte[] fixedVhd(long virtualSize, int dataSize) {
        byte[] data = new byte[dataSize + VhdFooter.FOOTER_SIZE];
        fill(data, 0, dataSize);
        writeFooter(data, dataSize, virtualSize, VhdFooter.DiskType.FIXED, true);
        return data;
    }

    /**
     * Minimal dynamic VHD: footer copy + dynamic header + BAT + one
     * allocated block (with pattern data) + footer.
     */
    public static byte[] dynamicVhd(long virtualSize, int blockSize, boolean allocateFirstBlock) {
        int bitmapSize = blockBitmapSize(blockSize);
        int entries = 4; // enough for the first block, small table
        long tableOffset = VhdFooter.FOOTER_SIZE + VhdDynamicHeader.HEADER_SIZE;
        long dataStart = tableOffset + entries * 4L;
        dataStart = ((dataStart + 511) / 512) * 512; // block start must be sector-aligned
        int totalSize = (int) dataStart + (allocateFirstBlock ? bitmapSize + blockSize : 0)
                + VhdFooter.FOOTER_SIZE;
        byte[] data = new byte[totalSize];

        // Footer copy at offset 0
        writeFooter(data, 0, virtualSize, VhdFooter.DiskType.DYNAMIC, false);

        // Dynamic header at 512
        ByteBuffer header = ByteBuffer.wrap(data, VhdFooter.FOOTER_SIZE,
                VhdDynamicHeader.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        header.put(VhdDynamicHeader.MAGIC);
        header.putLong(0xFFFFFFFFFFFFFFFFL); // data offset
        header.putLong(tableOffset);
        header.putInt(0x00010000);           // header version
        header.putInt(entries);
        header.putInt(blockSize);
        header.putInt(0);                    // checksum
        header.putLong(0);                   // parent unique id hi
        header.putLong(0);                   // parent unique id lo
        header.putInt(0);                    // parent timestamp
        header.putInt(0);                    // reserved
        header.put(new byte[512]);           // parent unicode name (all zero)
        header.put(new byte[VhdDynamicHeader.HEADER_SIZE - 576]); // locators + reserved

        // BAT: first entry allocated, rest unused
        ByteBuffer bat = ByteBuffer.wrap(data, (int) tableOffset, entries * 4)
                .order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < entries; i++) {
            if (i == 0 && allocateFirstBlock) {
                bat.putInt((int) (dataStart / 512));
            } else {
                bat.putInt(VhdDynamicHeader.BAT_ENTRY_UNUSED);
            }
        }

        if (allocateFirstBlock) {
            // Block bitmap (all ones = allocated) + block data
            for (int i = 0; i < bitmapSize; i++) {
                data[(int) dataStart + i] = (byte) 0xFF;
            }
            fill(data, (int) dataStart + bitmapSize, blockSize);
        }

        // Footer at end
        writeFooter(data, totalSize - VhdFooter.FOOTER_SIZE, virtualSize,
                VhdFooter.DiskType.DYNAMIC, true);
        return data;
    }

    /** Minimal differencing VHD: dynamic layout with a parent name. */
    public static byte[] differencingVhd(long virtualSize, int blockSize) {
        byte[] data = dynamicVhd(virtualSize, blockSize, false);
        ByteBuffer footer = ByteBuffer.wrap(data, data.length - VhdFooter.FOOTER_SIZE,
                VhdFooter.FOOTER_SIZE).order(ByteOrder.BIG_ENDIAN);
        // Only the disk type field needs changing; rewrite it in place.
        writeFooter(data, data.length - VhdFooter.FOOTER_SIZE, virtualSize,
                VhdFooter.DiskType.DIFFERENCING, true);
        // Set parent name in the dynamic header copy at offset 0
        ByteBuffer header = ByteBuffer.wrap(data, VhdFooter.FOOTER_SIZE,
                VhdDynamicHeader.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        header.position(576);
        byte[] parent = "parent.vhd".getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        header.put(parent);
        return data;
    }

    private static void writeFooter(byte[] data, int offset, long virtualSize,
                                    VhdFooter.DiskType type, boolean atEnd) {
        ByteBuffer footer = ByteBuffer.wrap(data, offset, VhdFooter.FOOTER_SIZE);
        footer.order(ByteOrder.BIG_ENDIAN);
        footer.put("conectix".getBytes());
        footer.putInt(0x00000002);
        footer.putInt(0x00010000);
        footer.putLong(type == VhdFooter.DiskType.FIXED ? 0xFFFFFFFFFFFFFFFFL : 512);
        footer.putInt(0);
        footer.put("test".getBytes());
        footer.putInt(0x00010000);
        footer.put("Wi2k".getBytes());
        footer.putLong(virtualSize);
        footer.putLong(virtualSize);
        int cylinders = (int) Math.min(virtualSize / (16 * 63 * 512), 65535);
        footer.putShort((short) cylinders);
        footer.put((byte) 16);
        footer.put((byte) 63);
        footer.putInt(type.value());
        footer.putInt(0);
        footer.putLong(System.currentTimeMillis());
        footer.putLong(System.nanoTime());
        footer.put((byte) 0);
    }

    private static int blockBitmapSize(int blockSize) {
        int bits = blockSize / 512;
        int bytes = (bits + 7) / 8;
        return ((bytes + 511) / 512) * 512;
    }

    /** Physical offset of block data in {@link #dynamicVhd} (entries = 4). */
    public static long vhdDynamicDataStart(int blockSize, int entries) {
        long start = VhdFooter.FOOTER_SIZE + VhdDynamicHeader.HEADER_SIZE + entries * 4L;
        return ((start + 511) / 512) * 512;
    }

    /** Physical offset of block data in {@link #vhdx}. */
    public static long vhdxDataStart(long virtualSize, int blockSize, boolean allocated) {
        int totalBlocks = (int) ((virtualSize + blockSize - 1) / blockSize);
        long batLength = totalBlocks * 8L;
        long oneMb = 1024 * 1024;
        return ((512 * 1024L + batLength + oneMb - 1) / oneMb) * oneMb;
    }

    /** Physical offset of block data in {@link #vdi}. */
    public static long vdiDataOffset(long virtualSize, int blockSize) {
        int numBlocks = (int) ((virtualSize + blockSize - 1) / blockSize);
        int bamSize = numBlocks * 4;
        int dataOffset = VdiHeader.MIN_HEADER_SIZE + bamSize;
        return ((dataOffset + 511) / 512) * 512;
    }

    /** Physical offset of grain data in {@link #vmdk} (gd entries = 4). */
    public static long vmdkGrainDataStart(int grainSizeBytes) {
        int gdEntries = 4;
        int gdSize = align512(gdEntries * 4);
        int gtSize = 512;
        return 512L + gdSize + gtSize;
    }

    // ---------------------------------------------------------------- VDI

    /**
     * Minimal VDI: preamble + header + BAM + optionally one allocated
     * block with pattern data.
     */
    public static byte[] vdi(long virtualSize, int blockSize, boolean allocateFirstBlock,
                             UUID parentUuid) {
        int numBlocks = (int) ((virtualSize + blockSize - 1) / blockSize);
        int bamSize = numBlocks * 4;
        int dataOffset = VdiHeader.MIN_HEADER_SIZE + bamSize;
        dataOffset = ((dataOffset + 511) / 512) * 512;
        int totalSize = dataOffset + (allocateFirstBlock ? blockSize : 0);
        byte[] data = new byte[totalSize];

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        String preamble = "<<< Oracle VM VirtualBox Disk Image >>>\n";
        System.arraycopy(preamble.getBytes(), 0, data, 0, preamble.length());
        buffer.position(VdiHeader.MAGIC_OFFSET);
        buffer.putInt(VdiHeader.MAGIC);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(0x180);
        buffer.putInt(VdiHeader.ImageType.DYNAMIC.value());
        buffer.putInt(0);
        buffer.position(buffer.position() + 256); // comment
        buffer.putInt(VdiHeader.MIN_HEADER_SIZE);
        buffer.putInt(dataOffset);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(512);
        buffer.putInt(0);
        buffer.putLong(virtualSize);
        buffer.putInt(blockSize);
        buffer.putInt(0); // blockExtraSize
        buffer.putInt(numBlocks);
        buffer.putInt(allocateFirstBlock ? 1 : 0);
        buffer.putLong(System.currentTimeMillis());
        buffer.putLong(System.nanoTime());
        buffer.putLong(0);
        buffer.putLong(0);
        buffer.putLong(0);
        buffer.putLong(0);
        // parent UUID
        if (parentUuid != null) {
            buffer.putLong(parentUuid.getMostSignificantBits());
            buffer.putLong(parentUuid.getLeastSignificantBits());
        } else {
            buffer.putLong(0);
            buffer.putLong(0);
        }
        buffer.position(VdiHeader.MIN_HEADER_SIZE);
        for (int i = 0; i < numBlocks; i++) {
            buffer.putInt(i == 0 && allocateFirstBlock ? 0 : VdiHeader.BLOCK_FREE);
        }
        if (allocateFirstBlock) {
            fill(data, dataOffset, blockSize);
        }
        return data;
    }

    // --------------------------------------------------------------- VMDK

    /**
     * Minimal monolithic-sparse VMDK: header + optional descriptor + grain
     * directory + optionally a grain table and one grain with pattern
     * data.
     */
    public static byte[] vmdk(long capacityBytes, int grainSizeBytes,
                              boolean allocateFirstGrain, boolean compressed,
                              String descriptorText) {
        int headerSize = SparseExtentHeader.HEADER_SIZE;
        int descSize = descriptorText == null ? 0 : align512(descriptorText.length());
        int gdEntries = 4;
        int gdSize = align512(gdEntries * 4);
        int gtSize = 512; // 512 entries
        int grainSectors = grainSizeBytes / 512;
        // Layout: header, descriptor, grain directory, grain table, grain data
        int gdeOffset = (headerSize + descSize) / 512;
        long gdOffsetBytes = gdeOffset * 512L;
        long gtOffsetBytes = gdOffsetBytes + gdSize;
        long grainDataOffsetBytes = gtOffsetBytes + gtSize;
        int overhead = (int) ((grainDataOffsetBytes + (allocateFirstGrain ? grainSizeBytes : 0)) / 512);

        byte[] data = new byte[(int) grainDataOffsetBytes + (allocateFirstGrain ? grainSizeBytes : 0)];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(SparseExtentHeader.MAGIC);
        buffer.putInt(1); // version
        int flags = 0;
        if (compressed) {
            flags |= 0x00010000; // COMPRESSED flag
        }
        buffer.putInt(flags);
        buffer.putLong(capacityBytes / 512);
        buffer.putLong(grainSectors);
        if (descriptorText != null) {
            buffer.putLong(1);              // descriptor offset in sectors
            buffer.putLong(descSize / 512); // descriptor size in sectors
        } else {
            buffer.putLong(0);
            buffer.putLong(0);
        }
        buffer.putInt(512); // numGTEsPerGT
        buffer.putLong(0);  // rgdOffset
        buffer.putLong(gdeOffset);
        buffer.putLong(overhead);
        buffer.put((byte) 0); // unclean shutdown
        // pad to 512
        buffer.position(headerSize);

        if (descriptorText != null) {
            System.arraycopy(descriptorText.getBytes(), 0, data, headerSize,
                    descriptorText.length());
        }

        // Grain directory
        ByteBuffer gd = ByteBuffer.wrap(data, (int) gdOffsetBytes, gdSize)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < gdEntries; i++) {
            if (i == 0 && allocateFirstGrain) {
                gd.putInt((int) (gtOffsetBytes / 512)); // sector of GT
            } else {
                gd.putInt(0);
            }
        }

        if (allocateFirstGrain) {
            // Grain table: first entry points at grain data (compressed or not)
            ByteBuffer gt = ByteBuffer.wrap(data, (int) gtOffsetBytes, gtSize)
                    .order(ByteOrder.LITTLE_ENDIAN);
            gt.putInt((int) (grainDataOffsetBytes / 512));
            if (compressed) {
                // Compressed grain: marker (LBA + size) + deflate data
                byte[] raw = new byte[grainSizeBytes];
                fill(raw, 0, grainSizeBytes);
                byte[] deflated = deflate(raw);
                ByteBuffer grain = ByteBuffer.wrap(data, (int) grainDataOffsetBytes,
                        grainSizeBytes).order(ByteOrder.LITTLE_ENDIAN);
                grain.putLong(0);                 // LBA
                grain.putInt(deflated.length);    // compressed size
                grain.put(deflated);
            } else {
                fill(data, (int) grainDataOffsetBytes, grainSizeBytes);
            }
        }
        return data;
    }

    private static int align512(int n) {
        return ((n + 511) / 512) * 512;
    }

    private static byte[] deflate(byte[] input) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.util.zip.Deflater deflater = new java.util.zip.Deflater();
            deflater.setInput(input);
            deflater.finish();
            byte[] buf = new byte[1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            deflater.end();
            return out.toByteArray();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    // --------------------------------------------------------------- VHDX

    private static final UUID BAT_REGION_GUID =
            UUID.fromString("2dc27766-f623-4200-9d64-115e9bfd4a08");
    private static final UUID METADATA_REGION_GUID =
            UUID.fromString("8b7ca206-4790-4b9a-b8fe-575f050f886e");
    private static final UUID FILE_PARAMETERS_GUID =
            UUID.fromString("caa16737-fa36-4d43-b3b6-33f0aa44e76b");
    private static final UUID VIRTUAL_DISK_SIZE_GUID =
            UUID.fromString("2fa54224-cd1b-4876-b211-5dbed83bf4b8");

    /**
     * Minimal valid VHDX: file identifier, one header, region table,
     * metadata region, BAT region, and optionally one allocated block.
     */
    public static byte[] vhdx(long virtualSize, int blockSize, boolean allocateFirstBlock,
                              boolean hasParent) {
        long header1 = 64 * 1024;
        long regionTable = 192 * 1024;
        long metadataOffset = 320 * 1024;
        int metadataLength = 1024;
        long batOffset = 512 * 1024;
        int totalBlocks = (int) ((virtualSize + blockSize - 1) / blockSize);
        long batLength = totalBlocks * 8L;
        long oneMb = 1024 * 1024;
        long dataStart = ((batOffset + batLength + oneMb - 1) / oneMb) * oneMb;
        int totalSize = (int) (dataStart + (allocateFirstBlock ? blockSize : 0));
        byte[] data = new byte[totalSize];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // File identifier
        buf.position(0);
        buf.put("vhdxfile".getBytes());
        buf.putLong(0x7869_6365_6C69_7665L); // "icelive\0"-ish creator (any bytes)
        buf.putLong(0);
        buf.position((int) header1);

        // Header 1
        buf.put("head".getBytes());
        buf.putInt(0);               // checksum
        buf.putLong(1);              // sequence number
        buf.putLong(0);              // fileWriteGuid
        buf.putLong(0);              // fileWriteGuid
        buf.putLong(0);              // dataWriteGuid
        buf.putLong(0);              // dataWriteGuid
        buf.putLong(0);              // logGuid
        buf.putLong(0);              // logGuid
        buf.putShort((short) 0);     // logVersion
        buf.putShort((short) 1);     // version
        buf.putInt(1024 * 1024);     // logLength
        buf.putLong(0);              // logOffset
        buf.position((int) regionTable);

        // Region table (entry count 2)
        buf.put("regi".getBytes());
        buf.putInt(0);
        buf.putInt(2);               // entry count
        buf.putInt(0);
        writeGuid(buf, METADATA_REGION_GUID);
        buf.putLong(metadataOffset);
        buf.putInt(metadataLength);
        buf.putInt(1);               // required
        writeGuid(buf, BAT_REGION_GUID);
        buf.putLong(batOffset);
        buf.putInt((int) batLength);
        buf.putInt(1);               // required

        // Metadata region
        buf.position((int) metadataOffset);
        buf.put("metadata".getBytes());
        buf.putShort((short) 0);
        buf.putShort((short) 3);     // entry count: fileParams, size, id
        buf.put(new byte[20]);
        // entry 0: file parameters
        writeGuid(buf, FILE_PARAMETERS_GUID);
        buf.putInt(128);             // item offset (after 32-byte header + 3 entries)
        buf.putInt(8);
        buf.putInt(0x04);            // required
        buf.putInt(0);
        // entry 1: virtual disk size
        writeGuid(buf, VIRTUAL_DISK_SIZE_GUID);
        buf.putInt(136);
        buf.putInt(8);
        buf.putInt(0x04);
        buf.putInt(0);
        // entry 2: virtual disk id
        writeGuid(buf, UUID.fromString("beca12ab-b2e6-4523-93ef-c309e000c746"));
        buf.putInt(144);
        buf.putInt(16);
        buf.putInt(0x04);
        buf.putInt(0);
        // items
        buf.position((int) metadataOffset + 128);
        buf.putInt(blockSize);
        buf.putInt(hasParent ? 0x02 : 0x00);
        buf.putLong(virtualSize);
        buf.putLong(0x12345678_9abcdef0L);
        buf.putLong(0x0fedcba9_87654321L);

        // BAT region: first block allocated
        buf.position((int) batOffset);
        for (int i = 0; i < totalBlocks; i++) {
            if (i == 0 && allocateFirstBlock) {
                buf.putLong(dataStart | 6L); // state FULLY_PRESENT, offset in MB units
            } else {
                buf.putLong(0);
            }
        }

        if (allocateFirstBlock) {
            fill(data, (int) dataStart, blockSize);
        }
        return data;
    }

    private static void writeGuid(ByteBuffer buf, UUID uuid) {
        buf.putInt((int) (uuid.getMostSignificantBits() >>> 32));
        buf.putShort((short) (uuid.getMostSignificantBits() >>> 16));
        buf.putShort((short) uuid.getMostSignificantBits());
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            buf.put((byte) (lsb >>> (i * 8)));
        }
    }

    // -------------------------------------------------------------- QCOW2

    /**
     * QCOW2 with TWO L2 tables (two L1 entries) so reads exercise L2
     * cache replacement: L2A covers virtual cluster 0, L2B covers the
     * cluster at {@code l2Size * clusterSize}. Each data cluster carries a
     * distinct seed so cross-table reads mismatch.
     */
    public static byte[] qcow2TwoL2Tables(int version, int clusterBits,
                                          int seedA, int seedB) {
        int clusterSize = 1 << clusterBits;
        int headerSize = version >= 3 ? 104 : 72;
        long l2Entries = clusterSize / 8L;
        long l1Size = 2;
        long virtualSize = 2L * l2Entries * clusterSize;
        long l1Offset = clusterSize;
        long refcountTableOffset = 2L * clusterSize;
        long refcountBlockOffset = 3L * clusterSize;
        long l2AOffset = 4L * clusterSize;
        long l2BOffset = 5L * clusterSize;
        long dataAOffset = 6L * clusterSize;
        long dataBOffset = 7L * clusterSize;
        long totalSize = dataBOffset + clusterSize;

        byte[] data = new byte[(int) totalSize];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0x51_46_49_fb);
        buf.putInt(version);
        buf.putLong(0);
        buf.putInt(0);
        buf.putInt(clusterBits);
        buf.putLong(virtualSize);
        buf.putInt(0);
        buf.putInt((int) l1Size);
        buf.putLong(l1Offset);
        buf.putLong(refcountTableOffset);
        buf.putInt(1);
        buf.putInt(0);
        buf.putLong(0);
        if (version >= 3) {
            buf.putLong(0);
            buf.putLong(0);
            buf.putLong(0);
            buf.putInt(4);
            buf.putInt(104);
        }
        // L1 table: entry 0 -> L2A, entry 1 -> L2B
        buf.position((int) l1Offset);
        buf.putLong(l2AOffset);
        buf.putLong(l2BOffset);
        // Refcount table
        buf.position((int) refcountTableOffset);
        buf.putLong(refcountBlockOffset);
        // Refcount block: mark clusters 0..7 allocated
        buf.position((int) refcountBlockOffset);
        for (int i = 0; i <= 7; i++) {
            buf.putShort((short) 1);
        }
        // L2A: entry 0 -> dataA
        buf.position((int) l2AOffset);
        buf.putLong(dataAOffset);
        // L2B: entry 0 -> dataB
        buf.position((int) l2BOffset);
        buf.putLong(dataBOffset);
        // Data clusters with distinct per-offset seeds
        for (int i = 0; i < clusterSize; i++) {
            data[(int) dataAOffset + i] = pattern(i * 2L + seedA);
            data[(int) dataBOffset + i] = pattern(i * 2L + 1 + seedB);
        }
        return data;
    }

    /**
     * Minimal QCOW2 with one allocated cluster carrying pattern data.
     */
    public static byte[] qcow2AllocatedCluster(int version, int clusterBits,
                                               long virtualSize, byte[] clusterData) {
        int clusterSize = 1 << clusterBits;
        int headerSize = version >= 3 ? 104 : 72;
        long l2Entries = clusterSize / 8L;
        long l1Size = Math.max(1, (virtualSize + (long) clusterSize * l2Entries - 1)
                / ((long) clusterSize * l2Entries));
        long l1Offset = clusterSize;
        long refcountTableOffset = 2L * clusterSize;
        long refcountBlockOffset = 3L * clusterSize;
        long l2Offset = 4L * clusterSize;
        long dataClusterOffset = 5L * clusterSize;
        long totalSize = dataClusterOffset + clusterSize;

        byte[] data = new byte[(int) totalSize];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0x51_46_49_fb);
        buf.putInt(version);
        buf.putLong(0);                     // backing file offset
        buf.putInt(0);                      // backing file size
        buf.putInt(clusterBits);
        buf.putLong(virtualSize);
        buf.putInt(0);                      // crypt
        buf.putInt((int) l1Size);
        buf.putLong(l1Offset);
        buf.putLong(refcountTableOffset);
        buf.putInt(1);                      // refcount table clusters
        buf.putInt(0);                      // snapshots
        buf.putLong(0);                     // snapshots offset
        if (version >= 3) {
            buf.putLong(0);                 // incompatible features
            buf.putLong(0);                 // compatible
            buf.putLong(0);                 // autoclear
            buf.putInt(4);                  // refcount order
            buf.putInt(104);                // header length
        }
        // L1 table: entry 0 -> L2 at l2Offset
        buf.position((int) l1Offset);
        buf.putLong(l2Offset);
        // Refcount table
        buf.position((int) refcountTableOffset);
        buf.putLong(refcountBlockOffset);
        // Refcount block: mark clusters allocated
        buf.position((int) refcountBlockOffset);
        for (int i = 0; i <= 5; i++) {
            buf.putShort((short) 1);
        }
        // L2 table: entry 0 -> data cluster
        buf.position((int) l2Offset);
        buf.putLong(dataClusterOffset);
        // Data cluster
        System.arraycopy(clusterData, 0, data, (int) dataClusterOffset, clusterData.length);
        return data;
    }
}
