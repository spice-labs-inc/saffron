# YAFFS2 Filesystem Support

Saffron supports read-only mounting of YAFFS2 (Yet Another Flash File
System 2), the log-structured NAND filesystem used by embedded and
industrial devices, older Android devices, and IP cameras.

## Detection

YAFFS2 has no magic number and no superblock. Detection infers the image
geometry and validates the object population:

1. The image size must be an exact multiple of `page + spare` for a
   candidate geometry (page ∈ {512..16384}, spare ∈ {16..512}).
2. Chunk tags (the first 16 spare bytes) must be plausible under one of
   the two tag encodings:
   - little-endian: `(seq_number, obj_id, chunk_id, n_bytes)`;
   - big-endian: `(n_bytes, seq_number, obj_id, chunk_id)`.
   Tag fields may be stored plain or as `value << 16 | serial`
   (`mkyaffs2image` with a serial number) — both are normalized.
3. At least one object header (chunk id 0) with a known type, and at
   least one entry whose parent is the root (the root object itself may
   be implied, as in images written by older tools).

Trailing erased chunks (tags `0xffffffff`) are skipped, not fatal.
Detection is wired into `FilesystemDetector` (after cramfs, before ext),
`SaffronProbe` (`FILESYSTEM_YAFFS2`), and the extension fallbacks
(`.yaffs2` opens as `RAW`). Verified by `Yaffs2DetectionTest` against 21
wild images and by `SaffronProbeTest.yaffs2Image_returnsFilesystemYaffs2`.

## Mounting

`FileSystemMount` mounts YAFFS2 through `Yaffs2FileSystemImpl` (a
`FileSystem.Yaffs2FileSystem`). Verified format facts (checked against the
reference `mkyaffs2image` source and 41 real/generated images):

- Chunk = page data + spare; chunk 0 of each object carries the 512-byte
  object header; data chunks start at the chunk start and the last chunk
  is truncated to `n_bytes`.
- The header fields after the name are 4-byte aligned: name ends at
  offset 266, two pad bytes follow, so mode/uid/gid/mtime/file_size/
  equiv_id/alias/rdev sit 2 bytes later than a naive packed struct would
  suggest (verified against `mkyaffs2image` output).
- Object types: file, symlink (target in `alias`, falling back to data),
  directory, hardlink (`equiv_id`), special (`rdev`).
- The directory tree is recovered by grouping headers by parent id;
  objects whose parent is the unlinked (3) or deleted (4) sentinel are
  hidden; higher sequence numbers win for the same chunk.
- Old-style headers (only type/parent/sum/name) leave later fields
  uninitialized; file sizes are therefore always computed from the data
  chunk extents, never trusted from the header.

Verified by `Yaffs2SecurityTest` (hand-crafted images: tree round-trip,
symlink/hardlink/special/holey files, seq-wins, deleted-object hiding,
path traversal, implied root) and `Yaffs2WildCorpusTest` (41 images mount
and walk on every run).

## Hardening

- Every chunk offset is bounds-checked; chunk ids are capped; erased
  chunks are skipped.
- Entry names with `/`, `\`, `..`, or NUL are dropped; directory walks
  are cycle-guarded; symlink resolution is hop-limited.
- File reads are lazy: one chunk at a time, holes read as zeros.

## Corpus

- **Wild** (`src/test/resources/yaffs2/wild/`, 21 images, licenses in the
  adjacent `LICENSE-*.txt`): binwalk, unblob (page/spare matrix), ofrak
  (BE/LE), fact_extractor (BE/LE), binaryanalysis-ng (empty dirs/files,
  hardlinks, dirs-with-files, mixed endianness), and a USTC embedded-lab
  device rootfs.
- **Synthetic** (`src/test/resources/yaffs2/synthetic/`, 20 images, see
  its README): built with the reference `mkyaffs2image` (GPL-2.0) for
  four geometries (2048/64, 4096/128, 1024/32, 8192/256), both endians,
  with devices, deep nesting, long names, hardlinks, symlinks, and
  multi-chunk files.

## Known limitations

- Raw NAND dumps with vendor OOB layouts (ECC in the spare area, page
  512 small-page devices) are not supported; the NUC972 sample images
  found during research use such a layout and are excluded pending
  further research.
- Detection requires at least one object header; an image with no
  objects at all (0 bytes) is not a filesystem and is rejected.

## Key files

| File | Purpose |
|------|---------|
| `src/main/java/io/spicelabs/saffron/filesystem/yaffs2/Yaffs2Node.java` | constants, tag decoding, header records |
| `src/main/java/io/spicelabs/saffron/filesystem/yaffs2/Yaffs2Superblock.java` | geometry + endianness detection |
| `src/main/java/io/spicelabs/saffron/filesystem/yaffs2/Yaffs2FileSystemImpl.java` | read-only mount |
| `src/test/java/io/spicelabs/saffron/filesystem/yaffs2/Yaffs2DetectionTest.java` | detection tests |
| `src/test/java/io/spicelabs/saffron/filesystem/yaffs2/Yaffs2SecurityTest.java` | hand-crafted + hardening tests |
| `src/test/java/io/spicelabs/saffron/filesystem/yaffs2/Yaffs2ImageWriter.java` | hand-crafted image builder |
| `src/test/java/io/spicelabs/saffron/filesystem/yaffs2/Yaffs2WildCorpusTest.java` | wild + synthetic corpus test |
| `src/test/resources/yaffs2/wild/` | committed wild images + licenses |
| `src/test/resources/yaffs2/synthetic/` | committed synthetic images |
| `src/test/resources/yaffs2/generate-synthetic.sh` | one-time Docker generator |
