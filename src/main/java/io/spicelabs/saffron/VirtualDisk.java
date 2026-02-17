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
package io.spicelabs.saffron;

import com.github.packageurl.PackageURL;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Represents a virtual machine disk image.
 *
 * <p>This sealed interface follows Baharat's {@code Package} pattern, providing
 * a unified API for accessing different VM disk formats. Each format has its own
 * implementation (permit classes) that handles format-specific details.
 *
 * <p>VirtualDisk instances are obtained through {@link DiskReader#open(java.nio.file.Path)}
 * and must be closed after use.
 *
 * <p>Example usage:
 * <pre>{@code
 * try (VirtualDisk disk = DiskReader.open(path)) {
 *     System.out.println("Format: " + disk.format());
 *     System.out.println("Size: " + disk.virtualSize());
 *     System.out.println("pURL: " + disk.packageUrl());
 * }
 * }</pre>
 *
 * @see DiskReader
 * @see DiskFormat
 */
public sealed interface VirtualDisk extends Closeable
        permits VirtualDisk.Qcow2Disk, VirtualDisk.VmdkDisk, VirtualDisk.VhdDisk,
                VirtualDisk.VhdxDisk, VirtualDisk.VdiDisk, VirtualDisk.RawDisk,
                VirtualDisk.GcpDisk, VirtualDisk.AmiDisk {

    /**
     * Returns the disk format.
     *
     * @return the format of this disk image
     */
    @NotNull DiskFormat format();

    /**
     * Returns the virtual (logical) size of the disk in bytes.
     *
     * <p>This is the size that the guest OS sees, not the actual file size
     * on the host filesystem (which may be smaller due to sparse allocation).
     *
     * @return the virtual size in bytes
     */
    long virtualSize();

    /**
     * Returns the actual allocated size on disk in bytes.
     *
     * @return the allocated size in bytes
     */
    long allocatedSize();

    /**
     * Reads data from the virtual disk at the specified offset.
     *
     * @param offset the byte offset to read from
     * @param length the number of bytes to read
     * @return a ByteBuffer containing the read data
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if offset or length is negative,
     *         or if offset + length exceeds virtual size
     */
    @NotNull ByteBuffer read(long offset, int length) throws IOException;

    /**
     * Opens an InputStream for reading the virtual disk contents.
     *
     * <p>The returned stream reads the virtual disk as a contiguous byte sequence,
     * handling sparse regions and compression transparently.
     *
     * @return an InputStream for reading the disk contents
     * @throws IOException if an I/O error occurs
     */
    @NotNull InputStream openStream() throws IOException;

    /**
     * Returns metadata associated with this disk image.
     *
     * <p>The metadata includes format-specific information such as:
     * <ul>
     *   <li>Version numbers</li>
     *   <li>Compression type</li>
     *   <li>Encryption status</li>
     *   <li>Creation/modification timestamps</li>
     *   <li>Comments or descriptions</li>
     * </ul>
     *
     * @return an unmodifiable map of metadata key-value pairs
     */
    @NotNull Map<String, String> metadata();

    /**
     * Returns a Package URL (pURL) identifying this disk image.
     *
     * <p>The pURL follows the format:
     * {@code pkg:vmdisk/<format>/<name>@<version>?<qualifiers>}
     *
     * <p>This aligns with Baharat's {@code packageUrl()} method naming.
     *
     * @return a Package URL for this disk image
     */
    @NotNull PackageURL packageUrl();

    /**
     * Returns the path to a backing file, if this disk has one.
     *
     * <p>Some disk formats support backing files (also called parent images
     * or base images) for copy-on-write scenarios.
     *
     * @return an Optional containing the backing file path, or empty if none
     */
    @NotNull Optional<String> backingFile();

    /**
     * Returns whether this disk is encrypted.
     *
     * @return true if the disk content is encrypted
     */
    boolean isEncrypted();

    /**
     * Returns whether this disk uses compression.
     *
     * @return true if the disk uses any form of compression
     */
    boolean isCompressed();

    /**
     * Returns snapshots contained in this disk image.
     *
     * @return a stream of snapshot information
     */
    @NotNull Stream<Snapshot> snapshots();

    /**
     * Represents a snapshot within a disk image.
     *
     * @param id the unique snapshot identifier
     * @param name the snapshot name (may be null)
     * @param vmStateSize the size of VM state data in bytes
     * @param dateSeconds the creation date as Unix timestamp (seconds)
     * @param dateNanos the nanosecond component of creation date
     */
    record Snapshot(
            @NotNull String id,
            @Nullable String name,
            long vmStateSize,
            long dateSeconds,
            int dateNanos
    ) {}

    // ========================================================================
    // Format-specific implementations (sealed permits)
    // ========================================================================

    /**
     * QCOW2 format disk implementation.
     */
    non-sealed interface Qcow2Disk extends VirtualDisk {
        /**
         * Returns the QCOW2 version (2 or 3).
         */
        int version();

        /**
         * Returns the cluster size in bytes.
         */
        int clusterSize();

        /**
         * Returns the refcount order (log2 of refcount bits).
         */
        int refcountOrder();

        /**
         * Returns the compression type if compressed.
         */
        @NotNull Optional<String> compressionType();
    }

    /**
     * VMDK format disk implementation.
     */
    non-sealed interface VmdkDisk extends VirtualDisk {
        /**
         * Returns the VMDK descriptor type.
         */
        @NotNull String descriptorType();

        /**
         * Returns the adapter type (ide, lsilogic, etc.).
         */
        @NotNull Optional<String> adapterType();

        /**
         * Returns the hardware version.
         */
        @NotNull Optional<String> hardwareVersion();
    }

    /**
     * VHD (legacy) format disk implementation.
     */
    non-sealed interface VhdDisk extends VirtualDisk {
        /**
         * Returns the disk type (fixed, dynamic, differencing).
         */
        @NotNull String diskType();

        /**
         * Returns the unique identifier.
         */
        @NotNull String uniqueId();

        /**
         * Returns the creator application identifier.
         */
        @NotNull String creatorApplication();
    }

    /**
     * VHDX format disk implementation.
     */
    non-sealed interface VhdxDisk extends VirtualDisk {
        /**
         * Returns the log version.
         */
        int logVersion();

        /**
         * Returns the block size in bytes.
         */
        int blockSize();

        /**
         * Returns the logical sector size.
         */
        int logicalSectorSize();

        /**
         * Returns the physical sector size.
         */
        int physicalSectorSize();
    }

    /**
     * VDI format disk implementation.
     */
    non-sealed interface VdiDisk extends VirtualDisk {
        /**
         * Returns the VDI image type.
         */
        @NotNull String imageType();

        /**
         * Returns the VDI version.
         */
        int vdiVersion();

        /**
         * Returns the block size in bytes.
         */
        int blockSize();
    }

    /**
     * RAW format disk implementation.
     *
     * <p>Raw disk images are byte-for-byte copies of a disk with no container
     * format or metadata. The virtual size equals the file size.
     */
    non-sealed interface RawDisk extends VirtualDisk {
        /**
         * Returns the sector size (typically 512 bytes).
         */
        int sectorSize();
    }

    /**
     * GCP format disk implementation.
     *
     * <p>Google Cloud Platform images are tar.gz archives containing a disk.raw file.
     */
    non-sealed interface GcpDisk extends VirtualDisk {
        /**
         * Returns the inner raw disk.
         */
        @NotNull RawDisk innerDisk();
    }

    /**
     * AMI format disk implementation.
     *
     * <p>Amazon Machine Images are chunked, optionally encrypted disk bundles
     * with an XML manifest file.
     */
    non-sealed interface AmiDisk extends VirtualDisk {
        /**
         * Returns the AMI image name from the manifest.
         */
        @NotNull String imageName();

        /**
         * Returns the architecture (x86_64, i386, etc.).
         */
        @NotNull String architecture();

        /**
         * Returns the number of image parts.
         */
        int partCount();
    }
}
