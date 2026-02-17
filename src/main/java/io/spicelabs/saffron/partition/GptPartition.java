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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a partition from a GPT partition table.
 *
 * <p>GPT partition entry structure (128 bytes minimum):
 * <pre>
 * Offset  Size   Description
 * 0       16     Partition type GUID
 * 16      32     Unique partition GUID
 * 32      8      Starting LBA (little-endian)
 * 40      8      Ending LBA (inclusive, little-endian)
 * 48      8      Attribute flags
 * 56      72     Partition name (UTF-16LE)
 * </pre>
 */
public record GptPartition(
        int index,
        @NotNull UUID typeGuid,
        @NotNull UUID uniqueGuid,
        long startLba,
        long endLba,
        long attributes,
        @NotNull Optional<String> partitionName
) implements Partition {

    // Common GPT partition type GUIDs

    /** Unused entry */
    public static final UUID TYPE_UNUSED = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** EFI System Partition */
    public static final UUID TYPE_EFI_SYSTEM = UUID.fromString("C12A7328-F81F-11D2-BA4B-00A0C93EC93B");

    /** Microsoft Reserved */
    public static final UUID TYPE_MS_RESERVED = UUID.fromString("E3C9E316-0B5C-4DB8-817D-F92DF00215AE");

    /** Microsoft Basic Data (NTFS, FAT) */
    public static final UUID TYPE_MS_BASIC_DATA = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7");

    /** Microsoft LDM Metadata */
    public static final UUID TYPE_MS_LDM_METADATA = UUID.fromString("5808C8AA-7E8F-42E0-85D2-E1E90434CFB3");

    /** Microsoft LDM Data */
    public static final UUID TYPE_MS_LDM_DATA = UUID.fromString("AF9B60A0-1431-4F62-BC68-3311714A69AD");

    /** Windows Recovery Environment */
    public static final UUID TYPE_MS_RECOVERY = UUID.fromString("DE94BBA4-06D1-4D40-A16A-BFD50179D6AC");

    /** Linux filesystem */
    public static final UUID TYPE_LINUX_FILESYSTEM = UUID.fromString("0FC63DAF-8483-4772-8E79-3D69D8477DE4");

    /** Linux swap */
    public static final UUID TYPE_LINUX_SWAP = UUID.fromString("0657FD6D-A4AB-43C4-84E5-0933C84B4F4F");

    /** Linux LVM */
    public static final UUID TYPE_LINUX_LVM = UUID.fromString("E6D6D379-F507-44C2-A23C-238F2A3DF928");

    /** Linux RAID */
    public static final UUID TYPE_LINUX_RAID = UUID.fromString("A19D880F-05FC-4D3B-A006-743F0F84911E");

    /** Linux /home */
    public static final UUID TYPE_LINUX_HOME = UUID.fromString("933AC7E1-2EB4-4F13-B844-0E14E2AEF915");

    /** Linux /root (x86-64) */
    public static final UUID TYPE_LINUX_ROOT_X86_64 = UUID.fromString("4F68BCE3-E8CD-4DB1-96E7-FBCAF984B709");

    /** Apple HFS+ */
    public static final UUID TYPE_APPLE_HFS = UUID.fromString("48465300-0000-11AA-AA11-00306543ECAC");

    /** Apple APFS */
    public static final UUID TYPE_APPLE_APFS = UUID.fromString("7C3457EF-0000-11AA-AA11-00306543ECAC");

    /** FreeBSD boot */
    public static final UUID TYPE_FREEBSD_BOOT = UUID.fromString("83BD6B9D-7F41-11DC-BE0B-001560B84F0F");

    /** FreeBSD UFS */
    public static final UUID TYPE_FREEBSD_UFS = UUID.fromString("516E7CB6-6ECF-11D6-8FF8-00022D09712B");

    /** FreeBSD ZFS */
    public static final UUID TYPE_FREEBSD_ZFS = UUID.fromString("516E7CBA-6ECF-11D6-8FF8-00022D09712B");

    /** VMware VMFS */
    public static final UUID TYPE_VMWARE_VMFS = UUID.fromString("AA31E02A-400F-11DB-9590-000C2911D1B8");

    // Attribute flags
    /** Platform required */
    public static final long ATTR_PLATFORM_REQUIRED = 1L << 0;

    /** EFI should ignore this partition */
    public static final long ATTR_EFI_IGNORE = 1L << 1;

    /** Legacy BIOS bootable */
    public static final long ATTR_LEGACY_BOOTABLE = 1L << 2;

    /** Read-only */
    public static final long ATTR_READ_ONLY = 1L << 60;

    /** Hidden */
    public static final long ATTR_HIDDEN = 1L << 62;

    /** No drive letter (Windows) */
    public static final long ATTR_NO_DRIVE_LETTER = 1L << 63;

    /** Map of known type GUIDs to names */
    private static final Map<UUID, String> TYPE_NAMES = Map.ofEntries(
            Map.entry(TYPE_UNUSED, "Unused"),
            Map.entry(TYPE_EFI_SYSTEM, "EFI System"),
            Map.entry(TYPE_MS_RESERVED, "Microsoft Reserved"),
            Map.entry(TYPE_MS_BASIC_DATA, "Microsoft Basic Data"),
            Map.entry(TYPE_MS_LDM_METADATA, "Microsoft LDM Metadata"),
            Map.entry(TYPE_MS_LDM_DATA, "Microsoft LDM Data"),
            Map.entry(TYPE_MS_RECOVERY, "Windows Recovery"),
            Map.entry(TYPE_LINUX_FILESYSTEM, "Linux Filesystem"),
            Map.entry(TYPE_LINUX_SWAP, "Linux Swap"),
            Map.entry(TYPE_LINUX_LVM, "Linux LVM"),
            Map.entry(TYPE_LINUX_RAID, "Linux RAID"),
            Map.entry(TYPE_LINUX_HOME, "Linux /home"),
            Map.entry(TYPE_LINUX_ROOT_X86_64, "Linux root (x86-64)"),
            Map.entry(TYPE_APPLE_HFS, "Apple HFS+"),
            Map.entry(TYPE_APPLE_APFS, "Apple APFS"),
            Map.entry(TYPE_FREEBSD_BOOT, "FreeBSD Boot"),
            Map.entry(TYPE_FREEBSD_UFS, "FreeBSD UFS"),
            Map.entry(TYPE_FREEBSD_ZFS, "FreeBSD ZFS"),
            Map.entry(TYPE_VMWARE_VMFS, "VMware VMFS")
    );

    @Override
    public @NotNull String typeName() {
        return Optional.ofNullable(TYPE_NAMES.get(typeGuid))
                .orElse("Unknown (" + typeGuid + ")");
    }

    @Override
    public @NotNull Optional<String> name() {
        return partitionName;
    }

    @Override
    public boolean isBootable() {
        // Check legacy BIOS bootable flag or EFI system partition
        return (attributes & ATTR_LEGACY_BOOTABLE) != 0 ||
               typeGuid.equals(TYPE_EFI_SYSTEM);
    }

    /**
     * Returns whether this partition has the platform required attribute.
     */
    public boolean isPlatformRequired() {
        return (attributes & ATTR_PLATFORM_REQUIRED) != 0;
    }

    /**
     * Returns whether this partition is hidden.
     */
    public boolean isHidden() {
        return (attributes & ATTR_HIDDEN) != 0;
    }

    /**
     * Returns whether this partition is read-only.
     */
    public boolean isReadOnly() {
        return (attributes & ATTR_READ_ONLY) != 0;
    }
}
