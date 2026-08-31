# JFFS2 Support — LLM Summary

## State
Implemented and tested. Detection + read-only mount. No shell-out in tests;
fixtures committed. Wild-image corpus committed: the LeapFrog Didj handheld
root filesystem (erootfs.jffs2, archive.org item "erootfs").

## Format facts (verified against real mkfs.jffs2 output)
- No superblock. Image = stream of 12-byte-header nodes, little-endian.
- Common header: magic u16 = 0x1985, nodetype u16, totlen u32, hdr_crc u32.
- Node types (with compat bits): DIRENT 0xe001, INODE 0xe002, CLEANMARKER
  0x2003, PADDING 0x2004, SUMMARY 0x2006, XATTR 0xe008, XREF 0xe009.
- **totlen is NOT 4-aligned** in real images (e.g. 47 for a 7-byte name);
  the node body is padded to the next 4-byte boundary with 0xFF and the scan
  advances by `alignUp(totlen, 4)`. This was a real bug found by fixtures.
- CRC = reflected CRC-32 (poly 0xedb88320), init 0, NO final XOR.
  - hdr_crc = crc(first 8 bytes). Property: crc(12-byte header) == 0.
  - inode node_crc = crc(node bytes 0..59). data_crc = crc(compressed data).
  - dirent node_crc = crc(node bytes 0..31). name_crc = crc(name bytes).
- Inode body after common header (56 bytes): ino, version, mode, uid, gid,
  isize, atime, mtime, ctime, offset, csize, dsize, compr, usercompr, flags,
  data_crc, node_crc; data at node offset 68.
- Dirent body (28 bytes): pino, version, ino, mctime, nsize, type (DT_*),
  unused, node_crc, name_crc; name at node offset 40.
- Compression ids: NONE 0, ZERO 1, RTIME 2, RUBINMIPS 3 (rejected), COPY 4,
  DYNRUBIN 5 (rejected), ZLIB 6, LZO 7.
- Root inode = 1. Deleted dirent = latest version has ino 0.
- mkfs.jffs2 (Debian mtd-utils 2.1.5): `--compression-mode` accepts only
  none/priority/size; force a compressor via `-x`/`-X` (e.g. zlib-only =
  `-m priority -x lzo -x rtime`).

## Key classes
- `filesystem/jffs2/Jffs2Node.java` — constants, records (InodeFragment,
  InodeMeta, Dirent), `crc32(byte[],off,len)`, `isJffs2Magic(ByteBuffer)`.
- `filesystem/jffs2/Jffs2Superblock.java` — validates first node header
  (magic, known type, totlen>=12 within region, hdr_crc). blockSize()=4096.
- `filesystem/jffs2/Jffs2FileSystemImpl.java` — scan all nodes; skip bad-CRC
  nodes (kernel behaviour); per-(pino,name) dirent resolution (highest
  version; ino=0 = deleted); per-(ino,offset) fragment resolution; lazy
  fragment InputStream (bounded memory); symlink target cap 4096; entry
  names with `/`,`\`,`..`,NUL dropped; dirent to inode with no valid inode
  node hidden; per-fragment dsize cap 1 MiB.
- Integration: `FilesystemDetector` checks JFFS2 FIRST (strongest signal,
  avoids HFS+/ext coincidence at offset 1024); `FileSystem.FileSystemType.JFFS2`
  + `FileSystem.Jffs2FileSystem`; `FileSystemMount` mount + isSupported;
  `SaffronProbe.Kind.FILESYSTEM_JFFS2`; `DiskFormat.detectByExtension` and
  `SaffronProbe.byExtension` map `.jffs2` → RAW/DISK_RAW (like .squashfs).

## Tests
- Jffs2DetectionTest (13): fixtures per compression, empty/random/truncated/
  old-magic/unknown-type/bad-crc rejection, unaligned totlen ACCEPTED,
  lone cleanmarker.
- Jffs2FileSystemTest (11): full round-trip incl. sha256 of bulk files,
  symlink (via root().find, since resolve() follows links), hard links,
  walk paths, root listing, metadata.
- Jffs2SecurityTest (13): dirent version wins, deletion, hard links, sparse
  gaps, fragment overwrite, corrupt hdr/node-crc skip, zlib/zero/rtime
  decompress, unsupported compr IOException, truncated node mount failure,
  path traversal.
- Jffs2WildCorpusTest: always runs — mounts/walks the committed wild image,
  the committed mkfs.jffs2 fixtures, and any extras in test-corpus/jffs2/.
  The zero-byte detection-negative fixture empty.jffs2 is excluded (covered
  by Jffs2DetectionTest.rejectsEmptyImage).
- SaffronProbeTest: jffs2 magic → FILESYSTEM_JFFS2; .jffs2 name → DISK_RAW.
- Fixtures: src/test/resources/jffs2/fixtures/ (6 images), generator script
  committed; generated via Docker debian:bookworm + mtd-utils.

## Gotchas learned
- ByteBuffer.wrap(array, offset, length) sets position=offset; absolute
  get(i) still indexes array[i] (whole array!). Use fresh per-read buffers
  from DiskRegion (the impl does).
- JFFS2 logical file size may exceed image size (compression, esp. ZERO
  compr with no payload) — do not bound isize by region size; bound
  allocations per-fragment instead.
- fs.resolve() follows symlinks by design (matches squashfs); test symlink
  entries via parent.find().

## Parallel corpus tests (option 1)
- src/test/resources/junit-platform.properties: parallel.enabled=true,
  mode.default=same_thread, fixed pool 4.
- @Execution(CONCURRENT) on CorpusFileVerificationTest,
  CorpusFullVerificationTest, PerFilesystemVerificationTest (their
  @TestFactory DynamicTests now run 4-wide; shared state was already
  synchronized/immutable).
- CorpusValidationTest.corpus_allImageChecksumsMatch is a single @Test
  hashing ~100 GB — not parallelizable without @TestFactory conversion.
