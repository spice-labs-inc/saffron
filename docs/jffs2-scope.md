# JFFS2 Filesystem Support — Scope

## Executive Summary

JFFS2 (Journalling Flash File System, version 2) is a log-structured flash
filesystem used by embedded Linux distributions (notably OpenWrt). It has no
partition-table-style superblock; instead, an image is a stream of self-describing
nodes. This makes detection cheap and full read support moderately complex.

Two scope options are proposed:

- **Option A — Detection only:** Add JFFS2 recognition to
  `FilesystemDetector` and `SaffronProbe`. This is low-risk, unblocks Goat
  Rodeo from gating JFFS2 artifacts, and does not require changing the
  `FileSystem` sealed interface.
- **Option B — Detection + read-only mount:** Implement a complete
  `Jffs2FileSystemImpl` that parses the node log, resolves versions, and
  decompresses data. This is a medium-sized feature because of log-structured
  data reconstruction, compression variants, and obsolete node types.

The recommended first step is **Option A**.

## Format Overview

### On-disk node layout

Every JFFS2 node starts with the same 12-byte header (`jffs2_unknown_node`):

| Offset | Size | Field      | Notes                                              |
|--------|------|------------|----------------------------------------------------|
| 0      | 2    | `magic`    | Little-endian `0x1985` (`JFFS2_MAGIC_BITMASK`)     |
| 2      | 2    | `nodetype` | See node-type table below                          |
| 4      | 4    | `totlen`   | Total node length, including header, 4-byte padded |
| 8      | 4    | `hdr_crc`  | CRC over the first 12 bytes                        |

### Node types relevant to Saffron

| Value    | Name        | Purpose                                    |
|----------|-------------|--------------------------------------------|
| `0x0001` | `DIRENT`    | Directory entry (name → inode number)      |
| `0x0002` | `INODE`     | File metadata + compressed data fragment   |
| `0x0003` | `PADDING`   | Unused padding between nodes               |
| `0x0004` | `CHECKPOINT`| Obsolete but valid in older images         |
| `0x0006` | `SUMMARY`   | Erase-block summary (scan accelerator)     |
| `0x0008` | `XATTR`     | Extended attribute                         |
| `0x0009` | `XREF`      | Extended attribute cross-reference         |
| `0x0200` | `CLEANMARKER`| Empty-flash marker at start of erase block |

### Inode node (`jffs2_raw_inode`)

The inode node follows the common header and contains a file data fragment:

| Offset | Size | Field       |
|--------|------|-------------|
| 12     | 4    | `ino`       |
| 16     | 4    | `version`   |
| 20     | 4    | `mode`      |
| 24     | 2    | `uid`       |
| 26     | 2    | `gid`       |
| 28     | 4    | `isize`     |
| 32     | 4    | `atime`     |
| 36     | 4    | `mtime`     |
| 40     | 4    | `ctime`     |
| 44     | 4    | `offset`    | Byte offset within the file this fragment covers |
| 48     | 4    | `csize`     | Compressed size of the fragment payload          |
| 52     | 4    | `dsize`     | Uncompressed size of the fragment payload        |
| 56     | 1    | `compr`     | Compression algorithm (see below)                |
| 57     | 1    | `usercompr` | User-requested compression                       |
| 58     | 2    | `flags`     |                                                  |
| 60     | 4    | `data_crc`  | CRC of the decompressed payload                  |
| 64     | 4    | `node_crc`  | CRC of the node header (offset 12–64)            |
| 68     | var  | data        | Compressed file fragment                         |

### Dirent node (`jffs2_raw_dirent`)

| Offset | Size | Field      | Notes                                     |
|--------|------|------------|-------------------------------------------|
| 12     | 4    | `pino`     | Parent inode number                       |
| 16     | 4    | `version`  | Version of this directory entry           |
| 20     | 4    | `ino`      | Inode number the name points to           |
| 24     | 4    | `mctime`   |                                           |
| 28     | 1    | `nsize`    | Name length                               |
| 29     | 1    | `type`     | File type (matches `DT_*` values)         |
| 30     | 2    | `unused`   |                                           |
| 32     | 4    | `node_crc` | CRC of the node header (offset 12–32)     |
| 36     | 4    | `name_crc` | CRC of the name bytes                       |
| 40     | var  | `name`     | Null-terminated, padded to 4-byte boundary |

### Compression algorithms

| Value | Name         | Notes                                             |
|-------|--------------|---------------------------------------------------|
| 0x00  | `NONE`       | Raw bytes                                         |
| 0x01  | `ZERO`       | All-zero fragment (no compressed bytes)           |
| 0x02  | `RTIME`      | Simple RLE-like compressor, easy to implement     |
| 0x03  | `RUBINMIPS`  | Obsolete, rarely seen                             |
| 0x04  | `COPY`       | Uncompressed copy                                 |
| 0x05  | `DYNRUBIN`   | Obsolete, rarely seen                             |
| 0x06  | `ZLIB`       | Default for `mkfs.jffs2`; available in `java.util.zip` |
| 0x07  | `LZO`        | Available in the existing `lzo-core` dependency   |

## Scope Option A — Detection Only

### Goal

Saffron can identify a JFFS2 image and report it as `FILESYSTEM_JFFS2` from
both `FilesystemDetector` and `SaffronProbe`, without being able to mount it.

### Files changed / created

- `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2Superblock.java`
  (or `Jffs2Detector.java`) — static detection helper.
- `src/main/java/io/spicelabs/saffron/filesystem/FilesystemDetector.java` —
  add `tryDetectJffs2` and wire it into the detection chain.
- `src/main/java/io/spicelabs/saffron/fs/FileSystem.java` — add
  `FileSystemType.JFFS2` and a `Jffs2FileSystem` sealed subtype.
  **Note:** adding the type is required for detection, but it can be marked as
  not yet mountable in `FileSystemMount.isSupported`.
- `src/main/java/io/spicelabs/saffron/SaffronProbe.java` — add
  `Kind.FILESYSTEM_JFFS2` and map `FileSystemType.JFFS2` to it.
- `src/main/java/io/spicelabs/saffron/fs/FileSystemMount.java` — detect and
  report JFFS2 as an unmountable filesystem (similar to swap), or reject it with
  `UnsupportedOperationException` until Option B is implemented.
- Tests: `Jffs2DetectionTest`, `SaffronProbeTest` additions.

### Detection algorithm

1. Need at least 12 bytes from the start of the candidate region.
2. Read the first 12 bytes as little-endian.
3. Accept if:
   - `magic == 0x1985`;
   - `nodetype` is one of the known values listed above;
   - `totlen >= 12` and `totlen` is a multiple of 4;
   - Optional but recommended: `hdr_crc` matches the first 12 bytes.
4. Return `FilesystemInfo` with:
   - `type = FileSystemType.JFFS2`;
   - `version = "jffs2"`;
   - `totalSize = region.size()`;
   - other numeric fields defaulted to 0 (no cheap superblock metadata).

### Tests for Option A

- `detectsJffs2MagicAtStart` — a minimal fixture created by `mkfs.jffs2` is
  recognized.
- `rejectsRandomBytes` — random data does not match.
- `rejectsTruncatedHeader` — fewer than 12 bytes returns empty.
- `rejectsWrongMagic` — a valid node with magic changed to `0x1984` is
  rejected.
- `rejectsUnknownNodetype` — a node with magic `0x1985` but unknown type is
  rejected.
- `rejectsUnalignedTotlen` — `totlen` not a multiple of 4 is rejected.
- `rejectsBadHdrCrc` — if CRC validation is enabled, a corrupted header is
  rejected.
- `SaffronProbeTest` additions — verify that a JFFS2 prefix yields
  `FILESYSTEM_JFFS2` and that a JFFS2 image without a partition table is still
  detected as a filesystem (not `DISK_RAW`).

## Scope Option B — Detection + Read-Only Mount

### Goal

Implement a full `Jffs2FileSystemImpl` that can be returned from
`FileSystemMount.mount` and supports `resolve`, `walk`, and `readAllBytes`.

### Files changed / created

- All files from Option A, plus:
- `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2FileSystemImpl.java`
- `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2Node.java` —
  records for parsed nodes.
- `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2Compression.java` —
  pluggable decompressors.
- `src/main/java/io/spicelabs/saffron/fs/FileSystemMount.java` — add JFFS2 to
  `isSupported` and the mount switch.
- Tests: `Jffs2PropertyTest`, `Jffs2MountTest`, `Jffs2CompressionTest`,
  `Jffs2SecurityTest` (fuzz/negative cases).

### Mount algorithm

1. Scan the entire region from offset 0 to `size()`, reading nodes.
2. For each node:
   - Validate magic, nodetype, `totlen`, and CRCs.
   - Skip `CLEANMARKER`, `PADDING`, `SUMMARY`, `XATTR`, `XREF`, `CHECKPOINT`.
   - For `INODE`: record the fragment keyed by `(ino, version, offset)`.
   - For `DIRENT`: record the directory entry keyed by `(pino, name, version)`.
3. After the scan, resolve the latest version of each directory entry and each
   inode fragment.
   - For a given `(pino, name)`, the highest `version` wins; if the latest
     `ino` is 0, the entry is deleted.
   - For a given `ino`, the highest `version` of each `(offset, dsize)`
     fragment wins; fragments are concatenated by `offset` to reconstruct the
     file.
4. Build the `FileSystem` tree:
   - Inode 1 is the root directory.
   - Traverse `pino` → `ino` links from dirent records to construct paths.
5. `resolve()` walks the path, dereferencing directories by name and returning
   the inode record.
6. `walk()` performs a depth-first traversal of the directory tree.
7. For a regular file, `readAllBytes()` collects the latest fragment per
   offset, decompresses each fragment using the recorded `compr`, and writes
   them into the correct byte range of the output.

### Compression support plan

| Algorithm | Phase-1 support | Implementation notes                            |
|-----------|-----------------|-------------------------------------------------|
| `NONE`    | Yes             | Direct copy                                     |
| `ZERO`    | Yes             | Fill with zeros                                 |
| `COPY`    | Yes             | Direct copy (alias of NONE)                     |
| `ZLIB`    | Yes             | `java.util.zip.Inflater`                        |
| `LZO`     | Yes             | Existing `org.anarres.lzo` dependency           |
| `RTIME`   | Yes             | ~20 lines of Java RLE decoder                   |
| `RUBIN*`  | No              | Obsolete; reject with clear error message       |

If an unsupported compressor is encountered during a read, the mount or read
operation throws `IOException` with a descriptive message, but detection still
succeeds.

### Security & defensive parsing

- `totlen` must be bounded by remaining region size; otherwise reject the node.
- `csize` and `dsize` must be bounded by `totlen` and by a reasonable
  multiplier (e.g., `dsize <= 4 * csize` for compressed data, with a hard
  ceiling). This mirrors the decompression-bomb limits used elsewhere in
  Saffron.
- `nsize` must be less than the remaining node length and less than a sane
  maximum (e.g., 255).
- Cyclic parent/child dirent links must be detected during path resolution.
- The scan must not allocate memory proportional to untrusted header fields.

### Tests for Option B

- `roundTripMinimalImage` — create a small JFFS2 image with a directory and a
  few files, mount it, and verify content and paths.
- `roundTripNestedDirectories` — deeper tree.
- `roundTripWithCompression` — parameterized over `zlib`, `lzo`, `rtime`,
  `none`, `zero`, `copy`.
- `handlesDeletedAndOverwrittenFiles` — old versions of files/directories are
  ignored.
- `handlesHardLinks` — two dirent entries with the same `ino`.
- `handlesEmptyFiles` — `dsize == 0`.
- `handlesSparseFiles` — fragments with gaps (unwritten offsets should read as
  zeros).
- `rejectsTrucatedNode` — a node whose `totlen` exceeds the region is skipped
  or causes mount failure.
- `rejectsBadCrc` — a corrupted fragment is rejected.
- `rejectsUnsupportedCompression` — `rubin`/`dynrubin` nodes are rejected.
- `fuzzJffs2Header` — random mutations of a valid header return empty or fail
  gracefully, never throwing unchecked exceptions.

## Fixture Strategy

`mkfs.jffs2` is not installed in the current environment. Per project rules,
fixtures are generated once using Docker, committed to the repository, and
referenced by tests. Tests never invoke external processes.

A suitable Docker image is `debian:bookworm-slim` with `mtd-utils` installed.
Example fixture generation script:

```bash
#!/bin/bash
set -euo pipefail
mkdir -p src/test/resources/jffs2/fixtures
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/root"
echo "root" > "$TMP/root/root.txt"
mkdir -p "$TMP/root/dir"
echo "nested" > "$TMP/root/dir/nested.txt"

docker run --rm --user "$(id -u):$(id -g)" \
  -v "$TMP:/work:ro" \
  -v "$(pwd)/src/test/resources/jffs2/fixtures:/out" \
  debian:bookworm-slim \
  bash -c 'apt-get update -qq && apt-get install -y -qq mtd-utils >/dev/null 2>&1 && \
           mkfs.jffs2 --root=/work/root --output=/out/minimal-zlib.jffs2 \
                      --eraseblock=64KiB --compr-mode=none'
```

Additional fixtures are generated with `--compr-mode=zlib`, `--compr-mode=lzo`,
`--compr-mode=rtime`, etc., to exercise the compression matrix. The generation
script is committed under `src/test/resources/jffs2/generate-fixtures.sh`.

## Integration Points

1. `FilesystemDetector` — add `tryDetectJffs2` after the existing filesystem
   checks.
2. `SaffronProbe` — `FILESYSTEM_JFFS2` must appear in the `Kind` enum and in
   `toFilesystemKind`. Detection must remain byte-range-only, never throw, and
   deterministic.
3. `FileSystem` — add `JFFS2("jffs2", "JFFS2 flash filesystem")` to
   `FileSystemType` and a matching `non-sealed interface Jffs2FileSystem`.
4. `FileSystemMount` — for Option A, report JFFS2 in `detected` but not
   `mounted`. For Option B, add it to `isSupported` and the mount switch.
5. `DiskReader` — no changes required. JFFS2 images are bare filesystems,
   detected by `FilesystemDetector` once the raw disk is opened, similar to
   squashfs.

## Risks and Dependencies

| Risk | Mitigation |
|------|------------|
| `mkfs.jffs2` unavailable for local fixture generation | Use Docker with `mtd-utils`; commit generated fixtures. |
| JFFS2 images in the wild may use obsolete compression (`rubin`) | Phase-1 supports the common algorithms; obsolete ones are rejected cleanly. |
| Log-structured version resolution is easy to get wrong | Property tests compare against a known-good tool (`jefferson`, `jffs2dump`, or `mount` via Docker) for selected fixtures. |
| Large JFFS2 images scan slowly | Full scan is required for a correct mount; this is acceptable for the read-only use case. Streaming is not part of the initial scope. |
| CRC mismatches on real flash dumps (bit flips) | Strict CRC validation is required for security; if real-world images fail, we can add a permissive mode later behind an explicit policy. |

## Estimated Effort

- **Option A (detection only):** ~1–2 days, mostly tests and fixture
  generation.
- **Option B (full read-only mount):** ~1–2 weeks, driven by the node scan,
  version resolution, decompression, and round-trip property testing.

## Recommendation

Proceed with **Option A** first. It is low-risk, keeps the `FileSystem` sealed
interface small (JFFS2 exists as a type but is not yet mounted), and gives Goat
Rodeo the `SaffronProbe` signal it needs to avoid spilling non-JFFS2 artifacts
that might be misidentified by MIME type. Full read support can be added as a
follow-up phase once Option A is tested and merged.

## Open Questions

1. Should JFFS2 also be recognized by extension (e.g., `.jffs2`)? JFFS2 images
   are usually named without a standard extension, so magic-based detection is
   preferred, but an extension fallback can be added if required.
2. Is Goat Rodeo interested in JFFS2 mount support, or only detection for
   gating? This determines whether we proceed to Option B.
3. Do we need to handle NAND OOB cleanmarkers? Standard `mkfs.jffs2` images do
   not include OOB data, but raw NAND dumps do. This is out of scope for the
   initial implementation.
