# UBIFS/UBI Scope — LLM Summary

## Decision shape
Multi-phase, in the JFFS2 style: detection → UBI attach → UBIFS mount.
Largest FS effort yet; the recommendation is to do cramfs/YAFFS2 first and
UBIFS phases in order.

## Verified format facts (kernel headers)
- UBI: all BE. EC header 64B at each PEB start: magic 0x55424923 "UBI#",
  version=1, ec(8), vid_hdr_offset(4), data_offset(4), image_seq(4),
  hdr_crc = zlib-crc32(init 0xFFFFFFFF) over bytes 0..59.
  VID header at vid_hdr_offset (usually 512): magic 0x55424921 "UBI!",
  vol_type(1 dyn/2 static), vol_id, lnum, data_size, used_ebs, data_pad,
  data_crc, sqnum(u64; higher wins for duplicate LEBs; copy_flag+data_crc
  fallback). Layout volume id 0x7FFFEFFF holds vtbl (132B records:
  reserved_pebs, alignment, data_pad, vol_type, name_len, name[128],
  flags, crc) in 2 LEBs. Fastmap (magics 0x7B11D69F etc.) ignorable for RO.
- UBIFS: all LE. Common node header 24B: magic 0x06101831, crc, sqnum(u64),
  len, node_type, group_type. Node types 0-13: INO DATA DENT XENT TRUN PAD
  SB MST REF IDX CS ORPH AUTH SIG. SB node at LEB 0 (min_io_size, leb_size,
  leb_cnt, fanout, fmt_version 4/5, default_compr, uuid). MST node at
  LEB 1,2 (root_lnum/offs/len, log_lnum, cmt_no, DIRTY flag bit0).
  Keyed nodes carry key[]; simple key format: INO/DATA key = (type:3,
  ino:29); DENT key = (type:3, pino:29, R5-hash(name):29); key.c R5 hash
  ~30 lines. DATA node: size, compr_type (0 none,1 lzo,2 zlib,3 zstd),
  payload; block size 4096. DENT: inum, type, nlen, name (255 max).
  Saffron already has zlib/lzo/zstd decompressors.
- Root ino 1, first regular ino 64.

## Phases
1. **Detection** (~1-1.5 wk): EC+VID header validation; vtbl parse;
   UBIFS SB validation; FileSystemType.UBIFS + UBI; SaffronProbe
   CONTAINER_UBI/FILESYSTEM_UBIFS; extensions .ubi/.ubifs → RAW.
2. **UBI attach** (~1-2 wk): full PEB scan; newest-sqnum LEB mapping;
   UbiVolumeRegion implements DiskRegion.
3. **UBIFS mount** (~3-5 wk): SB → MST → (reject DIRTY first, replay
   later) → index B-tree walk → keyed node collection → entry tree →
   lazy decompression. Reject FLG_ENCRYPTION/FLG_AUTHENTICATION.

## Tests
Per phase: detection positives/negatives (ubinize/mkfs.ubifs fixtures);
attach round-trips (2 volumes, dup-LEB sqnum, copy_flag fallback);
mount round-trips (zlib/lzo/zstd/none, symlink/hardlink/sparse/empty,
fanout>1 multi-level index), fuzz (CRC/bounds/traversal), wild hooks
test-corpus/ubi/ + OpenWrt sysupgrade (real UBI) extraction at fixture
generation time (Docker only; tests never shell out).

## Open questions
1. Mount UBIFS inside OpenWrt FIT-wrapped UBI via BinaryContainerMount? (rec: yes)
2. Multi-volume reporting: per-volume FilesystemLocation vs container type? (rec: per-volume)
3. DIRTY master: reject-first vs replay-first? (rec: reject-first)
