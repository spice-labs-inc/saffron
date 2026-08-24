# Saffron Stream Probe (`SaffronProbe`)

`SaffronProbe` is a stream-friendly, byte-range-only detector that answers "is
this a Saffron-supported artifact?" from a bounded prefix and optional suffix —
**without any file access**. It exists so callers (notably Goat Rodeo) can gate
an expensive temp-file spill behind a cheap byte probe, instead of spilling
every artifact to disk just to find out Saffron can't read it.

The design rationale and decisions are recorded in
[`docs/adr/0001-saffron-stream-probe.md`](adr/0001-saffron-stream-probe.md).

## API

```java
package io.spicelabs.saffron;

public final class SaffronProbe {
    public static final int MIN_PREFIX = 131072; // head magics + partition probe @512 + btrfs superblock (128 KiB, < 1 MiB)
    public static final int MIN_SUFFIX = 512;  // VHD/DMG footer
    public enum Kind { DISK_QCOW2, DISK_VMDK, DISK_VHD, DISK_VHDX, DISK_VDI,
        DISK_RAW, DISK_AMI, DISK_GZIP_WRAPPED_RAW,
        CONTAINER_ELF, CONTAINER_FIT_IMAGE, CONTAINER_DTB,
        CONTAINER_LINUX_KERNEL, CONTAINER_RPI_FIRMWARE,
        CONTAINER_ANDROID_BOOT, CONTAINER_COMPRESSED_SINGLE,
        CONTAINER_WIM, CONTAINER_DMG,
        FILESYSTEM_EXT, FILESYSTEM_FAT, FILESYSTEM_EXFAT, FILESYSTEM_NTFS,
        FILESYSTEM_XFS, FILESYSTEM_BTRFS, FILESYSTEM_SQUASHFS,
        FILESYSTEM_HFSPLUS, FILESYSTEM_APFS, FILESYSTEM_SWAP, NONE }
    public record Result(Kind kind) { public static final Result NONE = new Result(Kind.NONE); }
    public static Optional<Result> detect(byte[] prefix, byte[] suffix, String fileName);
}
```

`detect` takes the first bytes of the artifact (`prefix`, may be shorter than
`MIN_PREFIX`), the last bytes (`suffix`, may be empty/null — needed only for
VHD and DMG footer detection), and the artifact's file name (for extension
fallbacks). It returns `Optional.empty()` when nothing is detected.

## Contract

- **No file access** (R1): `detect` never opens, seeks, stats, or reads a file.
  All signature knowledge is applied to the caller-supplied byte ranges.
- **Never throws** (R3): malformed input, null ranges, or unexpected bytes
  return an empty result. Only `Exception` is folded (deliberately not
  `Throwable`, so `Error`s like `OutOfMemoryError` are not swallowed).
- **Deterministic and pure** (R4): same inputs → same result; caller byte
  arrays are never mutated; thread-safe.

## Detection order

Deterministic, short-circuiting:

1. Disk head magics: QCOW2 `QFI\xfb`, VMDK `KDMV`, VHDX `vhdxfile`, VDI text
   signature, gzip-wrapped raw (gzip magic + `.img.gz`/`.raw.gz`/`.tar.gz`/`.tgz`).
2. VHD footer `conectix` in the suffix.
3. Binary containers: ELF, Linux kernel, DTB/FIT, RPi firmware, Android boot,
   WIM, compressed-single (reusing `ContainerDetector`); DMG `koly` footer in
   the suffix; `d00d feed` fallback to the coarse `CONTAINER_DTB`.
4. Bare filesystems: superblock magics via `FilesystemDetector` (ext, FAT,
   exFAT, NTFS, XFS, HFS+, APFS, swap) plus a magic-only squashfs check.
5. Raw GPT `EFI PART`@512 / MBR `55 AA`@510.
6. Extension fallbacks mirroring `DiskFormat.detectByExtension`.

Filesystems run before the raw-`55 AA`-MBR heuristic so a bare FAT/NTFS/exFAT
image is reported as a filesystem, not `DISK_RAW`.

Claim: Filesystem detection is ordered before the raw-MBR heuristic, so a bare
FAT and NTFS image (both carrying `55 AA` at offset 510) are reported as
`FILESYSTEM_FAT`/`FILESYSTEM_NTFS`, not `DISK_RAW`. Tests:
`SaffronProbeTest.fatWithBootSignature_isNotShadowedByRaw`,
`SaffronProbeTest.ntfsWithBootSignature_isNotShadowedByRaw`.

## Parity with `DiskReader.isSupported(Path)`

The parity contract (R2) is enforced *direction-by-kind* rather than as a strict
`iff`:

- **No false negatives**: whenever `DiskFormat.detect(P)` or
  `ContainerDetector.detect(P)` is present, `SaffronProbe` is present. Tested
  across 14 real corpus disk images plus a bare squashfs filesystem. Test:
  `SaffronProbeTest.parity_noFalseNegatives_onDiskImages`,
  `SaffronProbeTest.parity_noFalseNegatives_onBareFilesystem`.
- **No false positives** for non-filesystem kinds: random/plain files are
  rejected by both. Tests: `SaffronProbeTest.parity_noFalsePositives_onNonArtifacts`,
  `SaffronProbeTest.parity_randomBytes_bothEmpty`.
- **Intended superset**: bare filesystem images (no recognized extension) may
  be reported present where `DiskFormat`/`ContainerDetector` would not. This is
  the point of the `FILESYSTEM_*` kinds.

Claim: Per-format positives detect the specific `Kind` by content alone (null
file name), so extension fallbacks cannot mask a broken magic check. Tests:
`SaffronProbeTest.qcow2Magic_returnsDiskQcow2`,
`SaffronProbeTest.vmdkMagic_returnsDiskVmdk`,
`SaffronProbeTest.vhdxMagic_returnsDiskVhdx`,
`SaffronProbeTest.vdiTextSignature_returnsDiskVdi`,
`SaffronProbeTest.vhdFooter_returnsDiskVhd`,
`SaffronProbeTest.rawGptSignature_returnsDiskRaw`,
`SaffronProbeTest.rawMbrSignature_returnsDiskRaw`,
`SaffronProbeTest.elfMagic_returnsContainerElf`,
`SaffronProbeTest.linuxKernel_returnsContainerLinuxKernel`,
`SaffronProbeTest.rpiFirmware_returnsContainerRpiFirmware`,
`SaffronProbeTest.androidBoot_returnsContainerAndroidBoot`,
`SaffronProbeTest.wimMagic_returnsContainerWim`,
`SaffronProbeTest.dmgFooter_returnsContainerDmg`,
`SaffronProbeTest.extSuperblock_returnsFilesystemExt`,
`SaffronProbeTest.squashfsSuperblock_returnsFilesystemSquashfs`,
`SaffronProbeTest.xfsMagic_returnsFilesystemXfs`,
`SaffronProbeTest.swapSignature_returnsFilesystemSwap`,
`SaffronProbeTest.exFatSuperblock_returnsFilesystemExfat`,
`SaffronProbeTest.hfsPlusSignature_returnsFilesystemHfsPlus`,
`SaffronProbeTest.apfsSignature_returnsFilesystemApfs`,
`SaffronProbeTest.btrfsSuperblock_returnsFilesystemBtrfs`.

## Known limitations

- **FIT vs DTB**: a large FIT's structure block may exceed `MIN_PREFIX`; when it
  does, it is reported as the coarse `CONTAINER_DTB`. DTBs that fit within the
  prefix are classified precisely. Presence parity still holds. Test:
  `SaffronProbeTest.deviceTreeMagic_returnsContainerDtb`.
- **squashfs** is detected by magic only (`SquashfsSuperblock.isSquashfsMagic`);
  its `bytes_used` validation needs the full artifact size, unknown to a prefix
  probe.
- **DMG** is detected by the `koly` footer magic in the suffix
  (`ContainerDetector.isDmgFooterMagic`), lighter than the full region/size
  validation performed by `ContainerDetector` on a complete artifact.
- **`.tar.gz`/`.tgz`** (a GCP disk in `DiskFormat`) map to `DISK_GZIP_WRAPPED_RAW`
  because the `Kind` taxonomy has no GCP value; this preserves parity presence.

Claim: gzip magic alone is a compressed-single payload; gzip magic plus a
`.raw.gz`/`.img.gz`/`.tar.gz`/`.tgz` name is a wrapped raw disk. Test:
`SaffronProbeTest.gzipWrappedRaw_requiresExtension`.

## Robustness

Claim: The probe never throws on malformed input (R3). Tests:
`SaffronProbeTest.neverThrows_onPathologicalInputs`,
`SaffronProbeTest.nullPrefix_returnsEmpty`,
`SaffronProbeTest.emptyPrefix_noExtension_returnsEmpty`,
`SaffronProbeTest.randomBytes_noExtension_returnsEmpty`,
`SaffronProbeTest.truncatedPrefix_returnsEmpty`.

Claim: The probe is deterministic and does not mutate caller arrays (R4). Tests:
`SaffronProbeTest.repeatedCalls_areDeterministic`,
`SaffronProbeTest.detect_doesNotMutateCallerArrays`.

## Fixture generation (no shell-out)

The squashfs test fixtures under `src/test/resources/squashfs/fixtures/` are
pre-built images committed to the repo; they were generated once with the
`mksquashfs` tool (via Docker) during fixture setup. **No Saffron test or main
code ever invokes an external process.** See
`SquashfsStreamingTest`/`SquashfsPropertyTest`.