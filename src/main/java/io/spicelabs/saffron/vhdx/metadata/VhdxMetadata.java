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
package io.spicelabs.saffron.vhdx.metadata;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.common.SecurityUtils;
import io.spicelabs.saffron.exception.InvalidMagicException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Represents parsed VHDX metadata.
 *
 * <p>The metadata region contains information about the virtual disk's
 * properties including virtual size, block size, and sector sizes.
 *
 * <p>Key metadata items:
 * <ul>
 *   <li>File Parameters: Block size and flags</li>
 *   <li>Virtual Disk Size</li>
 *   <li>Virtual Disk ID</li>
 *   <li>Logical Sector Size</li>
 *   <li>Physical Sector Size</li>
 *   <li>Parent Locator (for differencing disks)</li>
 * </ul>
 */
public record VhdxMetadata(
        long virtualDiskSize,
        int blockSize,
        int logicalSectorSize,
        int physicalSectorSize,
        boolean hasParent,
        boolean leaveBlocksAllocated,
        @Nullable UUID virtualDiskId,
        @Nullable String parentLocatorType
) {

    /** Metadata table signature */
    public static final byte[] METADATA_TABLE_MAGIC = "metadata".getBytes(StandardCharsets.US_ASCII);

    /** Default logical sector size */
    public static final int DEFAULT_LOGICAL_SECTOR_SIZE = 512;

    /** Default physical sector size */
    public static final int DEFAULT_PHYSICAL_SECTOR_SIZE = 4096;

    /** File parameters GUID */
    private static final UUID FILE_PARAMETERS_GUID =
            UUID.fromString("caa16737-fa36-4d43-b3b6-33f0aa44e76b");

    /** Virtual disk size GUID */
    private static final UUID VIRTUAL_DISK_SIZE_GUID =
            UUID.fromString("2fa54224-cd1b-4876-b211-5dbed83bf4b8");

    /** Virtual disk ID GUID */
    private static final UUID VIRTUAL_DISK_ID_GUID =
            UUID.fromString("beca12ab-b2e6-4523-93ef-c309e000c746");

    /** Logical sector size GUID */
    private static final UUID LOGICAL_SECTOR_SIZE_GUID =
            UUID.fromString("8141bf1d-a96f-4709-ba47-f233a8faab5f");

    /** Physical sector size GUID */
    private static final UUID PHYSICAL_SECTOR_SIZE_GUID =
            UUID.fromString("cda348c7-445d-4471-9cc9-e9885251c556");

    /** Parent locator GUID */
    private static final UUID PARENT_LOCATOR_GUID =
            UUID.fromString("a8d35f2d-b30b-454d-abf7-d3d84834ab0c");

    /**
     * Reads metadata from the specified region.
     *
     * @param channel the channel to read from
     * @param regionOffset the offset of the metadata region
     * @param regionLength the length of the metadata region
     * @return the parsed metadata
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VhdxMetadata read(@NotNull SeekableByteChannel channel,
                                              long regionOffset, int regionLength)
            throws IOException {
        // Read metadata table header
        ByteBuffer headerBuffer = ByteBuffer.allocate(32);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(regionOffset);
        int read = channel.read(headerBuffer);
        if (read < 32) {
            throw new IOException("Failed to read VHDX metadata header");
        }
        headerBuffer.flip();

        // Validate signature
        byte[] signature = new byte[8];
        headerBuffer.get(signature);
        if (!SecurityUtils.constantTimeEquals(signature, METADATA_TABLE_MAGIC)) {
            throw new InvalidMagicException(
                    "Invalid VHDX metadata signature",
                    METADATA_TABLE_MAGIC, signature, regionOffset, DiskFormat.VHDX);
        }

        // Skip reserved (2 bytes)
        headerBuffer.getShort();

        // Entry count
        int entryCount = headerBuffer.getShort() & 0xFFFF;

        // Skip reserved (20 bytes)

        // Read metadata entries
        long virtualDiskSize = 0;
        int blockSize = 0;
        int logicalSectorSize = DEFAULT_LOGICAL_SECTOR_SIZE;
        int physicalSectorSize = DEFAULT_PHYSICAL_SECTOR_SIZE;
        boolean hasParent = false;
        boolean leaveBlocksAllocated = false;
        UUID virtualDiskId = null;
        String parentLocatorType = null;

        // Read entry table (each entry is 32 bytes)
        ByteBuffer entriesBuffer = ByteBuffer.allocate(entryCount * 32);
        entriesBuffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(regionOffset + 32);
        read = channel.read(entriesBuffer);
        entriesBuffer.flip();

        for (int i = 0; i < entryCount && entriesBuffer.remaining() >= 32; i++) {
            // Read entry GUID
            UUID itemId = readGuid(entriesBuffer);

            // Offset within metadata region
            int itemOffset = entriesBuffer.getInt();

            // Length
            int itemLength = entriesBuffer.getInt();

            // Flags
            int flags = entriesBuffer.getInt();
            boolean isRequired = (flags & 0x04) != 0;

            // Skip reserved (4 bytes)
            entriesBuffer.getInt();

            // Read the actual metadata item
            if (itemId.equals(FILE_PARAMETERS_GUID) && itemLength >= 8) {
                ByteBuffer itemBuffer = ByteBuffer.allocate(itemLength);
                itemBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(regionOffset + itemOffset);
                channel.read(itemBuffer);
                itemBuffer.flip();

                blockSize = itemBuffer.getInt();
                int fileFlags = itemBuffer.getInt();
                leaveBlocksAllocated = (fileFlags & 0x01) != 0;
                hasParent = (fileFlags & 0x02) != 0;
            } else if (itemId.equals(VIRTUAL_DISK_SIZE_GUID) && itemLength >= 8) {
                ByteBuffer itemBuffer = ByteBuffer.allocate(itemLength);
                itemBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(regionOffset + itemOffset);
                channel.read(itemBuffer);
                itemBuffer.flip();

                virtualDiskSize = itemBuffer.getLong();
            } else if (itemId.equals(VIRTUAL_DISK_ID_GUID) && itemLength >= 16) {
                ByteBuffer itemBuffer = ByteBuffer.allocate(itemLength);
                itemBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(regionOffset + itemOffset);
                channel.read(itemBuffer);
                itemBuffer.flip();

                virtualDiskId = readGuid(itemBuffer);
            } else if (itemId.equals(LOGICAL_SECTOR_SIZE_GUID) && itemLength >= 4) {
                ByteBuffer itemBuffer = ByteBuffer.allocate(itemLength);
                itemBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(regionOffset + itemOffset);
                channel.read(itemBuffer);
                itemBuffer.flip();

                logicalSectorSize = itemBuffer.getInt();
            } else if (itemId.equals(PHYSICAL_SECTOR_SIZE_GUID) && itemLength >= 4) {
                ByteBuffer itemBuffer = ByteBuffer.allocate(itemLength);
                itemBuffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.position(regionOffset + itemOffset);
                channel.read(itemBuffer);
                itemBuffer.flip();

                physicalSectorSize = itemBuffer.getInt();
            }
        }

        return new VhdxMetadata(
                virtualDiskSize,
                blockSize,
                logicalSectorSize,
                physicalSectorSize,
                hasParent,
                leaveBlocksAllocated,
                virtualDiskId,
                parentLocatorType
        );
    }

    private static UUID readGuid(ByteBuffer buffer) {
        int data1 = buffer.getInt();
        short data2 = buffer.getShort();
        short data3 = buffer.getShort();
        byte[] data4 = new byte[8];
        buffer.get(data4);

        long msb = ((long) data1 << 32) | ((long) (data2 & 0xFFFF) << 16) | (data3 & 0xFFFF);
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            lsb = (lsb << 8) | (data4[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }
}
