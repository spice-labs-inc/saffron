/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ntfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for NTFS filesystem implementation.
 */
class NtfsFileSystemTest {

    @Test
    void ntfsBootSector_detectsValidSignature(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createMinimalNtfsVolume();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            NtfsBootSector bootSector = NtfsBootSector.read(disk, 0);

            assertThat(bootSector.bytesPerSector()).isEqualTo(512);
            assertThat(bootSector.sectorsPerCluster()).isEqualTo(8);
            assertThat(bootSector.clusterSize()).isEqualTo(4096);
        }
    }

    @Test
    void ntfsBootSector_rejectsInvalidOemId(@TempDir Path tempDir) throws Exception {
        byte[] diskData = new byte[1024 * 1024];
        // Add boot signature but wrong OEM ID
        ByteBuffer buf = ByteBuffer.wrap(diskData);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0xEB);
        buf.position(3);
        buf.put("INVALID ".getBytes(StandardCharsets.US_ASCII));
        buf.putShort(510, (short) 0xAA55);

        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath)) {
            assertThatThrownBy(() -> NtfsBootSector.read(disk, 0))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid NTFS OEM ID");
        }
    }

    @Test
    void ntfsFileSystem_canMount(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createNtfsVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = NtfsFileSystemImpl.mount(disk, 0)) {
            assertThat(fs).isInstanceOf(FileSystem.NtfsFileSystem.class);
            assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.NTFS);
        }
    }

    @Test
    void ntfsFileSystem_canReadRootDirectory(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createNtfsVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = NtfsFileSystemImpl.mount(disk, 0)) {
            FileSystemEntry.Directory root = fs.root();
            assertThat(root.path()).isEqualTo("/");
        }
    }

    @Test
    void ntfsFileSystem_providesMetadata(@TempDir Path tempDir) throws Exception {
        byte[] diskData = createNtfsVolumeWithFiles();
        Path diskPath = tempDir.resolve("disk.qcow2");
        createQcow2(diskPath, diskData);

        try (VirtualDisk disk = DiskReader.open(diskPath);
             FileSystem fs = NtfsFileSystemImpl.mount(disk, 0)) {
            FileSystem.NtfsFileSystem ntfsFs = (FileSystem.NtfsFileSystem) fs;

            assertThat(ntfsFs.clusterSize()).isEqualTo(4096);
            assertThat(ntfsFs.version()).isEqualTo("3.1");

            var metadata = fs.metadata();
            assertThat(metadata).containsKey("fsType");
            assertThat(metadata.get("fsType")).isEqualTo("NTFS");

            System.out.println("Metadata: " + metadata);
            System.out.println("Total size: " + fs.totalSize());
            System.out.println("UUID: " + fs.uuid().orElse("(none)"));
        }
    }

    @Test
    void mftRecord_parsesFileSignature() {
        byte[] data = createValidMftRecord(0, false);
        ByteBuffer buf = ByteBuffer.wrap(data);

        Optional<MftRecord> record = MftRecord.parse(buf, 0, 1024);

        assertThat(record).isPresent();
        assertThat(record.get().isInUse()).isTrue();
    }

    @Test
    void mftRecord_parsesDirectoryFlag() {
        byte[] data = createValidMftRecord(5, true);
        ByteBuffer buf = ByteBuffer.wrap(data);

        Optional<MftRecord> record = MftRecord.parse(buf, 5, 1024);

        assertThat(record).isPresent();
        assertThat(record.get().isDirectory()).isTrue();
    }

    @Test
    void ntfsAttribute_parsesStandardInformation() {
        byte[] attrData = createStandardInformationAttribute();

        Optional<NtfsAttribute> attr = NtfsAttribute.parse(attrData);

        assertThat(attr).isPresent();
        assertThat(attr.get().type()).isEqualTo(NtfsAttribute.TYPE_STANDARD_INFORMATION);
        assertThat(attr.get().isResident()).isTrue();

        Optional<NtfsAttribute.StandardInformation> si = attr.get().asStandardInformation();
        assertThat(si).isPresent();
    }

    @Test
    void ntfsAttribute_parsesFileName() {
        byte[] attrData = createFileNameAttribute("TestFile.txt");

        Optional<NtfsAttribute> attr = NtfsAttribute.parse(attrData);

        assertThat(attr).isPresent();
        assertThat(attr.get().type()).isEqualTo(NtfsAttribute.TYPE_FILE_NAME);

        Optional<NtfsAttribute.FileName> fn = attr.get().asFileName();
        assertThat(fn).isPresent();
        assertThat(fn.get().fileName()).isEqualTo("TestFile.txt");
    }

    @Test
    void ntfsAttribute_parseDataRuns() {
        // Create a non-resident DATA attribute with data runs
        byte[] attrData = createNonResidentDataAttribute();

        Optional<NtfsAttribute> attr = NtfsAttribute.parse(attrData);

        assertThat(attr).isPresent();
        assertThat(attr.get().type()).isEqualTo(NtfsAttribute.TYPE_DATA);
        assertThat(attr.get().isResident()).isFalse();
        assertThat(attr.get().dataRuns()).isNotEmpty();
    }

    // ========================================================================
    // Helper methods to create test NTFS structures
    // ========================================================================

    private byte[] createMinimalNtfsVolume() {
        // Create a minimal 16MB NTFS volume
        int volumeSize = 16 * 1024 * 1024;
        byte[] data = new byte[volumeSize];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Boot sector
        buf.put(0, (byte) 0xEB);  // Jump instruction
        buf.put(1, (byte) 0x52);
        buf.put(2, (byte) 0x90);

        // OEM ID "NTFS    "
        buf.position(3);
        buf.put("NTFS    ".getBytes(StandardCharsets.US_ASCII));

        // BPB
        buf.putShort(11, (short) 512);     // Bytes per sector
        buf.put(13, (byte) 8);              // Sectors per cluster
        buf.putShort(14, (short) 0);        // Reserved sectors
        buf.put(21, (byte) 0xF8);           // Media descriptor
        buf.putShort(24, (short) 63);       // Sectors per track
        buf.putShort(26, (short) 255);      // Number of heads
        buf.putInt(28, 0);                  // Hidden sectors
        buf.putLong(40, volumeSize / 512);  // Total sectors

        // MFT at cluster 6 (offset 6 * 4096 = 24576)
        buf.putLong(48, 6);                 // MFT cluster number
        buf.putLong(56, 7);                 // MFT mirror cluster

        // -10 means 2^10 = 1024 byte MFT records
        buf.put(64, (byte) -10);
        buf.put(68, (byte) -12);            // -12 = 4096 byte index records

        // Volume serial number
        buf.putLong(72, 0x1234567890ABCDEFL);

        // Boot signature
        buf.putShort(510, (short) 0xAA55);

        // Create MFT at cluster 6
        int mftOffset = 6 * 4096;
        createMftRecords(buf, mftOffset);

        return data;
    }

    private byte[] createNtfsVolumeWithFiles() {
        return createMinimalNtfsVolume();
    }

    private void createMftRecords(ByteBuffer buf, int mftOffset) {
        int recordSize = 1024;

        // $MFT (record 0)
        createMftRecordHeader(buf, mftOffset, 0, false);

        // $MFTMirr (record 1)
        createMftRecordHeader(buf, mftOffset + recordSize, 1, false);

        // $LogFile (record 2)
        createMftRecordHeader(buf, mftOffset + 2 * recordSize, 2, false);

        // $Volume (record 3)
        createMftRecordHeader(buf, mftOffset + 3 * recordSize, 3, false);

        // $AttrDef (record 4)
        createMftRecordHeader(buf, mftOffset + 4 * recordSize, 4, false);

        // Root directory (record 5)
        createMftRecordHeader(buf, mftOffset + 5 * recordSize, 5, true);
        addIndexRootAttribute(buf, mftOffset + 5 * recordSize + 56);
    }

    private void createMftRecordHeader(ByteBuffer buf, int offset, int recordNum, boolean isDirectory) {
        buf.position(offset);

        // Signature "FILE"
        buf.putInt(0x454C4946);

        // Update sequence offset (at offset 4)
        buf.putShort((short) 48);

        // Update sequence size (at offset 6) - 3 means: 1 seq number + 2 fixups (for 2 sectors)
        buf.putShort((short) 3);

        // LSN (at offset 8)
        buf.putLong(0);

        // Sequence number (at offset 16)
        buf.putShort((short) 1);

        // Link count (at offset 18)
        buf.putShort((short) 1);

        // First attribute offset (at offset 20)
        buf.putShort((short) 56);

        // Flags (at offset 22) - in use, optionally directory
        buf.putShort((short) (MftRecord.FLAG_IN_USE | (isDirectory ? MftRecord.FLAG_DIRECTORY : 0)));

        // Used size (at offset 24)
        buf.putInt(512);

        // Allocated size (at offset 28)
        buf.putInt(1024);

        // Base record reference (at offset 32)
        buf.putLong(0);

        // Next attribute ID (at offset 40)
        buf.putShort((short) 1);

        // Padding (at offset 42)
        buf.putShort((short) 0);

        // Record number (at offset 44)
        buf.putInt(recordNum);

        // Update sequence array at offset 48:
        // - Bytes 48-49: Update sequence number
        // - Bytes 50-51: Original value from end of sector 1 (offset 510)
        // - Bytes 52-53: Original value from end of sector 2 (offset 1022)
        short updateSeqNum = (short) 0xBEEF;
        buf.putShort(offset + 48, updateSeqNum);
        buf.putShort(offset + 50, (short) 0x0000);  // Original value for sector 1 end
        buf.putShort(offset + 52, (short) 0x0000);  // Original value for sector 2 end

        // Put the update sequence number at sector ends (will be replaced during fixup)
        buf.putShort(offset + 510, updateSeqNum);
        if (offset + 1022 < buf.limit()) {
            buf.putShort(offset + 1022, updateSeqNum);
        }

        // Add end-of-attributes marker after the header
        buf.putInt(offset + 56, 0xFFFFFFFF);
    }

    private void addIndexRootAttribute(ByteBuffer buf, int offset) {
        buf.position(offset);

        // Attribute type ($INDEX_ROOT)
        buf.putInt(NtfsAttribute.TYPE_INDEX_ROOT);

        // Attribute length
        buf.putInt(80);

        // Non-resident flag
        buf.put((byte) 0);

        // Name length
        buf.put((byte) 4);

        // Name offset
        buf.putShort((short) 24);

        // Flags
        buf.putShort((short) 0);

        // Attribute ID
        buf.putShort((short) 1);

        // Value length
        buf.putInt(48);

        // Value offset
        buf.putShort((short) 32);

        // Flags
        buf.putShort((short) 0);

        // Attribute name "$I30" at offset 24
        buf.position(offset + 24);
        buf.put("$I30".getBytes(StandardCharsets.UTF_16LE));

        // Index root content at offset 32
        buf.position(offset + 32);
        buf.putInt(NtfsAttribute.TYPE_FILE_NAME);  // Indexed attribute type
        buf.putInt(1);                              // Collation rule
        buf.putInt(4096);                           // Index block size
        buf.put((byte) 1);                          // Clusters per index block
        buf.put((byte) 0);
        buf.putShort((short) 0);

        // Index header
        buf.putInt(16);  // Entries offset
        buf.putInt(16);  // Index size
        buf.putInt(32);  // Allocated size
        buf.putInt(0);   // Flags

        // End marker for index entries
        buf.putLong(0);  // MFT reference
        buf.putShort((short) 16);  // Entry length
        buf.putShort((short) 0);   // Content length
        buf.putInt(NtfsAttribute.IndexEntry.FLAG_LAST);  // Last entry flag

        // End of attributes marker
        buf.position(offset + 80);
        buf.putInt(0xFFFFFFFF);
    }

    private byte[] createValidMftRecord(int recordNum, boolean isDirectory) {
        byte[] data = new byte[1024];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        createMftRecordHeader(buf, 0, recordNum, isDirectory);

        return data;
    }

    private byte[] createStandardInformationAttribute() {
        byte[] data = new byte[96];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Attribute header
        buf.putInt(NtfsAttribute.TYPE_STANDARD_INFORMATION);
        buf.putInt(96);       // Total length
        buf.put((byte) 0);    // Resident
        buf.put((byte) 0);    // Name length
        buf.putShort((short) 24);  // Name offset
        buf.putShort((short) 0);   // Flags
        buf.putShort((short) 0);   // Attribute ID

        buf.putInt(48);       // Value length
        buf.putShort((short) 24);  // Value offset
        buf.putShort((short) 0);   // Flags

        // Standard information at offset 24
        buf.position(24);
        long ntfsTime = 132456789012345678L;  // Some NTFS timestamp
        buf.putLong(ntfsTime);  // Creation time
        buf.putLong(ntfsTime);  // Modification time
        buf.putLong(ntfsTime);  // MFT modification time
        buf.putLong(ntfsTime);  // Access time
        buf.putInt(0x20);       // File attributes (archive)

        return data;
    }

    private byte[] createFileNameAttribute(String fileName) {
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_16LE);
        int dataLen = 66 + nameBytes.length;
        int totalLen = 24 + dataLen;
        byte[] data = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Attribute header
        buf.putInt(NtfsAttribute.TYPE_FILE_NAME);
        buf.putInt(totalLen);
        buf.put((byte) 0);     // Resident
        buf.put((byte) 0);     // Name length
        buf.putShort((short) 24);  // Name offset
        buf.putShort((short) 0);   // Flags
        buf.putShort((short) 0);   // Attribute ID

        buf.putInt(dataLen);   // Value length
        buf.putShort((short) 24);  // Value offset
        buf.putShort((short) 0);   // Flags

        // File name attribute content at offset 24
        buf.position(24);
        buf.putLong(5);        // Parent reference (root)
        long ntfsTime = 132456789012345678L;
        buf.putLong(ntfsTime); // Creation
        buf.putLong(ntfsTime); // Modification
        buf.putLong(ntfsTime); // MFT modification
        buf.putLong(ntfsTime); // Access
        buf.putLong(0);        // Allocated size
        buf.putLong(0);        // Real size
        buf.putInt(0x20);      // Flags
        buf.putInt(0);         // Reparse
        buf.put((byte) (fileName.length()));  // Name length
        buf.put((byte) NtfsAttribute.FileName.NAMESPACE_WIN32);  // Namespace
        buf.put(nameBytes);

        return data;
    }

    private byte[] createNonResidentDataAttribute() {
        byte[] data = new byte[72];
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Attribute header
        buf.putInt(NtfsAttribute.TYPE_DATA);
        buf.putInt(72);        // Total length
        buf.put((byte) 1);     // Non-resident
        buf.put((byte) 0);     // Name length
        buf.putShort((short) 64);  // Name offset
        buf.putShort((short) 0);   // Flags
        buf.putShort((short) 0);   // Attribute ID

        // Non-resident header
        buf.putLong(0);        // Start VCN
        buf.putLong(9);        // End VCN
        buf.putShort((short) 64);  // Data runs offset
        buf.putShort((short) 0);   // Compression unit
        buf.putInt(0);         // Padding
        buf.putLong(40960);    // Allocated size
        buf.putLong(35000);    // Data size
        buf.putLong(35000);    // Initialized size

        // Data run at offset 64: 10 clusters starting at LCN 100
        buf.position(64);
        buf.put((byte) 0x21);  // 2 bytes for offset, 1 byte for length
        buf.put((byte) 10);    // Length: 10 clusters
        buf.putShort((short) 100);  // LCN offset: 100
        buf.put((byte) 0);     // End of data runs

        return data;
    }

    private void createQcow2(Path path, byte[] content) throws IOException {
        int clusterSize = 65536;

        int l1Offset = clusterSize;
        int l2Offset = clusterSize * 2;
        int refcountTableOffset = clusterSize * 3;
        int refcountBlockOffset = clusterSize * 4;
        int dataOffset = clusterSize * 5;

        byte[] qcow2 = new byte[dataOffset + content.length];
        ByteBuffer header = ByteBuffer.wrap(qcow2);
        header.order(ByteOrder.BIG_ENDIAN);

        header.putInt(0x514649fb);
        header.putInt(3);
        header.putLong(0);
        header.putInt(0);
        header.putInt(16);
        header.putLong(content.length);
        header.putInt(0);
        header.putInt(1);
        header.putLong(l1Offset);
        header.putLong(refcountTableOffset);
        header.putInt(1);
        header.putInt(0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.putInt(4);
        header.putInt(104);

        header.position(l1Offset);
        header.putLong(l2Offset | 0x8000000000000000L);

        header.position(l2Offset);
        header.putLong(dataOffset | 0x8000000000000000L);

        header.position(refcountTableOffset);
        header.putLong(refcountBlockOffset);

        System.arraycopy(content, 0, qcow2, dataOffset, content.length);

        Files.write(path, qcow2);
    }
}
