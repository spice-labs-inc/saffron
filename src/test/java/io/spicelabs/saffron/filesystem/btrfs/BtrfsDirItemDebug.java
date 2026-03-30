/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Debug tool to analyze Btrfs DIR_ITEM entries for subvolume name resolution.
 */
public class BtrfsDirItemDebug {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        String fedoraPath = CORPUS_BASE + "/qcow2/modern/Fedora-Cloud-Base-Generic-42-1.1.x86_64.qcow2";

        System.out.println("=== Analyzing Btrfs DIR_ITEM entries ===");
        analyzeDirItems(fedoraPath);
    }

    private static void analyzeDirItems(String imagePath) throws IOException {
        Path path = Path.of(imagePath);
        if (!java.nio.file.Files.exists(path)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(path)) {
            Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);
            if (tableOpt.isEmpty()) {
                System.out.println("No partition table found");
                return;
            }

            PartitionTable table = tableOpt.get();
            List<Partition> partitions = table.partitions();

            for (int i = 0; i < partitions.size(); i++) {
                Partition p = partitions.get(i);
                long offset = p.startLba() * 512;

                var fsInfoOpt = io.spicelabs.saffron.filesystem.FilesystemDetector.detect(disk, offset);
                if (fsInfoOpt.isPresent() && fsInfoOpt.get().type() == FileSystem.FileSystemType.BTRFS) {
                    System.out.println("\n=== Partition " + (i+1) + " - Btrfs ===");

                    DiskRegion region = DiskRegion.fromPartition(disk, offset, 0);
                    BtrfsSuperblock superblock = BtrfsSuperblock.read(region, 0);
                    BtrfsChunkTree chunkTree = BtrfsChunkTree.parse(region, 0, superblock);
                    BtrfsTreeReader treeReader = new BtrfsTreeReader(chunkTree, superblock.nodeSize());

                    // Find FS_TREE root
                    long fsTreeRoot = findFsTreeRoot(treeReader, superblock.rootTreeRoot());
                    System.out.println("FS_TREE root: " + fsTreeRoot);

                    // Scan DIR_ITEM entries
                    System.out.println("\n--- DIR_ITEM entries in root directory ---");
                    List<BtrfsTreeReader.SearchResult> dirItems = treeReader.scanForType(
                            fsTreeRoot, BtrfsKey.DIR_ITEM, 1000);

                    System.out.println("Found " + dirItems.size() + " DIR_ITEM entries:");
                    for (BtrfsTreeReader.SearchResult result : dirItems) {
                        byte[] data = result.data();
                        if (data.length < 16) continue;

                        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                        long locationObjectId = buf.getLong();
                        long locationOffset = buf.getLong();
                        int type = buf.get() & 0xFF;
                        int nameLen = buf.getShort() & 0xFFFF;
                        buf.getLong(); // transid

                        String name = "";
                        if (nameLen > 0 && nameLen < 256 && buf.remaining() >= nameLen) {
                            byte[] nameBytes = new byte[nameLen];
                            buf.get(nameBytes);
                            name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                        }

                        String typeStr = switch (type) {
                            case 1 -> "FT_REG_FILE";
                            case 2 -> "FT_DIR";
                            case 3 -> "FT_CHRDEV";
                            case 4 -> "FT_BLKDEV";
                            case 5 -> "FT_FIFO";
                            case 6 -> "FT_SOCK";
                            case 7 -> "FT_SYMLINK";
                            default -> "UNKNOWN(" + type + ")";
                        };

                        System.out.println("  Name: '" + name + "' -> locationObjectId=" + locationObjectId +
                                ", type=" + typeStr);
                    }

                    // Scan ROOT_ITEM entries for subvolumes
                    System.out.println("\n--- ROOT_ITEM entries (subvolumes) ---");
                    List<BtrfsTreeReader.SearchResult> rootItems = treeReader.scanForType(
                            superblock.rootTreeRoot(), BtrfsKey.ROOT_ITEM, 10000);

                    for (BtrfsTreeReader.SearchResult result : rootItems) {
                        long objId = result.item().key().objectId();
                        if (objId < 256) continue; // Skip system roots

                        byte[] data = result.data();
                        if (data.length < 184) continue;

                        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                        buf.position(176);
                        long treeRoot = buf.getLong();

                        System.out.println("  Subvolume objectId=" + objId + ", treeRoot=" + treeRoot);
                    }
                }
            }
        }
    }

    private static long findFsTreeRoot(BtrfsTreeReader reader, long rootTreeRoot) throws IOException {
        List<BtrfsTreeReader.SearchResult> results = reader.search(
                rootTreeRoot, BtrfsKey.FS_TREE_OBJECTID, BtrfsKey.ROOT_ITEM);

        if (results.isEmpty()) {
            throw new IOException("FS_TREE root not found");
        }

        byte[] data = results.get(0).data();
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(176);
        return buf.getLong();
    }
}
