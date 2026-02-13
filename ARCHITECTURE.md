# Architecture

This document describes the architecture of Saffron, a pure Java library for reading virtual machine disk images and their contained filesystems.

## Overview

Saffron uses a layered architecture where each layer handles one concern: disk format decoding, partition detection, LVM volume assembly, filesystem mounting, and file entry access.

```
┌─────────────────────────────────────────────────────────────────┐
│                        User Application                         │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                        DiskReader.open()                        │
│              Auto-detects format from magic bytes               │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      VirtualDisk (sealed)                       │
│  ┌────────┐┌──────┐┌─────┐┌──────┐┌─────┐┌─────┐┌─────┐┌────┐ │
│  │ QCOW2  ││ VMDK ││ VHD ││ VHDX ││ VDI ││ Raw ││ GCP ││AMI │ │
│  └────────┘└──────┘└─────┘└──────┘└─────┘└─────┘└─────┘└────┘ │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                  PartitionTable.detect() (sealed)               │
│                    ┌──────────┐  ┌──────────┐                   │
│                    │   GPT    │  │   MBR    │                   │
│                    └──────────┘  └──────────┘                   │
└─────────────────────────────────────────────────────────────────┘
                          │                │
                          ▼                ▼
┌──────────────────────────────┐  ┌────────────────────────┐
│      DiskRegion              │  │   LvmVolumeGroup       │
│  (partition slice)           │  │   └─ LogicalVolumeDisk │
└──────────────────────────────┘  └────────────────────────┘
                          │                │
                          └───────┬────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│          FilesystemDetector (magic-byte probing)                │
│          FileSystemMount.mountAll()                             │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      FileSystem (sealed)                        │
│  ┌──────┐┌──────┐┌───────┐┌───────┐┌─────┐┌──────┐┌────┐┌────┐│
│  │ ext4 ││ NTFS ││ FAT32 ││ exFAT ││ XFS ││ Btrfs││HFS+││APFS││
│  └──────┘└──────┘└───────┘└───────┘└─────┘└──────┘└────┘└────┘│
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                   FileSystemEntry (sealed)                      │
│   ┌───────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐  │
│   │ Directory │ │ RegularFile │ │ SymbolicLink│ │SpecialFile│  │
│   └───────────┘ └─────────────┘ └─────────────┘ └───────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Package Structure

```
io.spicelabs.saffron
├── VirtualDisk.java              # Sealed interface (8 disk format permits)
├── DiskReader.java               # Factory: auto-detect + open disk images
├── DiskFormat.java               # Disk format enum with magic-byte detection
├── SecurityPolicy.java           # Security limits record
├── BlockDevice.java              # Block-level read abstraction
│
├── fs/                           # Filesystem abstraction
│   ├── FileSystem.java           # Sealed interface (8 filesystem permits)
│   ├── FileSystemEntry.java      # Sealed: Directory, RegularFile, SymbolicLink, SpecialFile
│   └── FileSystemMount.java      # Discovery, mounting, mountAll()
│
├── partition/                    # Partition tables
│   ├── PartitionTable.java       # Sealed: MbrPartitionTable, GptPartitionTable
│   ├── Partition.java            # Sealed: MbrPartition, GptPartition
│   ├── MbrPartitionTable.java    # Master Boot Record parsing
│   ├── GptPartitionTable.java    # GUID Partition Table parsing
│   ├── MbrPartition.java
│   └── GptPartition.java
│
├── lvm/                          # LVM2 support
│   ├── DiskRegion.java           # Unified read interface for partitions and LVs
│   ├── LvmVolumeGroup.java       # VG detection and LV enumeration
│   ├── LogicalVolumeDisk.java    # Extent-to-physical mapping
│   ├── LvmLabel.java             # PV label parsing
│   └── LvmMetadata.java          # VG/LV/segment metadata records
│
├── filesystem/                   # Filesystem implementations
│   ├── FilesystemDetector.java   # Magic-byte probing at known offsets
│   ├── FilesystemInfo.java       # Detection result record
│   ├── ext4/                     # Superblock, block groups, extents, inodes
│   ├── ntfs/                     # MFT, $ATTRIBUTE_LIST, index allocation
│   ├── fat32/                    # Boot sector, FAT chain, directory entries
│   ├── exfat/                    # VBR, allocation bitmap, upcase table
│   ├── xfs/                      # Superblock, B-tree dirs, AG headers
│   ├── btrfs/                    # Chunk tree, subvolumes, zstd compression
│   ├── hfsplus/                  # Volume header, catalog B-tree, extents
│   └── apfs/                     # Container, volumes, omap, B-trees
│
├── qcow2/                       # QEMU Copy-On-Write v2/v3
│   ├── Qcow2DiskImpl.java
│   ├── header/                   # Header parsing
│   └── cluster/                  # L1/L2 table cluster resolution
│
├── vmdk/                         # VMware Virtual Machine Disk
│   ├── VmdkDiskImpl.java
│   ├── descriptor/               # Descriptor file parsing
│   └── sparse/                   # Sparse extent, grain directories
│
├── vhd/                          # Microsoft VHD (legacy)
│   ├── VhdDiskImpl.java
│   ├── footer/                   # Footer parsing (at end of file)
│   └── dynamic/                  # BAT-based dynamic allocation
│
├── vhdx/                         # Microsoft VHDX
│   ├── VhdxDiskImpl.java
│   ├── header/                   # File identifier, headers
│   └── metadata/                 # Metadata region parsing
│
├── vdi/                          # VirtualBox VDI
│   └── VdiDiskImpl.java
│
├── raw/                          # Raw byte-for-byte images
│   └── RawDiskImpl.java
│
├── gcp/                          # Google Cloud tar.gz archives
│   └── GcpDiskImpl.java
│
├── ami/                          # Amazon Machine Images
│   └── AmiDiskImpl.java
│
├── common/                       # Shared utilities
│   ├── ByteUtils.java            # Byte manipulation helpers
│   ├── PathSecurity.java         # Path traversal prevention
│   ├── SecurityUtils.java        # Security utilities
│   └── UnicodeSecurityUtils.java # Bidi/zero-width detection
│
├── exception/                    # Exception hierarchy
│   ├── SaffronException.java     # Base exception
│   ├── InvalidMagicException.java
│   ├── CorruptedDiskException.java
│   ├── ChecksumException.java
│   ├── EncryptedDiskException.java
│   ├── UnsupportedVersionException.java
│   └── ResourceLimitException.java
│
├── io/                           # I/O utilities
│   ├── BinaryReader.java         # Endian-aware binary reading
│   ├── BoundedInputStream.java   # Decompression bomb protection
│   └── SafeMath.java             # Overflow-safe arithmetic
│
└── adapter/                      # InputStreamSource adapters
    ├── InputStreamSource.java
    ├── FileInputStreamSource.java
    └── ByteArrayInputStreamSource.java
```

## Core Interfaces

### VirtualDisk

The top-level sealed interface for disk images:

```java
public sealed interface VirtualDisk extends Closeable
        permits VirtualDisk.Qcow2Disk, VirtualDisk.VmdkDisk, VirtualDisk.VhdDisk,
                VirtualDisk.VhdxDisk, VirtualDisk.VdiDisk, VirtualDisk.RawDisk,
                VirtualDisk.GcpDisk, VirtualDisk.AmiDisk {

    DiskFormat format();
    long virtualSize();
    long allocatedSize();
    ByteBuffer read(long offset, int length) throws IOException;
    InputStream openStream() throws IOException;
    Map<String, String> metadata();
    PackageURL packageUrl();
    Optional<String> backingFile();
    boolean isEncrypted();
    boolean isCompressed();
}
```

Each disk format has a non-sealed subinterface (e.g., `VirtualDisk.Qcow2Disk`) and a package-private implementation class (e.g., `Qcow2DiskImpl`).

### FileSystem

The sealed interface for filesystem access:

```java
public sealed interface FileSystem extends Closeable
        permits FileSystem.Ext4FileSystem, FileSystem.NtfsFileSystem,
                FileSystem.Fat32FileSystem, FileSystem.ExFatFileSystem,
                FileSystem.XfsFileSystem, FileSystem.BtrfsFileSystem,
                FileSystem.HfsPlusFileSystem, FileSystem.ApfsFileSystem {

    FileSystemType type();
    FileSystemEntry.Directory root() throws IOException;
    Optional<FileSystemEntry> resolve(String path) throws IOException;
    Stream<FileSystemEntry> walk() throws IOException;
    Optional<String> label();
    Optional<String> uuid();
    long totalSpace();
}
```

### FileSystemEntry

The sealed interface for filesystem entries with four permitted types:

```java
public sealed interface FileSystemEntry
        permits FileSystemEntry.RegularFile,
                FileSystemEntry.Directory,
                FileSystemEntry.SymbolicLink,
                FileSystemEntry.SpecialFile {

    BasicInfo basicInfo();    // name, path, size, timestamps
    PosixPermissions posixPermissions();

    non-sealed interface RegularFile extends FileSystemEntry {
        InputStream openStream() throws IOException;
        byte[] readAllBytes() throws IOException;
    }

    non-sealed interface Directory extends FileSystemEntry {
        Stream<FileSystemEntry> list() throws IOException;
        Optional<FileSystemEntry> find(String name) throws IOException;
    }

    non-sealed interface SymbolicLink extends FileSystemEntry {
        String target();
        Optional<FileSystemEntry> resolve() throws IOException;
    }

    non-sealed interface SpecialFile extends FileSystemEntry {
        Optional<Integer> majorDevice();
        Optional<Integer> minorDevice();
    }
}
```

### PartitionTable and Partition

Both sealed interfaces with two implementations each:

```java
public sealed interface PartitionTable
        permits MbrPartitionTable, GptPartitionTable {
    Type type();  // MBR or GPT
    List<Partition> partitions();
    static Optional<PartitionTable> detect(VirtualDisk disk) throws IOException;
}

public sealed interface Partition
        permits MbrPartition, GptPartition {
    int index();
    long startLba();
    long endLba();
    String typeName();
    Optional<String> name();
}
```

## Disk Format Detection

`DiskReader.open()` auto-detects the format by reading magic bytes from the file header:

| Format | Magic | Offset | Notes |
|--------|-------|--------|-------|
| QCOW2 | `QFI\xfb` | 0 | 4-byte magic |
| VMDK | `KDMV` | 0 | Sparse extent header |
| VHD | `conectix` | EOF-512 | Footer at end of file |
| VHDX | `vhdxfile` | 0 | File identifier |
| VDI | `\x7f\x10\xda\xbe` | 64 | Header magic at offset 64 |
| Raw | (none) | — | Fallback by extension |
| GCP | gzip magic | 0 | tar.gz containing disk.raw |
| AMI | — | — | Detected by directory structure |

If magic detection fails, `DiskReader` falls back to file extension matching.

## Filesystem Detection

`FilesystemDetector` probes for filesystem signatures at known offsets within a disk region:

| Filesystem | Magic | Offset | Notes |
|-----------|-------|--------|-------|
| ext4 | `0xEF53` | 1080 | Superblock magic at offset 0x438 |
| NTFS | `NTFS    ` | 3 | OEM ID in boot sector |
| FAT32 | `FAT32   ` | 82 | FS type in boot sector |
| FAT16 | `FAT16   ` | 54 | FS type in boot sector |
| exFAT | `EXFAT   ` | 3 | OEM ID |
| XFS | `XFSB` | 0 | Superblock magic |
| Btrfs | `_BHRfS_M` | 65600 | Superblock magic at 64K+0x40 |
| HFS+ | `H+` or `HX` | 1024 | Volume header signature |
| APFS | `NXSB` | 0 | Container superblock |

Detection works against both `VirtualDisk` (with offset) and `DiskRegion` (for LVM logical volumes).

## Mounting Pipeline

The typical flow from disk image to file access:

```
DiskReader.open(path)
    │
    ▼
VirtualDisk (format-specific impl)
    │
    ▼
PartitionTable.detect(disk)
    │
    ├── GPT partitions ──────────┐
    │                            ▼
    │                   FilesystemDetector.detect(disk, partition.offset)
    │                            │
    │                            ▼
    │                   FileSystemMount.mount(disk, offset, fsInfo)
    │
    ├── LVM detection ──────────┐
    │                           ▼
    │                  LvmVolumeGroup.detect(disk)
    │                           │
    │                           ▼
    │                  LogicalVolumeDisk (for each LV)
    │                           │
    │                           ▼
    │                  FilesystemDetector.detect(logicalVolume)
    │                           │
    │                           ▼
    │                  FileSystemMount.mount(DiskRegion, fsInfo)
    │
    └── Results combined in mountAll()
            │
            ▼
    List<FileSystem> ── each supports root(), resolve(), walk()
```

`FileSystemMount` provides convenience methods:
- `mountAll(disk)` — mount all filesystems from partitions + LVM
- `mountLargest(disk)` — mount only the largest filesystem
- `mountAllIncludingLvm(disk)` — mount all, preferring LVM when available

## Security Architecture

### SecurityPolicy

A record with configurable security limits:

```java
public record SecurityPolicy(
    long maxDecompressedSize,    // Default: 16 GB
    long maxAllocationSize,      // Default: 256 MB
    int maxClusterSize,          // Default: 2 MB
    long maxVirtualDiskSize,     // Default: 64 TB
    int maxPathDepth,            // Default: 256
    int maxSymlinkDepth,         // Default: 40
    boolean validateChecksums,   // Default: true
    boolean rejectBidiChars,     // Default: true
    boolean rejectZeroWidthChars // Default: true
)
```

### Security Features

| Feature | Implementation | Protection |
|---------|---------------|------------|
| **Decompression bomb** | `BoundedInputStream` | Limits decompressed output size |
| **Symlink depth** | FileSystem implementations | Prevents infinite symlink resolution |
| **Walk cycle detection** | `walk()` implementations | Detects directory cycles during traversal |
| **Path depth** | `SecurityPolicy.maxPathDepth` | Rejects excessively deep paths |
| **Path traversal** | `PathSecurity` | Validates and sanitizes paths |
| **Bidi attacks** | `UnicodeSecurityUtils` | Detects RTL override characters in filenames |
| **Zero-width chars** | `UnicodeSecurityUtils` | Detects zero-width joiners/non-joiners |
| **Integer overflow** | `SafeMath` | Safe arithmetic for offset calculations |
| **Resource limits** | `ResourceLimitException` | Thrown when any limit is exceeded |

## Corpus Verification

Saffron's correctness is verified against external tools:

1. **Docker scanner** (`tools/corpus-scanner/`) runs libguestfs against 70 real-world VM images
2. **Ground truth JSON** records filesystem count, file count, directory count, and SHA256 hashes for sampled files
3. **Corpus verification tests** (`CorpusFullVerificationTest`) compare Saffron output against ground truth with zero tolerance
4. JSON is never generated from Saffron code — any variance is a hard failure

This ensures that Saffron reads real-world disk images identically to established Linux filesystem tools.

## Thread Safety

| Component | Thread Safety | Notes |
|-----------|---------------|-------|
| `DiskReader` | Thread-safe | Static factory methods |
| `VirtualDisk` | Not thread-safe | Each instance owns a `SeekableByteChannel` |
| `FileSystem` | Not thread-safe | Shares the underlying disk's channel |
| `FileSystemEntry` | Not thread-safe | Reads from the filesystem on access |
| `SecurityPolicy` | Immutable | Safe to share across threads |
| `FilesystemDetector` | Thread-safe | Static detection methods |

For concurrent access, open separate `VirtualDisk` instances per thread.

## Extension Points

### Adding a New Disk Format

1. Add enum value to `DiskFormat`
2. Add non-sealed subinterface to `VirtualDisk` permits list
3. Create implementation class (`*DiskImpl`) with `open(Path)` factory
4. Add magic-byte detection in `DiskReader`
5. Write tests with real disk images

### Adding a New Filesystem

1. Add enum value to `FileSystemType`
2. Add non-sealed subinterface to `FileSystem` permits list
3. Create implementation class (`*FileSystemImpl`) with:
   - `mount(VirtualDisk, long offset)` and `mount(DiskRegion)` static factories
   - Inner classes for `Directory`, `RegularFile`, `SymbolicLink`, `SpecialFile`
4. Add magic-byte detection in `FilesystemDetector`
5. Add case to `FileSystemMount.mount()` switch
6. Add case to `FileSystemMount.isSupported()`
7. Write tests; add corpus images and regenerate ground truth

## Dependencies

### Runtime

| Dependency | Purpose |
|------------|---------|
| JetBrains Annotations | `@NotNull` / `@Nullable` null safety |
| packageurl-java | Package URL (PURL) generation |
| XZ for Java | XZ and LZMA decompression |
| zstd-jni | Zstandard decompression (Btrfs, VMDK) |
| Commons Compress | bzip2 decompression, additional formats |
| SLF4J API | Logging facade |

### Test

| Dependency | Purpose |
|------------|---------|
| JUnit 5 | Test framework |
| AssertJ | Fluent assertions |
| Gson | JSON parsing for corpus verification |
| Jazzer | Fuzz testing |
| ArchUnit | Architecture rule enforcement |
| JMH | Performance benchmarks |
| Awaitility | Async test utilities |
