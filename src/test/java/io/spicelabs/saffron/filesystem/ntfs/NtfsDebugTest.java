/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.ntfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Debug test for NTFS filesystem issues.
 */
class NtfsDebugTest {

    private static final Path NTFS_IMAGE = Path.of(
        "/home/dpp/tmp/vmreader/saffron/test-corpus/vhd/legacy/xp-mode/" +
        "Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/" +
        "Windows NT Workstation 4.0 Hard Disk.vhd"
    );

    private static final Path XP_MODE_IMAGE = Path.of(
        "/home/dpp/tmp/vmreader/saffron/test-corpus/vhd/legacy/xp-mode/" +
        "Windows Virtual PC, XP Mode, And Other VHD Collections/VHD Disks/" +
        "Windows XP Mode.vhd"
    );

    static boolean imageExists() {
        return Files.exists(NTFS_IMAGE);
    }

    static boolean xpModeImageExists() {
        return Files.exists(XP_MODE_IMAGE);
    }

    @Test
    @EnabledIf("imageExists")
    void debugNtfsFilesystem() throws Exception {
        System.out.println("=== NTFS Debug Test ===\n");
        System.out.println("Image: " + NTFS_IMAGE.getFileName());

        try (VirtualDisk disk = DiskReader.open(NTFS_IMAGE)) {
            System.out.println("Disk format: " + disk.format());
            System.out.println("Virtual size: " + disk.virtualSize() + " bytes");

            // Check partition table
            Optional<PartitionTable> pt = PartitionTable.detect(disk);
            if (pt.isPresent()) {
                System.out.println("\nPartition table: " + pt.get().type());
                System.out.println("Partitions:");
                for (Partition p : pt.get().partitions()) {
                    System.out.println("  - Start LBA: " + p.startLba() +
                                       ", Size: " + p.sizeInSectors() + " sectors");
                }
            } else {
                System.out.println("No partition table detected");
            }

            // Try to mount filesystem
            System.out.println("\n--- Attempting filesystem mount ---");
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {
                System.out.println("Filesystem type: " + fs.type());
                System.out.println("Total size: " + fs.totalSize());
                System.out.println("Label: " + fs.label().orElse("(none)"));
                System.out.println("UUID: " + fs.uuid().orElse("(none)"));

                // Get root directory
                FileSystemEntry.Directory root = fs.root();
                System.out.println("\nRoot directory: " + root.path());

                // List root contents
                System.out.println("\nRoot directory contents:");
                try (Stream<FileSystemEntry> entries = root.list()) {
                    entries.forEach(entry -> {
                        String type = entry.type().name();
                        String size = entry instanceof FileSystemEntry.RegularFile f ?
                                      String.valueOf(f.size()) : "-";
                        System.out.println("  " + type + " " + entry.name() + " (" + size + " bytes)");
                    });
                }

                // Check specific directories
                System.out.println("\n--- Checking WINNT directory directly ---");
                var winntEntry = fs.resolve("/WINNT");
                if (winntEntry.isPresent() && winntEntry.get() instanceof FileSystemEntry.Directory winntDir) {
                    System.out.println("WINNT exists: " + winntDir.path());
                    try (Stream<FileSystemEntry> winntChildren = winntDir.list()) {
                        long count = winntChildren.count();
                        System.out.println("WINNT direct children: " + count);
                    }
                    // Show all WINNT children
                    System.out.println("All WINNT children:");
                    try (Stream<FileSystemEntry> winntChildren = winntDir.list()) {
                        winntChildren.forEach(e -> {
                            String type = e instanceof FileSystemEntry.Directory ? "DIR " : "FILE";
                            System.out.println("  " + type + " " + e.name());
                        });
                    }
                    // Check if system32 exists
                    System.out.println("\nChecking for system32:");
                    var sys32 = fs.resolve("/WINNT/system32");
                    if (sys32.isPresent()) {
                        System.out.println("system32 EXISTS: " + sys32.get().type());
                    } else {
                        System.out.println("system32 NOT FOUND via resolve");
                    }

                    // Debug WINNT's MFT record structure
                    System.out.println("\n--- Debugging WINNT MFT record ---");
                    NtfsFileSystemImpl ntfsImpl = (NtfsFileSystemImpl) fs;
                    MftRecord winntMft = ntfsImpl.readMftRecord(17);  // WINNT is record 17
                    System.out.println("WINNT MFT record: " + winntMft.recordNumber());
                    for (NtfsAttribute attr : winntMft.attributes()) {
                        System.out.println("  Attr: 0x" + Integer.toHexString(attr.type()) +
                                           " (" + attr.typeName() + "), Resident: " + attr.isResident() +
                                           ", Size: " + (attr.isResident() ? attr.residentData().length : attr.dataSize()));
                        if (attr.type() == NtfsAttribute.TYPE_INDEX_ALLOCATION && !attr.isResident()) {
                            System.out.println("    Data runs: " + attr.dataRuns().size());
                            for (var run : attr.dataRuns()) {
                                System.out.println("      LCN: " + run.lcn() + ", Length: " + run.length() +
                                                   " clusters, Bytes: " + (run.length() * 512));
                            }
                            System.out.println("    Total size: " + attr.dataSize() + " bytes");
                            int indexBlockSize = 4096;
                            System.out.println("    Index blocks: " + (attr.dataSize() / indexBlockSize));

                            // Read and analyze INDX blocks
                            System.out.println("\n    Analyzing INDX blocks:");
                            byte[] indexData = new byte[(int) attr.dataSize()];
                            int clusterSize = 512;
                            int offset = 0;
                            for (var run : attr.dataRuns()) {
                                if (!run.sparse()) {
                                    long readOffset = run.lcn() * clusterSize;
                                    int readLen = (int) (run.length() * clusterSize);
                                    var buf = disk.read(32256 + readOffset, readLen);  // partOffset=32256
                                    buf.get(indexData, offset, readLen);
                                }
                                offset += run.length() * clusterSize;
                            }

                            var buf = java.nio.ByteBuffer.wrap(indexData);
                            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

                            for (int blockOff = 0; blockOff + indexBlockSize <= indexData.length; blockOff += indexBlockSize) {
                                int sig = buf.getInt(blockOff);
                                boolean valid = (sig == 0x58444E49);  // "INDX"
                                System.out.println("    Block at offset " + blockOff + ": signature=0x" +
                                                   Integer.toHexString(sig) + " valid=" + valid);
                                if (valid) {
                                    // Count entries in this block
                                    int usOff = buf.getShort(blockOff + 4) & 0xFFFF;
                                    int usSize = buf.getShort(blockOff + 6) & 0xFFFF;
                                    int entryOff = buf.getInt(blockOff + 24) + 24;
                                    int totalEntries = 0;
                                    String firstFile = null, lastFile = null;

                                    // Apply fixup
                                    short usNum = buf.getShort(blockOff + usOff);
                                    for (int i = 1; i < usSize && (i * 512 - 2) < indexBlockSize; i++) {
                                        int sectorEnd = blockOff + (i * 512) - 2;
                                        if (sectorEnd + 2 <= indexData.length) {
                                            short origVal = buf.getShort(blockOff + usOff + i * 2);
                                            buf.putShort(sectorEnd, origVal);
                                        }
                                    }

                                    int eOff = blockOff + entryOff;
                                    while (eOff + 16 < blockOff + indexBlockSize) {
                                        int eLen = buf.getShort(eOff + 8) & 0xFFFF;
                                        int sLen = buf.getShort(eOff + 10) & 0xFFFF;
                                        int eFlags = buf.getShort(eOff + 12) & 0xFFFF;
                                        if (eLen < 16) break;
                                        if ((eFlags & 0x02) != 0) {
                                            totalEntries++;
                                            break;  // LAST entry
                                        }
                                        // Get filename
                                        if (sLen >= 66) {
                                            int fnLen = buf.get(eOff + 16 + 64) & 0xFF;
                                            if (fnLen > 0 && eOff + 16 + 66 + fnLen * 2 <= indexData.length) {
                                                byte[] nb = new byte[fnLen * 2];
                                                buf.position(eOff + 16 + 66);
                                                buf.get(nb);
                                                String fn = new String(nb, java.nio.charset.StandardCharsets.UTF_16LE);
                                                if (firstFile == null) firstFile = fn;
                                                lastFile = fn;
                                            }
                                        }
                                        totalEntries++;
                                        eOff += eLen;
                                    }
                                    System.out.println("      Entries: " + totalEntries +
                                                       ", first: " + firstFile + ", last: " + lastFile);
                                }
                            }
                        }
                        if (attr.type() == NtfsAttribute.TYPE_INDEX_ROOT && attr.isResident()) {
                            var ir = attr.asIndexRoot();
                            if (ir.isPresent()) {
                                System.out.println("    IndexRoot entries: " + ir.get().entries().size());
                                for (var entry : ir.get().entries()) {
                                    String flagStr = (entry.hasSubnode() ? "SUBNODE " : "") +
                                                     (entry.isLastEntry() ? "LAST" : "");
                                    System.out.println("      mftRef=" + entry.mftReference() +
                                                       ", flags: " + entry.indexFlags() + " [" + flagStr.trim() + "]");
                                }
                            }
                        }
                    }
                } else {
                    System.out.println("WINNT not found or not a directory!");
                }

                // Walk and count
                System.out.println("\n--- Walking filesystem ---");
                AtomicLong fileCount = new AtomicLong(0);
                AtomicLong dirCount = new AtomicLong(0);

                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            fileCount.incrementAndGet();
                            if (fileCount.get() <= 20) {
                                System.out.println("  FILE: " + entry.path());
                            }
                        } else if (entry instanceof FileSystemEntry.Directory) {
                            dirCount.incrementAndGet();
                            if (dirCount.get() <= 30) {
                                System.out.println("  DIR:  " + entry.path());
                            }
                        }
                    });
                }

                System.out.println("\nTotal files: " + fileCount.get());
                System.out.println("Total directories: " + dirCount.get());
            }
        }
    }

    @Test
    @EnabledIf("imageExists")
    void debugNtfsMftRecords() throws Exception {
        System.out.println("=== NTFS MFT Debug Test ===\n");

        try (VirtualDisk disk = DiskReader.open(NTFS_IMAGE)) {
            Optional<PartitionTable> pt = PartitionTable.detect(disk);
            if (pt.isEmpty()) {
                System.out.println("No partition table");
                return;
            }

            // Use first partition
            Partition ntfsPart = pt.get().partitions().get(0);
            long partOffset = ntfsPart.startLba() * 512;
            System.out.println("Partition offset: " + partOffset);

            // Read boot sector for debug info
            var bootBuf = disk.read(partOffset, 512);
            bootBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int bytesPerSector = bootBuf.getShort(11) & 0xFFFF;
            int sectorsPerCluster = bootBuf.get(13) & 0xFF;
            int clusterSize = bytesPerSector * sectorsPerCluster;
            int clustersPerIndexRecord = bootBuf.get(68);
            int indexRecordSize = clustersPerIndexRecord > 0
                ? clustersPerIndexRecord * clusterSize
                : (1 << (-clustersPerIndexRecord));
            System.out.println("Bytes per sector: " + bytesPerSector);
            System.out.println("Sectors per cluster: " + sectorsPerCluster);
            System.out.println("Cluster size: " + clusterSize);
            System.out.println("Clusters per index record: " + clustersPerIndexRecord);
            System.out.println("Index record size: " + indexRecordSize);

            // Mount NTFS directly
            NtfsFileSystemImpl ntfs = NtfsFileSystemImpl.mount(disk, partOffset);
            System.out.println("NTFS mounted successfully");

            // Read root MFT record (record 5)
            System.out.println("\n--- Reading MFT root record ---");
            MftRecord rootRecord = ntfs.readMftRecord(MftRecord.MFT_RECORD_ROOT);
            System.out.println("Root record number: " + rootRecord.recordNumber());
            System.out.println("Root is in use: " + rootRecord.isInUse());
            System.out.println("Root is directory: " + rootRecord.isDirectory());
            System.out.println("Root filename: " + rootRecord.getLongFileName().orElse("(none)"));

            // List attributes
            System.out.println("\nRoot record attributes:");
            for (NtfsAttribute attr : rootRecord.attributes()) {
                System.out.println("  Type: 0x" + Integer.toHexString(attr.type()) +
                                   " (" + attrTypeName(attr.type()) + ")" +
                                   ", Resident: " + attr.isResident() +
                                   ", Size: " + (attr.isResident() ? attr.residentData().length : attr.dataSize()));
                if (attr.type() == NtfsAttribute.TYPE_INDEX_ALLOCATION && !attr.isResident()) {
                    System.out.println("    Data runs: " + attr.dataRuns().size());
                    for (var run : attr.dataRuns()) {
                        System.out.println("      LCN: " + run.lcn() + ", Length: " + run.length() + " clusters, Sparse: " + run.sparse());
                    }

                    // Debug: Read and dump the INDEX_ALLOCATION data
                    System.out.println("\n    --- Debugging INDEX_ALLOCATION content ---");
                    long totalBytes = attr.dataRuns().stream()
                        .mapToLong(r -> r.length() * clusterSize)
                        .sum();
                    System.out.println("    Total bytes from data runs: " + totalBytes);
                    System.out.println("    Attribute data size: " + attr.dataSize());

                    // Read first data run to check INDX signature
                    if (!attr.dataRuns().isEmpty()) {
                        var firstRun = attr.dataRuns().get(0);
                        long readOffset = partOffset + (firstRun.lcn() * clusterSize);
                        int readSize = (int) Math.min(firstRun.length() * clusterSize, 4096);
                        var indxBuf = disk.read(readOffset, readSize);
                        indxBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

                        // Check signature
                        int sig = indxBuf.getInt(0);
                        System.out.println("    First INDX block signature: 0x" + Integer.toHexString(sig) +
                                           " (expected 0x58444e49 'INDX')");

                        if (sig == 0x58444E49) {
                            // Parse INDX header
                            int updateSeqOff = indxBuf.getShort(4) & 0xFFFF;
                            int updateSeqSize = indxBuf.getShort(6) & 0xFFFF;
                            long vcn = indxBuf.getLong(16);
                            int entriesOff = indxBuf.getInt(24);
                            int totalSizeEntries = indxBuf.getInt(28);
                            int allocSize = indxBuf.getInt(32);
                            int indexFlags = indxBuf.getInt(36);

                            System.out.println("    Update seq offset: " + updateSeqOff + ", size: " + updateSeqSize);
                            System.out.println("    VCN: " + vcn);
                            System.out.println("    Entries offset (from header): " + entriesOff);
                            System.out.println("    Total size of entries: " + totalSizeEntries);
                            System.out.println("    Allocated size: " + allocSize);
                            System.out.println("    Index flags: " + indexFlags);

                            // Apply fixup and parse entries
                            System.out.println("\n    --- Parsing INDX entries ---");
                            // Apply fixup
                            short updateSeqNum = indxBuf.getShort(updateSeqOff);
                            for (int i = 1; i < updateSeqSize && (i * 512 - 2) < readSize; i++) {
                                int sectorEndOffset = (i * 512) - 2;
                                if (sectorEndOffset + 2 <= readSize) {
                                    short originalValue = indxBuf.getShort(updateSeqOff + i * 2);
                                    indxBuf.putShort(sectorEndOffset, originalValue);
                                }
                            }

                            // Parse entries starting at 24 + entriesOff
                            int entryOffset = 24 + entriesOff;
                            int entriesFound = 0;
                            while (entryOffset + 16 < readSize && entriesFound < 50) {
                                long mftRef = indxBuf.getLong(entryOffset) & 0x0000FFFFFFFFFFFFL;
                                int entryLen = indxBuf.getShort(entryOffset + 8) & 0xFFFF;
                                int streamLen = indxBuf.getShort(entryOffset + 10) & 0xFFFF;
                                int flags = indxBuf.getShort(entryOffset + 12) & 0xFFFF;

                                if (entryLen < 16) {
                                    System.out.println("    Entry " + entriesFound + " at offset " + entryOffset + ": invalid length " + entryLen);
                                    break;
                                }

                                String flagStr = "";
                                if ((flags & 0x01) != 0) flagStr += "SUBNODE ";
                                if ((flags & 0x02) != 0) flagStr += "LAST ";

                                System.out.println("    Entry " + entriesFound + " at offset " + entryOffset +
                                                   ": mftRef=" + mftRef + ", len=" + entryLen +
                                                   ", streamLen=" + streamLen + ", flags=" + flags + " [" + flagStr.trim() + "]");

                                // Try to parse filename from stream
                                if (streamLen >= 66 && (flags & 0x02) == 0) {
                                    int fnNameLen = indxBuf.get(entryOffset + 16 + 64) & 0xFF;
                                    if (fnNameLen > 0 && entryOffset + 16 + 66 + fnNameLen * 2 <= readSize) {
                                        byte[] nameBytes = new byte[fnNameLen * 2];
                                        indxBuf.position(entryOffset + 16 + 66);
                                        indxBuf.get(nameBytes);
                                        String fileName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_16LE);
                                        System.out.println("      -> Filename: " + fileName);
                                    }
                                }

                                entriesFound++;

                                if ((flags & 0x02) != 0) {
                                    System.out.println("    Reached LAST entry");
                                    break;
                                }

                                entryOffset += entryLen;
                            }
                            System.out.println("    Total entries found in first INDX block: " + entriesFound);
                        }
                    }
                }
                if (attr.type() == NtfsAttribute.TYPE_INDEX_ROOT && attr.isResident()) {
                    var indexRoot = attr.asIndexRoot();
                    if (indexRoot.isPresent()) {
                        System.out.println("    Index block size: " + indexRoot.get().indexBlockSize());
                        System.out.println("    Index entries: " + indexRoot.get().entries().size());
                        for (var entry : indexRoot.get().entries()) {
                            System.out.println("      MFT ref: " + entry.mftReference() +
                                             ", flags: " + entry.indexFlags() +
                                             ", last: " + entry.isLastEntry() +
                                             ", hasSubnode: " + entry.hasSubnode());
                        }
                    }
                }
            }

            // List directory
            System.out.println("\n--- Listing root directory via listDirectory() ---");
            var entries = ntfs.listDirectory(rootRecord);
            System.out.println("Found " + entries.size() + " entries");
            for (MftRecord entry : entries) {
                String name = entry.getLongFileName().orElse("(no name)");
                String type = entry.isDirectory() ? "DIR" : "FILE";
                System.out.println("  " + type + ": " + name + " (record " + entry.recordNumber() + ")");
            }

            // Check MFT data runs (the MFT itself might be fragmented)
            System.out.println("\n--- Checking MFT $DATA attribute ---");
            MftRecord mftRecord = ntfs.readMftRecord(MftRecord.MFT_RECORD_MFT);
            var mftDataAttr = mftRecord.findAttribute(NtfsAttribute.TYPE_DATA);
            if (mftDataAttr.isPresent()) {
                NtfsAttribute dataAttr = mftDataAttr.get();
                System.out.println("MFT $DATA resident: " + dataAttr.isResident());
                System.out.println("MFT $DATA size: " + dataAttr.dataSize());
                System.out.println("MFT $DATA runs: " + dataAttr.dataRuns().size());
                int mftRecordSize = 1024; // typical MFT record size
                long maxRecordFromSize = dataAttr.dataSize() / mftRecordSize;
                System.out.println("Estimated max MFT record from size: " + maxRecordFromSize);

                long cumulativeOffset = 0;
                for (int i = 0; i < Math.min(10, dataAttr.dataRuns().size()); i++) {
                    var run = dataAttr.dataRuns().get(i);
                    long runBytes = run.length() * clusterSize;
                    long startRecord = cumulativeOffset / mftRecordSize;
                    long endRecord = (cumulativeOffset + runBytes) / mftRecordSize - 1;
                    System.out.println("  Run " + i + ": LCN=" + run.lcn() +
                                       ", length=" + run.length() + " clusters (" + runBytes + " bytes)" +
                                       ", covers records " + startRecord + "-" + endRecord);
                    cumulativeOffset += runBytes;
                }
                if (dataAttr.dataRuns().size() > 10) {
                    System.out.println("  ... and " + (dataAttr.dataRuns().size() - 10) + " more runs");
                }
            }

            // Try reading specific high-numbered records
            System.out.println("\n--- Trying to read specific MFT records from INDX entries ---");
            int[] testRecords = {1289, 990, 1160, 17, 451};  // From INDX parsing
            for (int recNum : testRecords) {
                try {
                    MftRecord record = ntfs.readMftRecord(recNum);
                    System.out.println("Record " + recNum + ": in_use=" + record.isInUse() +
                                       ", name=" + record.getLongFileName().orElse("(none)"));
                } catch (Exception e) {
                    System.out.println("Record " + recNum + ": ERROR - " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            // Scan MFT for entries with WINNT as parent (record 17)
            System.out.println("\n--- Scanning MFT for WINNT children (parent ref 17) ---");
            int winntChildrenFound = 0;
            for (int i = 0; i < 3000 && winntChildrenFound < 100; i++) {
                try {
                    MftRecord record = ntfs.readMftRecord(i);
                    if (record.isInUse()) {
                        var parentRef = record.getParentReference();
                        if (parentRef.isPresent() && parentRef.get() == 17) {
                            String name = record.getLongFileName().orElse("(none)");
                            String type = record.isDirectory() ? "DIR " : "FILE";
                            System.out.println("  " + type + name + " (record " + i + ")");
                            winntChildrenFound++;
                        }
                    }
                } catch (Exception e) {
                    // Skip unreadable records
                }
            }
            System.out.println("Found " + winntChildrenFound + " entries with WINNT as parent (by MFT scan)");

            // Try scanning MFT directly for more records
            System.out.println("\n--- Scanning MFT for user files (records 24-500) ---");
            int userFiles = 0;
            int userDirs = 0;
            int inUseRecords = 0;
            for (int i = 24; i < 500; i++) {
                try {
                    MftRecord record = ntfs.readMftRecord(i);
                    if (record.isInUse()) {
                        inUseRecords++;
                        String name = record.getLongFileName().orElse(null);
                        if (name != null && !name.startsWith("$")) {
                            if (record.isDirectory()) {
                                userDirs++;
                                if (userDirs <= 10) {
                                    System.out.println("  DIR:  " + name + " (record " + i + ")");
                                }
                            } else {
                                userFiles++;
                                if (userFiles <= 20) {
                                    System.out.println("  FILE: " + name + " (record " + i + ")");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unreadable records
                }
            }
            System.out.println("In-use records in range 24-500: " + inUseRecords);
            System.out.println("Found " + userFiles + " user files, " + userDirs + " user dirs by MFT scan");

            ntfs.close();
        }
    }

    @Test
    @EnabledIf("xpModeImageExists")
    void testWindowsXpModeNtfs31() throws Exception {
        System.out.println("=== Windows XP Mode NTFS 3.1 Test ===\n");
        System.out.println("Image: " + XP_MODE_IMAGE.getFileName());

        try (VirtualDisk disk = DiskReader.open(XP_MODE_IMAGE)) {
            System.out.println("Disk format: " + disk.format());
            System.out.println("Virtual size: " + (disk.virtualSize() / (1024L * 1024 * 1024)) + " GB");

            // Check partition table
            Optional<PartitionTable> pt = PartitionTable.detect(disk);
            if (pt.isPresent()) {
                System.out.println("\nPartition table: " + pt.get().type());
                System.out.println("Partitions: " + pt.get().partitions().size());
                for (Partition p : pt.get().partitions()) {
                    System.out.println("  - Start LBA: " + p.startLba() +
                                       ", Size: " + (p.sizeInSectors() * 512 / (1024L * 1024)) + " MB");
                }
            }

            // Mount filesystem
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {
                System.out.println("\nFilesystem type: " + fs.type());
                System.out.println("Label: " + fs.label().orElse("(none)"));
                System.out.println("Total size: " + (fs.totalSize() / (1024L * 1024)) + " MB");
                System.out.println("UUID: " + fs.uuid().orElse("(none)"));

                // List root directory
                System.out.println("\nRoot directory contents:");
                try (Stream<FileSystemEntry> entries = fs.root().list()) {
                    entries.forEach(e -> {
                        String type = e instanceof FileSystemEntry.Directory ? "DIR " : "FILE";
                        System.out.println("  " + type + e.name());
                    });
                }

                // Check for Windows directory
                var windowsDir = fs.resolve("/WINDOWS");
                if (windowsDir.isPresent() && windowsDir.get() instanceof FileSystemEntry.Directory winDir) {
                    System.out.println("\nWINDOWS directory found!");
                    try (Stream<FileSystemEntry> children = winDir.list()) {
                        long count = children.count();
                        System.out.println("WINDOWS children count: " + count);
                    }

                    // Check for system32
                    var sys32 = fs.resolve("/WINDOWS/system32");
                    if (sys32.isPresent()) {
                        System.out.println("system32 directory found!");
                    }
                }

                // Walk and count (limited)
                System.out.println("\n--- Walking filesystem (limited to 5000 entries) ---");
                AtomicLong fileCount = new AtomicLong(0);
                AtomicLong dirCount = new AtomicLong(0);

                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.limit(5000).forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            fileCount.incrementAndGet();
                        } else if (entry instanceof FileSystemEntry.Directory) {
                            dirCount.incrementAndGet();
                        }
                    });
                }

                System.out.println("Files found: " + fileCount.get());
                System.out.println("Directories found: " + dirCount.get());
                System.out.println("\nWindows XP Mode NTFS 3.1 test completed successfully!");
            }
        }
    }

    private String attrTypeName(int type) {
        return switch (type) {
            case 0x10 -> "STANDARD_INFORMATION";
            case 0x20 -> "ATTRIBUTE_LIST";
            case 0x30 -> "FILE_NAME";
            case 0x40 -> "OBJECT_ID";
            case 0x50 -> "SECURITY_DESCRIPTOR";
            case 0x60 -> "VOLUME_NAME";
            case 0x70 -> "VOLUME_INFORMATION";
            case 0x80 -> "DATA";
            case 0x90 -> "INDEX_ROOT";
            case 0xA0 -> "INDEX_ALLOCATION";
            case 0xB0 -> "BITMAP";
            case 0xC0 -> "REPARSE_POINT";
            default -> "UNKNOWN";
        };
    }
}
