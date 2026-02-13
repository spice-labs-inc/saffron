/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.filesystem.xfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Diagnostic test to understand XFS directory reading failures.
 */
class XfsDiagnosticTest {

    private static final Path CENTOS_IMAGE = Path.of("test-corpus/qcow2/modern/centos-stream-9-cloud.qcow2");

    static boolean hasCentosImage() {
        return Files.exists(CENTOS_IMAGE);
    }

    @Test
    @EnabledIf("hasCentosImage")
    void diagnoseXfsDirectoryReading() throws Exception {
        try (VirtualDisk disk = DiskReader.open(CENTOS_IMAGE)) {
            Optional<PartitionTable> table = PartitionTable.detect(disk);
            if (table.isEmpty()) {
                System.out.println("No partition table found");
                return;
            }

            // Find the largest partition (root)
            Partition rootPartition = table.get().partitions().stream()
                    .filter(p -> p.sizeInSectors() > 1000000)
                    .findFirst()
                    .orElse(null);

            if (rootPartition == null) {
                System.out.println("No large partition found");
                return;
            }

            long partitionOffset = rootPartition.startLba() * 512;
            Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, partitionOffset);
            System.out.println("Detected FS: " + fsInfo);

            XfsFileSystemImpl xfs = XfsFileSystemImpl.mount(disk, partitionOffset);
            System.out.println("XFS mounted, blockSize=" + xfs.blockSize() + " isV5=" + xfs.metadata().get("version"));

            // Read root inode
            XfsSuperblock sb = XfsSuperblock.read(
                    io.spicelabs.saffron.lvm.DiskRegion.fromPartition(disk, partitionOffset, 0));
            XfsInode rootInode = xfs.readInode(sb.rootInode());

            System.out.println("Root inode #" + rootInode.inodeNumber() +
                    " format=" + formatName(rootInode.format()) +
                    " size=" + rootInode.size() +
                    " extentCount=" + rootInode.extentCount() +
                    " dataForkLen=" + rootInode.dataFork().length);

            // Read root directory entries
            List<XfsDirectoryEntry> rootEntries = xfs.readDirectoryEntries(rootInode);
            System.out.println("Root directory entries: " + rootEntries.size());
            for (XfsDirectoryEntry entry : rootEntries) {
                if (!entry.isDot() && !entry.isDotDot()) {
                    System.out.println("  " + entry.name() + " -> inode " + entry.inode() + " type=" + entry.fileType());
                }
            }

            // For each subdirectory, read its inode and check format
            System.out.println("\n=== Subdirectory Analysis ===");
            for (XfsDirectoryEntry entry : rootEntries) {
                if (entry.isDot() || entry.isDotDot()) continue;
                if (entry.fileType() != XfsDirectoryEntry.FT_DIR && entry.fileType() != XfsDirectoryEntry.FT_UNKNOWN) continue;

                try {
                    XfsInode childInode = xfs.readInode(entry.inode());
                    if (!childInode.isValid() || !childInode.isDirectory()) continue;

                    System.out.println("\n/" + entry.name() + " (inode " + entry.inode() + "):");
                    System.out.println("  format=" + formatName(childInode.format()) +
                            " size=" + childInode.size() +
                            " extentCount=" + childInode.extentCount() +
                            " blockCount=" + childInode.blockCount() +
                            " dataForkLen=" + childInode.dataFork().length);

                    if (childInode.hasInlineData()) {
                        List<XfsDirectoryEntry> entries = XfsDirectoryEntry.parseShortform(
                                childInode.dataFork(), childInode.inodeNumber(), true);
                        System.out.println("  shortform entries: " + entries.size());
                    } else if (childInode.hasExtents()) {
                        // Dump raw data fork bytes for extent parsing analysis
                        byte[] df = childInode.dataFork();
                        System.out.print("  dataFork hex (first 64 bytes): ");
                        for (int b = 0; b < Math.min(64, df.length); b++) {
                            System.out.printf("%02x ", df[b] & 0xFF);
                            if ((b + 1) % 16 == 0) System.out.print("| ");
                        }
                        System.out.println();

                        List<XfsExtent> extents = XfsExtent.parseExtents(
                                childInode.dataFork(), childInode.extentCount());
                        System.out.println("  extents: " + extents.size());
                        for (int i = 0; i < Math.min(5, extents.size()); i++) {
                            XfsExtent ext = extents.get(i);
                            System.out.println("    extent[" + i + "]: logical=" + ext.logicalOffset() +
                                    " physical=" + ext.physicalBlock() +
                                    " blocks=" + ext.blockCount() +
                                    " prealloc=" + ext.prealloc());

                            // Read the first block and check its magic
                            if (ext.physicalBlock() > 0 && ext.blockCount() > 0) {
                                ByteBuffer blockBuf = xfs.readBlock(ext.physicalBlock());
                                byte[] block = new byte[xfs.blockSize()];
                                blockBuf.get(block);
                                int magic = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN).getInt(0);
                                System.out.println("    block magic: 0x" + Integer.toHexString(magic) +
                                        " (" + magicName(magic) + ")");

                                // Try parsing it
                                List<XfsDirectoryEntry> blockEntries =
                                        XfsDirectoryEntry.parseBlock(block, xfs.blockSize(), true);
                                System.out.println("    parsed entries: " + blockEntries.size());
                            }
                        }

                        // Also try calling readDirectoryEntries to see what it returns
                        List<XfsDirectoryEntry> dirEntries = xfs.readDirectoryEntries(childInode);
                        System.out.println("  readDirectoryEntries result: " + dirEntries.size());
                    } else if (childInode.hasBtree()) {
                        System.out.println("  B-tree root:");
                        XfsExtent.BtreeRoot root = XfsExtent.parseBtreeRoot(childInode.dataFork());
                        System.out.println("    level=" + root.level() +
                                " numrecs=" + root.numrecs());
                        System.out.println("    pointers: " + root.pointers());

                        // Read first pointer block
                        if (!root.pointers().isEmpty()) {
                            long firstPtr = root.pointers().get(0);
                            System.out.println("    reading first B-tree block at " + firstPtr);
                            ByteBuffer blockBuf = xfs.readBlock(firstPtr);
                            byte[] block = new byte[xfs.blockSize()];
                            blockBuf.get(block);
                            int magic = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN).getInt(0);
                            System.out.println("    block magic: 0x" + Integer.toHexString(magic) +
                                    " (" + magicName(magic) + ")");

                            XfsExtent.BtreeBlockHeader header = XfsExtent.BtreeBlockHeader.parse(
                                    ByteBuffer.wrap(block), true);
                            System.out.println("    header: magic=0x" + Integer.toHexString(header.magic()) +
                                    " level=" + header.level() +
                                    " numrecs=" + header.numrecs() +
                                    " valid=" + header.isValid());
                        }

                        // Also try full readDirectoryEntries
                        List<XfsDirectoryEntry> dirEntries = xfs.readDirectoryEntries(childInode);
                        System.out.println("  readDirectoryEntries result: " + dirEntries.size());
                    }
                } catch (Exception e) {
                    System.out.println("  ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
    }

    private static String formatName(int format) {
        return switch (format) {
            case 0 -> "DEV";
            case 1 -> "LOCAL(shortform)";
            case 2 -> "EXTENTS";
            case 3 -> "BTREE";
            case 4 -> "UUID";
            default -> "UNKNOWN(" + format + ")";
        };
    }

    private static String magicName(int magic) {
        return switch (magic) {
            case 0x58444233 -> "XDB3 (v5 single-block dir)";
            case 0x58444433 -> "XDD3 (v5 multi-block data)";
            case 0x58443242 -> "XD2B (v4 single-block dir)";
            case 0x58443244 -> "XD2D (v4 multi-block data)";
            case 0x58444C33 -> "XDL3 (v5 leaf)";
            case 0x5844324C -> "XD2L (v4 leaf)";
            case 0x58444633 -> "XDF3 (v5 freeindex)";
            case 0x58443246 -> "XD2F (v4 freeindex)";
            case 0x424D4150 -> "BMAP (v4 btree)";
            case 0x424D4133 -> "BMA3 (v5 btree)";
            default -> "unknown";
        };
    }
}
