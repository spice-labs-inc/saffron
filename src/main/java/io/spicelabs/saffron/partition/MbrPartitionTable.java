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
package io.spicelabs.saffron.partition;

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents a Master Boot Record (MBR) partition table.
 *
 * <p>MBR structure (512 bytes):
 * <pre>
 * Offset  Size   Description
 * 0       440    Bootstrap code
 * 440     4      Disk signature
 * 444     2      Usually 0x0000
 * 446     16     Partition entry 1
 * 462     16     Partition entry 2
 * 478     16     Partition entry 3
 * 494     16     Partition entry 4
 * 510     2      Boot signature (0x55AA)
 * </pre>
 *
 * <p>MBR supports up to 4 primary partitions, or 3 primary + 1 extended
 * partition containing logical partitions.
 */
public record MbrPartitionTable(
        int diskSignatureInt,
        @NotNull List<Partition> partitions,
        boolean isProtectiveMbr
) implements PartitionTable {

    /** MBR boot signature */
    public static final int BOOT_SIGNATURE = 0xAA55;

    /** Offset of the disk signature */
    public static final int DISK_SIGNATURE_OFFSET = 440;

    /** Offset of the first partition entry */
    public static final int PARTITION_TABLE_OFFSET = 446;

    /** Size of each partition entry */
    public static final int PARTITION_ENTRY_SIZE = 16;

    /** Number of primary partition entries */
    public static final int NUM_PRIMARY_PARTITIONS = 4;

    /** Maximum number of logical partitions to read */
    public static final int MAX_LOGICAL_PARTITIONS = 128;

    @Override
    public @NotNull Type type() {
        return Type.MBR;
    }

    @Override
    public @NotNull String diskSignature() {
        return String.format("%08X", diskSignatureInt);
    }

    /**
     * Attempts to parse an MBR partition table from the disk.
     *
     * @param disk the virtual disk to read from
     * @return an Optional containing the MBR, or empty if invalid
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<MbrPartitionTable> tryParse(@NotNull VirtualDisk disk) throws IOException {
        if (disk.virtualSize() < 512) {
            return Optional.empty();
        }

        ByteBuffer mbr = disk.read(0, 512);
        mbr.order(ByteOrder.LITTLE_ENDIAN);

        // Check boot signature
        int bootSig = mbr.getShort(510) & 0xFFFF;
        if (bootSig != BOOT_SIGNATURE) {
            return Optional.empty();
        }

        // Read disk signature
        int diskSignature = mbr.getInt(DISK_SIGNATURE_OFFSET);

        // Calculate total sectors for validation
        long totalSectors = disk.virtualSize() / 512;

        // Parse partition entries
        List<Partition> partitions = new ArrayList<>();
        boolean isProtective = false;

        for (int i = 0; i < NUM_PRIMARY_PARTITIONS; i++) {
            int offset = PARTITION_TABLE_OFFSET + (i * PARTITION_ENTRY_SIZE);
            Optional<MbrPartition> partitionOpt = parsePartitionEntry(mbr, offset, i, false, totalSectors);

            if (partitionOpt.isPresent()) {
                MbrPartition partition = partitionOpt.get();
                // Check for GPT protective MBR
                if (partition.type() == MbrPartition.TYPE_GPT_PROTECTIVE) {
                    isProtective = true;
                }

                if (partition.isExtended()) {
                    // Parse logical partitions in extended partition
                    List<MbrPartition> logicals = parseExtendedPartition(disk, partition, partitions.size());
                    partitions.add(partition);
                    partitions.addAll(logicals);
                } else {
                    partitions.add(partition);
                }
            }
        }

        if (partitions.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new MbrPartitionTable(diskSignature, List.copyOf(partitions), isProtective));
    }

    /**
     * Parses a single partition entry from the MBR.
     *
     * @param buffer the MBR buffer
     * @param offset offset of the partition entry
     * @param index partition index
     * @param logical whether this is a logical partition
     * @param totalSectors total sectors on the disk (for validation)
     * @return the parsed partition, or empty if the entry is invalid or empty
     */
    private static @NotNull Optional<MbrPartition> parsePartitionEntry(ByteBuffer buffer, int offset,
                                                     int index, boolean logical, long totalSectors) {
        // Boot indicator
        int bootIndicator = buffer.get(offset) & 0xFF;
        // Valid boot indicators are 0x00 (not bootable) or 0x80 (bootable)
        // Other values suggest this is not a real partition entry
        if (bootIndicator != 0x00 && bootIndicator != 0x80) {
            return Optional.empty();
        }
        boolean bootable = (bootIndicator == 0x80);

        // Skip CHS start (3 bytes)

        // Partition type
        int partitionType = buffer.get(offset + 4) & 0xFF;
        if (partitionType == 0) {
            return Optional.empty(); // Empty partition
        }

        // Skip CHS end (3 bytes)

        // Starting LBA
        long startLba = buffer.getInt(offset + 8) & 0xFFFFFFFFL;

        // Size in sectors
        long sizeInSectors = buffer.getInt(offset + 12) & 0xFFFFFFFFL;

        if (startLba == 0 && sizeInSectors == 0) {
            return Optional.empty();
        }

        // Note: We don't validate partition size against disk size because:
        // 1. The boot indicator check (0x00 or 0x80) filters out garbage data
        // 2. Protective MBR for GPT may claim the entire disk
        // 3. Sparse/growing disk images may have smaller actual size

        return Optional.of(new MbrPartition(index, bootable, partitionType, startLba, sizeInSectors, logical));
    }

    /**
     * Parses logical partitions from an extended partition.
     */
    private static List<MbrPartition> parseExtendedPartition(VirtualDisk disk,
                                                              MbrPartition extended,
                                                              int startIndex) throws IOException {
        List<MbrPartition> logicals = new ArrayList<>();
        long extendedStart = extended.startLba();
        long currentEbr = extendedStart;
        int logicalIndex = startIndex + 1;
        int count = 0;

        while (count < MAX_LOGICAL_PARTITIONS) {
            ByteBuffer ebr = disk.read(currentEbr * 512, 512);
            ebr.order(ByteOrder.LITTLE_ENDIAN);

            // Check EBR signature
            int sig = ebr.getShort(510) & 0xFFFF;
            if (sig != BOOT_SIGNATURE) {
                break;
            }

            // First entry is the logical partition (relative to this EBR)
            int offset = PARTITION_TABLE_OFFSET;
            int partType = ebr.get(offset + 4) & 0xFF;
            if (partType == 0) {
                break;
            }

            long relativeStart = ebr.getInt(offset + 8) & 0xFFFFFFFFL;
            long size = ebr.getInt(offset + 12) & 0xFFFFFFFFL;
            long absoluteStart = currentEbr + relativeStart;

            boolean bootable = (ebr.get(offset) & 0xFF) == 0x80;
            logicals.add(new MbrPartition(logicalIndex++, bootable, partType, absoluteStart, size, true));
            count++;

            // Second entry points to next EBR (relative to extended partition start)
            offset = PARTITION_TABLE_OFFSET + PARTITION_ENTRY_SIZE;
            int nextType = ebr.get(offset + 4) & 0xFF;
            if (nextType == 0 || (nextType != MbrPartition.TYPE_EXTENDED_CHS &&
                                  nextType != MbrPartition.TYPE_EXTENDED_LBA)) {
                break;
            }

            long nextRelative = ebr.getInt(offset + 8) & 0xFFFFFFFFL;
            if (nextRelative == 0) {
                break;
            }

            currentEbr = extendedStart + nextRelative;
        }

        return logicals;
    }

    /**
     * Returns whether this is a GPT protective MBR.
     */
    public boolean isProtective() {
        return isProtectiveMbr;
    }
}
