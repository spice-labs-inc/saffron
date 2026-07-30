# Saffron Filesystems and Binary Containers (LLM Summary)

## What Saffron does

Saffron is a pure-Java disk-image and filesystem library. Callers traverse
supported formats through a single `FileSystem` interface.

## Supported filesystems

| Format | Detection | Notes | Key test |
|--------|-----------|-------|----------|
| squashfs | `hsqs` magic at offset 0 | Phase 1. Supports xz, gzip, lzo, lz4, zstd, uncompressed. | `SquashfsDetectionTest.detectsMagic` |
| ext2/3/4 | `0xEF53` at superblock offset 1024 | Standard Linux filesystem. | existing corpus tests |
| NTFS | `"NTFS    "` OEM ID | Windows filesystem. | existing corpus tests |
| FAT32 / exFAT | FAT boot sector | Legacy/UEFI filesystems. | existing corpus tests |
| XFS | `"XFSB"` | Linux journaling filesystem. | existing corpus tests |
| Btrfs | `"_BHRfS_M"` | Linux copy-on-write filesystem. | existing corpus tests |
| HFS+ | `"H+"` / `"HX"` | macOS filesystem. | existing corpus tests |
| APFS | `"NXSB"` | Modern macOS filesystem. | existing corpus tests |
| swap | magic signature | Detected but not mounted. | existing tests |

## Supported binary containers

| Format | Detection | Notes | Key tests |
|--------|-----------|-------|-----------|
| Linux kernel images | `ContainerDetector` | x86 bzImage, ARM32 zImage, ARM64 Image, U-Boot uImage. Payload size is read from the image header; payloads are decompressed for gzip, bzip2, lzma, xz, lzo, lz4, and zstd. Exposes `/kernel-payload`, `/config.gz`, `/initramfs`, `/dtb`, and `/certificates` when present. The only source-size limit is `Integer.MAX_VALUE`; decompression output is bounded by the compressed input size, not by a fixed cap. | `LinuxKernelDetectionTest`, `LinuxKernelContainerTest`, `LinuxKernelContainerFuzzTest`, `LinuxKernelExtractionTest`, `LinuxKernelContainerFactoryTest`, `KernelDecompressorTest` |
| FIT / U-Boot images | `ContainerDetector` | DTB container with `/images` node. Exposes `/<node>` for every image, aliases `/kernel`, `/ramdisk`, `/dtb`, overlay DTBs by node name, and `/signature` if present. | `FitContainerDetectionTest`, `FitContainerTest`, `FitContainerMountTest`, `FitContainerFuzzTest`, `DeviceTreeBlobTest` |
| ELF | `ContainerDetector` | 32/64-bit, little/big-endian. Exposes `/sections/<name>` and `/segments/<index>`. | `ElfContainerFixtureTest`, `ElfContainerSectionTest`, `ElfContainerSegmentTest`, `ElfContainerDetectionTest`, `ElfContainerMountTest`, `ElfContainerSecurityTest`, `ElfContainerSyntheticTest`, `ElfContainerFuzzTest`, `ElfContainerMetadataTest`, `ElfContainerStreamTest` |
| DTB | `ContainerDetector` | Raw blob at `/dtb`; decoded properties at `/<name>` and `/<node-path>/<name>`. | `DtbContainerFixtureTest`, `DtbContainerDetectionTest`, `DtbContainerTest`, `DtbContainerMountTest`, `DtbContainerFitNegativeTest`, `DtbContainerSecurityTest`, `DtbContainerFuzzTest` |
| Raspberry Pi firmware | `ContainerDetector` | `BOOTCODE.BIN` and `FIXUP.DAT` by filename + content layout. `/raw` always, `/bootcode` for bootcode, `/fixup` for fixup. Backed by `InputStreamSource` for arbitrary size. | `RpiFirmwareContainerDetectionTest`, `RpiFirmwareContainerTest`, `RpiFirmwareContainerSecurityTest`, `RpiFirmwareContainerFuzzTest` |
| Android boot image | `ContainerDetector` | `ANDROID!` magic + header versions 0-2. Exposes `/raw`, `/kernel`, `/ramdisk`, `/second` (if present), `/dtb` (v2). Synthetic fixture. | `AndroidBootContainerDetectionTest`, `AndroidBootContainerTest`, `AndroidBootContainerSecurityTest`, `AndroidBootContainerFuzzTest` |

## FIT / U-Boot key facts

- Detected as a DTB with an `/images` node (`FitContainerDetectionTest.detectsFit`).
- Plain DTB without `/images` is classified as `DTB` (`FitContainerDetectionTest.rejectsPlainDtb`).
- Parsed by `DeviceTreeBlob` with bounds checks and overflow-safe arithmetic
  (`DeviceTreeBlobTest.rejectsOversizedStructureBlock`,
  `DeviceTreeBlobTest.rejectsOverflowingOffsets`). Property name offsets must
  point to null-terminated strings inside the strings block
  (`DeviceTreeBlobTest.rejectsUnterminatedStringName`).
- Exposes every `/images` child as a named entry, plus `/kernel`, `/ramdisk`, `/dtb`
  aliases (`FitContainerTest.exposesKernel`, `FitContainerTest.exposesRamdisk`,
  `FitContainerTest.exposesDtb`, `FitContainerTest.exposesOverlayDtbs`).
- No `/signature` entry when the FIT is unsigned (`FitContainerTest.handlesNoSignature`).
- Entry metadata includes `type`, `compression`, `arch`, `os`, `description`
  (`FitContainerTest.entryMetadataMatches`).
- Header fuzzing with Jazzer never produces an unchecked crash
  (`FitContainerFuzzTest.dtbHeaderFuzz`).

## ELF key facts

- Detection reads a 512-byte header and checks ELF magic (`0x7f454c46`)
  (`ElfContainerDetectionTest.detectsLibElf`,
  `ElfContainerDetectionTest.detectsTinyElf`).
- `ElfHeader` parses 32/64-bit, little/big-endian, validates structural sizes
  and table bounds, and uses safe overflow arithmetic
  (`ElfContainerSecurityTest.rejectsProgramHeaderTableBeyondFile`,
  `ElfContainerSecurityTest.rejectsSectionHeaderTableBeyondFile`,
  `ElfContainerSecurityTest.rejectsOverflowingOffsetPlusSize`).
- `ElfContainer` exposes `/sections/<name>` and `/segments/<index>`; duplicate
  section names are deduplicated; `SHT_NOBITS` sections are empty
  (`ElfContainerSectionTest.handlesDuplicateSectionNames`,
  `ElfContainerSectionTest.handlesNodataSection`,
  `ElfContainerSegmentTest.exposesLoadSegments`).
- Metadata includes `type`, `machine`, `entry`, `flags`, `flags_human`, etc.
  (`ElfContainerMetadataTest`,
  `ElfContainerSectionTest.sectionMetadataContainsTypeAndFlags`).
- Malformed input and out-of-bounds offsets return empty results, never
  unchecked `RuntimeException` (`ElfContainerSecurityTest`,
  `ElfContainerFuzzTest`).

## DTB key facts

- Detected by DTB magic (`0xd00dfeed`) and shared `DeviceTreeBlob` parser
  (`DtbContainerDetectionTest.detectsDtb`).
- FIT images (root has `/images`) are classified as `FIT_IMAGE`, not `DTB`
  (`DtbContainerFitNegativeTest.fitNotDtb`).
- `DeviceTreeBlob` validates structure/strings block bounds and uses safe
  arithmetic (`DtbContainerSecurityTest.rejectsStructureBlockBeyondFile`,
  `DtbContainerSecurityTest.rejectsStringsBlockBeyondFile`). Property name
  offsets must point to null-terminated strings inside the strings block
  (`DeviceTreeBlobTest.rejectsUnterminatedStringName`).
- `DtbContainer` exposes `/dtb` (raw) and decoded properties at `/<name>` and
  `/<node-path>/<name>` (`DtbContainerTest.exposesRawDtb`,
  `DtbContainerTest.exposesModelProperty`).
- FIT rejection and path sanitization prevent traversal and misclassification
  (`DtbContainerFitNegativeTest.dtbContainerRejectsFit`,
  `DtbContainerFuzzTest`).

## Raspberry Pi firmware key facts

- Detected by filename (`BOOTCODE.BIN`, `FIXUP.DAT`, case-insensitive) plus
  content validation; ELF/Linux/DTB checks run first so `start.elf` stays ELF
  (`RpiFirmwareContainerDetectionTest.detectsBootcode`,
  `RpiFirmwareContainerDetectionTest.detectsFixup`,
  `RpiFirmwareContainerDetectionTest.rejectsStartElfAsRpiFirmware`).
- `bootcode.bin` validated by first 512 bytes zero and non-zero byte at 0x200
  (`RpiFirmwareContainerDetectionTest.rejectsBootcodeWithNonZeroPadding`,
  `RpiFirmwareContainerDetectionTest.rejectsBootcodeZeroAt0x200`).
- `fixup.dat` validated by 64-byte fixup-table pattern; no buffer-only detection
  (`RpiFirmwareContainerDetectionTest.rejectsFixupFromBuffer`).
- Container backed by `InputStreamSource`; arbitrarily large files can be opened
  without loading into memory (`RpiFirmwareContainerTest.largeFileDoesNotLoadWholeFile`).
- Entries are bounded views over the source (`RpiFirmwareContainerTest.rawEntryEqualsInput`,
  `RpiFirmwareContainerTest.independentStreams`).
- `/raw` is always present; `/bootcode` for bootcode; `/fixup` for fixup
  (`RpiFirmwareContainerTest.exposesBootcodeSections`,
  `RpiFirmwareContainerTest.exposesFixupSections`).

## Android boot image key facts

- Synthetic v2 fixture because no public `boot.img` under 6.5 MB was found;
  generated by `src/test/resources/android-boot/generate-boot-img.py`
  (`AndroidBootContainerFixtureTest.fixtureShaMatchesDocumented`,
  `AndroidBootContainerFixtureTest.fixtureIsUnderSizeLimit`).
- Detected by `ANDROID!` magic + header versions 0-2; page size validated
  (`AndroidBootContainerDetectionTest.detectsBootImage`,
  `AndroidBootContainerDetectionTest.detectsBootImageFromBuffer`,
  `AndroidBootContainerDetectionTest.detectsBootImageFromVirtualDisk`).
- Stronger formats (ELF, Linux kernel, DTB/FIT) checked first
  (`AndroidBootContainerDetectionTest.strongerFormatWinsOverAndroidMagic`).
- Parses header, computes page-aligned offsets, validates against source size with
  overflow-safe arithmetic; v3/v4 not supported
  (`AndroidBootContainerTest.exposesKernel`,
  `AndroidBootContainerTest.exposesRamdisk`,
  `AndroidBootContainerTest.exposesSecond`,
  `AndroidBootContainerTest.exposesDtb`,
  `AndroidBootContainerTest.handlesMissingDtb`).
- Backed by `InputStreamSource`; entries are `RegionInputStream` views
  (`AndroidBootContainerTest.rawEntryEqualsInput`,
  `AndroidBootContainerTest.independentStreams`,
  `AndroidBootContainerTest.largeFileDoesNotLoadWholeFile`).
- Malformed/overflow inputs rejected; Jazzer header fuzz never causes unchecked
  crashes (`AndroidBootContainerSecurityTest`,
  `AndroidBootContainerFuzzTest`).

## squashfs key facts

- Standalone `.squashfs` files are treated as raw disks by `DiskFormat`.
- Superblock parsed little-endian at offset 0 (`SquashfsDetectionTest.detectsMagic`).
- Version 4.0 required (`SquashfsOverflowTest.rejectsBadVersion`).
- Compression dispatched by ID: none, gzip, lzo, xz, lz4, zstd
  (`SquashfsPropertyTest.roundTripWithCompression`,
  `SquashfsPropertyTest.roundTripUncompressed`).
- Parses inode table, directory table, fragment table, ID table
  (`SquashfsMountTest.mountsIoTGoatRoot`).
- Exposes directories, regular files, symlinks, special files
  (`SquashfsMountTest.mountsAlpineRoot`).
- Bounds-checks every offset and size against the source image
  (`SquashfsOverflowTest`, `SquashfsCorruptionTest`).
- `RegularFile.openStream()` streams one block at a time; no up-front allocation
  sized by the declared file length (`SquashfsStreamingTest.openStreamReadsFileInChunks`).
- `RegularFile.readAllBytes()` validates the declared file size against the
  available data blocks before allocating
  (`SquashfsStreamingTest.rejectsDeclaredFileSizeLargerThanAvailableBlocks`).

## Security model

- Pure Java, no native code.
- All offsets/sizes validated before use.
- Safe arithmetic via `SafeMath`.
- Malformed input yields empty `Optional` or checked exceptions, never
  unchecked `RuntimeException`.

## Test fixtures

- `src/test/resources/squashfs/alpine-minimal.squashfs`
- `src/test/resources/squashfs/alpine-rootfs.squashfs`
- `src/test/resources/squashfs/iotgoat-rpi-rootfs.squashfs`
- `src/test/resources/elf/libmbedx509.so`
- `src/test/resources/elf/start.elf`
- `src/test/resources/dtb/bcm2710-rpi-3-b.dtb`
- `src/test/resources/dtb/bcm2710-rpi-3-b-plus.dtb`
- `src/test/resources/rpi-firmware/bootcode.bin`
- `src/test/resources/rpi-firmware/fixup.dat`
- `src/test/resources/android-boot/boot.img`

## Future work

Binary containers (WIM, DMG) are planned for later phases. Linux kernel, FIT,
ELF, DTB, Raspberry Pi firmware, and Android boot are complete.
