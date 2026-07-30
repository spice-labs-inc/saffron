# Supported Binary Container Formats (LLM Summary)

## What this covers

Binary containers that Saffron detects and mounts as `BINARY_CONTAINER`
filesystems through `ContainerDetector` and `BinaryContainerMount`.

## Container formats

| Format | Enum | Detection | Entry |
|---|---|---|---|
| Linux kernel image | `LINUX_KERNEL` | Magic at offset 0 (bzImage, zImage, Image, uImage) | `/kernel-payload`, `/initramfs`, `/dtb` |
| FIT / uImage | `FIT_IMAGE` | DTB magic + `/images` node | `/kernel`, `/ramdisk`, `/fdt` |
| Device tree blob | `DTB` | `0xd00dfeed` at offset 0 | `/dtb` |
| ELF | `ELF` | `0x7f ELF` at offset 0 | `/segments`, `/sections` |
| Raspberry Pi firmware | `RPI_FIRMWARE` | Filename + content probes | `start.elf`, `fixup.dat` |
| Android boot image | `ANDROID_BOOT` | `ANDROID!` magic | `/kernel`, `/ramdisk`, `/second`, `/dtb` |
| Compressed single payload | `COMPRESSED_SINGLE` | gzip/xz/bzip2 magic at offset 0 | `/payload` |
| Windows Imaging (WIM) | `WIM` | `MSWIM\0\0\0` magic + 208-byte header validation | `/raw` |
| Apple disk image (DMG) | `DMG` | UDIF `koly` footer at end of file + 512-byte footer validation | `/raw` |

## Detection order

1. Compressed single payload
2. ELF
3. Linux kernel
4. DTB / FIT
5. Raspberry Pi firmware
6. Android boot
7. WIM
8. DMG

## WIM quick facts

- Header is little-endian.
- Magic: `MSWIM\0\0\0` at offset 0.
- Minimum valid header size: 208 bytes.
- Validated fields: header size (must be >= 208 and <= source size), version
  (non-zero), image count (non-negative).
- Mounted container exposes exactly one entry: `/raw`, whose size equals the
  source file size.
- Metadata keys: `format`, `source_size`, `entry_count`, `wim.header_size`,
  `wim.version`, `wim.flags`, `wim.image_count`.

## DMG quick facts

- Footer (`koly`) is big-endian and lives in the last 512 bytes of the file.
- Minimum file size: 512 bytes.
- Validated footer fields: version (> 0), header size (must equal 512).
- Validated fork regions: data fork, resource fork, XML plist fork. Each region
  must either be empty (offset 0 and length 0) or fit entirely before the
  footer. Offset + length overflow is rejected.
- Mounted container exposes exactly one entry: `/raw`, whose size equals the DMG
  data fork length.
- Metadata keys: `format`, `source_size`, `entry_count`, `dmg.version`,
  `dmg.data_fork_offset`, `dmg.data_fork_length`.

## Security controls

- All arithmetic (offset + length, header size comparisons) uses safe checks or
  `Math.addExact` to avoid overflow.
- Negative parsed values are rejected.
- `ByteBuffer` overloads reject `sourceSize` larger than the buffer's
  remaining bytes.
- `SecurityPolicy` is accepted by `WimContainer.open(...)` and
  `DmgContainer.open(...)` even though this phase does not allocate large
  buffers.

## Code entry points

- `io.spicelabs.saffron.container.ContainerDetector.detect(Path|ByteBuffer|VirtualDisk)`
- `io.spicelabs.saffron.container.BinaryContainerMount.mount(Path|ByteBuffer|VirtualDisk, SecurityPolicy)`
- `io.spicelabs.saffron.container.wim.WimContainer.open(Path|ByteBuffer|VirtualDisk, SecurityPolicy)`
- `io.spicelabs.saffron.container.dmg.DmgContainer.open(Path|ByteBuffer|VirtualDisk, SecurityPolicy)`

## Important limitations

- WIM: only the file header is parsed. Images, files, and compressed resources
  inside the WIM are not extracted.
- DMG: only the UDIF footer and data fork bounds are read. The resource fork,
  blkx tables, XML plist, and any filesystem inside the image are not parsed.

## WIM tests by claim

| Claim | Test |
|---|---|
| Valid WIM detected from `Path` | `WimContainerDetectionTest.detectsValidWimFromPath` |
| Valid WIM detected from `ByteBuffer` | `WimContainerDetectionTest.detectsValidWimFromByteBuffer` |
| Valid WIM detected from `VirtualDisk` | `WimContainerDetectionTest.detectsValidWimFromVirtualDisk` |
| Multi-image WIM detected | `WimContainerDetectionTest.detectsTwoImagesWimFromPath` |
| Empty/truncated/wrong-magic files rejected | `WimContainerDetectionTest.rejectsEmptyFile`, `rejectsTruncatedMagic`, `rejectsWrongMagic`, `rejectsTruncatedHeader`, `WimContainerFixtureTest.rejectsNegativeFixtures` |
| Header size mismatch / too small rejected | `WimContainerDetectionTest.rejectsHeaderSizeMismatch`, `rejectsSourceSmallerThanHeader` |
| Zero version / negative image count rejected | `WimContainerDetectionTest.rejectsManipulatedVersion`, `rejectsNegativeImageCount` |
| Header size overflow / max rejected | `WimContainerSecurityTest.rejectsHeaderSizeExceedingSource`, `rejectsMaximumHeaderSize` |
| Mounted WIM is `BINARY_CONTAINER` | `WimContainerMountTest.mountReturnsBinaryContainer` |
| `/raw` exists and has correct size | `WimContainerMountTest.rawEntryExistsAndHasCorrectSize` |
| `/raw` is readable and matches source bytes | `WimContainerMountTest.rawEntryIsReadable` |
| Multiple `/raw` streams are independent | `WimContainerMountTest.rawEntryStreamsAreIndependent` |
| Metadata keys present | `WimContainerMountTest.metadataContainsExpectedKeys` |
| Fuzzing does not crash | `WimContainerFuzzTest.headerFuzz` |

## DMG tests by claim

| Claim | Test |
|---|---|
| Valid DMG detected from `Path` | `DmgContainerDetectionTest.detectsValidDmgFromPath` |
| Valid DMG detected from `ByteBuffer` | `DmgContainerDetectionTest.detectsValidDmgFromByteBuffer` |
| Valid DMG detected from `VirtualDisk` | `DmgContainerDetectionTest.detectsValidDmgFromVirtualDisk` |
| Empty/truncated/missing-koly files rejected | `DmgContainerDetectionTest.rejectsEmptyFile`, `rejectsTruncatedFooter`, `rejectsMissingKolySignature`, `DmgContainerFixtureTest.rejectsNegativeFixtures` |
| Footer not at end / invalid header size rejected | `DmgContainerDetectionTest.rejectsFooterNotAtEnd`, `rejectsInvalidHeaderSize` |
| Data/resource/XML fork beyond source rejected | `DmgContainerDetectionTest.rejectsDataForkBeyondSource`, `DmgContainerSecurityTest.rejectsResourceForkBeyondSource`, `rejectsXmlBeyondSource` |
| Negative data fork offset rejected | `DmgContainerDetectionTest.rejectsNegativeDataForkOffset` |
| Offset + length overflow rejected | `DmgContainerDetectionTest.rejectsDataForkOverflow`, `DmgContainerSecurityTest.rejectsDataForkLengthOverflow` |
| Fork overlapping footer rejected | `DmgContainerSecurityTest.rejectsDataForkOverlappingFooter` |
| Mounted DMG is `BINARY_CONTAINER` | `DmgContainerMountTest.mountReturnsBinaryContainer` |
| `/raw` exists and has data fork size | `DmgContainerMountTest.rawEntryExistsAndHasCorrectSize` |
| `/raw` contains data fork bytes | `DmgContainerMountTest.rawEntryContainsDataFork` |
| Multiple `/raw` streams are independent | `DmgContainerMountTest.rawEntryStreamsAreIndependent` |
| Metadata keys present | `DmgContainerMountTest.metadataContainsExpectedKeys` |
| Fuzzing does not crash | `DmgContainerFuzzTest.footerFuzz` |
