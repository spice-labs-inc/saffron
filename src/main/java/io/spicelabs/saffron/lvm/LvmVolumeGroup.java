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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.lvm;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.partition.GptPartition;
import io.spicelabs.saffron.partition.MbrPartition;
import io.spicelabs.saffron.partition.Partition;
import io.spicelabs.saffron.partition.PartitionTable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents an LVM2 Volume Group and provides access to its Logical Volumes.
 *
 * <p>This class is the main entry point for working with LVM volumes in a disk image.
 * It handles the detection and parsing of LVM structures and provides access to
 * individual Logical Volumes as readable disk regions.
 *
 * <p>Example usage:
 * <pre>{@code
 * try (VirtualDisk disk = DiskReader.open(path)) {
 *     Optional<LvmVolumeGroup> vgOpt = LvmVolumeGroup.detect(disk);
 *     if (vgOpt.isPresent()) {
 *         LvmVolumeGroup vg = vgOpt.get();
 *         for (LogicalVolumeDisk lv : vg.logicalVolumes()) {
 *             System.out.println("LV: " + lv.name() + " size: " + lv.size());
 *         }
 *     }
 * }
 * }</pre>
 */
public class LvmVolumeGroup {

    private final VirtualDisk disk;
    private final long partitionOffset;
    private final LvmLabel label;
    private final LvmMetadata metadata;
    private final List<LogicalVolumeDisk> logicalVolumes;

    private LvmVolumeGroup(VirtualDisk disk, long partitionOffset, LvmLabel label, LvmMetadata metadata) {
        this.disk = disk;
        this.partitionOffset = partitionOffset;
        this.label = label;
        this.metadata = metadata;

        // Create LogicalVolumeDisk instances for each LV
        this.logicalVolumes = new ArrayList<>();
        for (LvmMetadata.LogicalVolume lv : metadata.logicalVolumes()) {
            logicalVolumes.add(new LogicalVolumeDisk(disk, partitionOffset, metadata, lv));
        }
    }

    /**
     * Detects and parses an LVM Volume Group from a disk.
     *
     * <p>This method scans the disk for LVM Physical Volumes, either by:
     * <ul>
     *   <li>Checking partitions marked with LVM type (0x8E for MBR, specific GUID for GPT)</li>
     *   <li>Checking all partitions for LVM signatures</li>
     *   <li>Checking the disk directly at offset 0</li>
     * </ul>
     *
     * @param disk the virtual disk to scan
     * @return the detected Volume Group, or empty if no LVM found
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<LvmVolumeGroup> detect(@NotNull VirtualDisk disk) throws IOException {
        // First, try to detect partition table and look for LVM partitions
        Optional<PartitionTable> tableOpt = PartitionTable.detect(disk);

        if (tableOpt.isPresent()) {
            PartitionTable table = tableOpt.get();

            // Check each partition for LVM
            for (Partition p : table.partitions()) {
                // Skip tiny partitions
                if (p.sizeInSectors() < 100) {
                    continue;
                }

                // Check if this is an LVM partition by type
                boolean isLvmType = isLvmPartitionType(p);

                // If it's marked as LVM type, or we should check all partitions
                if (isLvmType || shouldCheckPartition(p)) {
                    long offset = p.startLba() * 512;
                    Optional<LvmVolumeGroup> vg = tryParseAt(disk, offset);
                    if (vg.isPresent()) {
                        return vg;
                    }
                }
            }
        }

        // Also try at offset 0 (whole disk as PV)
        return tryParseAt(disk, 0);
    }

    /**
     * Tries to detect and parse LVM at a specific partition.
     *
     * @param disk the virtual disk
     * @param partition the partition to check
     * @return the Volume Group, or empty if not LVM
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<LvmVolumeGroup> detectAt(@NotNull VirtualDisk disk, @NotNull Partition partition)
            throws IOException {
        long offset = partition.startLba() * 512;
        return tryParseAt(disk, offset);
    }

    /**
     * Tries to parse LVM structures at the given offset.
     */
    private static Optional<LvmVolumeGroup> tryParseAt(VirtualDisk disk, long partitionOffset) throws IOException {
        // Try to read the LVM label
        Optional<LvmLabel> labelOpt = LvmLabel.tryParse(disk, partitionOffset);
        if (labelOpt.isEmpty()) {
            return Optional.empty();
        }

        LvmLabel label = labelOpt.get();

        // Parse the metadata
        Optional<LvmMetadata> metadataOpt = LvmMetadata.parse(disk, partitionOffset, label);
        if (metadataOpt.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new LvmVolumeGroup(disk, partitionOffset, label, metadataOpt.get()));
    }

    /**
     * Checks if a partition is marked as LVM type.
     */
    private static boolean isLvmPartitionType(Partition p) {
        // Check partition type by type name or raw type
        String typeName = p.typeName();
        if (typeName.contains("LVM")) {
            return true;
        }

        // Check raw type for MBR partitions
        if (p instanceof MbrPartition mbr) {
            return mbr.partitionType() == MbrPartition.TYPE_LINUX_LVM;
        }

        // Check GPT partition by type name
        if (p instanceof GptPartition gpt) {
            return gpt.typeName().contains("LVM");
        }

        return false;
    }

    /**
     * Determines if we should check a partition for LVM signatures.
     * Returns true for Linux-related partition types.
     */
    private static boolean shouldCheckPartition(Partition p) {
        String typeName = p.typeName();
        // Check for Linux-related partition types
        if (typeName.contains("Linux") || typeName.contains("LVM")) {
            return true;
        }

        // Check raw type for MBR partitions
        if (p instanceof MbrPartition mbr) {
            int type = mbr.partitionType();
            return type == MbrPartition.TYPE_LINUX
                    || type == MbrPartition.TYPE_LINUX_LVM
                    || type == MbrPartition.TYPE_LINUX_SWAP;
        }

        return false;
    }

    /**
     * Returns the Volume Group name.
     */
    public @NotNull String name() {
        return metadata.vgName();
    }

    /**
     * Returns the Volume Group UUID.
     */
    public @NotNull String uuid() {
        return metadata.vgUuid();
    }

    /**
     * Returns the Physical Volume UUID.
     */
    public @NotNull String pvUuid() {
        return label.pvUuid();
    }

    /**
     * Returns the extent size in bytes.
     */
    public long extentSizeBytes() {
        return metadata.extentSizeBytes();
    }

    /**
     * Returns all Logical Volumes in this Volume Group.
     */
    public @NotNull List<LogicalVolumeDisk> logicalVolumes() {
        return List.copyOf(logicalVolumes);
    }

    /**
     * Finds a Logical Volume by name.
     *
     * @param name the LV name
     * @return the Logical Volume, or empty if not found
     */
    public @NotNull Optional<LogicalVolumeDisk> findLogicalVolume(@NotNull String name) {
        return logicalVolumes.stream()
                .filter(lv -> lv.name().equals(name))
                .findFirst();
    }

    /**
     * Returns the largest Logical Volume (typically the root filesystem).
     *
     * @return the largest LV, or empty if no LVs exist
     */
    public @NotNull Optional<LogicalVolumeDisk> largestLogicalVolume() {
        return logicalVolumes.stream()
                .max((a, b) -> Long.compare(a.size(), b.size()));
    }

    /**
     * Returns the number of Logical Volumes.
     */
    public int logicalVolumeCount() {
        return logicalVolumes.size();
    }

    /**
     * Returns the number of Physical Volumes in the metadata.
     */
    public int physicalVolumeCount() {
        return metadata.physicalVolumes().size();
    }

    /**
     * Returns the underlying LVM metadata.
     */
    public @NotNull LvmMetadata metadata() {
        return metadata;
    }

    /**
     * Returns the underlying LVM label.
     */
    public @NotNull LvmLabel label() {
        return label;
    }

    @Override
    public String toString() {
        return String.format("LvmVolumeGroup[name=%s, pvs=%d, lvs=%d, extentSize=%d]",
                name(), physicalVolumeCount(), logicalVolumeCount(), extentSizeBytes());
    }
}
