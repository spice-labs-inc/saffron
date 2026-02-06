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
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Represents a readable region of a disk.
 *
 * <p>This interface abstracts the read operations needed by filesystem implementations,
 * allowing them to work with both raw disk partitions and LVM logical volumes.
 */
public interface DiskRegion {

    /**
     * Reads data from the region at the specified offset.
     *
     * @param offset the byte offset to read from (relative to region start)
     * @param length the number of bytes to read
     * @return a ByteBuffer containing the read data
     * @throws IOException if an I/O error occurs
     */
    @NotNull ByteBuffer read(long offset, int length) throws IOException;

    /**
     * Returns the total size of this region in bytes.
     *
     * @return the size in bytes
     */
    long size();

    /**
     * Creates a DiskRegion from a VirtualDisk at a given partition offset.
     *
     * @param disk the virtual disk
     * @param partitionOffset the offset where the partition starts
     * @param partitionSize the size of the partition (0 means use remaining disk space)
     * @return a DiskRegion representing the partition
     */
    static @NotNull DiskRegion fromPartition(@NotNull VirtualDisk disk, long partitionOffset, long partitionSize) {
        long effectiveSize = partitionSize > 0 ? partitionSize : disk.virtualSize() - partitionOffset;
        return new DiskRegion() {
            @Override
            public @NotNull ByteBuffer read(long offset, int length) throws IOException {
                return disk.read(partitionOffset + offset, length);
            }

            @Override
            public long size() {
                return effectiveSize;
            }
        };
    }

    /**
     * Creates a DiskRegion from a VirtualDisk at offset 0.
     *
     * @param disk the virtual disk
     * @return a DiskRegion representing the entire disk
     */
    static @NotNull DiskRegion fromDisk(@NotNull VirtualDisk disk) {
        return fromPartition(disk, 0, disk.virtualSize());
    }
}
