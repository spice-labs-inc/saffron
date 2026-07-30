# Compressed Single Payload Container

Saffron can expose a plain compressed file (`.gz`, `.xz`, `.bz2`) as a binary
container with one entry: `/payload`. This lets Goat Rodeo and other callers
walk the decompressed payload through the standard Saffron binary-container path
without adding special-case archive logic.

## Supported formats

| Extension | Magic | Decompressor |
|-----------|-------|--------------|
| `.gz`     | `0x1f 0x8b` | `java.util.zip.GZIPInputStream` |
| `.xz`     | `0xfd 0x37 0x7a 0x58 0x5a 0x00` | `org.apache.commons.compress.compressors.xz.XZCompressorInputStream` |
| `.bz2`    | `BZh`       | `org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream` |

Detection is by magic at offset 0. The container does not inspect the filename
for ByteBuffer or VirtualDisk sources.

## Detection exclusions

For `Path` sources, the following archive-in-compression and compressed-disk
extensions are **not** detected as `COMPRESSED_SINGLE` so that existing
behaviour is preserved:

- `.tar.gz`, `.tgz`
- `.tar.xz`, `.txz`
- `.tar.bz2`, `.tbz2`
- `.tar.lzma`, `.tlzma`
- `.img.gz`, `.raw.gz`

Claim: `DiskReader.open(Path)` returns a `RAW` disk for a plain `.gz` file, not
a `GCP` disk or a decompressed raw disk. Tests: `CompressedSingleContainerTest.diskReaderReturnsRawForPlainGz`, `GcpDiskTest.formatDetection`.

## Container contents

A mounted compressed-single container exposes exactly one entry:

- `/payload` — the decompressed bytes as a regular file.

Claim: Mounting a `.gz`/`xz`/`bz2` file yields a filesystem containing `/payload`
with the exact decompressed bytes. Tests: `CompressedSingleContainerTest.detectsCompressedTextFromPath`, `CompressedSingleContainerTest.roundTrip`.

Claim: The reported filesystem size equals the decompressed size. Test:
`CompressedSingleContainerTest.payloadSizeIsDecompressedSize`.

## Mounting API

```java
// Default security policy
Optional<FileSystem> fs = BinaryContainerMount.mount(path);

// Custom policy
SecurityPolicy policy = SecurityPolicy.builder()
        .maxDecompressedSize(16 * 1024 * 1024)
        .build();
Optional<FileSystem> fs = BinaryContainerMount.mount(path, policy);
```

The same overloads exist for `ByteBuffer` and `VirtualDisk` sources.

Claim: `BinaryContainerMount` accepts `SecurityPolicy` for path, buffer, and disk
sources. Tests: `CompressedSingleContainerTest.customSecurityPolicyLimit`,
`CompressedSingleContainerTest.rejectsBomb`.

## Security and resource limits

Decompression is bounded by the supplied `SecurityPolicy`:

- `maxDecompressedSize` limits the number of bytes written to the temporary
  payload file. Exceeding it throws `DecompressionBombException`.
- `maxAllocationSize` is converted to a KiB limit for the XZ dictionary. Exceeding
  it causes the XZ decompressor to reject the stream before allocating a large
  dictionary.

The temporary file is created with POSIX permissions `rw-------` when running on
a POSIX filesystem. It is deleted when the container is closed (which happens when
the `FileSystem` is closed), and `deleteOnExit()` is registered as a safety net.

Claim: A decompressed payload larger than `maxDecompressedSize` is rejected. Tests:
`CompressedSingleContainerTest.rejectsBomb`, `CompressedSingleContainerTest.fileSystemMountPropagatesResourceLimitException`.

Claim: The XZ decompressor uses the policy-derived memory limit. Test:
`CompressedSingleContainerTest.xzMemoryLimitPreventsOom`.

Claim: Temporary files are cleaned up on success and failure. Tests:
`CompressedSingleContainerTest.cleanupOnClose`, `CompressedSingleContainerTest.cleanupOnFailure`.

Claim: Temporary files are created with owner-only permissions on POSIX systems.
Test: `CompressedSingleContainerTest.tempFilePermissionsRestrictive`.

## DiskReader and FileSystemMount integration

`DiskReader.open(Path)` no longer auto-decompresses a plain `.gz` file. It
returns a `RAW` disk backed by the compressed bytes. When `FileSystemMount`
finds no filesystem in that raw disk, it falls back to binary-container detection,
which now recognizes `COMPRESSED_SINGLE`, decompresses the payload, and returns a
filesystem with `/payload`.

Claim: The `DiskReader` -> `FileSystemMount` fallback path yields a compressed-single
container for `.gz`, `.xz`, and `.bz2`. Tests: `CompressedSingleContainerTest.fileSystemMountFallbackToCompressedSingleGz`, `CompressedSingleContainerTest.fileSystemMountFallbackToCompressedSingleXz`, `CompressedSingleContainerTest.fileSystemMountFallbackToCompressedSingleBz2`.

`DiskReader.openRaw(Path, DiskFormat, SecurityPolicy)` still auto-decompresses
`.img.gz` and `.raw.gz` into a raw disk image for filesystem mounting, because
those are compressed raw disk images, not generic payloads.

Claim: Compressed raw disk images bypass the compressed-single container. Tests:
`CompressedSingleContainerTest.rejectsExcludedExtensionsFromPath`, `RawDiskTest`.

## Detection ordering

Compressed-single detection is checked before Linux kernel gzip-image detection in
`ContainerDetector`. This prevents a generic `.gz` file from being misclassified
as a `LINUX_KERNEL` simply because it starts with gzip magic. As a result, a
gzip-compressed ARM64 kernel image such as `raspberrypi-kernel8.img` is detected
as `COMPRESSED_SINGLE` at the top level. The direct `LinuxKernelContainerFactory`
still supports gzip-compressed kernels if invoked explicitly.

Claim: A gzip-compressed kernel fixture is detected as `COMPRESSED_SINGLE`. Test:
`LinuxKernelDetectionTest.detectsImage`.

Claim: A synthetic gzip-compressed payload that would previously have been
classified as a kernel `GZIP_IMAGE` is now exposed as `/payload`. Test:
`CompressedSingleContainerTest.gzipCompressedKernelLikeImageExposesPayload`.

## Limitations

- ByteBuffer and VirtualDisk detection cannot inspect filenames. A gzip-compressed
  tar archive (`tar.gz`) supplied as a `ByteBuffer` or `VirtualDisk` will be detected
  as `COMPRESSED_SINGLE` and exposed as `/payload`, not as a tar archive. Callers
  passing streams/buffers must use the `Path` API if they need tar-archive handling.
- `ContainerDetector.detect(ByteBuffer)` and `detect(VirtualDisk)` only look at the
  first few magic bytes; they do not read the entire source to verify the payload.
- Very large compressed files are decompressed to a temporary file on disk, so the
  host must have enough free space for the decompressed payload plus the compressed
  source.

## Fuzz / robustness tests

Claim: Mutating the first 64 bytes of a compressed payload does not crash the
detector or mount path; it either returns empty, rejects with an `IOException`,
or mounts a valid container. Tests: `CompressedSingleContainerFuzzTest.gzipHeaderFuzz`, `CompressedSingleContainerFuzzTest.xzHeaderFuzz`, `CompressedSingleContainerFuzzTest.bzip2HeaderFuzz`.

Claim: Random bytes are not detected as `COMPRESSED_SINGLE`. Test:
`CompressedSingleContainerTest.wrongMagicWithGzExtensionIsNotDetected`.

Claim: A truncated or corrupt compressed file is rejected cleanly. Tests:
`CompressedSingleContainerTest.truncatedGzipIsNotDetected`, `CompressedSingleContainerTest.corruptGzipBodyFailsCleanly`.
