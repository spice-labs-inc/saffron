/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.btrfs;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Btrfs superblock parsing.
 *
 * <p>These tests require Btrfs disk images. Fedora cloud images use Btrfs by default.
 * Download from: https://fedoraproject.org/cloud/download
 *
 * <p>Place images in test-corpus/qcow2/modern/ or set BTRFS_TEST_IMAGE environment variable.
 */
class BtrfsSuperblockTest {

    // Fedora cloud images use Btrfs by default
    private static final Path FEDORA_IMAGE = Path.of("test-corpus/qcow2/modern/Fedora-Cloud-Base-Generic-42-1.1.x86_64.qcow2");
    private static final Path FEDORA_IMAGE_ALT = Path.of("test-corpus/qcow2/cloud/fedora/fedora-40-cloud-base-amd64.qcow2");

    // Allow custom image via environment variable
    private static final String BTRFS_TEST_IMAGE_ENV = System.getenv("BTRFS_TEST_IMAGE");

    static boolean hasBtrfsTestImage() {
        if (BTRFS_TEST_IMAGE_ENV != null && Files.exists(Path.of(BTRFS_TEST_IMAGE_ENV))) {
            return true;
        }
        return Files.exists(FEDORA_IMAGE) || Files.exists(FEDORA_IMAGE_ALT);
    }

    private static Path getBtrfsTestImage() {
        if (BTRFS_TEST_IMAGE_ENV != null && Files.exists(Path.of(BTRFS_TEST_IMAGE_ENV))) {
            return Path.of(BTRFS_TEST_IMAGE_ENV);
        }
        if (Files.exists(FEDORA_IMAGE)) return FEDORA_IMAGE;
        return FEDORA_IMAGE_ALT;
    }

    @Test
    @EnabledIf("hasBtrfsTestImage")
    void parseBtrfsSuperblock() throws Exception {
        Path imagePath = getBtrfsTestImage();
        System.out.println("Testing with: " + imagePath);

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Find Btrfs partition
            long btrfsOffset = findBtrfsPartition(disk);

            if (btrfsOffset < 0) {
                System.out.println("No Btrfs partition found in " + imagePath + ", skipping superblock test");
                return;
            }

            DiskRegion region = DiskRegion.fromPartition(disk, btrfsOffset, 0);
            BtrfsSuperblock sb = BtrfsSuperblock.read(region, 0);

            // Verify superblock fields
            assertThat(sb.nodeSize()).isGreaterThanOrEqualTo(4096);
            assertThat(sb.sectorSize()).isGreaterThanOrEqualTo(512);
            assertThat(sb.totalBytes()).isGreaterThan(0);
            assertThat(sb.generation()).isGreaterThan(0);
            assertThat(sb.uuid()).isNotNull().isNotEmpty();
            assertThat(sb.rootTreeRoot()).isGreaterThan(0);
            assertThat(sb.chunkTreeRoot()).isGreaterThan(0);

            System.out.println("Btrfs superblock:");
            System.out.println("  UUID: " + sb.uuid());
            System.out.println("  Label: " + (sb.label().isEmpty() ? "<none>" : sb.label()));
            System.out.println("  Node size: " + sb.nodeSize());
            System.out.println("  Sector size: " + sb.sectorSize());
            System.out.println("  Total size: " + sb.totalBytes() / 1024 / 1024 + " MB");
            System.out.println("  Generation: " + sb.generation());
        }
    }

    @Test
    void testBtrfsKeyConstants() {
        // Verify key constants are correct per Btrfs spec
        assertThat(BtrfsKey.INODE_ITEM).isEqualTo(1);
        assertThat(BtrfsKey.DIR_ITEM).isEqualTo(84);
        assertThat(BtrfsKey.DIR_INDEX).isEqualTo(96);
        assertThat(BtrfsKey.EXTENT_DATA).isEqualTo(108);
        assertThat(BtrfsKey.ROOT_ITEM).isEqualTo(132);
        assertThat(BtrfsKey.CHUNK_ITEM).isEqualTo(228);

        assertThat(BtrfsKey.FS_TREE_OBJECTID).isEqualTo(5);
        assertThat(BtrfsKey.FIRST_FREE_OBJECTID).isEqualTo(256);
    }

    @Test
    void testBtrfsInodeTypeConstants() {
        assertThat(BtrfsInode.S_IFREG).isEqualTo(0100000);
        assertThat(BtrfsInode.S_IFDIR).isEqualTo(0040000);
        assertThat(BtrfsInode.S_IFLNK).isEqualTo(0120000);
        assertThat(BtrfsInode.S_IFBLK).isEqualTo(0060000);
        assertThat(BtrfsInode.S_IFCHR).isEqualTo(0020000);
    }

    @Test
    void testBtrfsDirectoryEntryTypes() {
        assertThat(BtrfsDirectoryEntry.FT_REG_FILE).isEqualTo(1);
        assertThat(BtrfsDirectoryEntry.FT_DIR).isEqualTo(2);
        assertThat(BtrfsDirectoryEntry.FT_SYMLINK).isEqualTo(7);
    }

    @Test
    void testBtrfsExtentDataTypes() {
        assertThat(BtrfsExtentData.TYPE_INLINE).isEqualTo(0);
        assertThat(BtrfsExtentData.TYPE_REGULAR).isEqualTo(1);
        assertThat(BtrfsExtentData.TYPE_PREALLOC).isEqualTo(2);
        assertThat(BtrfsExtentData.COMPRESS_NONE).isEqualTo(0);
        assertThat(BtrfsExtentData.COMPRESS_ZLIB).isEqualTo(1);
        assertThat(BtrfsExtentData.COMPRESS_ZSTD).isEqualTo(3);
    }

    private long findBtrfsPartition(VirtualDisk disk) throws IOException {
        Optional<PartitionTable> table = PartitionTable.detect(disk);
        if (table.isPresent()) {
            for (Partition p : table.get().partitions()) {
                if (p.sizeInSectors() < 10000) continue;

                long offset = p.startLba() * 512;
                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);

                if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.BTRFS) {
                    return offset;
                }
            }
        }

        // Check at offset 0
        Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, 0);
        if (fsInfo.isPresent() && fsInfo.get().type() == FileSystem.FileSystemType.BTRFS) {
            return 0;
        }
        return -1;
    }
}
