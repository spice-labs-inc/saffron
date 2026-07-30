# Security Policy and Decompression Limits

Saffron's `SecurityPolicy` controls how much data decompression is allowed to
allocate and produce. It is threaded through every decompression path that
Saffron uses, including compressed raw disk images, GCP disk images, Linux kernel
payloads, squashfs, and the new compressed-single payload container.

## Default policy

`SecurityPolicy.defaults()` provides reasonable defaults:

- `maxDecompressedSize`: 16 GiB
- `maxAllocationSize`: 256 MiB

These defaults are used by overloads that do not take an explicit policy, so
existing callers are unaffected unless they pass a custom policy.

## Policy dimensions

| Dimension | Meaning | Where it applies |
|---|---|---|
| `maxDecompressedSize` | Maximum number of bytes a decompressor may write to the output | Compressed-single container, `GzipRawDiskImpl`, `GcpDiskImpl`, kernel decompression, squashfs |
| `maxAllocationSize` | Maximum amount of memory a decompressor may allocate at once | XZ dictionary size in `CompressedSingleFormat` and other XZ-based paths |

## API usage

```java
SecurityPolicy policy = SecurityPolicy.builder()
        .maxDecompressedSize(64 * 1024 * 1024)  // 64 MiB
        .maxAllocationSize(16 * 1024 * 1024)     // 16 MiB
        .build();

Optional<FileSystem> fs = BinaryContainerMount.mount(path, policy);
```

The same policy is accepted by `DiskReader.open(Path, SecurityPolicy)`,
`DiskReader.openRaw(Path, DiskFormat, SecurityPolicy)`, `FileSystemMount.mountAll`,
and the `BinaryContainerMount` overloads.

Claim: `SecurityPolicy` overloads exist for `DiskReader`, `FileSystemMount`, and
`BinaryContainerMount`. Tests: `CompressedSingleContainerTest.customSecurityPolicyLimit`,
`CompressedSingleContainerTest.fileSystemMountPropagatesResourceLimitException`.

## Compressed-single container limits

For the compressed-single container, `maxDecompressedSize` is enforced while
streaming the decompressed bytes to the temporary payload file. The container
catches the limit and throws `DecompressionBombException` (a
`ResourceLimitException`) before writing the oversized byte. If the limit is hit
after a partial temp file was created, the partial file is deleted.

Claim: Oversized decompressed payloads are rejected and temp files are cleaned up.
Tests: `CompressedSingleContainerTest.rejectsBomb`, `CompressedSingleContainerTest.cleanupOnFailure`.

## XZ memory limit

The XZ decompressor can pre-allocate a large dictionary based on the file header.
`CompressedSingleFormat.openDecompressor` passes a memory limit in KiB derived from
`SecurityPolicy.maxAllocationSize()` to `XZCompressorInputStream`. If the header
requests more memory than allowed, the decompressor rejects the stream before
allocating.

Claim: XZ decompression respects the policy-derived memory limit. Test:
`CompressedSingleContainerTest.xzMemoryLimitPreventsOom`.

## Legacy overloads

Existing methods that do not take a `SecurityPolicy` delegate to the defaults:

- `DiskReader.open(Path)` → `DiskReader.open(path, SecurityPolicy.defaults())`
- `FileSystemMount.mountAll(disk)` → `FileSystemMount.mountAll(disk, SecurityPolicy.defaults())`
- `BinaryContainerMount.mount(path)` → `BinaryContainerMount.mount(path, SecurityPolicy.defaults())`

This keeps backwards compatibility while allowing new callers to supply tighter
limits.

## Recommendations

- Use a custom `SecurityPolicy` when processing untrusted inputs.
- Set `maxDecompressedSize` low enough to match the expected payload size plus a
  small margin; a highly compressible few-KB file can expand to many gigabytes.
- Set `maxAllocationSize` to prevent XZ dictionary pre-allocation from consuming
  excessive heap.
