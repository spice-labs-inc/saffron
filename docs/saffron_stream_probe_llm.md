# Saffron Stream Probe — LLM Reference

## What it is

`io.spicelabs.saffron.SaffronProbe` is a pure, byte-range-only, never-throwing
detector. It answers "is this a Saffron-supported artifact?" from a bounded
prefix + optional suffix, with **no file access**. Goat Rodeo uses it to gate
an expensive temp-file spill (`withFile`) behind a cheap `withStream` byte probe
so millions of archive members are not spilled.

## API

```
SaffronProbe.detect(byte[] prefix, byte[] suffix, String fileName) -> Optional<Result>
SaffronProbe.MIN_PREFIX = 131072  // 128 KiB, < 1 MiB; covers head magics + partition probe @512 + btrfs superblock
SaffronProbe.MIN_SUFFIX = 512     // VHD/DMG footer
Result.kind() -> Kind             // 28-value enum, NONE = no match
```

## Contract

- R1: operates only on caller-supplied byte ranges (no file I/O).
- R2: parity with `DiskReader.isSupported` — direction-by-kind: no false
  negatives always; no false positives for non-filesystem kinds. Bare
  filesystems are the intended superset.
- R3: never throws; `catch (Exception)` only (not `Throwable`).
- R4: deterministic, pure, thread-safe; does not mutate caller arrays.

## Detection order (short-circuit)

1. Disk head magics: QCOW2 `QFI\xfb`@0, VMDK `KDMV`@0, VHDX `vhdxfile`@0,
   VDI text sig@0, gzip-wrapped (gzip magic + `.img.gz`/`.raw.gz`/`.tar.gz`/`.tgz`).
2. VHD footer `conectix` in suffix.
3. Containers (reuse `ContainerDetector.detect(ByteBuffer)`): ELF, Linux kernel,
   DTB/FIT, RPi, Android boot, WIM, compressed-single; DMG `koly` in suffix
   (`ContainerDetector.isDmgFooterMagic`); `d00d feed` fallback → coarse `CONTAINER_DTB`.
4. Bare filesystems (reuse `FilesystemDetector.detect(DiskRegion)` over an
   in-memory prefix region): ext, FAT, exFAT, NTFS, XFS, HFS+, APFS, swap; plus
   magic-only squashfs (`SquashfsSuperblock.isSquashfsMagic`).
5. Raw GPT `EFI PART`@512 / MBR `55 AA`@510 → `DISK_RAW`.
6. Extension fallbacks (mirror `DiskFormat.detectByExtension`).

Filesystems run before the raw-`55 AA` heuristic so FAT/NTFS/exFAT are not
shadowed by their boot signature.

## Known limits

- FIT vs DTB: a large FIT's structure block may exceed `MIN_PREFIX`; then it is
  reported as the coarse `CONTAINER_DTB`. DTBs that fit within the prefix are
  classified precisely.
- squashfs: magic-only (full validation needs artifact size).
- DMG: `koly` footer magic only.
- `.tar.gz`/`.tgz` (GCP) → `DISK_GZIP_WRAPPED_RAW` (no GCP Kind).

btrfs IS detectable at `MIN_PREFIX=131072` (superblock at offset 65536).

## Key tests (all in `SaffronProbeTest`)

- Content positives with null fileName (forces magic check): `qcow2Magic_returnsDiskQcow2`,
  `vmdkMagic_returnsDiskVmdk`, `vhdxMagic_returnsDiskVhdx`,
  `vdiTextSignature_returnsDiskVdi`, `vhdFooter_returnsDiskVhd`,
  `rawGptSignature_returnsDiskRaw`, `rawMbrSignature_returnsDiskRaw`,
  `elfMagic_returnsContainerElf`, `linuxKernel_returnsContainerLinuxKernel`,
  `rpiFirmware_returnsContainerRpiFirmware`, `androidBoot_returnsContainerAndroidBoot`,
  `wimMagic_returnsContainerWim`, `dmgFooter_returnsContainerDmg`,
  `extSuperblock_returnsFilesystemExt`, `squashfsSuperblock_returnsFilesystemSquashfs`,
  `xfsMagic_returnsFilesystemXfs`, `swapSignature_returnsFilesystemSwap`,
  `exFatSuperblock_returnsFilesystemExfat`, `hfsPlusSignature_returnsFilesystemHfsPlus`,
  `apfsSignature_returnsFilesystemApfs`, `btrfsSuperblock_returnsFilesystemBtrfs`.
- Filesystem-not-shadowed: `fatWithBootSignature_isNotShadowedByRaw`,
  `ntfsWithBootSignature_isNotShadowedByRaw`.
- Parity: `parity_noFalseNegatives_onDiskImages` (14 corpus images),
  `parity_noFalseNegatives_onBareFilesystem`, `parity_noFalsePositives_onNonArtifacts`,
  `parity_randomBytes_bothEmpty`.
- Robustness: `neverThrows_onPathologicalInputs`, `nullPrefix_returnsEmpty`,
  `randomBytes_noExtension_returnsEmpty`, `truncatedPrefix_returnsEmpty`.
- Purity: `repeatedCalls_areDeterministic`, `detect_doesNotMutateCallerArrays`.
- gzip: `gzipWrappedRaw_requiresExtension`.

## Supporting changes

- `ContainerDetector.isDmgFooterMagic(ByteBuffer)` — public DMG footer magic check.
- `SquashfsSuperblock.isSquashfsMagic(ByteBuffer)` — public magic-only squashfs check.

## No shell-out rule

No Saffron test or main code invokes an external process. The squashfs fixtures
(`src/test/resources/squashfs/fixtures/`) are pre-built and committed; they were
generated once via Docker `mksquashfs` during setup.