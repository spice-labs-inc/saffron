# Compressed Single Payload Container (LLM Summary)

## What it is

Plain `.gz`, `.xz`, `.bz2` files exposed as a Saffron binary container with one
entry: `/payload`.

## Supported formats

- `gzip` — magic `0x1f8b`
- `xz` — magic `0xfd377a585a00`
- `bzip2` — magic `BZh`

## Quick facts

- Container format: `ContainerFormat.COMPRESSED_SINGLE`.
- Detection: by magic at offset 0.
- Path exclusions: `.tar.gz`, `.tgz`, `.tar.xz`, `.txz`, `.tar.bz2`, `.tbz2`,
  `.tar.lzma`, `.tlzma`, `.img.gz`, `.raw.gz`.
- Only one entry: `/payload` (the decompressed bytes).
- `DiskReader.open(Path)` returns `RAW` for plain `.gz`/`.xz`/`.bz2`; fallback
  through `FileSystemMount` yields the compressed-single container.
- `DiskReader.openRaw` still auto-decompresses only `.img.gz` and `.raw.gz`.
- Detection runs before Linux kernel gzip-image detection to avoid misclassifying
  generic `.gz` files as kernels.
- `BinaryContainer` now extends `Closeable`; the temp payload is deleted on close.

## Security controls

- `SecurityPolicy.maxDecompressedSize` bounds the decompressed payload size.
- `SecurityPolicy.maxAllocationSize` bounds the XZ dictionary memory.
- Temp file created with `rw-------` POSIX permissions when possible.
- Temp file deleted on close and on failure; `deleteOnExit()` as safety net.
- Bzip2 block size validated (`BZh[1-9]`) before decompression starts.

## Code entry points

- `io.spicelabs.saffron.container.BinaryContainerMount.mount(Path, SecurityPolicy)`
- `io.spicelabs.saffron.container.BinaryContainerMount.mount(ByteBuffer, SecurityPolicy)`
- `io.spicelabs.saffron.container.BinaryContainerMount.mount(VirtualDisk, SecurityPolicy)`
- `io.spicelabs.saffron.container.compressed.CompressedSingleContainerFactory`

## Important limitations

- ByteBuffer/VirtualDisk detection cannot exclude tar archives by filename. A
  `tar.gz` buffer/disk will be detected as `COMPRESSED_SINGLE` and exposed as
  `/payload` (the compressed tar bytes), not as a tar archive.
- Large payloads decompress to a temp file; host needs free space for the
  decompressed size.
- Gzip-compressed Linux kernel images (e.g., `kernel8.img`) are now classified as
  `COMPRESSED_SINGLE` at the top level. Direct `LinuxKernelContainerFactory.open`
  still supports them.

## Tests by claim

| Claim | Test |
|---|---|
| `.gz`/`.xz`/`.bz2` files are detected and mounted | `CompressedSingleContainerTest.detectsCompressedTextFromPath` |
| `/payload` matches original bytes | `CompressedSingleContainerTest.roundTrip` |
| Filesystem size is decompressed size | `CompressedSingleContainerTest.payloadSizeIsDecompressedSize` |
| Excluded extensions are not detected | `CompressedSingleContainerTest.rejectsExcludedExtensionsFromPath` |
| Decompression bombs are rejected | `CompressedSingleContainerTest.rejectsBomb` |
| Custom `SecurityPolicy` limits work | `CompressedSingleContainerTest.customSecurityPolicyLimit` |
| Temp files are cleaned up | `CompressedSingleContainerTest.cleanupOnClose`, `CompressedSingleContainerTest.cleanupOnFailure` |
| POSIX permissions are owner-only | `CompressedSingleContainerTest.tempFilePermissionsRestrictive` |
| XZ memory limit is enforced | `CompressedSingleContainerTest.xzMemoryLimitPreventsOom` |
| `DiskReader`/`FileSystemMount` fallback works | `CompressedSingleContainerTest.fileSystemMountFallbackToCompressedSingleGz` (and Xz/Bz2) |
| Compressed raw images bypass container | `CompressedSingleContainerTest.rejectsExcludedExtensionsFromPath` |
| Gzip kernel fixture is `COMPRESSED_SINGLE` | `LinuxKernelDetectionTest.detectsImage` |
| Synthetic gzip payload is exposed as `/payload` | `CompressedSingleContainerTest.gzipCompressedKernelLikeImageExposesPayload` |
| Corrupt/truncated inputs rejected cleanly | `CompressedSingleContainerTest.truncatedGzipIsNotDetected`, `CompressedSingleContainerTest.corruptGzipBodyFailsCleanly` |
| Fuzzing does not crash | `CompressedSingleContainerFuzzTest.gzipHeaderFuzz`, `CompressedSingleContainerFuzzTest.xzHeaderFuzz`, `CompressedSingleContainerFuzzTest.bzip2HeaderFuzz` |
