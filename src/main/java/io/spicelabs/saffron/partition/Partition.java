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

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Represents a partition within a partition table.
 *
 * <p>This sealed interface supports partitions from both MBR and GPT schemes.
 */
public sealed interface Partition
        permits MbrPartition, GptPartition {

    /**
     * Returns the partition index (0-based).
     *
     * @return the partition index
     */
    int index();

    /**
     * Returns the starting LBA (logical block address).
     *
     * @return the start sector
     */
    long startLba();

    /**
     * Returns the ending LBA (inclusive).
     *
     * @return the end sector
     */
    long endLba();

    /**
     * Returns the size in sectors.
     *
     * @return the number of sectors
     */
    default long sizeInSectors() {
        return endLba() - startLba() + 1;
    }

    /**
     * Returns the size in bytes.
     *
     * @param sectorSize the sector size
     * @return the size in bytes
     */
    default long sizeInBytes(int sectorSize) {
        return sizeInSectors() * sectorSize;
    }

    /**
     * Returns the partition type as a human-readable string.
     *
     * @return the partition type name
     */
    @NotNull String typeName();

    /**
     * Returns the partition name/label if available.
     *
     * @return the partition name, or empty if not available
     */
    @NotNull Optional<String> name();

    /**
     * Returns whether this partition is bootable.
     *
     * @return true if bootable
     */
    boolean isBootable();

    /**
     * Returns whether this is an extended partition (MBR only).
     *
     * @return true if this is an extended partition
     */
    default boolean isExtended() {
        return false;
    }

    /**
     * Returns whether this is a logical partition inside an extended partition (MBR only).
     *
     * @return true if this is a logical partition
     */
    default boolean isLogical() {
        return false;
    }
}
