# Saffron Filesystems and Binary Containers

Saffron is a pure-Java disk-image and filesystem library. It exposes every
supported format through a single `FileSystem` interface.

## Supported filesystems

| Format | Magic / Signature | Detection | Mount path | Key test |
|--------|-------------------|-----------|------------|----------|
| squashfs | `hsqs` at offset 0 | `FilesystemDetector` | `FileSystemMount.mount` | `SquashfsDetectionTest.detectsMagic` |
| ext2/ext3/ext4 | `0xEF53` at superblock offset 1024 | `FilesystemDetector` | `FileSystemMount.mount` | existing corpus tests |
| NTFS | NTFS OEM ID at offset 3 | `FilesystemDetector` | `FileSystemMount.mount` | existing corpus tests |
| FAT32 / exFAT | FAT boot sector signatures | `FilesystemDetector` | `FileSystemMount.mount` | existing corpus tests |
| XFS | XFSB at offset 0 | `FilesystemDetector` | `FileSystemMount.mount` | existing corpus tests |
| Btrfs | BHRfS_M at offset 64 | `FilesystemDetector` | `FileSystemMount.mount` | existing corpus tests |
| HFS+ | H+ / HX signatures | `FilesystemDetector` | `FileSystemMount.mount` | existing corpus tests |
| APFS | NXSB container superblock | `FilesystemDetector` | `FileSystemMount.mount` | existing corpus tests |
| swap | SWAPSPACE2 or SWAP-SPACE | `FilesystemDetector` | Detected but not mounted | `LvmTest` / existing tests |

## Supported binary containers

| Format | Detection | Mount path | Current entries | Key tests |
|--------|-----------|------------|-----------------|-----------|
| Linux kernel images | `ContainerDetector` | `BinaryContainerMount.mount` | `/kernel-payload`, `/config.gz`, `/initramfs`, `/dtb`, `/certificates` (when present) | `LinuxKernelDetectionTest`, `LinuxKernelContainerTest`, `LinuxKernelContainerFuzzTest`, `LinuxKernelExtractionTest`, `LinuxKernelContainerFactoryTest` |
| FIT / U-Boot images | `ContainerDetector` | `BinaryContainerMount.mount` | `/<image-node>` for every `/images` child, plus aliases `/kernel`, `/ramdisk`, `/dtb`; overlay DTBs by node name; `/signature` if present | `FitContainerDetectionTest`, `FitContainerTest`, `FitContainerMountTest`, `FitContainerFuzzTest`, `DeviceTreeBlobTest` |
| ELF | `ContainerDetector` | `BinaryContainerMount.mount` | `/sections/<name>` for every named section, `/segments/<index>` for every program segment | `ElfContainerFixtureTest`, `ElfContainerSectionTest`, `ElfContainerSegmentTest`, `ElfContainerDetectionTest`, `ElfContainerMountTest`, `ElfContainerSecurityTest`, `ElfContainerSyntheticTest`, `ElfContainerFuzzTest`, `ElfContainerMetadataTest`, `ElfContainerStreamTest` |
| DTB | `ContainerDetector` | `BinaryContainerMount.mount` | `/dtb` (raw blob), `/<property-name>` for root properties, `/<node-path>/<property-name>` for child properties | `DtbContainerFixtureTest`, `DtbContainerDetectionTest`, `DtbContainerTest`, `DtbContainerMountTest`, `DtbContainerFitNegativeTest`, `DtbContainerSecurityTest`, `DtbContainerFuzzTest` |
| Raspberry Pi firmware | `ContainerDetector` | `BinaryContainerMount.mount` | `/raw` (whole file), `/bootcode` for `bootcode.bin` (code at offset 0x200), `/fixup` for `fixup.dat` | `RpiFirmwareContainerDetectionTest`, `RpiFirmwareContainerTest`, `RpiFirmwareContainerSecurityTest`, `RpiFirmwareContainerFuzzTest` |
| Android boot image | `ContainerDetector` | `BinaryContainerMount.mount` | `/raw` (whole file), `/kernel`, `/ramdisk`, `/second` (if present), `/dtb` (v2) | `AndroidBootContainerDetectionTest`, `AndroidBootContainerTest`, `AndroidBootContainerSecurityTest`, `AndroidBootContainerFuzzTest` |

Recognised subtypes: x86 `bzImage`, ARM32 `zImage`, ARM64 `Image`, and U-Boot
`uImage`. Payloads are decompressed according to the header-declared compression
algorithm (gzip, bzip2, lzma, xz, lzo, lz4, and zstd). Optional components
(initramfs cpio archive, DTB, X.509 certificates) are extracted from the
decompressed payload and from any raw bytes appended after the declared payload
region. The only size limit is the maximum addressable byte array size
(`Integer.MAX_VALUE`); decompression output is bounded by a limit derived from
the compressed input size, not by a fixed cap.

## FIT / U-Boot implementation details

- **Detection:** A Flattened Image Tree (FIT) is a device tree blob whose root
  contains an `/images` node. `ContainerDetector` checks the DTB magic
  (`0xd00dfeed`), validates the structure block against the source size, and
  walks the tree to distinguish FIT (`/images` present) from a plain DTB
  (`/images` absent). Verified by `FitContainerDetectionTest.detectsFit` and
  `FitContainerDetectionTest.rejectsPlainDtb`.
- **Parsing:** `DeviceTreeBlob` parses the big-endian DTB header and structure
  block with bounds checks and overflow-safe arithmetic; every property name
  offset is verified to point to a null-terminated string inside the strings block,
  and malformed input is rejected rather than trusted. Verified by
  `DeviceTreeBlobTest.rejectsOversizedStructureBlock`,
  `DeviceTreeBlobTest.rejectsOverflowingOffsets`, and
  `DeviceTreeBlobTest.rejectsUnterminatedStringName`.
- **Entries:** every child node under `/images` becomes an entry named after the
  node (e.g., `/kernel-1`, `/initrd-1`, `/fdt-1`). Standard aliases `/kernel`,
  `/ramdisk`, and `/dtb` point to the first image of type `kernel`, `ramdisk`,
  and `flat_dt` respectively. Device-tree overlays are exposed by their node names.
  If a `/signature` node exists, it is exposed as `/signature`. Verified by
  `FitContainerTest.exposesKernel`, `FitContainerTest.exposesRamdisk`,
  `FitContainerTest.exposesDtb`, `FitContainerTest.exposesOverlayDtbs`, and
  `FitContainerTest.handlesNoSignature`.
- **Metadata:** each entry carries the FIT properties `type`, `compression`,
  `arch`, `os`, and `description` as metadata. Verified by
  `FitContainerTest.entryMetadataMatches`.
- **Hardening:** container entry paths are validated; node names containing
  `/`, `\0`, or `..` are ignored. Malformed input yields empty results or
  documented checked exceptions, never unchecked `RuntimeException`. Verified by
  `FitContainerFuzzTest.dtbHeaderFuzz`.

## ELF implementation details

- **Detection:** `ContainerDetector` reads the first 512 bytes of a file or disk
  (enough for ELF and Linux-kernel headers) and checks the ELF magic
  (`0x7f454c46`) plus the structural header fields. Verified by
  `ElfContainerDetectionTest.detectsLibElf` and
  `ElfContainerDetectionTest.detectsTinyElf`.
- **Parsing:** `ElfHeader` parses 32-bit and 64-bit ELF headers in little-endian
  or big-endian byte order, validates the class/data/version, the header/entry
  sizes, and the bounds of the program and section header tables. Safe overflow
  arithmetic is used throughout. Verified by
  `ElfContainerSecurityTest.rejectsProgramHeaderTableBeyondFile`,
  `ElfContainerSecurityTest.rejectsSectionHeaderTableBeyondFile`,
  `ElfContainerSecurityTest.rejectsOverflowingOffsetPlusSize`,
  `ElfContainerSecurityTest.rejectsHeaderSizeMismatch`,
  `ElfContainerSecurityTest.rejectsProgramHeaderEntrySizeMismatch`, and
  `ElfContainerSecurityTest.rejectsSectionHeaderEntrySizeMismatch`.
- **Entries:** `ElfContainer` exposes every named section as `/sections/<name>`
  (duplicate names are deduplicated as `_1`, `_2`, etc.) and every program
  segment as `/segments/<index>`. `SHT_NOBITS` sections are exposed with empty
  content. Verified by `ElfContainerSectionTest.exposesTextSection`,
  `ElfContainerSectionTest.handlesDuplicateSectionNames`,
  `ElfContainerSectionTest.handlesNodataSection`, and
  `ElfContainerSegmentTest.exposesLoadSegments`.
- **Metadata:** container metadata includes ELF `type`, `machine`, `entry`,
  `source_size`, and `entry_count`. Each entry carries metadata such as `type`,
  `flags`, `flags_human`, `addr`/`vaddr`/`paddr`, `align`, etc. Verified by
  `ElfContainerMetadataTest` and
  `ElfContainerSectionTest.sectionMetadataContainsTypeAndFlags`.
- **Hardening:** section names are validated; names containing `/`, `\`, `\0`,
  `..`, or control characters are skipped. Out-of-bounds offsets and sizes
  return empty results. Malformed input never throws an unchecked
  `RuntimeException`. Verified by `ElfContainerSecurityTest`,
  `ElfContainerFuzzTest`, and
  `ElfContainerSectionTest.rejectsPathTraversalSectionName`.

## DTB implementation details

- **Detection:** `ContainerDetector` checks the DTB magic (`0xd00dfeed`) and
  parses the full structure block via the shared `DeviceTreeBlob` parser. A
  DTB whose root contains an `/images` node is classified as a FIT image
  (`FIT_IMAGE`), not as a plain DTB (`DTB`). Verified by
  `DtbContainerDetectionTest.detectsDtb` and
  `DtbContainerFitNegativeTest.fitNotDtb`.
- **Parsing:** `DeviceTreeBlob` validates the DTB header, structure block, and
  strings block bounds against the source size, and uses safe long arithmetic.
  It is shared with the FIT implementation. Verified by
  `DtbContainerSecurityTest.rejectsStructureBlockBeyondFile`,
  `DtbContainerSecurityTest.rejectsStringsBlockBeyondFile`, and
  `DtbContainerSecurityTest.rejectsTotalSizeBeyondBuffer`. Property name offsets
  are required to point to null-terminated strings inside the strings block;
  a missing terminator is rejected. Verified by
  `DeviceTreeBlobTest.rejectsUnterminatedStringName`.
- **Entries:** `DtbContainer` exposes the raw blob as `/dtb` and every decoded
  property as an entry whose path reflects the node hierarchy (root properties
  as `/<property-name>`, child properties as `/<node-path>/<property-name>`).
  Verified by `DtbContainerTest.exposesRawDtb`,
  `DtbContainerTest.exposesModelProperty`, and
  `DtbContainerTest.exposesChildProperty`.
- **Metadata:** container metadata includes `format`, `source_size`,
  `total_size`, and `entry_count`. Each property entry carries `type`, `node`,
  and `property_name`. Verified by `DtbContainerTest.containerMetadataContainsFormatAndSize`.
- **Hardening:** property and node names are validated; names containing `/`,
  `\`, `\0`, `..`, or control characters are skipped. FIT images are rejected.
  Malformed input never throws an unchecked `RuntimeException`. Verified by
  `DtbContainerSecurityTest`, `DtbContainerFuzzTest`, and
  `DtbContainerFitNegativeTest.dtbContainerRejectsFit`.

## Raspberry Pi firmware implementation details

- **Detection:** `ContainerDetector` identifies `BOOTCODE.BIN` and `FIXUP.DAT`
  (case-insensitive) by filename plus content validation. `bootcode.bin` is
  validated by reading only the first 0x201 bytes and checking that the first
  512 bytes are zero and the byte at offset 0x200 is non-zero. `fixup.dat` is
  validated by reading the first 64 bytes and checking that they are dominated
  by the documented `0x03` / `0x0f` fixup-table pattern. `start.elf` is still
  detected as `ELF` because the ELF check runs first. Verified by
  `RpiFirmwareContainerDetectionTest.detectsBootcode`,
  `RpiFirmwareContainerDetectionTest.detectsFixup`, and
  `RpiFirmwareContainerDetectionTest.rejectsStartElfAsRpiFirmware`.
- **Streaming source:** the container is backed by an `InputStreamSource` so
  arbitrarily large firmware files can be opened without loading the whole file
  into memory. Entries are zero-copy views through `RegionInputStream`.
  Verified by `RpiFirmwareContainerTest.largeFileDoesNotLoadWholeFile`.
- **Entries:** every container exposes `/raw` as a fallback. `bootcode.bin` also
  exposes `/bootcode` (code starting at offset 0x200). `fixup.dat` also exposes
  `/fixup` (the entire fixup table). Verified by
  `RpiFirmwareContainerTest.exposesBootcodeSections` and
  `RpiFirmwareContainerTest.exposesFixupSections`.
- **Hardening:** detection reads only the bytes needed for validation; no
  allocation is driven by untrusted sizes; malformed input returns empty or a
  checked exception. Verified by `RpiFirmwareContainerSecurityTest` and
  `RpiFirmwareContainerFuzzTest`.

## Android boot image implementation details

- **Fixture:** no public Android `boot.img` under 6.5 MB was found. The approved
  alternative is a synthetic header-version-2 image generated by
  `src/test/resources/android-boot/generate-boot-img.py` from the public
  `ANDROID!` header format and synthetic components. Verified by
  `AndroidBootContainerFixtureTest.fixtureShaMatchesDocumented` and
  `AndroidBootContainerFixtureTest.fixtureIsUnderSizeLimit`.
- **Detection:** `ContainerDetector` reads the first 2048 bytes, checks the
  `ANDROID!` magic, validates the page size, and recognizes header versions 0-2.
  Stronger formats (ELF, Linux kernel, DTB/FIT) are checked first. Verified by
  `AndroidBootContainerDetectionTest.detectsBootImage`,
  `AndroidBootContainerDetectionTest.detectsBootImageFromBuffer`,
  `AndroidBootContainerDetectionTest.detectsBootImageFromVirtualDisk`, and
  `AndroidBootContainerDetectionTest.strongerFormatWinsOverAndroidMagic`.
- **Parsing:** `AndroidBootContainer` parses the little-endian header, computes
  page-aligned offsets for kernel, ramdisk, second stage, and (v2) DTB, and
  validates every offset and size against the source size using overflow-safe
  arithmetic. Header versions 3 and 4 are not supported in this phase. Verified by
  `AndroidBootContainerTest.exposesKernel`,
  `AndroidBootContainerTest.exposesRamdisk`,
  `AndroidBootContainerTest.exposesSecond`,
  `AndroidBootContainerTest.exposesDtb`, and
  `AndroidBootContainerTest.handlesMissingDtb`.
- **Streaming source:** the container is backed by an `InputStreamSource` and
  entries are zero-copy views through `RegionInputStream`. Verified by
  `AndroidBootContainerTest.rawEntryEqualsInput`,
  `AndroidBootContainerTest.independentStreams`, and
  `AndroidBootContainerTest.largeFileDoesNotLoadWholeFile`.
- **Hardening:** invalid magic, unsupported versions, mismatched page sizes,
  negative sizes, overflow, and components that extend past the file are all
  rejected. Verified by `AndroidBootContainerSecurityTest` and
  `AndroidBootContainerFuzzTest`.

## squashfs implementation details

- **Detection:** standalone `.squashfs` files are treated as raw disk images by
  `DiskFormat` and opened with `DiskReader.open`; `FilesystemDetector` then
  finds the squashfs filesystem at offset 0.
- **Magic:** `hsqs` (little-endian `0x73717368`).
- **Superblock:** parsed from offset 0 in little endian; version must be 4.0.
- **Compression:** supports xz, gzip, lzo, lz4, zstd, and uncompressed. The
  compression ID is read from the superblock and dispatched through
  `SquashfsCompressor`. Verified by
  `SquashfsPropertyTest.roundTripWithCompression` and
  `SquashfsPropertyTest.roundTripUncompressed`.
- **Structures parsed:** superblock, inode table, directory table, fragment
  table, ID table.
- **Entry types:** directories, regular files, symbolic links, and special
  files (devices, FIFOs, sockets).
- **File reads:** `RegularFile.openStream()` reads one data block at a time and
  decompresses it on demand; no byte array sized by the declared file length is
  allocated up front. `RegularFile.readAllBytes()` is a convenience that still
  materializes the whole file, but it validates the declared size against the
  actual data blocks and rejects files larger than `Integer.MAX_VALUE` before
  allocating.

## Security model

- **Pure Java:** no shell execution, no native code.
- **Bounds checking:** every offset and size read from the image is validated
  against the source file size before use. Verified by
  `SquashfsOverflowTest` and `SquashfsCorruptionTest`.
- **No arbitrary resource caps:** entry counts, directory depth, and file sizes
  are not capped. Allocation is driven only by validated on-disk sizes. For
  squashfs regular files, `openStream()` never allocates a buffer sized by the
  declared file length, and `readAllBytes()` validates the declared size against
  the available data blocks before allocating.
- **Safe arithmetic:** `SafeMath` is used when adding offsets and sizes.
- **Malformed input:** corrupt or truncated images return empty results or
  throw documented checked exceptions; they never throw unchecked
  `RuntimeException`.

## Test fixtures

- `src/test/resources/squashfs/alpine-minimal.squashfs` — real Alpine
  minirootfs (xz compression) for fast tests.
- `src/test/resources/squashfs/alpine-rootfs.squashfs` — real Alpine
  minirootfs (xz compression) for real-world tests.
- `src/test/resources/squashfs/iotgoat-rpi-rootfs.squashfs` — real
  IoTGoat Raspberry Pi root filesystem (xz compression) for integration tests.
- `src/test/resources/fit/openwrt-23.05.3-mediatek-filogic-mediatek_mt7981-rfb-initramfs.itb`
  — real OpenWrt FIT image (7.00 MiB) for FIT tests.
- `src/test/resources/fit/mediatek_mt7981-rfb.dtb` — plain DTB extracted from the
  OpenWrt FIT fixture for negative-classification tests.
- `src/test/resources/elf/libmbedx509.so` — stripped ARM shared object (no
  section headers) used for segment tests.
- `src/test/resources/elf/start.elf` — Raspberry Pi firmware ELF with duplicate
  `.rsdata` sections used for section tests.
- `src/test/resources/dtb/bcm2710-rpi-3-b.dtb` — Raspberry Pi 3 Model B device
  tree blob from the IoTGoat boot partition.
- `src/test/resources/dtb/bcm2710-rpi-3-b-plus.dtb` — Raspberry Pi 3 Model B+
  device tree blob from the IoTGoat boot partition.
- `src/test/resources/rpi-firmware/bootcode.bin` and `fixup.dat` — Raspberry Pi
  firmware blobs extracted from the IoTGoat boot partition.
- `src/test/resources/android-boot/boot.img` — synthetic Android boot image v2
  fixture generated by `generate-boot-img.py`.

## Future work

Binary container formats (WIM, DMG) are planned for later phases. Linux kernel,
FIT, ELF, DTB, Raspberry Pi firmware, and Android boot are complete.
