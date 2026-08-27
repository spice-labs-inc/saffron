# cramfs Scope — LLM Summary

## Shape
Single phase (detection + mount), ~3-5 days. Simplest OT FS win; zlib path
already exists in Saffron.

## Verified format facts (include/uapi/linux/cramfs_fs.h)
- Superblock at 0: magic u32 0x28cd3d45 (LE; 0x453dcd28 = BE image),
  size u32, flags u32, future, signature "Compressed ROMFS" (16B),
  fsid {crc, edition, blocks, files}, name[16], root inode (12B).
  Images are in HOST byte order (mkcramfs); detect via magic byte order.
- Inode 12B bitfields: mode(16), uid(16); size(24), gid(8); namelen(6,
  =len/4, max name 252), offset(26, /4).
- Regular file: offset*4 → u32 block-pointer table, one entry per 4096B
  block. Entry: len in 4-byte units in bits 0..29 (byte len = len<<2),
  UNCOMPRESSED flag 0x80000000 (raw 4096B page stored after pointer
  table), DIRECT_PTR 0x40000000 (EXT_BLOCK_POINTERS), 0 = hole (zeros).
  Blocks zlib-compressed independently; compressed==4096 stored raw.
- Directory: offset*4 → first inode; inodes chained via offset (NULL
  terminated). No hard links. Symlink = file data. Device: size = rdev.

## Implementation map
CramfsSuperblock (endian-aware) + CramfsFileSystemImpl (root inode walk,
dir linked lists with cycle guard, lazy block-decompress InputStream) +
FilesystemDetector (after JFFS2) + FileSystemType.CRAMFS +
SaffronProbe FILESYSTEM_CRAMFS + .cramfs → RAW fallback.

## Tests
Detection (both endians, signature, truncation); round-trip (nested dirs,
empty, symlink, holes, multi-block file sha256, device); security (block
pointer beyond image, dir cycle, traversal, fuzz); SaffronProbe; wild
hook test-corpus/cramfs/.

## Fixtures
mkcramfs (cramfsprogs package; fallback build from source in Docker).
BE fixture via documented byte-swap of LE fixture if mkcramfs can't emit
BE. Committed under src/test/resources/cramfs/fixtures/.
Wild: legacy router firmware (D-Link/TP-Link GPL drops, old OpenWrt
ramdisk).

## Open questions
1. .cramfs extension → RAW fallback (rec: yes).
2. BE fixture by byte-swap (rec: yes).
