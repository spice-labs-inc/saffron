# UBI/UBIFS Support — LLM Summary

## State
All three phases implemented and green: UBI detection+attach, UBIFS
detection, UBIFS clean-image mount. 28 new tests; full non-corpus suite
955 tests, 0 failures. Wild corpus: 10 images. Synthetic: 13 + 5 fixtures.

## Verified format facts (kernel headers + mtd-utils source + real images)
- UBI (BE): EC header 64B at each PEB start: magic 0x55424923 "UBI#",
  version 1, ec@8, vid_hdr_offset@16 (NOT 12!), data_offset@20,
  image_seq@24, hdr_crc@60 = crc32 over bytes 0..59.
  VID header at vid_hdr_offset: magic 0x55424921 "UBI!", vol_type,
  vol_id@8, lnum@12, sqnum@32; erased PEBs = all-0xFF → skip.
  vtbl record = 172 BYTES (name[128]: reserved@0 alignment@4 data_pad@8
  type@12 upd@13 name_len@14 name@16 flags@144 padding@145 crc@168 over
  bytes 0..167). Layout volume id 0x7FFFEFFF.
- UBI/UBIFS crc32 = mtd_crc32: standard reflected CRC-32, init 0xFFFFFFFF,
  NO final XOR = java CRC32 ^ 0xFFFFFFFF. (NOT plain zlib!)
- UBIFS (LE): common header 24B: magic 0x06101831, crc@4, sqnum@8, len@16
  (UNALIGNED node length!), node_type@20. Header CRC covers the WHOLE node:
  crc32(init, node+8, len-8) — requires the full node buffer.
  Node types: INO 0 DATA 1 DENT 2 XENT 3 TRUN 4 PAD 5 SB 6 MST 7 REF 8
  IDX 9 CS 10 ORPH 11.
  SB node: flags@28 min_io@32 leb_size@36 leb_cnt@40 fanout@72 fmt@80
  default_compr@84 uuid@94. MST: highest_inum@24 cmt_no@32 flags@40
  log_lnum@44 root_lnum@48 root_offs@52 root_len@56. DIRTY flag = bit 0.
  INO: key@24 size@48 nlink@92 uid@96 gid@100 mode@104 data_len@112
  compr_type@132, inline data @160 (NOT 136 — ch24+key16+creat8+size8+
  times24+nsec12+nlink4+uid4+gid4+mode4+flags4+datalen4+xattr12+compr2+
  pad26 = 160).
  DATA: key@24 size@40 compr@44 data@48. DENT: key@24 inum@40 type@49
  nlen@50 name@56. IDX: child_cnt@24 level@26 branches@28 (20B each:
  lnum, offs, len, key[8]).
  Keys: word0 = ino/pino; word1 = (key_type << 29) | block_or_R5hash.
  R5 hash: a += (b<<4)&0xff0; a += (b>>4)&0xf; a *= 11 per byte.
- Index nodes with ONE child are legal (small FS).
- Inline data stored RAW when compression doesn't help (inline.length ==
  expected size) regardless of compr_type.
- mkfs.ubifs: min 17 LEBs; LEB must be multiple of min-io; ubinize min
  PEB 2048 (cannot make 2048-byte-PEB UBI with ubinize).
- Wild tiny containers (unblob fruits.ubi) use 2048-byte PEBs and may
  contain raw-text volumes + partial vtbl records → attach must skip bad
  vtbl records and tolerate non-UBIFS volumes.

## Key classes
- UbiNode: EcHdr/VidHdr/VtblRecord parsers + crc32 (XOR variant).
- UbiSuperblock.attach: PEB-size inference (2048..1MiB, truncated final
  PEB ok), EC validation per PEB, sqnum-max LEB mapping, vtbl parse
  (skip bad records), UbiVolume records.
- UbiVolumeRegion: DiskRegion over volume (lnum→peb, dataOffset skip,
  unmapped LEBs read zeros).
- UbifsNode: parseHeader(buf, off, avail) full-node CRC, key helpers,
  r5Hash.
- UbifsSuperblock.read: bounded 4096 read (prefix probes!), SB node
  validation, fmt 4/5, fanout/leb sanity.
- UbifsFileSystemImpl: mount → SB → MST (newest cmt) → DIRTY reject →
  index walk (IDX/INO/DENT/DATA nodes) → tree → lazy streams
  (zlib/lzo/zstd/none + inline). Rejects FLG_ENCRYPTION/AUTH.
- Integration: FilesystemDetector (UBIFS after YAFFS2, UBI after),
  FileSystemType.UBIFS + UBI, FileSystemMount (UBIFS direct; UBI mounts
  first UBIFS volume), SaffronProbe FILESYSTEM_UBIFS + CONTAINER_UBI,
  .ubifs/.ubi → RAW. SmokeTest 15→17.

## Tests (28 new)
- UbifsDetectionTest (16): 5 wild volumes, 5 wild containers, 4 synthetic
  volumes, synthetic container, random rejection.
- UbifsFileSystemTest (6): round-trip ×4 compressors (sha256 of 160KB
  compressed + sparse-with-hole files), symlink, walk, metadata.
- UbifsSecurityTest (3): dirty-master reject, encryption reject, garbage.
- UbifsWildCorpusTest (1): 10 wild + 13 synthetic — attach containers,
  mount+walk UBIFS volumes.
- SaffronProbeTest +3: ubifs volume, ubi container, and guards that the
  detector chain still works on small prefixes (bounded reads!).

## Gotchas learned
- Prefix probes: UbifsSuperblock.read must bound its read to the region
  size — an over-read throws and aborts the WHOLE detector chain
  (broke xfs/apfs/hfsplus probe tests).
- Full-node CRC (not header-only) and no-final-XOR CRC variant.
- EC header offsets: vid@16 not 12 (my first guess was wrong, caught by
  tests against ubinize output).
- vtbl = 172 bytes not 132.
- docker run heredoc stdin needs -i (again).

## Honest gaps
- Dirty-image journal replay: NOT implemented (clear rejection).
- Encrypted/authenticated: rejected.
- Wild count: 10 (standalone UBIFS images are rare; most live inside UBI
  containers >7MB; volume dumps from quectel/fimix8/huawei images were
  >7MB and excluded).
