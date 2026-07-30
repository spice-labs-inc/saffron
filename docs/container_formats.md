# Supported Binary Container Formats

Saffron detects and mounts the following binary container formats as
`io.spicelabs.saffron.fs.FileSystem` instances. Each format is identified by the
`ContainerFormat` enum.

| Format | ContainerFormat value | Entry examples | Detection method |
|---|---|---|---|
| Linux kernel image | `LINUX_KERNEL` | `/kernel-payload`, `/initramfs`, `/dtb` | Magic at offset 0 (bzImage, zImage, Image, uImage) |
| FIT / uImage | `FIT_IMAGE` | `/kernel`, `/ramdisk`, `/fdt` | DTB magic + `/images` node |
| Device tree blob | `DTB` | `/dtb` | `0xd00dfeed` at offset 0 |
| ELF shared object / executable | `ELF` | `/segments`, `/sections` | `0x7f ELF` at offset 0 |
| Raspberry Pi firmware | `RPI_FIRMWARE` | `start.elf`, `fixup.dat` | Filename + content probes |
| Android boot image | `ANDROID_BOOT` | `/kernel`, `/ramdisk`, `/second`, `/dtb` | `ANDROID!` magic |
| Compressed single payload | `COMPRESSED_SINGLE` | `/payload` | gzip/xz/bzip2 magic at offset 0 |
| Windows Imaging (WIM) | `WIM` | `/raw` | `MSWIM\0\0\0` magic at offset 0, 208-byte header validation |
| Apple disk image (DMG) | `DMG` | `/raw` | UDIF `koly` footer at end of file, 512-byte footer validation |

## Detection order

`ContainerDetector` checks formats in the following order:

1. Compressed single payload (gzip, xz, bzip2 magic) — checked first to avoid
   misclassifying a generic compressed file as a Linux kernel or other format.
2. ELF
3. Linux kernel
4. DTB / FIT
5. Raspberry Pi firmware
6. Android boot
7. WIM (header magic)
8. DMG (footer magic)

## Compressed single payload

`COMPRESSED_SINGLE` is the newest format. It exposes the decompressed bytes of a
plain `.gz`, `.xz`, or `.bz2` file as a single `/payload` entry. For details see
`compressed_single_container.md` and `compressed_single_container_llm.md`.

Claim: `.gz`/`.xz`/`.bz2` files are detected as `COMPRESSED_SINGLE`. Tests:
`CompressedSingleContainerTest.detectsCompressedTextFromPath`,
`CompressedSingleContainerTest.detectsFromByteBuffer`,
`CompressedSingleContainerTest.detectsFromVirtualDisk`.

Claim: Excluded archive-in-compression and compressed-disk extensions are not
detected as `COMPRESSED_SINGLE`. Test:
`CompressedSingleContainerTest.rejectsExcludedExtensionsFromPath`.

## Linux kernel and compressed payloads

Because compressed-single detection runs before Linux kernel detection, a
gzip-compressed kernel image such as `raspberrypi-kernel8.img` is detected as
`COMPRESSED_SINGLE` at the top level. The direct `LinuxKernelContainerFactory`
continues to support gzip-compressed kernels when invoked explicitly.

Claim: A gzip-compressed kernel fixture is detected as `COMPRESSED_SINGLE`. Test:
`LinuxKernelDetectionTest.detectsImage`.

## GCP disk images

GCP disk images are distributed as gzip-compressed tar archives (`.tar.gz` or
`.tgz`). `DiskFormat` detects them by extension, not by gzip magic alone. They
are opened as `DiskFormat.GCP` by `DiskReader`, not as `COMPRESSED_SINGLE`.

Claim: `DiskFormat.GCP` is detected by `.tar.gz` / `.tgz` extension. Test:
`GcpDiskTest.formatDetection`.

## Windows Imaging Format (WIM)

WIM files are detected by the `MSWIM\0\0\0` magic at offset 0. The detector
validates the 208-byte header, including the header size, version, and image
count, then exposes the whole source as a `/raw` entry.

Claim: Valid WIM fixtures are detected from `Path`, `ByteBuffer`, and
`VirtualDisk`. Tests: `WimContainerDetectionTest.detectsValidWimFromPath`,
`WimContainerDetectionTest.detectsValidWimFromByteBuffer`,
`WimContainerDetectionTest.detectsValidWimFromVirtualDisk`.

Claim: Malformed WIM inputs are rejected without unchecked exceptions. Tests:
`WimContainerDetectionTest.rejectsTruncatedMagic`,
`WimContainerDetectionTest.rejectsWrongMagic`,
`WimContainerDetectionTest.rejectsTruncatedHeader`,
`WimContainerDetectionTest.rejectsHeaderSizeMismatch`,
`WimContainerDetectionTest.rejectsSourceSmallerThanHeader`,
`WimContainerFixtureTest.rejectsNegativeFixtures`.

Claim: A mounted WIM container exposes a readable `/raw` entry. Tests:
`WimContainerMountTest.rawEntryExistsAndHasCorrectSize`,
`WimContainerMountTest.rawEntryIsReadable`,
`WimContainerMountTest.rawEntryStreamsAreIndependent`.

Claim: Fuzzing WIM detection and mounting does not produce unchecked crashes. Test:
`WimContainerFuzzTest.headerFuzz`.

## Apple Disk Image (DMG)

UDIF DMG files are detected by the `koly` footer at the end of the file. The
detector validates the 512-byte footer and the bounds of the data fork, resource
fork, and XML plist regions, then exposes the data fork as a `/raw` entry.

Claim: Valid DMG fixtures are detected from `Path`, `ByteBuffer`, and
`VirtualDisk`. Tests: `DmgContainerDetectionTest.detectsValidDmgFromPath`,
`DmgContainerDetectionTest.detectsValidDmgFromByteBuffer`,
`DmgContainerDetectionTest.detectsValidDmgFromVirtualDisk`.

Claim: Malformed DMG inputs are rejected without unchecked exceptions. Tests:
`DmgContainerDetectionTest.rejectsTruncatedFooter`,
`DmgContainerDetectionTest.rejectsMissingKolySignature`,
`DmgContainerDetectionTest.rejectsFooterNotAtEnd`,
`DmgContainerDetectionTest.rejectsInvalidHeaderSize`,
`DmgContainerDetectionTest.rejectsDataForkBeyondSource`,
`DmgContainerFixtureTest.rejectsNegativeFixtures`.

Claim: A mounted DMG container exposes a readable `/raw` entry for the data
fork. Tests: `DmgContainerMountTest.rawEntryExistsAndHasCorrectSize`,
`DmgContainerMountTest.rawEntryContainsDataFork`,
`DmgContainerMountTest.rawEntryStreamsAreIndependent`.

Claim: Fuzzing DMG detection and mounting does not produce unchecked crashes. Test:
`DmgContainerFuzzTest.footerFuzz`.

## Format tests

- `FormatDetectionTest` — general detection cases.
- `LinuxKernelDetectionTest` — Linux kernel detection.
- `FitContainerMountTest`, `DtbContainerMountTest` — FIT/DTB detection.
- `ElfContainerDetectionTest` — ELF detection.
- `RpiFirmwareContainerDetectionTest` — RPi firmware detection.
- `AndroidBootContainerDetectionTest` — Android boot detection.
- `CompressedSingleContainerTest` — compressed single payload detection.
- `WimContainerDetectionTest`, `WimContainerMountTest`, `WimContainerFixtureTest`,
  `WimContainerSecurityTest`, `WimContainerFuzzTest` — WIM detection and mounting.
- `DmgContainerDetectionTest`, `DmgContainerMountTest`, `DmgContainerFixtureTest`,
  `DmgContainerSecurityTest`, `DmgContainerFuzzTest` — DMG detection and mounting.
