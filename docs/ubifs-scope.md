# UBIFS / UBI Filesystem Support — Scope

## Executive Summary

UBIFS is the successor to JFFS2 for raw NAND flash and is the default
filesystem of modern OpenWrt routers and many embedded Linux devices. UBIFS
always sits on top of UBI (Unsorted Block Images), a wear-levelling volume
manager. Supporting UBIFS therefore means implementing (or at least
recognizing) two layers:

1. **UBI** — the container: maps logical eraseblocks (LEBs) of volumes onto
   physical eraseblocks (PEBs) via per-PEB erase-counter (EC) and
   volume-id (VID) headers.
2. **UBIFS** — the filesystem: a journaled, B-tree-indexed filesystem stored
   inside one UBI volume.

This is the largest filesystem effort Saffron has taken on. It is scoped in
**three phases**: UBI/UBIFS detection first (huge classification win for
router firmware), UBI attach + volume mapping second, and a read-only UBIFS
mount third.

## Format Overview (verified against the Linux kernel headers)

### UBI (drivers/mtd/ubi/ubi-media.h)

All multi-byte UBI fields are **big-endian** (`__be32`/`__be64`).

Every PEB starts with a 64-byte EC header:

| Offset | Size | Field | Notes |
|--------|------|-------|-------|
| 0 | 4 | `magic` | `0x55424923` = `"UBI#"` |
| 4 | 1 | `version` | must be 1 |
| 8 | 8 | `ec` | erase counter |
| 12 | 4 | `vid_hdr_offset` | offset of VID header within the PEB |
| 16 | 4 | `data_offset` | offset of user data within the PEB |
| 20 | 4 | `image_seq` | image sequence number |
| 60 | 4 | `hdr_crc` | CRC-32 (init `0xFFFFFFFF`, standard zlib final XOR) over bytes 0..59 |

The VID header (64 bytes) sits at `vid_hdr_offset` (typically the second
min-IO-unit, e.g. offset 512):

| Field | Notes |
|-------|-------|
| `magic` | `0x55424921` = `"UBI!"` |
| `vol_type` | 1 = dynamic, 2 = static |
| `vol_id` | volume id |
| `lnum` | logical eraseblock number |
| `data_size`, `used_ebs`, `data_pad`, `data_crc` | static-volume metadata |
| `sqnum` | 64-bit sequence number: higher wins when two PEBs map the same LEB |

The **layout volume** (volume id `0x7FFFEFFF`) contains the volume table
(vtbl): 132-byte records (`reserved_pebs`, `alignment`, `data_pad`,
`vol_type`, `name_len`, `name[128]`, `flags`, `crc`), duplicated across its
two LEBs. UBI fastmap structures (magics `0x7B11D69F` etc.) can be ignored
for a read-only implementation: a full scan works without them.

### UBIFS (fs/ubifs/ubifs-media.h)

All UBIFS fields are **little-endian**. Every UBIFS node begins with the
24-byte common header:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4 | `magic` = `0x06101831` |
| 4 | 4 | `crc` (CRC-32, init `0xFFFFFFFF`) |
| 8 | 8 | `sqnum` (sequence number) |
| 16 | 4 | `len` (full node length, 8-byte aligned) |
| 20 | 1 | `node_type` |
| 21 | 1 | `group_type` |

Node types: INO(0), DATA(1), DENT(2), XENT(3), TRUN(4), PAD(5), SB(6),
MST(7), REF(8), IDX(9), CS(10), ORPH(11), AUTH(12), SIG(13).

Fixed layout areas:

| Area | LEBs | Content |
|------|------|---------|
| superblock | LEB 0 | SB node with geometry: `min_io_size`, `leb_size`, `leb_cnt`, `fanout`, `fmt_version` (4/5), `default_compr`, `uuid` |
| master | LEB 1, 2 | two copies of the MST node: `root_lnum/offs/len`, `log_lnum`, `cmt_no`, `flags` (bit 0 = DIRTY), `ihead_lnum/offs` |
| log | from LEB 3 | journal; the last committed state is referenced from the MST node |
| LPT / orphan | per SB | free-space tracking — not needed for read-only mount |
| main | rest | index nodes (B-tree), inode nodes, data nodes, dents |

Keyed nodes (INO/DATA/DENT/XENT) carry a `key[]` after the common header.
The "simple key format" packs `(type:3, inode:29)` for INO/DATA keys and
`(type:3, parent_inode:29, hash:29, name)` for DENT keys; the hash is the
**R5 hash** of the entry name (implemented in `fs/ubifs/key.h`). Names are
255 bytes max.

- INO node: mode, uid/gid, nlink, size (u64), times, `data_len`, xattr
  counts, `compr_type`, plus inline data (up to 4096 bytes).
- DATA node: `size` (uncompressed), `compr_type` (NONE/LZO/ZLIB/ZSTD),
  payload. Block size is fixed 4096.
- DENT node: `inum`, `type`, `nlen`, name.
- TRUN node: truncation journal record. IDX node: B-tree branches
  (`lnum/offs/len/key`), fanout from SB (default 8).
- CS node: commit start; MST points at the newest commit.

Saffron already has zlib, LZO, and zstd decompressors (used by squashfs and
the kernel container), so UBIFS data decompression is covered.

### Where real images come from

OpenWrt publishes factory/sysupgrade images containing UBI (e.g.
`openwrt-*-mediatek-filogic-*sysupgrade.itb`, which holds a `root` UBI
volume). `ubinize`/`mkfs.ubifs` (mtd-utils) can also generate fixtures
directly, including a clean UBIFS with all three compressors.

## Phase 1 — Detection (UBI container + UBIFS)

### Goal

Saffron recognizes UBI containers and UBIFS volumes and classifies them in
`FilesystemDetector`, `SaffronProbe`, and extension fallbacks, without
mounting.

### Scope

- `UbiSuperblock`-style detector: at candidate offset 0, validate the EC
  header (`"UBI#"` magic, version 1, header CRC) and find the VID header at
  `vid_hdr_offset` (`"UBI!"` magic, header CRC).
- `UbiVolumeTable` reader (read-only): locate the layout volume, parse
  vtbl records, and report volume names (`FileSystemInfo` for the container
  listing volumes).
- `UbifsSuperblock`: at a volume's LEB 0, validate the SB node
  (`0x06101831` magic, CRC, `fmt_version` 4 or 5).
- Integration: `FilesystemDetector` detects UBI and reports each UBIFS
  volume (a multi-volume container needs a small extension to the
  single-filesystem `FilesystemInfo` model — see Open Questions).
  `SaffronProbe`: `FILESYSTEM_UBIFS` and `CONTAINER_UBI` kinds.
  `DiskFormat`/`SaffronProbe` extension fallbacks: `.ubi`, `.ubifs`.
- New `FileSystemType.UBIFS` and `UBI` (container type).

### Tests (Phase 1)

- `UbiDetectionTest`: ubinize-generated image detected; random/truncated/
  bad-CRC EC header rejected; VID/vtbl parsed (volume names correct).
- `UbifsDetectionTest`: mkfs.ubifs volume detected at LEB 0; wrong magic,
  truncated SB, bad CRC rejected.
- `SaffronProbeTest` additions: UBI prefix → `CONTAINER_UBI`/`FILESYSTEM_UBIFS`.
- Wild-corpus hook: `test-corpus/ubi/` and `test-corpus/ubifs/`.

## Phase 2 — UBI attach (volume mapping)

### Goal

A read-only `UbiVolume` implementation that turns a raw UBI image into a
`DiskRegion` per volume: scan all PEBs, resolve the LEB mapping (higher
`sqnum` wins; `copy_flag` + `data_crc` fallback per the kernel), and expose
each volume as a linear region (respecting `data_offset` and `data_pad`).

### Scope

- `UbiAttach`: full PEB scan; EC/VID header CRCs; per-(vol_id, lnum)
  newest-PEB resolution; static-volume `data_size` handling.
- `UbiVolumeRegion implements DiskRegion` (random reads translate
  LEB/offset to PEB/offset).
- `FileSystemMount` integration: mount UBIFS volumes through the region.

### Tests (Phase 2)

- Round-trip: fixture with two volumes; contents of both readable via the
  regions.
- Duplicate LEB (two PEBs, different sqnum) resolves to the newer.
- `copy_flag` + bad `data_crc` falls back to the older PEB.
- Truncated/bad PEB headers skipped without crashing.

## Phase 3 — Read-only UBIFS mount

### Goal

`UbifsFileSystemImpl` implementing the `FileSystem` interface.

### Scope

1. Read SB node at LEB 0; validate geometry (`min_io_size`, `leb_size`,
   `leb_cnt`, `fmt_version` 4/5, `default_compr`).
2. Read the newest valid MST node from LEB 1/2 (`cmt_no` comparison).
3. If `MST_DIRTY`, replay the journal from `log_lnum` (CS nodes,
   REF/INO/DENT/DATA/TRUN nodes appended to the index) — or, for the
   first cut, reject dirty images with a clear error and support clean
   ones (most mkfs.ubifs/OpenWrt images are cleanly committed).
4. Walk the index B-tree from `root_lnum/offs` (IDX nodes, fanout from SB)
   collecting INO, DENT, and DATA nodes; resolve keys.
5. Build the entry tree: dents (R5-hashed keys), inode metadata (latest
   `sqnum` wins per key), data nodes per inode ordered by block number;
   truncated sizes from TRUN nodes or inode `size`.
6. Entries: regular files (lazy decompression over data nodes: NONE, LZO,
   ZLIB, ZSTD), directories, symlinks (inline data), device/fifo/socket.
7. Reject encrypted/authenticated images (`FLG_ENCRYPTION`/`FLG_AUTHENTICATION`)
   with a clear message (no keys available).

### Tests (Phase 3)

- Round-trip mkfs.ubifs fixtures: default (zlib), lzo, zstd, none; nested
  dirs, symlink, hardlink, sparse file, empty file, 0-size FS.
- Clean vs DIRTY master node handling (reject or replay).
- Index fanout > 1 (enough files to force multiple index levels).
- Fuzz: corrupted node headers/CRCs skipped; absurd `len`/`size` bounds;
  name path traversal; unsupported fmt_version rejected.
- Wild images: OpenWrt sysupgrade ITB (real UBI) and any `test-corpus/ubi/`
  dumps.

## Fixture Strategy

Docker image with mtd-utils (`mkfs.ubifs`, `ubinize`, `ubireader`-style
dumps via `ubireader_extract_files` are Python — use `ubi-utils` +
`mtd-utils` only). Committed fixtures + generator script under
`src/test/resources/ubi/`. Wild images: extract the UBI volume from a real
OpenWrt sysupgrade image at fixture-generation time (Docker), then commit
the extracted partition — tests never shell out.

## Risks and Dependencies

| Risk | Mitigation |
|------|------------|
| UBIFS mount is genuinely complex (journal, TNC, keys) | Phase it: clean images first; DIRTY replay later; keep detection independent |
| Key format (R5 hash) subtlety | Port the ~30-line R5 hash from `fs/ubifs/key.h`; verify against mkfs.ubifs fixtures |
| UBI images with fastmap | Ignore fastmap structures; full scan is always correct |
| NAND dumps with OOB | Out of scope (same decision as JFFS2); UBI images from `ubinize` have no OOB |
| Multi-volume result model | `FilesystemDetector` returns one FS per offset today; UBI needs a container result — requires a small API decision (Open Question 2) |

## Estimated Effort

- Phase 1 (detection): ~1–1.5 weeks
- Phase 2 (UBI attach): ~1–2 weeks
- Phase 3 (UBIFS mount): ~3–5 weeks (largest single-filesystem effort in Saffron)

## Open Questions

1. Mount UBIFS from OpenWrt images: should Saffron also recognize the FIT
   container wrapping the UBI (already detected as FIT) and mount UBIFS
   inside it? Recommend yes, via `BinaryContainerMount` + Phase 2 regions.
2. How should `FileSystemMount.findFilesystems` report a UBI container with
   N volumes? Options: (a) return each UBIFS volume as a separate
   `FilesystemLocation`; (b) add a container result type. Recommend (a)
   with `FileSystemType.UBI` reported when no volume is UBIFS.
3. Dirty-master images in Phase 3: reject-first or replay-first?
   Recommend reject-first, replay as a follow-up phase.
   **Resolved:** reject-first, implemented (clear IOException).
