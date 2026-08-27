# cramfs Filesystem Support — Scope

## Executive Summary

cramfs ("Compressed ROMFS") is a tiny read-only filesystem used in legacy
router firmware (old D-Link/TP-Link, old OpenWrt targets) and early embedded
Linux. It is the simplest filesystem Saffron could add: a fixed superblock,
a flat inode table walked as linked lists, and per-4K-block zlib
compression. Detection + mount in a single phase.

## Format Overview (verified against include/uapi/linux/cramfs_fs.h)

### Superblock (offset 0)

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4 | `magic` = `0x28cd3d45` |
| 4 | 4 | `size` (total filesystem size in bytes) |
| 8 | 4 | `flags` |
| 12 | 4 | `future` (reserved) |
| 16 | 16 | `signature` = `"Compressed ROMFS"` |
| 32 | 16 | `fsid` (crc, edition, blocks, files) |
| 48 | 16 | `name` (user-defined) |
| 64 | 12 | root inode |

**Endianness:** `mkcramfs` writes the image in host byte order. The kernel
detects byte order by magic: `0x28cd3d45` = little-endian,
`0x453dcd28` = big-endian. Saffron must read both.

Feature flags: `SORTED_DIRS` (0x02), `HOLES` (0x100),
`WRONG_SIGNATURE` (0x200, some historical images), `SHIFTED_ROOT_OFFSET`
(0x400), `EXT_BLOCK_POINTERS` (0x800). For a read-only reader the flags
only affect directory sorting and hole handling; none block mounting.

### Inode (12 bytes, bitfields)

- `mode` (16 bits), `uid` (16 bits)
- `size` (24 bits), `gid` (8 bits)
- `namelen` (6 bits, name length / 4, rounded up — max name 252 bytes)
- `offset` (26 bits, offset / 4)

Semantics:
- **Regular file:** `offset*4` points at the block-pointer table: an array
  of u32 entries, one per 4096-byte block of the file
  (`ceil(size/4096)` entries). Each entry: `(len & 0x3fffffff)` compressed
  size (bytes 0..29 = length<<2? no — see below) plus flag bits
  `UNCOMPRESSED (1<<31)` and `DIRECT_PTR (1<<30)`. A length of 0 is a hole.
  Blocks are compressed independently with zlib; blocks whose compressed
  size equals 4096 are stored raw with the UNCOMPRESSED flag.
- **Directory:** `offset*4` points at the first inode in the directory;
  inodes of the same directory form a NULL-terminated linked list via
  `offset`. `size` is unused.
- **Symlink:** target is stored like file data.
- **Device/fifo:** `size` is the encoded rdev. **No hard links.**

### Block pointer details (from the kernel cramfs README)

The block-pointer entry value is
`(compressed_len >> 2) | flags`... precisely: an entry is a u32 where
`len = value & 0x3FFFFFFF` (in units of 4 bytes: actual byte length is
`len << 2`), `CRAMFS_BLK_FLAG_UNCOMPRESSED = 0x80000000`, and
`CRAMFS_BLK_FLAG_DIRECT_PTR = 0x40000000` (direct pointers for
EXT_BLOCK_POINTERS, shifted right 2). A zero entry is a hole (reads as
zeros). Blocks are max 4096 bytes; an uncompressed block is a raw 4096-byte
page stored directly after the pointer table.

### Detection rule

- Magic `0x28cd3d45` or byte-swapped `0x453dcd28` at offset 0 AND
- signature `"Compressed ROMFS"` at offset 16 (unless WRONG_SIGNATURE flag;
  accept magic+plausible geometry then).
- `size` field within the artifact size (defensive).

## Scope — single phase (detection + mount)

### Implementation map

1. `filesystem/cramfs/CramfsSuperblock.java` — endian-detecting superblock
   reader (magic both orders, signature check, flag parsing).
2. `filesystem/cramfs/CramfsFileSystemImpl.java` — mount:
   - parse superblock; locate root inode;
   - directory listing via inode linked list (`offset` chain);
   - file content via block-pointer table: per-block zlib inflate (or raw
     copy for UNCOMPRESSED), holes → zeros, lazy `InputStream`;
   - symlinks: file data = target; devices: rdev decode (new/huge encodings);
   - `FileSystem` API: root/resolve/walk/sizes/metadata (cramfs has no
     label/uuid beyond `fsid`).
3. Integration: `FilesystemDetector` (check after JFFS2; magic at offset 0),
   `FileSystem.FileSystemType.CRAMFS` + `CramfsFileSystem` subtype,
   `FileSystemMount`, `SaffronProbe.Kind.FILESYSTEM_CRAMFS`,
   extension fallback `.cramfs` → RAW.
4. Hardening: name lengths ≤ 252, path traversal filtering (same policy as
   JFFS2), block pointers bounds-checked against `super.size`, compressed
   block size capped at 4096, inode chain cycle guard for directories.

### Tests

- `CramfsDetectionTest`: fixture detected; random/truncated rejected;
  wrong magic rejected; signature check; little- and big-endian fixtures
  both detected (generate the BE one with a byte-swap helper if mkcramfs
  cannot emit BE).
- `CramfsFileSystemTest`: round-trip (nested dirs, empty file, symlink,
  holey file, ~100KB compressible file, device node); walk paths;
  per-block decompression verified via sha256 of a file spanning multiple
  blocks.
- `CramfsSecurityTest`: truncated block-pointer table; block pointer beyond
  image size; compressed-size 4096+ handling; directory chain cycle;
  path traversal names; fuzz (random bit flips in superblock).
- `SaffronProbeTest` additions + wild-corpus hook (`test-corpus/cramfs/`).

## Fixture Strategy

`mkcramfs` lives in the `cramfsprogs` Debian package (not in bookworm's
main archive by default — verify; fallback: build from source or generate
with the kernel's `fs/cramfs` + a small C/Python writer). Fixtures
committed under `src/test/resources/cramfs/fixtures/`; generator script
committed; tests never shell out.

Wild images: cramfs appears in countless legacy router firmware images
(D-Link/TP-Link GPL drops, old OpenWrt ramdisk images). The wild-corpus
hook lets real firmware-extracted cramfs partitions be dropped in.

## Risks

| Risk | Mitigation |
|------|------------|
| Endianness variety | Detect via magic byte order; test both |
| `mkcramfs` tool availability | Docker build from source if not packaged |
| WRONG_SIGNATURE legacy images | Accept magic + geometry when flag present |

## Estimated Effort

~3–5 days including fixtures and tests. This is the cheapest OT filesystem
win available; the zlib decompression path is already proven in Saffron.

## Open Questions

1. Should `.cramfs` extension fall back to RAW in `DiskFormat` (like
   `.squashfs`/`.jffs2`)? Recommend yes for parity.
   **Resolved:** yes, implemented in both `DiskFormat` and `SaffronProbe`.
2. Big-endian fixtures: generate via byte-swapped copy of an LE fixture
   (documented in the generator script) — acceptable? Recommend yes.
   **Resolved:** yes; the Debian `cramfsswap` tool does the byte swap and
   both endians are tested end-to-end.
