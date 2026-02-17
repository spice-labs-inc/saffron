/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.fs;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.filesystem.apfs.ApfsFileSystemImpl;
import io.spicelabs.saffron.filesystem.btrfs.BtrfsFileSystemImpl;
import io.spicelabs.saffron.filesystem.exfat.ExFatFileSystemImpl;
import io.spicelabs.saffron.filesystem.ext4.Ext4FileSystemImpl;
import io.spicelabs.saffron.filesystem.fat32.Fat32FileSystemImpl;
import io.spicelabs.saffron.filesystem.hfsplus.HfsPlusFileSystemImpl;
import io.spicelabs.saffron.filesystem.ntfs.NtfsFileSystemImpl;
import io.spicelabs.saffron.filesystem.xfs.XfsFileSystemImpl;
import io.spicelabs.saffron.lvm.DiskRegion;
import io.spicelabs.saffron.lvm.LogicalVolumeDisk;
import io.spicelabs.saffron.lvm.LvmVolumeGroup;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Utility class for mounting filesystems from virtual disks.
 *
 * <p>This class provides methods to detect and mount filesystems from virtual disk images,
 * handling the complexity of partition detection and filesystem type identification.
 */
public final class FileSystemMount {

    private FileSystemMount() {
        // Static utility class
    }

    /**
     * Information about a detected filesystem location.
     *
     * @param offset the byte offset where the filesystem starts
     * @param info information about the detected filesystem
     * @param partition the partition containing the filesystem, or empty if filesystem is at offset 0 without partition table
     */
    public record FilesystemLocation(
            long offset,
            @NotNull FilesystemInfo info,
            @NotNull Optional<Partition> partition
    ) {
        public FilesystemLocation {
            Objects.requireNonNull(info, "info must not be null");
            Objects.requireNonNull(partition, "partition must not be null (use Optional.empty())");
        }
    }

    /**
     * Finds all filesystems in the disk.
     *
     * @param disk the virtual disk to scan
     * @return list of filesystem locations found
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull List<FilesystemLocation> findFilesystems(@NotNull VirtualDisk disk) throws IOException {
        List<FilesystemLocation> locations = new ArrayList<>();

        // First check for partition table
        Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);

        if (tableOpt.isPresent()) {
            // Scan partitions for filesystems
            for (Partition p : tableOpt.get().partitions()) {
                // Skip tiny partitions
                if (p.sizeInSectors() < 200) continue;

                long offset = p.startLba() * 512;
                Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, offset);

                if (fsInfo.isPresent()) {
                    locations.add(new FilesystemLocation(offset, fsInfo.get(), Optional.of(p)));
                }
            }
        }

        // Also check for filesystem directly at offset 0 (some cloud images don't have partition tables)
        if (locations.isEmpty() || !hasFilesystemAtOffset(locations, 0)) {
            Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(disk, 0);
            if (fsInfo.isPresent()) {
                locations.add(new FilesystemLocation(0, fsInfo.get(), Optional.empty()));
            }
        }

        return locations;
    }

    /**
     * Finds the largest filesystem in the disk (typically the root filesystem).
     *
     * @param disk the virtual disk to scan
     * @return the largest filesystem location, or empty if none found
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FilesystemLocation> findLargestFilesystem(@NotNull VirtualDisk disk) throws IOException {
        List<FilesystemLocation> locations = findFilesystems(disk);

        return locations.stream()
                .max((a, b) -> Long.compare(a.info().totalSize(), b.info().totalSize()));
    }

    /**
     * Mounts a filesystem from the disk.
     *
     * <p>This method detects the filesystem type and uses the appropriate implementation.
     * Currently supports ext2/ext3/ext4. Other filesystem types will throw UnsupportedOperationException.
     *
     * @param disk the virtual disk containing the filesystem
     * @param location the filesystem location to mount
     * @return the mounted filesystem
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if the filesystem type is not supported
     */
    public static @NotNull FileSystem mount(@NotNull VirtualDisk disk, @NotNull FilesystemLocation location) throws IOException {
        return mount(disk, location.offset(), location.info());
    }

    /**
     * Mounts a filesystem from the disk at the specified offset.
     *
     * @param disk the virtual disk containing the filesystem
     * @param offset the byte offset where the filesystem starts
     * @param fsInfo information about the detected filesystem
     * @return the mounted filesystem
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if the filesystem type is not supported
     */
    public static @NotNull FileSystem mount(@NotNull VirtualDisk disk, long offset, @NotNull FilesystemInfo fsInfo) throws IOException {
        return switch (fsInfo.type()) {
            case EXT4 -> Ext4FileSystemImpl.mount(disk, offset);
            case NTFS -> NtfsFileSystemImpl.mount(disk, offset);
            case FAT32 -> Fat32FileSystemImpl.mount(disk, offset);
            case EXFAT -> ExFatFileSystemImpl.mount(disk, offset);
            case XFS -> XfsFileSystemImpl.mount(disk, offset);
            case BTRFS -> BtrfsFileSystemImpl.mount(disk, offset);
            case HFS_PLUS -> HfsPlusFileSystemImpl.mount(disk, offset);
            case APFS -> ApfsFileSystemImpl.mount(disk, offset);
            case UNKNOWN -> throw new UnsupportedOperationException("Unknown filesystem type");
        };
    }

    /**
     * Mounts the largest filesystem in the disk.
     *
     * @param disk the virtual disk
     * @return the mounted filesystem
     * @throws IOException if an I/O error occurs or no filesystem found
     * @throws UnsupportedOperationException if the filesystem type is not supported
     */
    public static @NotNull FileSystem mountLargest(@NotNull VirtualDisk disk) throws IOException {
        Optional<FilesystemLocation> location = findLargestFilesystem(disk);
        if (location.isEmpty()) {
            throw new IOException("No filesystem found in disk");
        }
        return mount(disk, location.get());
    }

    /**
     * Checks if the filesystem type is currently supported for mounting.
     *
     * @param type the filesystem type
     * @return true if supported
     */
    public static boolean isSupported(@NotNull FileSystem.FileSystemType type) {
        return type == FileSystem.FileSystemType.EXT4
                || type == FileSystem.FileSystemType.FAT32
                || type == FileSystem.FileSystemType.EXFAT
                || type == FileSystem.FileSystemType.NTFS
                || type == FileSystem.FileSystemType.XFS
                || type == FileSystem.FileSystemType.BTRFS
                || type == FileSystem.FileSystemType.HFS_PLUS
                || type == FileSystem.FileSystemType.APFS;
    }

    private static boolean hasFilesystemAtOffset(List<FilesystemLocation> locations, long offset) {
        return locations.stream().anyMatch(loc -> loc.offset() == offset);
    }

    // ========================================================================
    // LVM Support
    // ========================================================================

    /**
     * Information about a filesystem located within an LVM Logical Volume.
     *
     * @param logicalVolume the logical volume containing the filesystem
     * @param info information about the detected filesystem
     * @param volumeGroup the volume group containing the logical volume
     */
    public record LvmFilesystemLocation(
            @NotNull LogicalVolumeDisk logicalVolume,
            @NotNull FilesystemInfo info,
            @NotNull LvmVolumeGroup volumeGroup
    ) {
        public LvmFilesystemLocation {
            Objects.requireNonNull(logicalVolume, "logicalVolume must not be null");
            Objects.requireNonNull(info, "info must not be null");
            Objects.requireNonNull(volumeGroup, "volumeGroup must not be null");
        }
    }

    /**
     * Finds all filesystems in the disk, including those within LVM Logical Volumes.
     *
     * <p>This method first checks for regular partitions with filesystems, then
     * scans for LVM Volume Groups and detects filesystems within Logical Volumes.
     *
     * @param disk the virtual disk to scan
     * @param includeLvm whether to scan LVM volumes
     * @return list of filesystem locations found (both regular and LVM)
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull List<Object> findAllFilesystems(@NotNull VirtualDisk disk, boolean includeLvm) throws IOException {
        List<Object> allLocations = new ArrayList<>();

        // Find regular filesystem locations
        allLocations.addAll(findFilesystems(disk));

        // Find LVM filesystem locations if requested
        if (includeLvm) {
            allLocations.addAll(findLvmFilesystems(disk));
        }

        return allLocations;
    }

    /**
     * Finds filesystems within LVM Logical Volumes.
     *
     * @param disk the virtual disk to scan
     * @return list of LVM filesystem locations
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull List<LvmFilesystemLocation> findLvmFilesystems(@NotNull VirtualDisk disk) throws IOException {
        List<LvmFilesystemLocation> locations = new ArrayList<>();

        // Try to detect LVM Volume Group
        Optional<LvmVolumeGroup> vgOpt = LvmVolumeGroup.detect(disk);
        if (vgOpt.isEmpty()) {
            return locations;
        }

        LvmVolumeGroup vg = vgOpt.get();

        // Check each Logical Volume for filesystems
        for (LogicalVolumeDisk lv : vg.logicalVolumes()) {
            Optional<FilesystemInfo> fsInfo = FilesystemDetector.detect(lv);
            if (fsInfo.isPresent()) {
                locations.add(new LvmFilesystemLocation(lv, fsInfo.get(), vg));
            }
        }

        return locations;
    }

    /**
     * Finds the largest filesystem including LVM volumes.
     *
     * @param disk the virtual disk to scan
     * @return the largest filesystem (either FilesystemLocation or LvmFilesystemLocation), or empty
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<Object> findLargestFilesystemIncludingLvm(@NotNull VirtualDisk disk) throws IOException {
        List<Object> all = findAllFilesystems(disk, true);

        return all.stream()
                .max((a, b) -> Long.compare(getFilesystemSize(a), getFilesystemSize(b)));
    }

    /**
     * Gets the filesystem size from either FilesystemLocation or LvmFilesystemLocation.
     */
    private static long getFilesystemSize(Object location) {
        if (location instanceof FilesystemLocation fl) {
            return fl.info().totalSize();
        } else if (location instanceof LvmFilesystemLocation lfl) {
            return lfl.info().totalSize();
        }
        return 0;
    }

    /**
     * Mounts a filesystem from an LVM Logical Volume.
     *
     * @param location the LVM filesystem location
     * @return the mounted filesystem
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if the filesystem type is not supported
     */
    public static @NotNull FileSystem mount(@NotNull LvmFilesystemLocation location) throws IOException {
        return mount(location.logicalVolume(), location.info());
    }

    /**
     * Mounts a filesystem from a DiskRegion (supports both partitions and LVM volumes).
     *
     * @param region the disk region containing the filesystem
     * @param fsInfo information about the detected filesystem
     * @return the mounted filesystem
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if the filesystem type is not supported
     */
    public static @NotNull FileSystem mount(@NotNull DiskRegion region, @NotNull FilesystemInfo fsInfo) throws IOException {
        return switch (fsInfo.type()) {
            case EXT4 -> Ext4FileSystemImpl.mount(region);
            case NTFS -> NtfsFileSystemImpl.mount(region);
            case FAT32 -> Fat32FileSystemImpl.mount(region);
            case EXFAT -> ExFatFileSystemImpl.mount(region);
            case XFS -> XfsFileSystemImpl.mount(region);
            case BTRFS -> BtrfsFileSystemImpl.mount(region, 0);
            case HFS_PLUS -> HfsPlusFileSystemImpl.mount(region);
            case APFS -> ApfsFileSystemImpl.mount(region);
            case UNKNOWN -> throw new UnsupportedOperationException("Unknown filesystem type");
        };
    }

    /**
     * Mounts all filesystems in the disk, including LVM volumes.
     *
     * <p>For disks with LVM, returns all LVM Logical Volumes that contain recognized
     * filesystems plus any non-LVM partitions (e.g., /boot). For disks without LVM,
     * returns only the largest partition filesystem.
     *
     * @param disk the virtual disk
     * @return list of all mounted filesystems (caller must close each one)
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull List<FileSystem> mountAllIncludingLvm(@NotNull VirtualDisk disk) throws IOException {
        List<LvmFilesystemLocation> lvmLocations = findLvmFilesystems(disk);

        if (!lvmLocations.isEmpty()) {
            // LVM detected: mount all LVM volumes + non-LVM partition filesystems
            List<FileSystem> mounted = new ArrayList<>();
            for (FilesystemLocation fl : findFilesystems(disk)) {
                try {
                    if (fl.info().type() == FileSystem.FileSystemType.BTRFS) {
                        DiskRegion region = DiskRegion.fromPartition(disk, fl.offset(), 0);
                        mounted.addAll(BtrfsFileSystemImpl.mountWithSubvolumes(region, 0));
                    } else {
                        mounted.add(mount(disk, fl));
                    }
                } catch (Exception e) {
                    // Skip filesystems that fail to mount
                }
            }
            for (LvmFilesystemLocation lfl : lvmLocations) {
                try {
                    if (lfl.info().type() == FileSystem.FileSystemType.BTRFS) {
                        mounted.addAll(BtrfsFileSystemImpl.mountWithSubvolumes(lfl.logicalVolume(), 0));
                    } else {
                        mounted.add(mount(lfl));
                    }
                } catch (Exception e) {
                    // Skip filesystems that fail to mount
                }
            }
            return mounted;
        } else {
            // No LVM: mount only the largest filesystem
            List<FileSystem> mounted = new ArrayList<>();
            Optional<FilesystemLocation> largest = findLargestFilesystem(disk);
            if (largest.isPresent()) {
                mounted.add(mount(disk, largest.get()));
            }
            return mounted;
        }
    }

    /**
     * Mounts ALL filesystems in the disk, including LVM volumes and all partitions.
     *
     * <p>This always returns every mountable filesystem found in the disk,
     * regardless of whether LVM is present. For LVM disks, it returns both
     * non-LVM partitions (e.g. /boot) and all LVM logical volumes.
     * For non-LVM disks, it returns all partition filesystems.
     *
     * @param disk the virtual disk
     * @return list of all mounted filesystems (caller must close each one)
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull List<FileSystem> mountAll(@NotNull VirtualDisk disk) throws IOException {
        List<FileSystem> mounted = new ArrayList<>();

        // Mount all regular partition filesystems
        for (FilesystemLocation fl : findFilesystems(disk)) {
            try {
                if (fl.info().type() == FileSystem.FileSystemType.BTRFS) {
                    DiskRegion region = DiskRegion.fromPartition(disk, fl.offset(), 0);
                    mounted.addAll(BtrfsFileSystemImpl.mountWithSubvolumes(region, 0));
                } else {
                    mounted.add(mount(disk, fl));
                }
            } catch (Exception e) {
                // Skip filesystems that fail to mount
            }
        }

        // Mount all LVM logical volume filesystems
        for (LvmFilesystemLocation lfl : findLvmFilesystems(disk)) {
            try {
                if (lfl.info().type() == FileSystem.FileSystemType.BTRFS) {
                    mounted.addAll(BtrfsFileSystemImpl.mountWithSubvolumes(lfl.logicalVolume(), 0));
                } else {
                    mounted.add(mount(lfl));
                }
            } catch (Exception e) {
                // Skip filesystems that fail to mount
            }
        }

        return mounted;
    }

    /**
     * Mounts the largest filesystem in the disk, including LVM volumes.
     *
     * <p>This is typically used to mount the root filesystem of a Linux VM.
     *
     * @param disk the virtual disk
     * @return the mounted filesystem
     * @throws IOException if an I/O error occurs or no filesystem found
     * @throws UnsupportedOperationException if the filesystem type is not supported
     */
    public static @NotNull FileSystem mountLargestIncludingLvm(@NotNull VirtualDisk disk) throws IOException {
        Optional<Object> largest = findLargestFilesystemIncludingLvm(disk);
        if (largest.isEmpty()) {
            throw new IOException("No filesystem found in disk");
        }

        Object location = largest.get();
        if (location instanceof FilesystemLocation fl) {
            return mount(disk, fl);
        } else if (location instanceof LvmFilesystemLocation lfl) {
            return mount(lfl);
        } else {
            throw new IOException("Unknown filesystem location type");
        }
    }
}
