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
 * Debug tool to parse full ROOT_ITEM structure for subvolume names.
 */
public class BtrfsRootItemDebug {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    public static void main(String[] args) throws Exception {
        String fedoraPath = CORPUS_BASE + "/qcow2/modern/Fedora-Cloud-Base-Generic-42-1.1.x86_64.qcow2";

        System.out.println("=== Parsing Btrfs ROOT_ITEM structure ===");
        parseRootItems(fedoraPath);
    }

    private static void parseRootItems(String imagePath) throws IOException {
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

                    // Parse ROOT_ITEM entries in detail
                    System.out.println("\n--- ROOT_ITEM detailed parsing ---");
                    List<BtrfsTreeReader.SearchResult> rootItems = treeReader.scanForType(
                            rootTreeRoot, BtrfsKey.ROOT_ITEM, 10000);

                    for (BtrfsTreeReader.SearchResult result : rootItems) {
                        long objId = result.item().key().objectId();
                        byte[] data = result.data();

                        if (data.length < 184) continue;

                        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

                        // ROOT_ITEM structure based on btrfs-progs:
                        // struct btrfs_root_item {
                        //     struct btrfs_inode_item inode;  // 160 bytes
                        //     __le64 generation;
                        //     __le64 root_dirid;
                        //     __le64 bytenr;
                        //     __le64 byte_limit;
                        //     __le64 bytes_used;
                        //     __le64 last_snapshot;
                        //     __le64 flags;
                        //     __le32 refs;
                        //     struct btrfs_disk_key drop_progress;
                        //     __u8 drop_level;
                        //     __u8 level;
                        //     // If BTRFS_ROOT_ITEM_VERSION >= 1:
                        //     __le64 generation_v2;
                        //     __u8 uuid[16];
                        //     __u8 parent_uuid[16];
                        //     __u8 received_uuid[16];
                        //     __le64 ctransid;
                        //     __le64 otransid;
                        //     __le64 stransid;
                        //     __le64 rtransid;
                        //     struct btrfs_timespec ctime;
                        //     struct btrfs_timespec otime;
                        //     struct btrfs_timespec stime;
                        //     struct btrfs_timespec rtime;
                        //     __u8 reserved[8];
                        //     // If BTRFS_ROOT_ITEM_VERSION >= 2 (since Linux 4.19):
                        //     __le64 ogeneration;
                        //     __u8 ouuid[16];
                        // }

                        System.out.println("\n  Object ID: " + objId + " (0x" + Long.toHexString(objId) + ")");
                        System.out.println("  Data length: " + data.length);

                        // Skip inode (160 bytes)
                        buf.position(160);

                        long generation = buf.getLong();
                        long rootDirid = buf.getLong();
                        long bytenr = buf.getLong();
                        long byteLimit = buf.getLong();
                        long bytesUsed = buf.getLong();
                        long lastSnapshot = buf.getLong();
                        long flags = buf.getLong();
                        int refs = buf.getInt();

                        System.out.println("  generation: " + generation);
                        System.out.println("  root_dirid: " + rootDirid);
                        System.out.println("  bytenr (tree root): " + bytenr);
                        System.out.println("  byte_limit: " + byteLimit);
                        System.out.println("  bytes_used: " + bytesUsed);
                        System.out.println("  flags: " + flags);
                        System.out.println("  refs: " + refs);

                        // Print remaining bytes as potential name
                        int remaining = buf.remaining();
                        if (remaining > 0) {
                            System.out.println("  Remaining bytes: " + remaining);
                            System.out.print("  Hex dump of remaining: ");
                            int start = buf.position();
                            for (int j = 0; j < Math.min(64, remaining); j++) {
                                System.out.printf("%02x ", data[start + j]);
                                if ((j+1) % 16 == 0) System.out.print("\n    ");
                            }
                            System.out.println();

                            // Check if any of it looks like a name
                            // Try interpreting bytes 180-220 as a string
                            if (data.length > 180) {
                                for (int startOff = 180; startOff < Math.min(220, data.length); startOff++) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int k = startOff; k < Math.min(startOff + 20, data.length); k++) {
                                        byte b = data[k];
                                        if (b >= 32 && b < 127) {
                                            sb.append((char) b);
                                        } else {
                                            break;
                                        }
                                    }
                                    if (sb.length() >= 3) {
                                        System.out.println("  Potential name at offset " + startOff + ": '" + sb + "'");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
