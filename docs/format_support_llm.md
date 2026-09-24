# Format support matrix (LLM summary)

Single page answering "can Saffron read X?". Full table in
`format_support.md`; update both when support changes.

## Windows backups
- Works: Windows Server Backup / Backup and Restore system images (dynamic
  VHDX or VHD per volume, NTFS at offset 0), Disk2vhd, Hyper-V exports,
  single sparse/stream-optimised VMDK, raw dd, File History drives, images
  inside OVA/tar/zip/7z (one member extracted, bounded, nesting rejected).
- Works with gaps: modern NTFS. Covered by mkntfs/ntfs-3g fixtures
  (compression, sparse, ADS, fragmented $MFT, large clusters, 4K sectors).
  Gaps tracked in #35: hard-link names, coalesced compression units,
  directory reparse points/junctions, symlink resolution, EFS (ciphertext),
  WOF/CompactOS (stubs), initializedSize, $LogFile replay.
- Not supported, tracked: differencing VHD/VHDX and AVHDX (#25), descriptor
  / flat / split VMDK (#26), VSS shadow copies (#27), WIM/ESD contents
  (#28, header-only today), BitLocker (#29, not even detected), NTBackup
  .bkf (#30), Clonezilla/partclone (#31), LDM dynamic disks (#32), Storage
  Spaces (#33), ReFS (#34, not detected).
- Not realistic: Acronis, Veeam, Macrium, ShadowProtect, Paragon, EaseUS,
  AOMEI, Ghost, Datto, Commvault, Arcserve (undocumented, dedup, encrypted):
  convert with vendor tool to VHD/VHDX/VMDK/raw. Impossible: anything
  encrypted without a key; cloud-only backups.

## Containers
QCOW2 v2/v3 (backing chains, deflate; encryption/external data rejected;
zstd + extended L2 unhandled), VMDK sparse/stream-optimised, VHD fixed +
dynamic, VHDX fixed + dynamic (512/4K sectors, no log replay), VDI, raw,
gzip raw, GCP tar.gz, AMI (plain parts), OVA/tar/zip/7z wrappers. Not:
ISO/UDF, EWF, AFF, split raw.

## Filesystems / volumes
ext2/3/4, XFS, Btrfs, NTFS, FAT12/16/32, exFAT, HFS+, APFS (unencrypted),
SquashFS, cramfs, JFFS2, YAFFS2, UBI/UBIFS; swap detected only. MBR+EBR,
GPT (512-byte sectors assumed). LVM2 linear single-PV only. Not: LDM,
Storage Spaces, ReFS, BitLocker, LUKS, mdraid, APM, BSD disklabel.

## Binary containers
Kernel, FIT, DTB, ELF, RPi firmware, Android boot, gzip/xz/bzip2 payload
read; WIM and DMG detection-only (`/raw`).
