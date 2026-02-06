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
package io.spicelabs.saffron;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Abstraction for block-level read access to a virtual disk.
 *
 * <p>This interface represents the virtual disk's block device as it would
 * appear to the guest operating system. Implementations handle the
 * translation from the underlying disk image format (QCOW2, VMDK, etc.)
 * to a linear byte stream.
 *
 * <p>The interface supports:
 * <ul>
 *   <li>Random access reads at arbitrary byte offsets</li>
 *   <li>Querying the virtual disk size</li>
 *   <li>Stream-based access for sequential reads</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try (BlockDevice device = diskImage.openBlockDevice()) {
 *     // Read the MBR
 *     byte[] mbr = device.read(0, 512);
 *
 *     // Read a partition
 *     try (InputStream is = device.openInputStream(partitionStart)) {
 *         // Process partition data
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations are NOT required to be thread-safe. For concurrent
 * access, create multiple BlockDevice instances or synchronize externally.
 */
public interface BlockDevice extends Closeable {

    /**
     * Returns the size of the virtual disk in bytes.
     *
     * @return the virtual disk size
     */
    long size();

    /**
     * Returns the sector size in bytes.
     *
     * <p>Most virtual disks use 512-byte sectors, but some modern
     * formats support 4096-byte (4K) sectors.
     *
     * @return the sector size (typically 512 or 4096)
     */
    default int sectorSize() {
        return 512;
    }

    /**
     * Reads bytes from the virtual disk at the specified offset.
     *
     * @param offset the byte offset to read from
     * @param length the number of bytes to read
     * @return the read bytes
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if offset or length is invalid
     */
    byte @NotNull [] read(long offset, int length) throws IOException;

    /**
     * Reads bytes from the virtual disk into the provided buffer.
     *
     * @param offset the byte offset to read from
     * @param buffer the buffer to read into
     * @param bufferOffset the offset in the buffer to start writing
     * @param length the number of bytes to read
     * @return the number of bytes actually read
     * @throws IOException if an I/O error occurs
     */
    int read(long offset, byte @NotNull [] buffer, int bufferOffset, int length) throws IOException;

    /**
     * Opens an input stream starting at the specified offset.
     *
     * <p>The returned stream provides sequential read access starting
     * from the given offset. The caller is responsible for closing
     * the stream.
     *
     * @param offset the byte offset to start reading from
     * @return an input stream positioned at the offset
     * @throws IOException if an I/O error occurs
     */
    @NotNull InputStream openInputStream(long offset) throws IOException;

    /**
     * Opens an input stream starting at offset 0.
     *
     * @return an input stream positioned at the beginning
     * @throws IOException if an I/O error occurs
     */
    default @NotNull InputStream openInputStream() throws IOException {
        return openInputStream(0);
    }

    /**
     * Returns the disk format of this block device.
     *
     * @return the disk format
     */
    @NotNull DiskFormat getFormat();

    /**
     * Checks if a region of the disk is allocated/contains data.
     *
     * <p>For sparse disk formats, this can be used to skip unallocated
     * regions efficiently. Returns true if any part of the region is
     * allocated.
     *
     * <p>The default implementation returns true (assumes all regions
     * are allocated). Format-specific implementations may provide
     * more accurate sparse region detection.
     *
     * @param offset the byte offset to check
     * @param length the length of the region to check
     * @return true if the region is allocated or contains data
     * @throws IOException if an I/O error occurs
     */
    default boolean isAllocated(long offset, long length) throws IOException {
        return true;
    }

    /**
     * Closes this block device and releases any underlying resources.
     *
     * @throws IOException if an I/O error occurs during close
     */
    @Override
    void close() throws IOException;
}
