# Disk Image Formats — Hardening Contract

Saffron opens disk images (`raw`, `qcow2`, `vhd`, `vhdx`, `vdi`, `vmdk`,
`ami`, `gcp`) as `VirtualDisk` instances. This document records the
hardening contract applied in the 2026-08-26 cleanup (phase 1): how
truncated and hostile images behave.

## Truncation behavior

Reading an ALLOCATED region that extends past the end of a truncated file
throws a checked `IOException` (message starts with "Truncated"). Reads
wholly below the truncation point remain byte-exact. UNALLOCATED (sparse)
regions keep returning zeros without touching the backing file.

Claim: qcow2/VHD/VHDX/VDI/VMDK/raw reads touching a truncation point
throw `IOException`; sparse regions stay zero; never a hang, never
short/zero success. Tests: `DiskTruncationTest.*`
(`rawTruncatedReadThrows`, `qcow2TruncatedAllocatedReadThrows`,
`qcow2UnallocatedRegionStillZerosWhenTruncated`,
`vhdFixedTruncatedReadThrows`, `vhdDynamicTruncatedAllocatedReadThrows`,
`vhdxTruncatedAllocatedReadThrows`, `vdiTruncatedAllocatedReadThrows`,
`vmdkTruncatedAllocatedReadThrows`, `vmdkCompressedTruncatedReadThrows`),
`DiskTruncationSweepTest.randomTruncationsTerminateAndNeverReturnShortData`.

## Header validation (validate-before-allocate)

On-disk size fields are validated before any allocation; violations throw
`IOException` at open:

| Format | Field | Valid range |
|---|---|---|
| VHD | currentSize | > 0, ≤ 2 TiB |
| VHD | dynamic blockSize | power of 2, 512 B..8 MiB |
| VHD | maxTableEntries | ≤ 4M (16 MiB BAT budget) |
| VDI | blockSize | power of 2, > 0, ≤ 64 MiB |
| VDI | blocksInHdd | ≤ 4M, BAM within file |
| VHDX | virtualDiskSize | > 0, ≤ 64 TiB |
| VHDX | blockSize | power of 2, 1 MiB..256 MiB |
| VHDX | region entryCount | 1..2047 |
| VHDX | BAT | ≤ 16 MiB read, within file |
| VMDK | capacity | > 0, ≤ 2 TiB, no overflow |
| VMDK | grainSize | power of 2, 1..4096 sectors |
| VMDK | grain directory | ≤ 1M entries (rejected, not truncated) |
| GPT | entrySize | 128..4096 |
| GPT | entries range | within disk, no overflow |
| qcow2 | L1 table | ≤ 16 MiB |

Claim: hostile header fields are rejected with `IOException` at open.
Tests: `DiskValidationTest.*` (e.g. `vhdBlockSizeZeroRejected`,
`vhdBlockSizeNonPowerOfTwoRejected`, `vhdBlockSizeBeyondEightMiBRejected`,
`vhdMaxTableEntriesHugeRejected`, `vhdFooterCurrentSizeBeyondTwoTiBRejected`,
`vdiBlockSizeZeroRejected`, `vdiBlockSizeNonPowerOfTwoRejected`,
`vdiBlocksInHddHugeRejected`, `vhdxBlockSizeBelowOneMiBRejected`,
`vhdxBlockSizeNonPowerOfTwoRejected`,
`vhdxVirtualSizeBeyondSixtyFourTiBRejected`,
`vhdxRegionEntryCountHugeRejected`, `vmdkGrainSizeZeroRejected`,
`vmdkGrainSizeHugeRejected`, `vmdkCapacityOverflowRejected`,
`gptEntrySizeTooLargeRejected`, `gptEntriesLbaBeyondDiskRejected`,
`gptEntryCountSizeOverflowRejected`) and
`acceptedRangeBoundariesStillOpen`.

## Silent wrong data eliminated

- VMDK: grain-directory read failures rethrow (previously `new int[0]` →
  whole disk read as zeros); partial grain directories throw; grain-table
  entry reads verify byte counts; compressed-grain marker/size validation
  throws on corruption; decompression failure throws (previously zeros).
- qcow2: compressed clusters whose deflate stream is not finished throw
  (previously zero-filled).
- AMI: missing parts reject at open; `read()` uses skipFully/readFully
  (short/truncated parts throw); reads are bounds-checked; manifest number
  errors raise `SaffronException.InvalidDiskException`; parts are indexed
  (binary search), part count capped at 10k.
- VHDX: BAT/region/metadata reads are loop-until-full or reject.

Claim: `AmiDiskHardeningTest.*`, `AmiReadFullyTest.*`,
`DiskValidationTest.vmdkCapacityOverflowRejected` (and the VMDK paths in
`DiskTruncationTest`).

## Differencing images rejected

Differencing VHD (footer diskType), VDI (parent UUID), VHDX (metadata
hasParent), and VMDK (descriptor parentCID) are rejected at `open()` with
a checked `IOException` — they previously opened and silently returned
zeros for unallocated blocks. Format detection (`DiskFormat.detect`) is
unchanged. A dynamic VHD with a parent NAME string but diskType DYNAMIC
still opens (rejection keys on diskType, not name presence).

Claim: `DifferencingDiskRejectionTest.*` and
`parentNameButDynamicTypeStillOpens`.

## Checked-exception boundary

`DiskRegion.read` converts unchecked bounds errors
(`IllegalArgumentException`/`IndexOutOfBoundsException`/overflow) from the
disk layer into checked `IOException`, so hostile offsets cannot escape
filesystem drivers as unchecked exceptions. Detectors probing truncated
media return empty rather than throwing.

Claim: `CheckedBoundaryTest.diskRegionConvertsBoundsErrorsToCheckedIOException`,
`diskRegionOverflowingOffsetIsChecked`,
`gptProbeOnTruncatedDiskReturnsEmpty`,
`filesystemDetectorOnTinyTruncatedDiskReturnsEmpty`,
`containerDetectorOnTruncatedFileReturnsEmpty`.

## Thread safety

One `VirtualDisk` instance may be read concurrently. Channel
`position(x)` + `read(...)` sequences are atomic under the channel
monitor in every disk implementation (`raw`, `vdi`, `vmdk` were already
synchronized; `qcow2`, `vhd`, `vhdx` are now — including VHDX BAT,
region-table, and metadata reads). The qcow2 L2-table cache is checked
and updated under the same monitor, so a read can never pair L1 index N
with L2 table M's contents.

`openStream()` returns a single-threaded stream per call; concurrent
callers must use one stream per thread. The stream implementations honor
the `InputStream` contract (`read(b, off, 0)` returns 0).

Claim: two concurrent reads of different clusters (different L2 tables)
never cross-read, deterministically. Test:
`ChannelRaceTest.concurrentReadsOfDifferentL2TablesNeverCrossRead`.

Claim: concurrent random reads across qcow2/vhd/vhdx/vdi/vmdk/raw match
the single-threaded reference; L2 cache replacement under concurrency is
correct; one stream per thread reads correct bytes. Tests:
`DiskConcurrencyTest.concurrentRandomReadsMatchReferenceAcrossFormats`,
`DiskConcurrencyTest.l2CacheReplacementUnderConcurrencyIsCorrect`,
`DiskConcurrencyTest.concurrentStreamsOnePerThreadReadCorrectly`.

## Known notes

- AMI open-time failures use the module's documented
  `SaffronException.InvalidDiskException` (an unchecked library error
  type), matching its pre-existing contract; accidental leaks
  (`NumberFormatException`) are eliminated.
- AMI compression is not declared by the current manifest model;
  `isCompressed()` reports false and compressed parts are not detected
  (documented limitation).
- `DiskRegion.read` converts the library's unchecked corruption
  exceptions (`SaffronException.InvalidDiskException`, incl.
  `CorruptedDiskException`) into checked `IOException` at the driver
  boundary (post-audit fix — the earlier doc claim is now true in code).
  Claim → test:
  `CheckedBoundaryTest.diskRegionConvertsCorruptionExceptionsToIOException`.
- VDI cross-checks `blocksInHdd` against the declared disk geometry;
  VHDX metadata item offsets/lengths are bounded by the metadata region;
  VHD/VHDX BAT bound arithmetic is checked; qcow2's stream honors
  `read(b, 0, 0) == 0`.
