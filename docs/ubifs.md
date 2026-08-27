# UBI / UBIFS Filesystem Support

Saffron supports detection of UBI containers, read-only UBI attach (volume
mapping), and read-only mounting of cleanly-committed UBIFS filesystems —
the stack used by modern routers (OpenWrt NAND targets), modems, and
embedded Linux devices.

## Layers

### UBI (Unsorted Block Images)

UBI is the wear-levelling volume container under UBIFS. All UBI fields are
big-endian. Every PEB (physical erase block) starts with a 64-byte
erase-counter header (`"UBI#"`, version 1, CRC over bytes 0..59); the VID
header (`"UBI!"`) sits at the EC header's `vid_hdr_offset` (typically 512
or 2048), and user data starts at `data_offset`. The layout volume
(volume id `0x7FFFEFFF`) holds the volume table: 172-byte records
(reserved PEBs, alignment, data pad, type, name, flags, CRC).

Attach (`UbiSuperblock.attach`) infers the PEB size (2048..1 MiB, power of
two; a truncated final PEB is tolerated), validates every non-erased EC
header, resolves the LEB-to-PEB mapping (the highest `sqnum` wins per
(volume, LEB)), and parses the volume table. `UbiVolumeRegion` exposes each
volume as a `DiskRegion`.

Verified by `UbifsDetectionTest.detectsWildUbiContainers` and the wild
corpus test (attach + volume mounting).

### UBIFS

UBIFS stores everything as little-endian nodes with a 24-byte common
header: magic `0x06101831`, crc (the mtd-utils/kernel CRC-32 variant —
**no final XOR**), sqnum, len, node type. Layout: superblock node at LEB 0,
master nodes at LEB 1/2 (newest `cmt_no` wins), then the log and the main
area. The index is a B-tree rooted at the master node's `root_lnum/offs`;
index branches reference nodes by (lnum, offs, len).

Mount (`UbifsFileSystemImpl`):
1. validates the superblock node (format version 4/5, geometry);
2. rejects encrypted/authenticated images (no key material);
3. reads the newest master node; rejects **dirty** images (uncleanly
   unmounted — journal replay is not implemented);
4. walks the index tree, collecting inode, dent, and data nodes;
5. builds the entry tree; files are served through a lazy stream over
   data nodes (block size 4096) with zlib/LZO/ZSTD/none decompression and
   inline-data support.

Verified by `UbifsFileSystemTest` (round-trip of all four compressors,
symlinks, hardlinks, sparse files, metadata) and `UbifsSecurityTest`
(dirty-master rejection, encryption rejection, garbage rejection).

## Format facts verified against the reference toolchain

- The node header CRC covers the whole node (`crc32(init=0xFFFFFFFF,
  node+8, len-8)`) with the mtd-utils CRC variant that skips the final
  XOR — equivalent to `java.util.zip.CRC32 ^ 0xFFFFFFFF`.
- The vtbl record is **172 bytes**, not 132 (128-byte name field).
- EC header field offsets: ec@8, vid_hdr_offset@16, data_offset@20,
  image_seq@24, hdr_crc@60.
- Inline file data lives at offset 160 of the inode node and is stored
  raw when compression does not help, regardless of the inode's
  `compr_type`.
- Index nodes with a single child are legal (small filesystems).
- `mkfs.ubifs` requires ≥ 17 LEBs and LEB sizes that are multiples of the
  min-I/O size; `ubinize` cannot produce PEBs below 2048 (the 2048-byte-PEB
  geometry is covered by the wild `fruits.ubi`).

## Hardening

- Every node read is bounds-checked; node lengths are capped; index
  fanouts are validated; decompression output is bounded by the UBIFS
  block size.
- Dirty images, encrypted/authenticated images, and unsupported format
  versions fail with clear `IOException`s.
- Entry names with path separators are dropped; walks are cycle-guarded;
  symlink resolution is hop-limited.
- Partial/corrupt vtbl records are skipped rather than failing the attach.

## Corpus

- **Wild** (`src/test/resources/ubi/wild/`, 10 images, licenses in the
  adjacent `LICENSE-*.txt`): unblob (zlib/LZO/ZSTD volumes + two UBI
  containers incl. a truncated one), fact_extractor, a HiSilicon STB data
  volume, a Broadcom bcm53xx carved UBI, a Quectel modem UBI data volume,
  and a Linux driver-tutorial image.
- **Synthetic** (`src/test/resources/ubifs/synthetic/`, 13 images, see its
  README) plus `src/test/resources/ubifs/fixtures/` (round-trip fixtures):
  four compressors, five LEB geometries, empty/single-file trees, and
  two/three-volume UBI containers including a truncated one.

## Known limitations

- Journal replay for dirty images is not implemented (rejected with a
  clear message).
- Encrypted/authenticated images are rejected.
- Only the simple key format is supported (all images written by
  mkfs.ubifs use it).

## Key files

| File | Purpose |
|------|---------|
| `src/main/java/io/spicelabs/saffron/filesystem/ubi/UbiNode.java` | UBI headers, vtbl, CRC |
| `src/main/java/io/spicelabs/saffron/filesystem/ubi/UbiSuperblock.java` | detection + attach |
| `src/main/java/io/spicelabs/saffron/filesystem/ubi/UbiVolumeRegion.java` | volume DiskRegion |
| `src/main/java/io/spicelabs/saffron/filesystem/ubifs/UbifsNode.java` | node constants, keys, R5 hash |
| `src/main/java/io/spicelabs/saffron/filesystem/ubifs/UbifsSuperblock.java` | superblock detection |
| `src/main/java/io/spicelabs/saffron/filesystem/ubifs/UbifsFileSystemImpl.java` | read-only mount |
| `src/test/java/io/spicelabs/saffron/filesystem/ubifs/UbifsDetectionTest.java` | detection tests |
| `src/test/java/io/spicelabs/saffron/filesystem/ubifs/UbifsFileSystemTest.java` | round-trip tests |
| `src/test/java/io/spicelabs/saffron/filesystem/ubifs/UbifsSecurityTest.java` | rejection tests |
| `src/test/java/io/spicelabs/saffron/filesystem/ubifs/UbifsWildCorpusTest.java` | wild + synthetic corpus test |
| `src/test/resources/ubi/wild/` | committed wild images + licenses |
| `src/test/resources/ubifs/synthetic/` + `fixtures/` | committed synthetic images |
| `src/test/resources/ubifs/generate-synthetic.sh` | one-time Docker generator |
