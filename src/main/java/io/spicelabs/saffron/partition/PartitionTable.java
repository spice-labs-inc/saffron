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
package io.spicelabs.saffron.partition;

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * Represents a partition table on a virtual disk.
 *
 * <p>This sealed interface supports both MBR and GPT partition schemes.
 * Use {@link #detect(VirtualDisk)} to automatically detect and parse
 * the partition table from a disk image.
 *
 * <p>Example usage:
 * <pre>{@code
 * try (VirtualDisk disk = DiskReader.open(path)) {
 *     Optional<PartitionTable> table = PartitionTable.detect(disk);
 *     if (table.isPresent()) {
 *         for (Partition partition : table.get().partitions()) {
 *             System.out.println("Partition: " + partition.startLba() +
 *                 " - " + partition.endLba());
 *         }
 *     }
 * }
 * }</pre>
 */
public sealed interface PartitionTable
        permits MbrPartitionTable, GptPartitionTable {

    /**
     * Partition table type.
     */
    enum Type {
        /** Master Boot Record */
        MBR,
        /** GUID Partition Table */
        GPT
    }

    /**
     * Returns the partition table type.
     *
     * @return the type (MBR or GPT)
     */
    @NotNull Type type();

    /**
     * Returns the list of partitions.
     *
     * @return an unmodifiable list of partitions
     */
    @NotNull List<Partition> partitions();

    /**
     * Returns the disk signature or identifier.
     *
     * @return the disk signature (4 bytes for MBR, GUID string for GPT)
     */
    @NotNull String diskSignature();

    /**
     * Returns the sector size used by this partition table.
     *
     * @return the sector size in bytes (typically 512)
     */
    default int sectorSize() {
        return 512;
    }

    /**
     * Detects and parses the partition table from a virtual disk.
     *
     * <p>This method first checks for a GPT signature, then falls back
     * to MBR if no GPT is found.
     *
     * @param disk the virtual disk to read from
     * @return an Optional containing the partition table, or empty if none found
     * @throws IOException if an I/O error occurs
     */
    static @NotNull Optional<PartitionTable> detect(@NotNull VirtualDisk disk) throws IOException {
        // Check for GPT first (it has a protective MBR)
        Optional<GptPartitionTable> gpt = GptPartitionTable.tryParse(disk);
        if (gpt.isPresent()) {
            return Optional.of(gpt.get());
        }

        // Try MBR
        Optional<MbrPartitionTable> mbr = MbrPartitionTable.tryParse(disk);
        if (mbr.isPresent()) {
            return Optional.of(mbr.get());
        }

        return Optional.empty();
    }

    /**
     * Reads sectors from the disk.
     *
     * @param disk the disk to read from
     * @param lba the logical block address (sector number)
     * @param count the number of sectors to read
     * @param sectorSize the sector size in bytes
     * @return the read data
     * @throws IOException if an I/O error occurs
     */
    static @NotNull ByteBuffer readSectors(@NotNull VirtualDisk disk,
                                            long lba, int count, int sectorSize) throws IOException {
        return disk.read(lba * sectorSize, count * sectorSize);
    }
}
