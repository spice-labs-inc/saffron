# YAFFS2 Scope — LLM Summary

## Shape
Single phase (detection + mount), ~1-2 weeks. Reuses the JFFS2
scan-and-resolve pattern.

## Format facts
- Image = linear chunks of 2048 data + 64 spare (mkyaffs2image layout).
  Raw NAND vendor OOB out of scope (same call as JFFS2).
- Tags in spare: seq_number u32, obj_id u32 (0=erased), chunk_id u32
  (0=header, >=1=data index), n_bytes u32 (valid bytes of last chunk);
  extra tags carry obj_type (0 UNKNOWN, 1 FILE, 2 SYMLINK, 3 DIRECTORY,
  4 HARDLINK, 5 SPECIAL). Exact spare byte placement MUST be verified
  against mkyaffs2image output during implementation (like JFFS2 CRC
  verification). ECC fields ignored.
- Object header = chunk 0, 512B: type u32, parent_obj_id u32, reserved,
  name[256], yst_mode/uid/gid/times, file_size u32, equiv_id (hardlink),
  rdev. File data in chunk 0 begins at offset 512.
- Root = obj_id 1 DIRECTORY parent 1. Directories = grouping by
  parent_obj_id (no dirent nodes). Deleted objects renamed
  "unlinked"/"deleted" → hide. Newest seq_number wins per (obj_id,
  chunk_id). Holes read zeros.

## Detection (heuristic — NO magic)
size % 2112 == 0 (or first chunk fits) + valid header type 1..5 +
root object (id 1, DIRECTORY, parent 1) + plausible tags. Run AFTER
magic-based FS checks in FilesystemDetector.

## Implementation map
Yaffs2Chunk reader (tag parsing, ECC skipped) + Yaffs2FileSystemImpl
(scan → per-obj header/data maps → tree by parent_obj_id → lazy
chunk-stream files) + FilesystemDetector + FileSystemType.YAFFS2 +
SaffronProbe FILESYSTEM_YAFFS2 + .yaffs2 → RAW.

## Tests
Detection pos/neg (size multiple, root missing); round-trip (dirs,
empty, symlink, hardlink, 1MB, hole); security (seq-wins, deletion,
bounds, traversal, tag fuzz); SaffronProbe; wild hook
test-corpus/yaffs2/.

## Fixtures
mkyaffs2image built from source in Docker (not in Debian). Committed
fixtures + generator script under src/test/resources/yaffs2/.

## Risks
Spare-layout variants (verify first); heuristic false positives (root
check + ordering); tool availability (Docker build).

## Open questions
1. Only standard mkyaffs2image spare layout in v1 (rec: yes).
2. Hide renamed-deleted objects (rec: yes).
3. .yaffs2 → RAW fallback (rec: yes).
