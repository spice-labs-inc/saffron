/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

/**
 * Debug tool to analyze Btrfs ROOT_TREE entries for subvolume name resolution.
 */
public class BtrfsRootTreeDebug {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        String fedoraPath = CORPUS_BASE + "/qcow2/modern/Fedora-Cloud-Base-Generic-42-1.1.x86_64.qcow2";

        System.out.println("=== Analyzing Btrfs ROOT_TREE entries ===");
        analyzeRootTree(fedoraPath);
    }

    private static void analyzeRootTree(String imagePath) throws IOException {
        Path path = Path.of(imagePath);
        if (!java.nio.file.Files.exists(path)) {
            System.out.println("Image not found: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(path)) {
            PartitionTable tableOpt = PartitionTable.detect(disk).orElse(null);
            if (tableOpt == null) {
                System.out.println("No partition table found");
                return;
            }

            List<Partition> partitions = tableOpt.partitions();

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

                    long rootTreeRoot = superblock.rootTreeRoot();
                    System.out.println("ROOT_TREE root: " + rootTreeRoot);

                    // Scan all items in ROOT_TREE
                    System.out.println("\n--- All ROOT_TREE entries ---");
                    List<BtrfsTreeReader.SearchResult> allItems = treeReader.scanForType(
                            rootTreeRoot, (byte)-1, 10000); // -1 means all types

                    for (BtrfsTreeReader.SearchResult result : allItems) {
                        BtrfsKey key = result.item().key();
                        System.out.println("  objectId=" + key.objectId() +
                                ", type=" + key.type() +
                                ", offset=" + key.offset() +
                                " (data len=" + result.data().length + ")");
                    }

                    // Specifically look for ROOT_ITEM and ROOT_BACKREF
                    System.out.println("\n--- ROOT_ITEM entries ---");
                    List<BtrfsTreeReader.SearchResult> rootItems = treeReader.scanForType(
                            rootTreeRoot, BtrfsKey.ROOT_ITEM, 10000);

                    for (BtrfsTreeReader.SearchResult result : rootItems) {
                        BtrfsKey key = result.item().key();
                        byte[] data = result.data();

                        System.out.println("  objectId=" + key.objectId() + ", data len=" + data.length);

                        if (data.length >= 184) {
                            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

                            // ROOT_ITEM structure (first 184 bytes):
                            // inode (160) + generation (8) + root_dirid (8) + bytenr (8) + ...
                            // Let's print first 200 bytes as hex to see structure
                            System.out.print("    Hex: ");
                            for (int j = 0; j < Math.min(64, data.length); j++) {
                                System.out.printf("%02x ", data[j]);
                                if ((j+1) % 16 == 0) System.out.print("\n         ");
                            }
                            System.out.println();
                        }
                    }

                    // Look for ROOT_BACKREF (type 132)
                    System.out.println("\n--- ROOT_BACKREF entries (type 132) ---");
                    List<BtrfsTreeReader.SearchResult> backrefs = treeReader.scanForType(
                            rootTreeRoot, (byte)132, 10000);

                    for (BtrfsTreeReader.SearchResult result : backrefs) {
                        BtrfsKey key = result.item().key();
                        byte[] data = result.data();
                        System.out.println("  objectId=" + key.objectId() +
                                ", offset=" + key.offset() +
                                ", data len=" + data.length);

                        if (data.length >= 4) {
                            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                            int nameLen = buf.getShort() & 0xFFFF;
                            System.out.println("    nameLen: " + nameLen);

                            if (nameLen > 0 && nameLen < 256 && data.length >= 4 + nameLen) {
                                byte[] nameBytes = new byte[nameLen];
                                buf.get(nameBytes);
                                String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                                System.out.println("    name: '" + name + "'");
                            }
                        }
                    }
                }
            }
        }
    }
}
