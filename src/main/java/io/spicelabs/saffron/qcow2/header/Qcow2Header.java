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
package io.spicelabs.saffron.qcow2.header;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.common.SecurityUtils;
import io.spicelabs.saffron.exception.CorruptedDiskException;
import io.spicelabs.saffron.exception.InvalidMagicException;
import io.spicelabs.saffron.exception.UnsupportedVersionException;
import io.spicelabs.saffron.io.BinaryReader;
import io.spicelabs.saffron.io.SafeMath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * QCOW2 header structure.
 *
 * <p>The QCOW2 header is always at the beginning of the file and is stored in
 * big-endian byte order. This record represents the parsed header with validated
 * values.
 *
 * <p>QCOW2 format specification: https://github.com/qemu/qemu/blob/master/docs/interop/qcow2.txt
 *
 * <h2>Header Layout</h2>
 * <pre>
 * Offset  Size  Description
 * 0       4     Magic number ('QFI\xfb')
 * 4       4     Version (2 or 3)
 * 8       8     Backing file offset
 * 16      4     Backing file size
 * 20      4     Cluster bits (log2 of cluster size)
 * 24      8     Virtual size
 * 32      4     Encryption method (0=none, 1=AES, 2=LUKS)
 * 36      4     L1 table size (number of entries)
 * 40      8     L1 table offset
 * 48      8     Refcount table offset
 * 56      4     Refcount table clusters
 * 60      4     Number of snapshots
 * 64      8     Snapshots offset
 *
 * Version 3 extensions:
 * 72      8     Incompatible features
 * 80      8     Compatible features
 * 88      8     Autoclear features
 * 96      4     Refcount order (log2 of refcount bits)
 * 100     4     Header length
 * </pre>
 */
public record Qcow2Header(
        int version,
        long backingFileOffset,
        int backingFileSize,
        int clusterBits,
        long virtualSize,
        int cryptMethod,
        int l1Size,
        long l1TableOffset,
        long refcountTableOffset,
        int refcountTableClusters,
        int nbSnapshots,
        long snapshotsOffset,
        // V3 fields (0 for v2)
        long incompatibleFeatures,
        long compatibleFeatures,
        long autoclearFeatures,
        int refcountOrder,
        int headerLength,
        // Derived/parsed values
        @Nullable String backingFile
) {

    /** QCOW2 magic number: 'QFI\xfb' */
    public static final byte[] MAGIC = {0x51, 0x46, 0x49, (byte) 0xfb};

    /** Minimum supported version */
    public static final int MIN_VERSION = 2;

    /** Maximum supported version */
    public static final int MAX_VERSION = 3;

    /** Minimum cluster bits (512 bytes) */
    public static final int MIN_CLUSTER_BITS = 9;

    /** Maximum cluster bits (2 MB) */
    public static final int MAX_CLUSTER_BITS = 21;

    /** Default refcount order for v2 (16-bit refcounts) */
    public static final int DEFAULT_REFCOUNT_ORDER = 4;

    /** V2 header length */
    public static final int V2_HEADER_LENGTH = 72;

    /** Minimum V3 header length */
    public static final int MIN_V3_HEADER_LENGTH = 104;

    // Encryption methods
    public static final int CRYPT_NONE = 0;
    public static final int CRYPT_AES = 1;
    public static final int CRYPT_LUKS = 2;

    // Incompatible feature flags
    public static final long INCOMPAT_DIRTY = 1L << 0;
    public static final long INCOMPAT_CORRUPT = 1L << 1;
    public static final long INCOMPAT_EXTERNAL_DATA_FILE = 1L << 2;
    public static final long INCOMPAT_COMPRESSION_TYPE = 1L << 3;
    public static final long INCOMPAT_EXTENDED_L2 = 1L << 4;

    // Compatible feature flags
    public static final long COMPAT_LAZY_REFCOUNTS = 1L << 0;

    // Autoclear feature flags
    public static final long AUTOCLEAR_BITMAPS = 1L << 0;
    public static final long AUTOCLEAR_RAW_EXTERNAL = 1L << 1;

    /**
     * Returns the cluster size in bytes.
     */
    public int clusterSize() {
        return 1 << clusterBits;
    }

    /**
     * Returns the refcount bits (entries per refcount block).
     */
    public int refcountBits() {
        return 1 << refcountOrder;
    }

    /**
     * Checks if the disk is marked as dirty.
     */
    public boolean isDirty() {
        return (incompatibleFeatures & INCOMPAT_DIRTY) != 0;
    }

    /**
     * Checks if the disk is marked as corrupt.
     */
    public boolean isCorrupt() {
        return (incompatibleFeatures & INCOMPAT_CORRUPT) != 0;
    }

    /**
     * Checks if the disk uses an external data file.
     */
    public boolean hasExternalDataFile() {
        return (incompatibleFeatures & INCOMPAT_EXTERNAL_DATA_FILE) != 0;
    }

    /**
     * Checks if the disk uses extended L2 entries.
     */
    public boolean hasExtendedL2() {
        return (incompatibleFeatures & INCOMPAT_EXTENDED_L2) != 0;
    }

    /**
     * Checks if lazy refcounts are enabled.
     */
    public boolean hasLazyRefcounts() {
        return (compatibleFeatures & COMPAT_LAZY_REFCOUNTS) != 0;
    }

    /**
     * Checks if encryption is enabled.
     */
    public boolean isEncrypted() {
        return cryptMethod != CRYPT_NONE;
    }

    /**
     * Reads and parses a QCOW2 header from an input stream.
     *
     * @param in the input stream positioned at the start of the file
     * @return the parsed header
     * @throws IOException if an I/O error occurs
     * @throws InvalidMagicException if the magic number is wrong
     * @throws UnsupportedVersionException if the version is not supported
     * @throws CorruptedDiskException if the header is malformed
     */
    public static @NotNull Qcow2Header read(@NotNull InputStream in) throws IOException {
        BinaryReader reader = new BinaryReader(in, ByteOrder.BIG_ENDIAN);

        // Read and verify magic
        byte[] magic = reader.readBytes(4);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new InvalidMagicException(
                    "Invalid QCOW2 magic number",
                    MAGIC, magic, 0L, DiskFormat.QCOW2);
        }

        // Read version
        int version = reader.readInt32();
        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new UnsupportedVersionException(
                    String.format("Unsupported QCOW2 version %d (supported: %d-%d)",
                            version, MIN_VERSION, MAX_VERSION),
                    version, MIN_VERSION, MAX_VERSION, DiskFormat.QCOW2);
        }

        // Read common header fields
        long backingFileOffset = reader.readInt64();
        int backingFileSize = reader.readInt32();
        int clusterBits = reader.readInt32();
        long virtualSize = reader.readInt64();
        int cryptMethod = reader.readInt32();
        int l1Size = reader.readInt32();
        long l1TableOffset = reader.readInt64();
        long refcountTableOffset = reader.readInt64();
        int refcountTableClusters = reader.readInt32();
        int nbSnapshots = reader.readInt32();
        long snapshotsOffset = reader.readInt64();

        // Validate common fields
        validateClusterBits(clusterBits);
        validateVirtualSize(virtualSize, clusterBits);
        validateL1Table(l1Size, l1TableOffset, clusterBits);

        // V3-specific fields
        long incompatibleFeatures = 0;
        long compatibleFeatures = 0;
        long autoclearFeatures = 0;
        int refcountOrder = DEFAULT_REFCOUNT_ORDER;
        int headerLength = V2_HEADER_LENGTH;

        if (version >= 3) {
            incompatibleFeatures = reader.readInt64();
            compatibleFeatures = reader.readInt64();
            autoclearFeatures = reader.readInt64();
            refcountOrder = reader.readInt32();
            headerLength = reader.readInt32();

            validateV3Fields(incompatibleFeatures, refcountOrder, headerLength);
        }

        // Read backing file name if present
        String backingFile = null;
        if (backingFileOffset > 0 && backingFileSize > 0) {
            // Backing file is stored separately in the file
            // We'll read it later if needed; for now just note it exists
            backingFile = null; // Will be populated by caller if needed
        }

        return new Qcow2Header(
                version,
                backingFileOffset,
                backingFileSize,
                clusterBits,
                virtualSize,
                cryptMethod,
                l1Size,
                l1TableOffset,
                refcountTableOffset,
                refcountTableClusters,
                nbSnapshots,
                snapshotsOffset,
                incompatibleFeatures,
                compatibleFeatures,
                autoclearFeatures,
                refcountOrder,
                headerLength,
                backingFile
        );
    }

    private static void validateClusterBits(int clusterBits) {
        if (clusterBits < MIN_CLUSTER_BITS || clusterBits > MAX_CLUSTER_BITS) {
            throw new CorruptedDiskException(
                    String.format("Invalid cluster_bits %d (must be %d-%d)",
                            clusterBits, MIN_CLUSTER_BITS, MAX_CLUSTER_BITS),
                    20L, "header.cluster_bits", DiskFormat.QCOW2);
        }
    }

    private static void validateVirtualSize(long virtualSize, int clusterBits) {
        if (virtualSize < 0) {
            throw new CorruptedDiskException(
                    "Negative virtual size: " + virtualSize,
                    24L, "header.size", DiskFormat.QCOW2);
        }

        // Maximum virtual size depends on cluster size
        // With 64K clusters and 8-byte L2 entries, max is about 64 PB
        // We use a more conservative limit
        long maxVirtualSize = 64L * 1024 * 1024 * 1024 * 1024; // 64 TB
        SecurityUtils.validateAllocationSize(virtualSize, maxVirtualSize, "virtual size");
    }

    private static void validateL1Table(int l1Size, long l1TableOffset, int clusterBits) {
        if (l1Size < 0) {
            throw new CorruptedDiskException(
                    "Negative L1 size: " + l1Size,
                    36L, "header.l1_size", DiskFormat.QCOW2);
        }

        if (l1TableOffset < 0) {
            throw new CorruptedDiskException(
                    "Negative L1 table offset: " + l1TableOffset,
                    40L, "header.l1_table_offset", DiskFormat.QCOW2);
        }

        // L1 table must be cluster-aligned for v3
        int clusterSize = 1 << clusterBits;
        if (l1TableOffset > 0 && (l1TableOffset % clusterSize) != 0) {
            // This is a warning for v2, error for v3 with certain features
            // For now, just validate it exists if non-zero
        }

        // Validate L1 table doesn't require excessive memory
        long l1TableSize = SafeMath.safeMultiply(l1Size, 8L);
        SecurityUtils.validateAllocationSize(l1TableSize, 256 * 1024 * 1024, "L1 table");
    }

    private static void validateV3Fields(long incompatibleFeatures, int refcountOrder, int headerLength) {
        // Check for unsupported incompatible features
        long knownIncompatFeatures = INCOMPAT_DIRTY | INCOMPAT_CORRUPT |
                INCOMPAT_EXTERNAL_DATA_FILE | INCOMPAT_COMPRESSION_TYPE | INCOMPAT_EXTENDED_L2;
        long unknownFeatures = incompatibleFeatures & ~knownIncompatFeatures;
        if (unknownFeatures != 0) {
            throw new CorruptedDiskException(
                    String.format("Unknown incompatible features: 0x%016x", unknownFeatures),
                    72L, "header.incompatible_features", DiskFormat.QCOW2);
        }

        // Validate refcount order (1 to 6, meaning 2-bit to 64-bit refcounts)
        if (refcountOrder < 0 || refcountOrder > 6) {
            throw new CorruptedDiskException(
                    String.format("Invalid refcount_order %d (must be 0-6)", refcountOrder),
                    96L, "header.refcount_order", DiskFormat.QCOW2);
        }

        // Validate header length
        if (headerLength < MIN_V3_HEADER_LENGTH) {
            throw new CorruptedDiskException(
                    String.format("Invalid header_length %d (minimum %d)",
                            headerLength, MIN_V3_HEADER_LENGTH),
                    100L, "header.header_length", DiskFormat.QCOW2);
        }
    }
}
