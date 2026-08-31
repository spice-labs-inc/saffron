# cramfs Support — LLM Summary

## State
Implemented, tested, green. Detection + read-only mount, single phase.
LE + BE images. 25 new tests; full non-corpus suite 894 tests, 0 failures
(2 skips = wild-corpus hooks).

## Verified format facts (kernel fs/cramfs/inode.c + real mkfs.cramfs output)
- Superblock 76 bytes at 0: magic u32 0x28cd3d45 (LE) / 0x453dcd28 (BE
  image), size u32 (offset 4; valid with FLAG_FSID_V2=0x1), flags u32,
  signature "Compressed ROMFS" at 16 (WRONG_SIGNATURE flag 0x200 allows
  bad signature), fsid {crc, edition, blocks, files} at 32, name[16] at
  48, root inode at 64. Empty fs = root offset 0.
- Inode 12 bytes + namelen*4 name bytes. LE bitfields: w0 = mode(0-15) |
  uid(16-31); w1 = size(0-23) | gid(24-31); w2 = namelen(0-5) |
  offset(6-31, /4). BE images: mode = w0>>>16, size = w1>>>8,
  namelen = w2>>>26, offset = w2 & 0x3ffffff. Names zero-padded,
  max 252.
- Directories: SEQUENTIAL entries from dirInode.offset*4, bounded by
  dirInode.size bytes. NO chain, NO hard links, NO terminator entry.
  Each member inode's offset = its own data location.
- File blocks: 4096 each; pointer table (u32 per block) at
  inode.offset*4. Pointer value = ABSOLUTE byte offset one-past-block-end
  (NO <<2 shift; earlier scope doc was wrong), flags bit31 UNCOMPRESSED
  (raw block), bit30 DIRECT_PTR (unsupported → IOException). Hole =
  pointer == previous pointer. Block start = (i==0) ? table+4*maxblock :
  prev pointer. Guard: stored len must be 0..2*4096, within image.
- Symlink = file-like data (cap 4096). Devices: old_decode_dev(size).

## Key classes
- CramfsSuperblock: read(DiskRegion) → Optional; byte-order detection by
  magic; signature/flag/root/size validation; effectiveSize =
  FSID_V2 ? size : region size.
- CramfsFileSystemImpl: mount(VirtualDisk|DiskRegion); endian-aware u32;
  inodeAt with BE/LE bitfield decode; CramfsDirectory (sequential listing
  bounded by dir size, entry names stripped of NUL padding, traversal
  filtered); CramfsRegularFile (lazy InputStream: per-block
  hole/uncompressed/zlib, last-block partial inflate, len guards);
  CramfsSymlink (eager target read, relative-target resolution);
  CramfsSpecialFile (old_decode_dev); walk cycle-guard by path;
  metadata: version/name/edition/blockCount/fileCount/endian.
- Integration: FilesystemDetector (after JFFS2), FileSystemType.CRAMFS +
  FileSystem.CramfsFileSystem, FileSystemMount mount + isSupported,
  SaffronProbe FILESYSTEM_CRAMFS, .cramfs → RAW (DiskFormat +
  SaffronProbe.byExtension). SmokeTest enum count 13→14.

## Tests
- CramfsDetectionTest (8): LE/BE/empty fixtures, random/truncated,
  magic-without-signature, WRONG_SIGNATURE accept, non-dir root reject.
- CramfsFileSystemTest (7): round-trip incl sha256 of 90KB compressed
  file + 104KB sparse-with-hole, both endians; symlink via root().find;
  walk paths; empty fs; resolve missing; metadata.
- CramfsSecurityTest (8): crafted images — compressed/hole/uncompressed
  blocks, pointer beyond image, oversized stored block (>8192),
  unsupported flags, traversal name, FIFO entry.
- CramfsWildCorpusTest: test-corpus/cramfs/ hook (skipped by default).
- SaffronProbeTest: cramfsMagic → FILESYSTEM_CRAMFS.

## Gotchas learned
- Bitfield packing flips between LE/BE images (low-bits vs high-bits
  first); cramfsswap byte-swaps words but REPACKS inode bitfields
  high-first — a naive whole-file word swap produces a nonstandard layout.
- Pointer values are absolute offsets (one-past-end), NOT shifted — the
  <<2 in the kernel applies only to DIRECT_PTR entries. Scope doc claim
  corrected during implementation (empirical verification, like JFFS2).
- Directory size bounds the entry walk; there is no chain.
- Crafted-image tests: remember name padding (12+namelen*4), namelen bits
  live in the same word as offset, and the superblock size must be
  re-patched after appending data.
- Kernel accepts the superblock at offset 512 too (boot-sector prefix);
  Saffron supports both, plus SHIFTED_ROOT_OFFSET.
- OpenRG extension ("cramfs-lzma"): flags bits 11..13 = block size
  (4096<<v), bits 14..15 = comp method (0 none, 1 zlib, 2 LZMA). Bit 11
  collides with mainline EXT_BLOCK_POINTERS (0x800); extension active
  only when comp-method bits or higher blksz bits are set. Detection/
  mount/walk supported; LZMA/non-4096 content reads rejected with clear
  IOException.

## Corpus
- wild/: 10 real cramfs images (util-linux BE+LE, e2fsprogs blkid,
  binwalk, dissect.cramfs x3 incl. holes + real device web fs,
  fact_extractor, Hisilicon IP-cam ko.cramfs, Xiaomi Xiaofang rootfs),
  each with .license.txt. All mount+walk.
- synthetic/: 23 images from 3 toolchains (util-linux, npitre
  cramfs-tools incl. -B/-D/-p/-x, OpenRG mkcramfs-lzma -c lzma/gzip/none
  at 4-64K) + flag/layout patches (wrong-signature, unsorted, fsid v1,
  shifted-512). README.md documents each.
- CramfsWildCorpusTest scans wild/ + synthetic/ + test-corpus/cramfs/.
- Research note: public standalone cramfs images are scarce; DD-WRT v16-24
  (all squashfs), Linksys WRT54G 4.20/4.21 (squashfs), OpenWrt WR/Kamikaze
  (squashfs), D-Link DSL-2750U (squashfs); uClinux dists ship romfs.
  ViewSonic VEB620 book.img is cramfs but 21MB (>7MB cap).
