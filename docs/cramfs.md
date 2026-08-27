# cramfs Filesystem Support

Saffron supports read-only mounting of cramfs ("Compressed ROMFS"), the tiny
read-only compressed filesystem used by legacy router firmware (D-Link,
TP-Link, old OpenWrt ramdisks) and early embedded Linux.

## Detection

cramfs is recognized by a validated superblock at offset 0:

| Check | Rule |
|-------|------|
| magic | `0x28cd3d45` (little-endian) or `0x453dcd28` (big-endian); mkcramfs writes images in host byte order, so both exist in the wild |
| signature | `"Compressed ROMFS"` at offset 16 (a wrong signature is accepted only with the `WRONG_SIGNATURE` flag) |
| flags | unknown feature flags rejected |
| root inode | must be a directory, or offset 0 (empty filesystem) |
| root offset | `76` or `512 + 76` unless `SHIFTED_ROOT_OFFSET` (kernel parity) |
| size | within the artifact size (meaningful with `FSID_VERSION_2`) |

Detection is wired into `FilesystemDetector` (checked after JFFS2, before
ext), `SaffronProbe` (`FILESYSTEM_CRAMFS`), and the extension fallbacks
(`.cramfs` opens as `RAW`, like `.squashfs`). Verified by
`CramfsDetectionTest` and `SaffronProbeTest.cramfsMagic_returnsFilesystemCramfs`.

## Mounting

`FileSystemMount` mounts cramfs through `CramfsFileSystemImpl` (a
`FileSystem.CramfsFileSystem`). Format facts verified against the kernel
(`fs/cramfs/inode.c`) and real mkfs.cramfs output:

- Inode = 12 bytes of bitfields + zero-padded name (`namelen * 4` bytes,
  max name 252). Bitfield packing is endian-dependent (low bits first on
  little-endian images, high bits first on big-endian images).
- Directory entries are sequential, bounded by the directory inode's
  `size`; there is no chain and no hard links.
- File data is per-4096-byte blocks. Block pointer values are **absolute
  byte offsets, one past the end of the block** (not shifted); flags live
  in bits 30/31. A pointer equal to its predecessor marks a hole (zeros).
  `UNCOMPRESSED` blocks are stored raw; otherwise the block is an
  independent zlib stream.
- Symlinks are file-like; targets are read via the same block mechanism
  and capped at 4096 bytes.
- Device/fifo/socket entries use `old_decode_dev(size)` for major/minor.

Verified by `CramfsFileSystemTest` (round-trip of all fixture contents —
including SHA-256 of a 90 KB multi-block compressed file and a sparse file
with a hole — for both endians, symlink resolution, walk, metadata) and
`CramfsSecurityTest` (hand-crafted images: compressed/uncompressed/hole
blocks, corrupt pointers, oversized blocks, unsupported flags, traversal
names, FIFO entries).

## Hardening

- Every offset is bounds-checked against the effective image size before
  use; the scan never follows an untrusted length.
- Stored block lengths are capped at `2 * 4096` bytes (kernel parity).
- Direct block pointers (`EXT_BLOCK_POINTERS` feature) are rejected with a
  checked `IOException` rather than misparsed.
- File content is served through a lazy `InputStream`: only the blocks
  being read are decompressed.
- Entry names containing `/`, `\`, `..`, or NUL are dropped; directory
  walks are cycle-guarded.

## Fixtures and corpus

Tests never invoke external processes.

- **Wild corpus** (`src/test/resources/cramfs/wild/`, 10 images, each with an
  adjacent `.license.txt`): cramfs images recovered from public projects and
  device firmware — util-linux test suite (big- and little-endian),
  e2fsprogs 1.41.12 libblkid, binwalk, dissect.cramfs (standard,
  hole-support, and a real device web filesystem), fact_extractor, a
  Hisilicon IP-camera module cramfs, and a Xiaomi Xiaofang camera rootfs.
- **Synthetic corpus** (`src/test/resources/cramfs/synthetic/`, 23 images,
  see its README): generated once with three toolchains — util-linux
  mkfs.cramfs 2.39, npitre/cramfs-tools, and the OpenRG `mkcramfs-lzma`
  from the Actiontec MI424WR GPL sources — plus flag/layout patches
  (wrong-signature, unsorted, fsid v1, 512-byte-shifted). Covers both
  endians, holes, uncompressed blocks, device nodes, extended block
  pointers, and the OpenRG LZMA/block-size variants.
- `CramfsWildCorpusTest` mounts and walks every corpus image on every run.
- `src/test/resources/cramfs/generate-fixtures.sh` and
  `generate-synthetic.sh` reproduce the synthetic sets.

## OpenRG extension ("cramfs-lzma")

The OpenRG router stack (Actiontec MI424WR and similar) encodes the block
size in flag bits 11..13 (`4096 << value`) and the compression method in
bits 14..15 (0 none, 1 zlib, 2 **LZMA**) — an extension absent from the
mainline format. Bit 11 overlaps the mainline `EXT_BLOCK_POINTERS` flag;
the extension is treated as active only when the compression-method bits
(or higher block-size bits) are set. Detection, mount, and walk support
these images; **content reads of OpenRG LZMA or non-4096-block images are
not yet supported** and fail with a clear `IOException` (see
`CramfsSuperblock.compressionMethod()`).

## Key files

| File | Purpose |
|------|---------|
| `src/main/java/io/spicelabs/saffron/filesystem/cramfs/CramfsSuperblock.java` | constants + endian-aware superblock reader |
| `src/main/java/io/spicelabs/saffron/filesystem/cramfs/CramfsFileSystemImpl.java` | read-only mount |
| `src/test/java/io/spicelabs/saffron/filesystem/cramfs/CramfsDetectionTest.java` | detection tests |
| `src/test/java/io/spicelabs/saffron/filesystem/cramfs/CramfsFileSystemTest.java` | round-trip tests |
| `src/test/java/io/spicelabs/saffron/filesystem/cramfs/CramfsSecurityTest.java` | hand-crafted + hardening tests |
| `src/test/java/io/spicelabs/saffron/filesystem/cramfs/CramfsImageWriter.java` | hand-crafted image builder |
| `src/test/java/io/spicelabs/saffron/filesystem/cramfs/CramfsWildCorpusTest.java` | wild-image hook |
| `src/test/resources/cramfs/fixtures/` | committed mkfs.cramfs images |
| `src/test/resources/cramfs/generate-fixtures.sh` | one-time Docker generator |
