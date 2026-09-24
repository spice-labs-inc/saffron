# NTFS fixtures

Eight small NTFS images built with the reference `mkntfs` and populated
through `ntfs-3g` (FUSE), each paired with an expectations file computed
from the source tree the image was populated from. Read by
`NtfsFixtureTest` (`src/test/java/io/spicelabs/saffron/filesystem/ntfs/`)
and exported to Surveyor through `test-fixtures.json` (format
`saffron-ntfs-fixture`, tier 0).

## Provenance

- **Self-generated.** No third-party content: every byte of file content is
  derived by `fixture-tree.py` from fixed strings (SHA-256 counter streams
  for incompressible data, numbered prose lines for compressible data), so
  regenerating produces the same expectations. The volume serial number is
  random per run; nothing asserts on it.
- **Toolchain:** `ntfs-3g` / `mkntfs` / `ntfsinfo` **2022.10.3** (Debian
  bookworm package `ntfs-3g`), GPL-2.0-or-later. Only the tool output (the
  images) is committed; the tools themselves are not redistributed.
- **Regenerate:** `src/test/resources/ntfs/generate-fixtures.sh` (needs
  Docker; one-time). The container is run as
  `docker run --rm -i --privileged --device /dev/fuse ...` so that FUSE
  works; this is the same arrangement `scripts/run-corpus-scanner.sh` uses.
  The mkntfs call is `mkntfs -F -Q -T -L <name> <extra>` (`-T` zeroes the
  system-file timestamps).
- **Expectations** (`fixtures/<name>.json`) are written by
  `fixture-tree.py expect` from the *source tree* with `hashlib`, never by
  reading the NTFS image back, so they are independent of both ntfs-3g and
  Saffron. Fields: `id` (= path under `src/test/resources` without
  `.json`, e.g. `ntfs/fixtures/basic-4k`), `image` (basename),
  `mkntfsOptions`, `mountOptions`, `label`, `fileCount`, `directoryCount`
  (**excluding the root**), `symlinkCount`, and `entries[]` with `path`,
  `type` (`file|dir|symlink`), `size`, `sha256`, `target` + `resolvesTo`
  (symlinks), `streams` (ADS name → `{size, sha256}`), `hardlinkGroup`,
  `compressed`, `sparse`. NTFS system files (`$MFT`, `$Extend`, …) are
  excluded from every count; `fileCount` counts names, so a hard-linked
  file counts once per name.

## Fixtures

| Image | Size | mkntfs / population | Exercises |
|-------|------|---------------------|-----------|
| `basic-4k.img` | 3 MiB | defaults (4 KiB clusters, 512 B sectors); 230-odd small files in nested dirs, `bigdir/` with 150 entries, unicode names (`café`, `日本語`, cyrillic, an astral-plane emoji), a 255-character name, an empty file, a 1 MiB + 4321 B file, sizes straddling the resident/non-resident boundary | resident + non-resident `$DATA`, `$INDEX_ALLOCATION`, UTF-16 names |
| `compressed.img` | 2 MiB | mounted `-o compression`; `comp/` marked `FILE_ATTRIBUTE_COMPRESSED` via `system.ntfs_attrib_be` before population; 300 KiB text (4 full 64 KiB units + a partial one), 200 KiB incompressible, a text/random/text mix, 10 KiB (< 1 unit), a resident file, all-zero units, exactly one unit, one unit + 1 byte, a plain file outside | LZNT1 units, partial last unit, uncompressible (stored) units, all-sparse units |
| `sparse-and-ads.img` | 2 MiB | mounted `-o streams_interface=xattr`; `truncate -s 8M` + `dd seek=` islands (a file larger than the volume), a 64 KiB file with one island, 16 KiB of data then a hole; ADS via `user.<name>` xattrs: two on one file (one non-resident), one on an empty file (60000 B, non-resident), one on a directory | sparse runs, `readAlternateStream`, `ntfs.ads.*` metadata |
| `links.img` | 2 MiB | Windows symlink reparse points (`IO_REPARSE_TAG_SYMLINK`, set through `system.ntfs_reparse_data`; POSIX `ln -s` would create Interix symlinks): relative same-dir, relative `..\other\o.txt`, a directory link, an absolute `\??\C:\other\o.txt`; hard links: a pair, and one file with three names in three directories | `$REPARSE_POINT`, symlink resolution, multiple `$FILE_NAME` |
| `fragmented.img` | 8 MiB | fillers until ENOSPC, every other pair deleted (single-cluster holes in both ntfs-3g data zones), a 2 MiB file written into the holes, most fillers deleted, then 2200 tiny resident files so `$MFT` grows into holes | `$ATTRIBUTE_LIST` on `$MFT` and on the big file (several unnamed `$DATA` pieces), an ADS beside split pieces |
| `cluster-64k.img` | 2 MiB | `mkntfs -c 65536` | 128 sectors/cluster (0x80), index records smaller than a cluster |
| `cluster-256k.img` | 6 MiB | `mkntfs -c 262144` (supported since ntfs-3g 2021.8.22) | sectors-per-cluster byte 0xF7 (−9 → 2^9 = 512 sectors), the signed large-cluster encoding |
| `sector-4k.img` | 2 MiB | `mkntfs -s 4096` | 4096-byte sectors (1 sector/cluster, 4 KiB MFT records) |

Total: 27 MiB of images plus ~370 KB of JSON.

## Notes for regenerating

- ntfs-3g's cluster allocator takes the largest free extent first and
  alternates between two data zones for consecutive files; the
  fragmentation recipe in `fixture-tree.py` (`populate_fragmented`) is
  tuned to that. The script verifies with `ntfsinfo` that `$MFT` has an
  `$ATTRIBUTE_LIST` and that `big.bin` has more than one `$DATA` and fails
  otherwise.
- Linux limits an xattr value to 64 KiB, so an ADS created through the
  xattr interface cannot be larger than that.
- `ntfs-3g` cannot show reparse points set through the xattr without its
  plugin; that only affects the mounted view during generation, not the
  image.
