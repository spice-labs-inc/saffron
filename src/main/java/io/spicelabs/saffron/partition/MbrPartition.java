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

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Represents a partition from an MBR partition table.
 *
 * <p>MBR partition entry structure (16 bytes):
 * <pre>
 * Offset  Size  Description
 * 0       1     Boot indicator (0x80 = bootable)
 * 1       3     Starting CHS address
 * 4       1     Partition type
 * 5       3     Ending CHS address
 * 8       4     Starting LBA (little-endian)
 * 12      4     Size in sectors (little-endian)
 * </pre>
 */
public record MbrPartition(
        int index,
        boolean bootable,
        int partitionType,
        long startLba,
        long sizeInSectors,
        boolean logical
) implements Partition {

    /** Extended partition type (CHS) */
    public static final int TYPE_EXTENDED_CHS = 0x05;

    /** Extended partition type (LBA) */
    public static final int TYPE_EXTENDED_LBA = 0x0F;

    /** Linux swap */
    public static final int TYPE_LINUX_SWAP = 0x82;

    /** Linux native */
    public static final int TYPE_LINUX = 0x83;

    /** Linux LVM */
    public static final int TYPE_LINUX_LVM = 0x8E;

    /** NTFS/exFAT */
    public static final int TYPE_NTFS = 0x07;

    /** FAT32 (LBA) */
    public static final int TYPE_FAT32_LBA = 0x0C;

    /** FAT32 (CHS) */
    public static final int TYPE_FAT32 = 0x0B;

    /** FAT16 (LBA) */
    public static final int TYPE_FAT16_LBA = 0x0E;

    /** FAT16 */
    public static final int TYPE_FAT16 = 0x06;

    /** GPT protective MBR */
    public static final int TYPE_GPT_PROTECTIVE = 0xEE;

    /** EFI System Partition */
    public static final int TYPE_EFI_SYSTEM = 0xEF;

    @Override
    public long endLba() {
        return startLba + sizeInSectors - 1;
    }

    @Override
    public @NotNull String typeName() {
        return getTypeName(partitionType);
    }

    @Override
    public @NotNull Optional<String> name() {
        // MBR partitions don't have names
        return Optional.empty();
    }

    @Override
    public boolean isBootable() {
        return bootable;
    }

    @Override
    public boolean isExtended() {
        return partitionType == TYPE_EXTENDED_CHS || partitionType == TYPE_EXTENDED_LBA;
    }

    @Override
    public boolean isLogical() {
        return logical;
    }

    /**
     * Returns a human-readable name for the partition type.
     *
     * @param type the partition type byte
     * @return the type name
     */
    public static @NotNull String getTypeName(int type) {
        return switch (type) {
            case 0x00 -> "Empty";
            case 0x01 -> "FAT12";
            case 0x04 -> "FAT16 (<32MB)";
            case TYPE_EXTENDED_CHS -> "Extended (CHS)";
            case TYPE_FAT16 -> "FAT16";
            case TYPE_NTFS -> "NTFS/exFAT";
            case TYPE_FAT32 -> "FAT32 (CHS)";
            case TYPE_FAT32_LBA -> "FAT32 (LBA)";
            case TYPE_FAT16_LBA -> "FAT16 (LBA)";
            case TYPE_EXTENDED_LBA -> "Extended (LBA)";
            case 0x11 -> "Hidden FAT12";
            case 0x14 -> "Hidden FAT16 (<32MB)";
            case 0x16 -> "Hidden FAT16";
            case 0x17 -> "Hidden NTFS";
            case 0x1B -> "Hidden FAT32";
            case 0x1C -> "Hidden FAT32 (LBA)";
            case 0x1E -> "Hidden FAT16 (LBA)";
            case 0x27 -> "Windows Recovery";
            case TYPE_LINUX_SWAP -> "Linux swap";
            case TYPE_LINUX -> "Linux";
            case TYPE_LINUX_LVM -> "Linux LVM";
            case 0xA5 -> "FreeBSD";
            case 0xA6 -> "OpenBSD";
            case 0xA9 -> "NetBSD";
            case 0xAF -> "macOS HFS+";
            case 0xBE -> "Solaris boot";
            case 0xBF -> "Solaris";
            case TYPE_GPT_PROTECTIVE -> "GPT Protective";
            case TYPE_EFI_SYSTEM -> "EFI System";
            case 0xFB -> "VMware VMFS";
            case 0xFC -> "VMware swap";
            case 0xFD -> "Linux RAID";
            default -> String.format("Unknown (0x%02X)", type);
        };
    }

    /**
     * Returns the partition type byte.
     */
    public int type() {
        return partitionType;
    }
}
