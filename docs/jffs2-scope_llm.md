# JFFS2 Scope — LLM Summary

## What this document is

A scoping proposal for adding JFFS2 (Journalling Flash File System v2) support to
Saffron. It covers two scope options: detection-only and full read-only mount.
The recommendation is to implement detection first.

## Key facts

- JFFS2 is a log-structured flash filesystem with no superblock.
- An image is a sequence of self-describing nodes.
- Common node header: `magic` (2 bytes, `0x1985` LE), `nodetype` (2 bytes),
  `totlen` (4 bytes), `hdr_crc` (4 bytes) = 12 bytes total.
- Relevant node types: `DIRENT 0x0001`, `INODE 0x0002`, `PADDING 0x0003`,
  `CHECKPOINT 0x0004` (obsolete), `SUMMARY 0x0006`, `XATTR 0x0008`,
  `XREF 0x0009`, `CLEANMARKER 0x0200`.
- Inode node layout: common header (12) + `ino, version, mode, uid, gid, isize,
  atime, mtime, ctime, offset, csize, dsize, compr, usercompr, flags, data_crc,
  node_crc` = 68 bytes before the compressed data payload.
- Dirent node layout: common header (12) + `pino, version, ino, mctime, nsize,
  type, unused[2], node_crc, name_crc` = 40 bytes before the name.
- Compression algorithms: `NONE 0x00`, `ZERO 0x01`, `RTIME 0x02`,
  `RUBINMIPS 0x03` (obsolete), `COPY 0x04`, `DYNRUBIN 0x05` (obsolete),
  `ZLIB 0x06`, `LZO 0x07`.
- `mkfs.jffs2` is not installed locally; fixtures must be generated once via
  Docker with `mtd-utils` and committed.

## Scope options

### Option A — Detection only (recommended)

- Add `Jffs2Detector` / `Jffs2Superblock` helper.
- Add `FileSystemType.JFFS2` and `Kind.FILESYSTEM_JFFS2`.
- Wire detection into `FilesystemDetector`, `SaffronProbe`, and
  `FileSystemMount` as a detected-but-not-mounted type (like swap).
- Detection rule: first 12 bytes have magic `0x1985`, a known nodetype,
  `totlen >= 12`, `totlen % 4 == 0`, and (optional) valid `hdr_crc`.
- No change to `FileSystem` sealed permits is strictly required, but adding
  the type enum is.
- Effort: ~1–2 days.

### Option B — Detection + read-only mount

- Implement `Jffs2FileSystemImpl` that scans all nodes, resolves latest
  versions, builds directory/inode trees, and reconstructs file fragments.
- Implement decompressors for `NONE`, `ZERO`, `COPY`, `ZLIB`, `LZO`, `RTIME`.
  Reject `RUBIN*` with a clear `IOException`.
- Add `Jffs2FileSystem` to `FileSystem` sealed permits and `FileSystemMount`.
- Effort: ~1–2 weeks.

## Implementation map for Option A

1. `src/main/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2Detector.java`
   - `isJffs2Magic(ByteBuffer)` and `detect(DiskRegion)` methods.
2. `src/main/java/io/spicelabs/saffron/filesystem/FilesystemDetector.java`
   - Add `tryDetectJffs2` before `return Optional.empty()`.
3. `src/main/java/io/spicelabs/saffron/fs/FileSystem.java`
   - Add `JFFS2` to `FileSystemType` enum.
   - Add `Jffs2FileSystem` non-sealed interface to the sealed permits.
4. `src/main/java/io/spicelabs/saffron/fs/FileSystemMount.java`
   - In `mount` switch, add `case JFFS2 -> throw new UnsupportedOperationException(...)` for now.
   - Or record it as detected-but-not-mounted in `mountAllWithDetected`.
5. `src/main/java/io/spicelabs/saffron/SaffronProbe.java`
   - Add `FILESYSTEM_JFFS2` to `Kind` enum.
   - Map `FileSystemType.JFFS2` in `toFilesystemKind`.
6. `src/test/java/io/spicelabs/saffron/filesystem/jffs2/Jffs2DetectionTest.java`
   - Positive fixture tests, negative/random tests, CRC tests.
7. `src/test/resources/jffs2/generate-fixtures.sh`
   - Docker-based `mkfs.jffs2` fixture generator.

## Test checklist

- `Jffs2DetectionTest.detectsMinimalJffs2Image`
- `Jffs2DetectionTest.rejectsRandomBytes`
- `Jffs2DetectionTest.rejectsTruncatedHeader`
- `Jffs2DetectionTest.rejectsWrongMagic`
- `Jffs2DetectionTest.rejectsUnknownNodetype`
- `Jffs2DetectionTest.rejectsUnalignedTotlen`
- `Jffs2DetectionTest.rejectsBadHdrCrc`
- `SaffronProbeTest.detectsJffs2FromPrefix`
- `SaffronProbeTest.jffs2DoesNotLookLikeDiskRaw` (no GPT/MBR fallback)
- Add to `SaffronProbeTest` parity property if fixture corpus is available.

## Design decisions needing approval

1. Should JFFS2 be mountable in the first phase, or detection-only?
   - Recommendation: detection-only.
2. Should we validate `hdr_crc` in detection, or only magic + nodetype?
   - Recommendation: validate all three (magic, nodetype, totlen) and `hdr_crc`.
3. Should `.jffs2` extension be a fallback in `SaffronProbe.byExtension`?
   - Recommendation: not needed; JFFS2 images rarely use that extension.
4. Should unsupported compression in a real image cause mount to fail or
   return partial data?
   - Recommendation: fail with a clear `IOException`; partial data is unsafe.

## References

- Linux kernel: `fs/jffs2/jffs2.h` and `fs/jffs2/nodelist.h`.
- `docs/jffs2-scope.md` — the human-readable version of this scope.
- Existing Saffron patterns: `SquashfsSuperblock`, `FilesystemDetector`,
  `SaffronProbe`, `FileSystemMount`.
