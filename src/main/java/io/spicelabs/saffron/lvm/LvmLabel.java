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
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Represents an LVM2 Physical Volume label.
 *
 * <p>The PV label is located in one of the first 4 sectors (typically sector 1).
 * Structure:
 * <pre>
 * Offset  Size  Description
 * 0       8     Signature "LABELONE"
 * 8       8     Sector number containing this label
 * 16      4     CRC32 of sector (from offset 20 to end)
 * 20      4     Offset from start of label to PV header
 * 24      8     Label type "LVM2 001"
 * 32      ...   PV header
 * </pre>
 *
 * <p>PV Header structure:
 * <pre>
 * 0       32    PV UUID (ASCII)
 * 32      8     Device size in bytes
 * 40      ...   Data area descriptors (location, size pairs)
 * ...     ...   Metadata area descriptors
 * </pre>
 */
public record LvmLabel(
        long sectorNumber,
        @NotNull String pvUuid,
        long deviceSize,
        long metadataOffset,
        long metadataSize,
        long dataOffset,
        long dataSize
) {

    /** Label signature */
    public static final String LABEL_SIGNATURE = "LABELONE";

    /** LVM2 type identifier */
    public static final String LVM2_TYPE = "LVM2 001";

    /** Sector size */
    public static final int SECTOR_SIZE = 512;

    /**
     * Tries to detect and parse an LVM label at the given offset.
     *
     * @param disk the virtual disk
     * @param partitionOffset the offset where the partition/PV starts
     * @return the parsed label, or empty if not an LVM PV
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<LvmLabel> tryParse(@NotNull VirtualDisk disk, long partitionOffset)
            throws IOException {
        // LVM label can be in sectors 0-3, but is typically in sector 1
        for (int sector = 0; sector < 4; sector++) {
            long offset = partitionOffset + (long) sector * SECTOR_SIZE;

            if (offset + SECTOR_SIZE > disk.virtualSize()) {
                continue;
            }

            ByteBuffer buf = disk.read(offset, SECTOR_SIZE);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // Check signature
            byte[] sigBytes = new byte[8];
            buf.get(sigBytes);
            String signature = new String(sigBytes, StandardCharsets.US_ASCII);

            if (!signature.equals(LABEL_SIGNATURE)) {
                continue;
            }

            // Verify sector number
            long labelSector = buf.getLong();
            if (labelSector != sector) {
                continue;
            }

            // Skip CRC (we don't validate it for read-only access)
            buf.getInt();

            // Offset to PV header (from start of label)
            int pvHeaderOffset = buf.getInt();

            // Check label type
            byte[] typeBytes = new byte[8];
            buf.get(typeBytes);
            String labelType = new String(typeBytes, StandardCharsets.US_ASCII);

            if (!labelType.equals(LVM2_TYPE)) {
                continue;
            }

            // Parse PV header
            buf.position(pvHeaderOffset);

            // PV UUID (32 bytes, but formatted with dashes)
            byte[] uuidBytes = new byte[32];
            buf.get(uuidBytes);
            String pvUuid = new String(uuidBytes, StandardCharsets.US_ASCII).trim();

            // Device size
            long deviceSize = buf.getLong();

            // Parse data area descriptors
            long dataOffset = 0;
            long dataSize = 0;

            // Data area descriptor list (terminated by zero offset)
            while (buf.remaining() >= 16) {
                long areaOffset = buf.getLong();
                long areaSize = buf.getLong();

                if (areaOffset == 0) {
                    break;
                }

                if (dataOffset == 0) {
                    dataOffset = areaOffset;
                    dataSize = areaSize;
                }
            }

            // Parse metadata area descriptors
            long metadataOffset = 0;
            long metadataSize = 0;

            while (buf.remaining() >= 16) {
                long areaOffset = buf.getLong();
                long areaSize = buf.getLong();

                if (areaOffset == 0) {
                    break;
                }

                if (metadataOffset == 0) {
                    metadataOffset = areaOffset;
                    metadataSize = areaSize;
                }
            }

            return Optional.of(new LvmLabel(
                    labelSector, pvUuid, deviceSize,
                    metadataOffset, metadataSize,
                    dataOffset, dataSize
            ));
        }

        return Optional.empty();
    }

    /**
     * Checks if a partition contains an LVM PV.
     */
    public static boolean isLvmPartition(@NotNull VirtualDisk disk, long partitionOffset) throws IOException {
        return tryParse(disk, partitionOffset).isPresent();
    }
}
