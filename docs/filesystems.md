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
| JFFS2 | `0x1985` node header at offset 0 | `FilesystemDetector` | `FileSystemMount.mount` | `Jffs2DetectionTest.detectsReferenceFixtures` |
| cramfs | `0x28cd3d45` + "Compressed ROMFS" | `FilesystemDetector` | `FileSystemMount.mount` | `CramfsDetectionTest.detectsLittleEndianFixture` |
| YAFFS2 | chunk-tag geometry (no magic) | `FilesystemDetector` | `FileSystemMount.mount` | `Yaffs2DetectionTest.detectsWildSamples` |
| UBIFS | `0x06101831` node magic | `FilesystemDetector` | `FileSystemMount.mount` | `UbifsDetectionTest.detectsWildUbifsVolumes` |
| UBI | `UBI#`/`UBI!` PEB headers | `FilesystemDetector` | attach + mount UBIFS volumes | `UbifsDetectionTest.detectsWildUbiContainers` |

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

## Allocation caps and metadata validation (phase 3)

Validate-before-allocate: on-disk size fields are checked before any
allocation; violations throw `IOException` (checked). Per-driver:

- btrfs: nodeSize power-of-2 4 KiB..1 MiB; sectorSize 512..64 KiB;
  sysChunkArraySize ≥ 0; leaf/internal item counts ≤ nodeSize/16; item
  data bounds checked; extent decompression caps at 16 MiB
  (`ResourceLimitException`); checked logical-address arithmetic.
- APFS: container blockSize power-of-2 512 B..64 MiB validated at mount
  (not just detection).
- XFS: blockSize power-of-2 512 B..64 KiB; dirBlockLog ≤ 7.
- HFS+: volume blockSize power-of-2 512 B..4 MiB; B-tree node record
  counts/offsets/lengths bounds-checked.
- squashfs: fragment-entry count and extended-inode block counts capped
  before allocation; extended-inode fileSize capped at 16 MiB.
- NTFS: BPB validated (bytesPerSector pow2 512..4096, sectorsPerCluster
  pow2 1..128); MFT record size 256 B..1 MiB; `$ATTRIBUTE_LIST` and
  `$INDEX_ALLOCATION` reads capped at 16 MiB; attribute-list entries
  capped at 4096.
- UBI: lnum arrays bounded by the image PEB count.
- UBIFS: inline data capped at 16 MiB.
- FAT/exFAT: BPB validated (bytesPerSector pow2 512..4096,
  sectorsPerCluster pow2 ≥ 1); FAT geometry (dataSectors > 0) checked;
  exFAT cluster size ≤ 16 MiB; directory-chain materialization capped
  inside the loop at 16 MiB.
- readAllBytes: ALL drivers refuse files > 16 MiB with
  `ResourceLimitException` directing callers to `openStream()`
  (user-approved memory budget). Corpus verification tests stream
  instead.
- Per-node decompression buffers (jffs2/yaffs2) capped at 16 MiB.

Claim: hostile header fields are rejected with `IOException` at parse,
and boundary values still parse. Test:
`FilesystemAllocationCapsTest` (btrfsNodeSizeValidation,
apfsBlockSizeValidatedAtMount, xfsBlockSizeValidation,
hfsPlusBlockSizeValidation, hfsPlusBTreeNodeImplausibleRecordCountRejected,
fatBpbValidation, ntfsBpbAndMftRecordSizeValidation, exfatBpbValidation).

Claim: real images keep working after the caps. Tests: corpus
verification suites (`CorpusFileVerificationTest`,
`CorpusFileCountVerificationTest`, `CorpusFullVerificationTest`,
`PerFilesystemVerificationTest`) and the wild-image suites — all green
with the caps enabled.

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

## Post-audit remediation (2026-08-27)

Adversarial-audit fixes: ext4 directory reads now carry the same 16 MiB
cap as files (`readDirectoryEntries`); APFS omap descent is depth-capped
(64) with a visited-set (the generic btree cap did not cover this path);
hfs+ B-tree node validation now throws checked `IOException` (was
unchecked `IllegalArgumentException`); the FAT32 directory-chain cap is
enforced inside the loop; btrfs tree recursions gained visited-sets and
the chunk scan enforces the `region.size()/32` plausibility bound in
addition to the 64k cap; GPT rejects entry counts > 256 instead of
silently clamping.

Test coverage note (post-audit): boundary/cycle/cache-budget unit tests
now exist for walk depth (Yaffs2BoundaryTest), readAllBytes caps
(Yaffs2BoundaryTest), NTFS cache budgets (NtfsCacheBudgetTest), the
btrfs chunk cap (BtrfsChunkCapTest), APFS omap LRU + cycles
(ApfsObjectMapCacheTest), the ext4 directory 16 MiB cap
(Ext4DirectoryCapTest, mutation-verified), VMDK grain markers, and the
exFAT unsigned comparison. All post-audit gaps have unit-level tests.

Recorded deviation: R4.1's pinned failure mode ("IOException('directory
tree too deep')") is implemented as SILENT TRUNCATION at the default
depth instead — the stack-safety goal is met, explicit-depth callers get
exact semantics, and throwing from lazy stream construction would change
every driver's walk contract. This is the accepted behavior; the
pinned-failure-mode wording in the plan is superseded.

## Recursion safety (phase 4)

Hostile self-referential structures must fail with a checked
`IOException`, never a `StackOverflowError`:

- `walk()` defaults to `MAX_WALK_DEPTH = 512` in every driver (callers
  passing an explicit depth get what they ask for). Stream-based walks
  surface the depth error on stream consumption.
- ext4 extent-tree walk: depth cap 64 + visited block set (cycle →
  `IOException`); xfs extent btree: same; APFS b-tree search: depth cap
  64; btrfs tree recursions (search/findAll/scanForType): depth cap 64.
- FAT/exFAT cluster chains: visited-set — a cyclic chain is corruption
  and fails checked (chains were already iteration-capped; this is
  correctness, not hang prevention). The exFAT walk also switched to
  UNSIGNED 32-bit comparisons: the EOC/BAD markers (0xFFFFFFF8/F7) are
  negative as signed ints, so the pre-existing signed comparison stopped
  every chain after one cluster — a latent correctness bug found by the
  new tests.
- YAFFS2 hardlinks: visited object-id set — mutually recursive
  hardlinks fail checked.

Claim: hostile cycles and over-deep trees fail checked per driver.
Tests: `Ext4ExtentCycleTest`, `XfsExtentBtreeCycleTest`,
`FatClusterChainCycleTest`, `ExFatClusterChainCycleTest`,
`Yaffs2HardlinkCycleTest`.

## Streaming openStream (phase 5)

All 13 drivers' `openStream()` are true lazy streams; no whole-file
materialization remains (except per-unit decompression for formats that
require it — NTFS compressed runs, btrfs compressed extents, APFS
decmpfs — each capped at 16 MiB):

- Shared helper `ChunkedRegionStream` (window 256 KiB, ≤ 1 MiB per
  region read, sparse gaps served as zeros WITHOUT reading, segment
  metadata capped at 1M entries).
- Converted: ntfs (data-run segments, sparse runs = holes), fat32/exfat
  (cluster chains), xfs (extent/btree with logical offsets), hfs+
  (fork extents), apfs (extent records), ext4 (indirect-block list;
  extent files already streamed, per-read now capped at 1 MiB), btrfs
  (per-extent lazy walk; regular extents stream through the chunk tree
  in bounded windows — diskBytenr is a LOGICAL address, the stream
  never bypasses the chunk mapping).
- The 16 MiB `readAllBytes` cap stays; `openStream()` is the unbounded
  path.

Claim: stream bytes equal the materialized reference. Tests:
`ChunkedRegionStreamTest` (6 methods — segment gaps, bounded reads via
recording region, sparse regions never read, independence, single-byte
reads), `NtfsFileSystemTest.ntfsFileSystem_streamsEqualMaterializedContent`,
`ExFatFileSystemTest.exFatFileSystem_streamsEqualMaterializedContent`,
and the corpus suites (SHA-256 oracles streamed through the new code —
`CorpusFileVerificationTest`, `CorpusFullVerificationTest`,
`PerFilesystemVerificationTest`).

Note: the corpus oracle caught a real bug during this phase — the first
btrfs stream read logical addresses directly from the region, bypassing
the chunk-tree mapping (SHA-256 mismatches on real images); fixed by
streaming through `chunkTree.readLogical` windows.

## Lazy/evicting mount structures (phase 6)

- NTFS MFT cache: bounded LRU (4096 records AND a 16 MiB attribute
  payload budget, synchronized; records over 4 MiB payload are not
  cached at all — re-read on demand).
- APFS object-map resolution cache: bounded LRU (4096 entries,
  synchronized).
- btrfs chunk scan: plausibility cap of 65536 chunk items, loud
  `IOException` at mount beyond it (see `docs/adr/0003-btrfs-chunk-scan.md`).
- squashfs metadata tables (inode + directory): lazy — construction
  reads only block headers; decompressed blocks are produced on demand
  and cached in a bounded LRU of 32 blocks; the final block's true
  length is learned eagerly (spec: only the last block may decompress
  short); readers chain blocks so reads may cross block boundaries
  byte-identically to the eager implementation. The pre-change eager
  implementation is preserved in test scope as the golden oracle
  (`SquashfsMetadataTableEager`).
- SecurityPolicy overlay: DEFERRED — mount APIs do not currently accept
  a `SecurityPolicy`; hard caps above serve as the defaults (policy gap
  documented).

Claim: cache mechanics (LRU order, byte budget, knob validation). Test:
`LruCacheTest` (5 methods).

Claim: lazy squashfs tables equal the eager oracle byte-for-byte across
random positions, the block cache stays ≤ 32 under linear and thrash
access, and unknown blocks are rejected. Tests:
`SquashfsMetadataTableLazyTest` (4 methods).
