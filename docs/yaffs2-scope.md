# YAFFS2 Filesystem Support — Scope

## Executive Summary

YAFFS2 (Yet Another Flash File System 2) is a log-structured NAND
filesystem used in industrial devices, older Android devices (boot/recovery
partitions), and assorted embedded gear. It shares Saffron's JFFS2
"scan-all-nodes" pattern: every object is a sequence of 2048-byte chunks
(plus 64-byte spare area carrying tags), and the directory structure is
recovered by grouping object headers by parent id. Detection + mount in a
single phase, ~1–2 weeks.

## Format Overview

### Media layout

An image file is a linear sequence of chunks: **2048 bytes of data + 64
bytes of spare area (OOB)** — the layout produced by `mkyaffs2image` (the
"yaffs2" format with tags in the spare area). Raw NAND dumps with vendor
OOB layouts are out of scope, mirroring the JFFS2 decision.

### Tags (spare area)

Each chunk's tags carry (all little-endian in `mkyaffs2image` output —
verified at implementation time against fixtures):

- `seq_number` (u32) — monotonically increasing per block; highest wins
  for the same chunk;
- `obj_id` (u32) — object id (0 = invalid/erased);
- `chunk_id` (u32) — 0 = object header, ≥1 = data chunk (index);
- `n_bytes` (u32) — valid bytes in this chunk (last data chunk);
- extra tags: `obj_type` (4 bits): 0 UNKNOWN, 1 FILE, 2 SYMLINK,
  3 DIRECTORY, 4 HARDLINK, 5 SPECIAL;
- ECC fields in the spare (ignored by a read-only reader).

The precise spare-area byte layout (which bytes hold the packed tags
versus ECC) varies between yaffs2 and yaffs2-oob variants and must be
verified empirically against `mkyaffs2image` output in Phase 1 of the
implementation, the same way JFFS2 CRC placement was verified.

### Object header (chunk 0, 512 bytes)

`yaffs_ObjectHeader`: `type` (u32), `parent_obj_id` (u32),
reserved(2), `name` (256 bytes, null-terminated), mode/uid/gid/times
(yst_*), `file_size` (u32), plus `equiv_id` (hardlink target),
`rdev` (device), alias id, etc. File data in chunk 0 starts at offset
512; subsequent chunks hold 2048 bytes each.

### Semantics

- **Root** = object id 1, a DIRECTORY with parent 1.
- **Directories:** no separate dirent nodes — an object belongs to
  `parent_obj_id`; the parent's listing is all objects with that parent.
  Deleted objects are renamed `"unlinked"`/`"deleted"` or re-pointed;
  `".."` entries are encoded via parent ids, not stored.
- **Files:** data chunks in `chunk_id` order, 2048 bytes each,
  `n_bytes` truncates the last chunk; holes (missing chunk ids) read as
  zeros.
- **Hardlinks:** HARDLINK object references `equiv_id`.
- **Symlinks:** target string in the object's data.
- **Special:** FIFO/char/block devices via `rdev`.
- **Deletion/overwrites:** later `seq_number` for the same (obj_id,
  chunk_id) wins; scan-once grouping like JFFS2.

### Detection rule (heuristic, no magic number)

1. Image size is a multiple of 2112 (2048 + 64), or at least the first
   chunk fits;
2. chunk 0 of some object is a valid object header (type in 1..5,
   plausible `name`, `parent_obj_id` within the object count);
3. object id 1 exists and is a DIRECTORY with parent 1;
4. tags fields are consistent (obj_id ≠ 0, chunk_id monotonic per
   object).

This is weaker than a magic number; require the root-object check to keep
the false-positive rate negligible, and run it after the stronger
magic-based filesystem checks in `FilesystemDetector`.

## Scope — single phase (detection + mount)

### Implementation map

1. `filesystem/yaffs2/Yaffs2Chunk.java` — chunk reader: 2048+64 records,
   tag parsing (both candidate spare layouts, decided by fixture
   verification), ECC ignored.
2. `filesystem/yaffs2/Yaffs2FileSystemImpl.java` — scan all chunks; per
   obj_id: header (newest seq) + data chunk map (highest seq per chunk_id);
   build tree from parent_obj_id; resolve deletions (renamed-deleted
   objects hidden); entries incl. hardlinks/symlinks/devices; lazy file
   `InputStream` (2048-byte chunk reads, zero-filled holes).
3. Integration: `FilesystemDetector` (heuristic after magic-based checks),
   `FileSystem.FileSystemType.YAFFS2` + subtype, `FileSystemMount`,
   `SaffronProbe.Kind.FILESYSTEM_YAFFS2`, extension `.yaffs2` → RAW.
4. Hardening: obj_id/chunk_id bounds, name traversal filtering, tag
   plausibility guards, chunk-count caps from image size.

### Tests

- `Yaffs2DetectionTest`: fixture detected; random/truncated/size-not-
  multiple-of-2112 rejected; missing root object rejected.
- `Yaffs2FileSystemTest`: round-trip (nested dirs, empty file, symlink,
  hardlink, 1MB file, file with a hole); walk; metadata.
- `Yaffs2SecurityTest`: newest-seq-wins for header and data chunks;
  deleted-object hiding; absurd n_bytes; traversal names; fuzz (bit flips
  in tags).
- `SaffronProbeTest` additions + wild-corpus hook (`test-corpus/yaffs2/`).

## Fixture Strategy

`mkyaffs2image` from the yaffs2 reference utilities (built from source in
a Docker container; not packaged in Debian). Committed fixtures +
generator script under `src/test/resources/yaffs2/`. Tests never shell
out.

Wild images: YAFFS2 appears in older Android boot/recovery images and
industrial firmware; the wild-corpus hook accepts real dumps when
available.

## Risks

| Risk | Mitigation |
|------|------------|
| Spare-area layout variants (yaffs2 vs oob) | Verify against mkyaffs2image output; support the standard variant first |
| No magic: heuristic detection | Strong root-object check + ordering after magic-based FS checks |
| `mkyaffs2image` not packaged | Docker build from source (documented script) |
| Endianness (tool writes host order) | LE only in practice; assert and document |

## Estimated Effort

~1–2 weeks including fixture generation and tests. Reuses the JFFS2
scan-and-resolve pattern already proven in Saffron.

## Open Questions

1. Support only the standard `mkyaffs2image` spare layout in v1 (no raw
   NAND OOB)? Recommend yes.
2. Hidden-object semantics: rename-deleted objects to
   `"unlinked"`/`"deleted"` and hide — or expose them as entries?
   Recommend hide (matches kernel behaviour).
3. `.yaffs2` extension fallback → RAW (recommend yes, parity with
   `.jffs2`).
