# JFFS2 Filesystem Support

Saffron supports read-only mounting of JFFS2 (Journalling Flash File System
version 2), the log-structured flash filesystem used by embedded Linux devices
(notably OpenWrt routers).

## Detection

JFFS2 has no superblock. A filesystem is recognized by a well-formed node
header at offset 0:

| Check | Rule |
|-------|------|
| magic | `0x1985` little-endian (the legacy `0x1984` magic is rejected) |
| nodetype | one of dirent / inode / cleanmarker / padding / summary / xattr / xref |
| totlen | `>= 12` and within the region (not necessarily 4-aligned: real mkfs.jffs2 images store the true node length and pad the body with `0xFF`) |
| hdr_crc | JFFS2 CRC-32 over the first 8 bytes (initial value 0, no final XOR) |

Detection is wired into `FilesystemDetector` (checked first, since a validated
offset-0 node header is the strongest possible signal), `SaffronProbe`
(`FILESYSTEM_JFFS2`), and `DiskFormat.detectByExtension` (`.jffs2` opens as
`RAW`, like `.squashfs`). Verified by `Jffs2DetectionTest` and
`SaffronProbeTest.jffs2Magic_returnsFilesystemJffs2`.

## Mounting

`FileSystemMount` mounts JFFS2 through `Jffs2FileSystemImpl` (a
`FileSystem.Jffs2FileSystem`). Mounting scans the whole region for nodes and
resolves the log semantics:

- **Dirents:** keyed by `(pino, name)`; the highest version wins. A dirent
  whose latest version has ino 0 records a deletion and the entry disappears.
- **Hard links:** multiple dirents with the same ino expose the same content.
- **Inode metadata:** keyed by ino; the highest version wins.
- **File data:** fragments keyed by `(ino, offset)`; the highest version wins
  for each byte range. Unwritten ranges read as zeros (sparse files).
- **Compression:** `none`, `zero`, `rtime`, `copy`, `zlib`, and `lzo` are
  supported. The obsolete `rubinmips`/`dynrubin` algorithms are rejected with
  a checked `IOException`.

Verified by `Jffs2SecurityTest` (version resolution, deletion, hard links,
sparse files, compression, CRC handling) and `Jffs2FileSystemTest` (round-trip
of all fixture contents, symlink resolution, walk, metadata).

## Hardening

- Node lengths are bounds-checked against the region before any allocation is
  derived from them; the scan advances by the 4-byte-aligned length.
- Nodes with an invalid header, node, or name CRC are skipped (matching kernel
  behaviour), never trusted.
- A single fragment's decompressed size is capped at 1 MiB (real JFFS2 data
  nodes never exceed the target flash page; the default is 4 KiB).
- File content is served through a lazy `InputStream`: fragments are
  decompressed on demand, so memory is bounded by the largest fragment rather
  than the logical file size (which can legitimately exceed the image size
  because of compression, especially zero-filled files).
- Symlink targets are capped at 4096 bytes; entry names containing `/`, `\`,
  `..`, or NUL are dropped (path-traversal hardening).
- A dirent referencing an inode with no valid inode node is hidden (kernel
  lookup would fail).

Verified by `Jffs2SecurityTest` and the fixture round-trips.

## Fixtures

Tests never invoke external processes. Fixtures are committed JFFS2 images
generated once with the reference `mkfs.jffs2` (mtd-utils) in a Docker
container — see `src/test/resources/jffs2/generate-fixtures.sh`. The fixture
tree contains regular files, nested directories, an empty file, a hard link,
a symlink, an incompressible 40 KiB file, a highly compressible 82 KB file,
and a 16 KB zero-filled file, generated once per compression mode (none, zlib,
lzo, rtime) plus a no-cleanmarker variant.

Real-world ("wild") JFFS2 images: standalone JFFS2 images are rare in the
wild — common distributions publish squashfs or combined firmware images;
JFFS2 filesystems normally exist inside router firmware as flash partitions.
We searched the OpenWrt download server, OWASP IoTGoat releases, the
`jefferson` extraction tool, the binwalk test suite, and archive.org, and
found one published standalone image: the LeapFrog Didj handheld root
filesystem (`erootfs.jffs2`, part of the Didj NAND, archive.org item
`erootfs`). It is committed under `src/test/resources/jffs2/wild/` with an
adjacent license file, and `Jffs2WildCorpusTest` mounts and walks it (plus
the committed fixtures and any additional images dropped into
`test-corpus/jffs2/`). The test always runs; the zero-byte
detection-negative fixture `empty.jffs2` is excluded because it must not be
detected (see `Jffs2DetectionTest.rejectsEmptyImage`).

## Key files

| File | Purpose |
|------|---------|
| `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2Node.java` | on-disk constants, records, CRC |
| `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2Superblock.java` | detection |
| `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2FileSystemImpl.java` | read-only mount |
| `src/test/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2DetectionTest.java` | detection tests |
| `src/test/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2FileSystemTest.java` | round-trip tests |
| `src/test/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2SecurityTest.java` | semantics + hardening |
| `src/test/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2NodeWriter.java` | hand-crafted image builder |
| `src/test/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2WildCorpusTest.java` | wild-image hook |
| `src/test/resources/jffs2/fixtures/` | committed mkfs.jffs2 images |
| `src/test/resources/jffs2/generate-fixtures.sh` | one-time Docker generator |
