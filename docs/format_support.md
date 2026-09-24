# Format support matrix

What Saffron can read today, what it could read with more work, and what is
out of reach. This page exists so that a support question such as "can you
read our Windows backups?" can be answered from one place. Keep it current
when a format lands or a limitation is lifted; each row that is not yet
supported links to its tracking issue.

Terminology: a *container* (disk image, archive) is opened by
`DiskReader.open`; a *filesystem* is mounted by `FileSystemMount.mountAll`.
"Works today" means the path is exercised by unit tests or the verification
corpus. Caveats are listed with the row.

## Windows backups

Windows's own tools all write VHD or VHDX with NTFS inside, so the built-in
backup story is the strong one. Third-party products use proprietary,
mostly undocumented formats and are best converted with the vendor's tool.

| Backup source / format | Status | Notes and effort |
|---|---|---|
| Windows Server Backup, "Backup and Restore (Windows 7)" system image (one dynamic VHDX or VHD per volume under `WindowsImageBackup`) | Works today | NTFS at offset zero, no partition table. Earlier backup versions are VSS shadow copies inside the VHDX and are not visible (#27). |
| Disk2vhd, Hyper-V export, vendor exports to plain VHD or VHDX | Works today | Fixed and dynamic VHD/VHDX with MBR or GPT. VHDX images larger than 4 GiB depend on the BAT chunk-ratio handling described in `disk_formats.md`. |
| Single monolithic-sparse or stream-optimised VMDK | Works today | Descriptor-plus-flat and split-extent VMDKs fail to open (#26). |
| Raw `dd` images, including `.img.gz` and `.raw.gz` | Works today | Whole-disk or single-volume. |
| File History drives | Works today | A dated directory tree on NTFS or exFAT; no special format. |
| Images inside OVA, `.tar`, zip or 7z wrappers | Works today | One disk member is extracted (bounded by `SecurityPolicy`) and opened; nested archives and encrypted entries are rejected. See `archive_wrappers.md`. |
| NTFS from Windows 10, 11 and Server 2016+ | Works, with gaps | Fixtures built with mkntfs/ntfs-3g cover compression, sparse files, ADS, fragmented `$MFT`, large clusters and 4K sectors (`filesystems.md`). Remaining gaps, all tracked in #35: hard-link names, compression units coalesced across runs, directory reparse points and junctions, symlink resolution, EFS (returns ciphertext), WOF/CompactOS (reads stubs), `initializedSize`, `$LogFile` replay. |
| Hyper-V checkpoints (AVHDX), differencing VHD/VHDX chains | Not supported | Rejected at open. Parent locator, chain resolution and sector bitmaps needed; a few days (#25). |
| Descriptor-first, flat and split-extent VMDK | Not supported | Descriptor parser exists; reader insists on sparse magic at offset zero. A few days (#26). |
| VSS shadow copies inside NTFS (earlier backup versions) | Not supported | libvshadow is the reference. One to two weeks (#27). |
| WIM / ESD contents (DISM captures, WDS, some backup tools) | Header only | Whole file exposed as one `/raw` entry. Needs lookup table, metadata resource and XPRESS, LZX and LZMS decompressors; LZMS (ESD) is the hard part (#28). |
| BitLocker volumes | Not supported | No detection today, so an encrypted volume fails to mount silently. Decryption with a supplied key is feasible in pure Java; a week or more (#29). |
| NTBackup `.bkf` (Microsoft Tape Format) | Not supported | Documented format, low demand. Moderate (#30). |
| Clonezilla / partclone images | Not supported | Documented, split and compressed per partition. Moderate (#31). |
| Windows dynamic disks (LDM) | Not supported | ldmtool is the reference. Moderate (#32). |
| Storage Spaces | Not supported | Undocumented pool database; needs a multi-disk API. Large (#33). |
| ReFS volumes | Not supported | No detection. Undocumented; multi-week (#34). |
| Acronis `.tib`/`.tibx`, Veeam `.vbk`/`.vib`, Macrium `.mrimg`, ShadowProtect `.spf`/`.spi`, Paragon/EaseUS `.pbd`, AOMEI `.adi`, Ghost `.gho`, Datto, Commvault, Arcserve | Not realistic | Undocumented, deduplicated, often encrypted by default. Export or mount with the vendor tool to VHD, VHDX, VMDK or raw, then read that. |
| Anything encrypted without a key (vendor encryption, BitLocker without recovery key) | Impossible | |
| Cloud-only backups (Windows 11 "Windows Backup" app, OneDrive, Backblaze, Carbonite) | Impossible | No on-disk image format to read. |

### Answering a customer

Ask which tool produced the backup. If it is Windows Server Backup, Backup
and Restore, Disk2vhd or a Hyper-V export, the answer is yes, with the
caveat that checkpoint chains and earlier VSS versions are not yet
supported. If it is a third-party product, ask whether it can export to
VHD, VHDX, VMDK or raw.

## Disk image containers

| Format | Status | Notes |
|---|---|---|
| QCOW2 v2/v3 | Works today | Backing chains (depth 16), deflate-compressed clusters. Encrypted images and external data files rejected. zstd clusters and extended L2 entries are not handled. |
| VMDK monolithic sparse, stream-optimised | Works today | Deflate grains. See the VMDK rows above for other layouts (#26). |
| VHD fixed, dynamic | Works today | Differencing rejected (#25). |
| VHDX fixed, dynamic | Works today | 512 and 4 KiB logical sectors. Log replay is not performed; differencing rejected (#25). |
| VDI dynamic, fixed | Works today | Differencing rejected. Detected by extension only. |
| Raw, gzip-wrapped raw (`.img.gz`, `.raw.gz`) | Works today | |
| GCP `.tar.gz` (`disk.raw` member) | Works today | |
| AMI bundle (`*.manifest.xml` + parts) | Works today | Unencrypted, uncompressed parts only. |
| OVA, `.tar`, zip, 7z | Works today | One disk member; see `archive_wrappers.md`. |
| ISO 9660 / UDF, EWF (E01), AFF, split raw (`.001`) | Not supported | No tracking issue yet. |

## Filesystems and volume managers

| Format | Status | Notes |
|---|---|---|
| ext2/3/4, XFS, Btrfs (subvolumes, zstd/zlib/lzo) | Works today | |
| NTFS | Works today | NTFS 1.2 through 3.1; see the NTFS row above for remaining gaps (#35). |
| FAT12/16/32, exFAT | Works today | Reported as `FAT32` regardless of variant. |
| HFS+/HFSX, APFS | Works today | Encrypted APFS volumes are not readable. |
| SquashFS, cramfs, JFFS2, YAFFS2, UBI/UBIFS | Works today | Encrypted or authenticated UBIFS rejected. |
| Linux swap | Detected only | |
| MBR (with EBR chains), GPT | Works today | 512-byte sectors assumed by the partition layer. Hybrid MBR ignored in favour of GPT. |
| LVM2 linear, single PV | Works today | Striped, mirrored, thin and multi-PV volume groups are not supported. |
| LDM, Storage Spaces, ReFS, BitLocker, LUKS, mdraid, Apple Partition Map, BSD disklabel | Not supported | See the Windows rows above for LDM (#32), Storage Spaces (#33), ReFS (#34) and BitLocker (#29). |

## Binary containers

Linux kernel images, FIT/uImage, DTB, ELF, Raspberry Pi firmware, Android
boot images and gzip/xz/bzip2 single payloads are read; WIM and DMG are
detected and exposed as a single `/raw` entry only. See
`container_formats.md`.
