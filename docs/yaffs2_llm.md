# YAFFS2 Support — LLM Summary

## State
Implemented, tested, green. Detection + read-only mount. 41 committed
corpus images (21 wild + 20 synthetic) all detected/mounted/walked.

## Verified format facts (reference mkyaffs2image source + 41 images)
- Chunk = page data + spare; image size % (page+spare) == 0. Tags = first
  16 spare bytes, two encodings:
  - LE: (seq_number, obj_id, chunk_id, n_bytes)
  - BE: (n_bytes, seq_number, obj_id, chunk_id)
  - Fields may be stored plain OR as value<<16|serial (mkyaffs2image with
    serial); normalizeField(): if raw >= 1<<16 and low16==0 → raw>>16.
  - Header chunks: n_bytes = 0xffff (marker). Erased chunks: cid =
    0xffffffff → SKIP (not fatal).
- Object header 512 bytes: type(4) parent(4) sum(2) name[256]@10, then
  TWO PAD BYTES (name ends at 266, not 4-aligned), so mode@268 uid@272
  gid@276 mtime@284 file_size@292 equiv_id@296 alias[160]@300 rdev@460.
  Verified against tool output — naive packed-struct offsets are 2 bytes
  off.
- Old-style headers (binwalk-era) leave mode/fileSize garbage: NEVER
  trust header file_size; compute size from data chunk extents
  ((cid-1)*page + n_bytes, max).
- obj types: 1 file, 2 symlink, 3 dir, 4 hardlink, 5 special.
  Deleted objects: parent == 3 (unlinked) or 4 (deleted) → hide.
  Root obj 1 may be IMPLIED (no header) in old-tool images.
- Data chunks: start at chunk start (no header), last chunk truncated to
  n_bytes; holes (missing cids) read as zeros.
- mkyaffs2image: empty dir → 0-byte image; serial=1 hardcoded (shifted
  encoding); 'convert' arg → BE; page/spare are compile-time #defines
  (build 4 variants by sed-patching).

## Key classes
- Yaffs2Node: constants, decodeTag (endian + serial normalization),
  Header record, normalizeId.
- Yaffs2Superblock: geometry scan (PAGES × SPARES × 4 endian combos),
  requires ≥1 header + ≥1 root child (or ≥2 headers); skip erased.
- Yaffs2FileSystemImpl: full scan (highest seq per header/chunk),
  children map, hardlink targets, lazy per-chunk InputStream with hole
  zero-fill, symlink alias w/ data fallback, special rdev decode.
- Integration: FilesystemDetector (after cramfs), FileSystemType.YAFFS2 +
  Yaffs2FileSystem, FileSystemMount, SaffronProbe FILESYSTEM_YAFFS2,
  .yaffs2 → RAW. SmokeTest enum count 14→15.

## Tests (95 green incl. probe/smoke)
- Yaffs2DetectionTest (24): 21 wild samples + random/unaligned/tiny
  rejection.
- Yaffs2SecurityTest (6): crafted tree round-trip (symlink/hardlink/
  fifo/holey), header seq-wins, deleted hiding, traversal, implied root,
  garbage mount failure.
- Yaffs2WildCorpusTest: 41 images detect+mount+walk.
- SaffronProbeTest: yaffs2 prefix → FILESYSTEM_YAFFS2.

## Gotchas learned
- docker run heredoc stdin needs `-i`.
- mkyaffs2image needs a u8/u32 typedef shim (old source) + python3 in the
  container; device nodes via mknod inside the container as root, then
  chown output.
- NUC972 raw-flash images use an exotic OOB layout (name bytes appear in
  the spare area) — excluded from v1, documented limitation.
- The 2-byte pad after the name field: struct is not packed; alignment
  matters.
