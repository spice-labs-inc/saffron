/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.xfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.corpus.RequiresImage;
import io.spicelabs.saffron.corpus.TestCorpusUtils;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

/**
 * Diagnostic tests for Amazon Linux XFS filesystem.
 *
 * <p>These tests are specifically designed to diagnose XFS implementation issues
 * with Amazon Linux images. They use filesystem-aware discovery to find any
 * XFS image when Amazon Linux is not available.
 */
class AmazonLinuxDiagnosticTest {

    /**
     * Condition method - prefer Amazon Linux, but accept any XFS image.
     */
    static boolean hasXfsImage() {
        return TestCorpusUtils.hasFilesystem("xfs");
    }

    @Test
    @RequiresImage(filesystem = "xfs")
    void diagnoseXfsFilesystem() throws Exception {
        // Try to find Amazon Linux specifically, fall back to any XFS image
        Path imagePath = TestCorpusUtils.findImageWithFilesystemAndFormat("xfs", "qcow2")
                .filter(p -> p.toString().contains("amazon"))
                .orElseGet(() -> TestCorpusUtils.findBestTestImage("xfs")
                        .orElseThrow(() -> new AssertionError("No XFS image found")));

        System.out.println("Running XFS diagnostics with: " + imagePath);

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);

            for (FileSystem fs : allFs) {
                if (!(fs instanceof XfsFileSystemImpl xfs)) continue;
                System.out.println("XFS: blockSize=" + xfs.blockSize() + " isV5=" + xfs.metadata().get("version"));

                // Find root inode from . entry
                var rootDir = xfs.root();
                // Get root inode number from readDirectoryEntries
                // We need to find the . entry in root directory
                // Use resolve to navigate
                for (String checkPath : new String[]{"/usr/share/man/man1", "/usr/bin", "/usr/lib64"}) {
                    var resolved = xfs.resolve(checkPath);
                    if (resolved.isPresent() && resolved.get() instanceof io.spicelabs.saffron.fs.FileSystemEntry.Directory dir) {
                        System.out.println("\n=== " + checkPath + " (size=" + dir.size() + ") ===");
                        // We can only look at this through list()
                        try (var children = dir.list()) {
                            long count = children.count();
                            System.out.println("  list() children: " + count);
                        }
                    } else {
                        System.out.println("\n" + checkPath + ": NOT FOUND");
                    }
                }

                // Read the root inode from the superblock
                ByteBuffer sbBuf = xfs.readBlock(0);
                byte[] sbBytes = new byte[xfs.blockSize()];
                sbBuf.get(sbBytes);
                long rootIno = ByteBuffer.wrap(sbBytes).order(ByteOrder.BIG_ENDIAN).getLong(56);
                System.out.println("\nRoot inode from superblock: " + rootIno);
                diagnoseDir(xfs, rootIno, "/usr/share/man/man1");
                diagnoseDir(xfs, rootIno, "/usr/bin");
            }
        }
    }

    private void diagnoseDir(XfsFileSystemImpl xfs, long rootIno, String path) throws Exception {
        System.out.println("\n=== Diagnosing " + path + " ===");
        long ino = rootIno;
        String[] parts = path.substring(1).split("/");
        for (String part : parts) {
            List<XfsDirectoryEntry> entries = xfs.readDirectoryEntries(xfs.readInode(ino));
            boolean found = false;
            for (XfsDirectoryEntry e : entries) {
                if (part.equals(e.name())) {
                    ino = e.inode();
                    found = true;
                    break;
                }
            }
            if (!found) {
                System.out.println("  NOT FOUND: " + part + " in inode " + ino);
                return;
            }
        }

        XfsInode inode = xfs.readInode(ino);
        System.out.println("Inode " + ino + ":");
        System.out.println("  format=" + inode.format() + " size=" + inode.size() +
                " extentCount=" + inode.extentCount() + " blockCount=" + inode.blockCount() +
                " forkOff=" + inode.forkOffset() + " dataForkLen=" + inode.dataFork().length);

        List<XfsExtent> extents;
        if (inode.hasExtents()) {
            extents = XfsExtent.parseExtents(inode.dataFork(), inode.extentCount());
            System.out.println("  format=EXTENTS, parsed " + extents.size() + " extents:");
        } else if (inode.hasBtree()) {
            System.out.println("  format=BTREE");
            XfsExtent.BtreeRoot root = XfsExtent.parseBtreeRoot(inode.dataFork());
            System.out.println("  root: level=" + root.level() + " numrecs=" + root.numrecs());
            System.out.println("  pointers: " + root.pointers());
            return; // Can't easily dump B-tree extents here
        } else {
            System.out.println("  format=LOCAL (shortform)");
            return;
        }

        long leafBlockOffset = 32L * 1024 * 1024 * 1024 / xfs.blockSize();
        int totalEntries = 0;
        for (int e = 0; e < extents.size(); e++) {
            XfsExtent ext = extents.get(e);
            String isLeaf = ext.logicalOffset() >= leafBlockOffset ? " [LEAF/FREE - SKIPPED]" : "";
            System.out.println("  ext[" + e + "] logical=" + ext.logicalOffset() +
                    " physical=" + ext.physicalBlock() + " blocks=" + ext.blockCount() +
                    " prealloc=" + ext.prealloc() + isLeaf);

            if (ext.logicalOffset() >= leafBlockOffset) continue;

            // Read each block and check magic + entry count
            for (int i = 0; i < ext.blockCount() && ext.logicalOffset() + i < leafBlockOffset; i++) {
                long fsbno = ext.physicalBlock() + i;
                long offset = xfs.fsBlockToByteOffset(fsbno);
                ByteBuffer blockBuf = xfs.readBlock(fsbno);
                byte[] block = new byte[xfs.blockSize()];
                blockBuf.get(block);
                int magic = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN).getInt(0);

                List<XfsDirectoryEntry> blockEntries = XfsDirectoryEntry.parseBlock(block, xfs.blockSize(), true);
                totalEntries += blockEntries.size();

                if (i < 3 || i == ext.blockCount() - 1) {
                    System.out.println("    block[" + i + "] magic=0x" + Integer.toHexString(magic) +
                            " offset=" + offset + " entries=" + blockEntries.size());
                }
            }
        }
        System.out.println("  TOTAL entries from all data blocks: " + totalEntries);

        List<XfsDirectoryEntry> dirEntries = xfs.readDirectoryEntries(inode);
        System.out.println("  readDirectoryEntries result: " + dirEntries.size());
    }

    private long resolveInodeNumber(XfsFileSystemImpl xfs, String path) throws Exception {
        // For root, read the "." entry from the root directory
        var rootDir = xfs.root();
        // The root inode is in the . entry of the root directory listing
        XfsInode rootInode = xfs.readInode(128); // Try standard root inode
        if (rootInode.isValid() && rootInode.isDirectory()) {
            return 128;
        }
        // Fall back to 64
        rootInode = xfs.readInode(64);
        if (rootInode.isValid() && rootInode.isDirectory()) {
            return 64;
        }
        throw new RuntimeException("Can't find root inode");
    }
}
