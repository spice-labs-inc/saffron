# Saffron

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/saffron?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/saffron)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/saffron?label=GitHub%20Release)](https://github.com/spice-labs-inc/saffron/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/saffron/packages/)
[![Build Status](https://github.com/spice-labs-inc/saffron/actions/workflows/buildAndTest.yml/badge.svg)](https://github.com/spice-labs-inc/saffron/actions)

**Saffron** is a pure Java library for reading virtual machine disk images and their contained filesystems — no native dependencies required. It supports 8 disk image formats and 8 filesystem types through a unified, type-safe API built on Java 21 sealed interfaces and pattern matching.

## Quick Start

### Prerequisites

- **Java 21** or higher
- **Maven 3.6+** (for building)

### Installation

#### Maven

```xml
<dependency>
    <groupId>io.spicelabs</groupId>
    <artifactId>saffron</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

#### Gradle

```groovy
implementation 'io.spicelabs:saffron:0.1.0-SNAPSHOT'
```

---

## Supported Formats

### Disk Image Formats

| Format | Extensions | Platforms |
|--------|-----------|-----------|
| **QCOW2** | `.qcow2` | QEMU, KVM, libvirt |
| **VMDK** | `.vmdk` | VMware |
| **VHD** | `.vhd` | Hyper-V (legacy), VirtualBox |
| **VHDX** | `.vhdx` | Hyper-V |
| **VDI** | `.vdi` | VirtualBox |
| **Raw** | `.img`, `.raw` | All platforms |
| **GCP** | `.tar.gz` | Google Cloud |
| **AMI** | `.ami` | Amazon Web Services |

### Filesystem Formats

| Filesystem | OS | Notes |
|-----------|-----|-------|
| **ext4** | Linux | Most common Linux filesystem |
| **XFS** | Linux | Default on RHEL/CentOS |
| **Btrfs** | Linux | Copy-on-write, subvolume support |
| **NTFS** | Windows | Including v1.2 (NT 4.0) through modern |
| **FAT32** | Cross-platform | Including FAT16 |
| **exFAT** | Cross-platform | Flash storage |
| **HFS+** | macOS | Mac OS Extended |
| **APFS** | macOS | Apple File System |

### Binary Container Formats

Saffron also detects and mounts non-disk binary payloads as containers, exposing
named entries such as `/payload`, `/kernel`, `/dtb`, or `/ramdisk`.

| Format | Identifier | Notes |
|---|---|---|
| **Linux kernel** | bzImage / zImage / Image / uImage | Extracts kernel payload, initramfs, DTB, certificates |
| **FIT / uImage** | DTB magic + `/images` node | Extracts kernel, ramdisk, fdt |
| **Device tree blob** | `0xd00dfeed` | Plain DTB exposed as `/dtb` |
| **ELF** | `0x7f ELF` | Shared objects and executables |
| **Raspberry Pi firmware** | `start.elf`, `fixup.dat`, `bootcode.bin` | Firmware files |
| **Android boot** | `ANDROID!` | `boot.img` with kernel, ramdisk, second, dtb |
| **Compressed single payload** | gzip / xz / bzip2 magic | `.gz`, `.xz`, `.bz2` exposed as `/payload` |

---

## Features

- **Unified API**: Open any disk format with `DiskReader.open(path)` — format auto-detected from magic bytes
- **8 disk formats + 8 filesystems**: Comprehensive VM image support
- **Streaming reads**: Read file contents without loading entire disk images into memory
- **SecurityPolicy**: Configurable limits for decompression bombs, path depth, symlink cycles, and bidi attacks
- **PURL support**: Generate standard Package URLs for disk images
- **GPT + MBR partition detection**: Automatic partition table parsing
- **LVM2 support**: Detect and mount logical volumes within disk images
- **Sealed interfaces + pattern matching**: Type-safe API using Java 21 features
- **Null-safe API**: Uses `Optional<T>` and `@NotNull` annotations throughout
- **Zero native dependencies**: Pure Java — runs anywhere Java 21 runs

---

## Basic Usage

### Opening a Disk Image

```java
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;

try (VirtualDisk disk = DiskReader.open(Path.of("server.qcow2"))) {
    System.out.println("Format: " + disk.format());
    System.out.println("Virtual size: " + disk.virtualSize());
    System.out.println("PURL: " + disk.packageUrl());
}
```

### Mounting Filesystems

```java
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemMount;

try (VirtualDisk disk = DiskReader.open(Path.of("server.vmdk"))) {
    FileSystemMount mount = new FileSystemMount();

    // Mount all detected filesystems (partitions + LVM volumes)
    List<FileSystem> filesystems = mount.mountAll(disk);

    for (FileSystem fs : filesystems) {
        System.out.println("Type: " + fs.type());
        System.out.println("Label: " + fs.label().orElse("(none)"));
    }
}
```

### Walking a Filesystem

```java
import io.spicelabs.saffron.fs.FileSystemEntry;

try (VirtualDisk disk = DiskReader.open(path)) {
    FileSystemMount mount = new FileSystemMount();
    FileSystem fs = mount.mountLargest(disk);

    // Walk all entries depth-first
    try (Stream<FileSystemEntry> entries = fs.walk()) {
        entries.forEach(entry -> {
            System.out.println(entry.basicInfo().path());
        });
    }
}
```

### Reading File Contents

```java
// Resolve a specific file
Optional<FileSystemEntry> entry = fs.resolve("/etc/hostname");

if (entry.isPresent() && entry.get() instanceof FileSystemEntry.RegularFile file) {
    byte[] contents = file.readAllBytes();
    System.out.println(new String(contents));
}
```

### Pattern Matching on Filesystem Types

```java
FileSystem fs = mount.mountLargest(disk);

String info = switch (fs) {
    case FileSystem.Ext4FileSystem ext4 -> "ext4 filesystem";
    case FileSystem.NtfsFileSystem ntfs -> "NTFS filesystem";
    case FileSystem.XfsFileSystem xfs -> "XFS filesystem";
    case FileSystem.BtrfsFileSystem btrfs -> "Btrfs filesystem";
    case FileSystem.Fat32FileSystem fat -> "FAT32 filesystem";
    case FileSystem.ExFatFileSystem exfat -> "exFAT filesystem";
    case FileSystem.HfsPlusFileSystem hfs -> "HFS+ filesystem";
    case FileSystem.ApfsFileSystem apfs -> "APFS filesystem";
};
```

---

## Security

Saffron includes multiple security protections configurable via `SecurityPolicy`:

- **Decompression bomb protection**: Configurable limit on decompressed data size (default 16 GB)
- **Symlink depth limiting**: Prevents infinite symlink resolution loops (default 40 levels)
- **Walk cycle detection**: Detects and breaks filesystem traversal cycles
- **Path depth limits**: Prevents excessively deep directory trees (default 256 levels)
- **Bidi/zero-width character rejection**: Detects Unicode homoglyph attacks in filenames
- **ResourceLimitException**: Thrown when any security limit is exceeded

```java
SecurityPolicy policy = SecurityPolicy.builder()
    .maxDecompressedSize(4L * 1024 * 1024 * 1024)  // 4 GB
    .maxSymlinkDepth(20)
    .maxPathDepth(128)
    .build();

try (VirtualDisk disk = DiskReader.open(path)) {
    // SecurityPolicy is applied during filesystem operations
}
```

---

## Maintainers

### Build Locally

Install JDK 21+ and Maven 3.6+.

Clone the repo:

```bash
git clone https://github.com/spice-labs-inc/saffron.git
cd saffron
```

Build with Maven:

```bash
mvn clean install
```

Run tests only:

```bash
mvn test
```

Run tests without coverage checks (faster):

```bash
mvn test -Pquick
```

Generate Javadoc:

```bash
mvn javadoc:javadoc
```

Check test coverage (report in `target/site/jacoco/`):

```bash
mvn test jacoco:report
```

### Corpus Verification Tests

Saffron includes a comprehensive corpus of 70 real-world VM images tested against ground truth generated by external tools (libguestfs). These tests verify exact file counts, directory counts, and SHA256 hashes for sampled files.

Corpus tests require a local `test-corpus/` directory with disk images and are skipped in CI. See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

---

### Releasing

1. **Create a GitHub Release**
   Use a tag like `v0.1.0`. This triggers GitHub Actions to:

   - Build the JAR
   - Publish to GitHub Packages
   - Upload artifacts to Maven Central (automated)

2. **Monitor Maven Central** (optional)
   Visit [https://central.sonatype.com](https://central.sonatype.com) → Deployments
   Propagation takes ~40 minutes.

3. **Verify the JAR**

```bash
mvn dependency:get \
  -Dartifact=io.spicelabs:saffron:0.1.0
```

---

## Repository

Maintained by [Spice Labs](https://github.com/spice-labs-inc).

- [`saffron`](https://github.com/spice-labs-inc/saffron) — this library
- [`baharat`](https://github.com/spice-labs-inc/baharat) — Java library for reading Linux and BSD package files
- [`spice-labs-cli`](https://github.com/spice-labs-inc/spice-labs-cli) — Spice Labs Surveyor CLI

---

## References

### Disk Format Specifications

- [QCOW2 Specification](https://github.com/qemu/qemu/blob/master/docs/interop/qcow2.txt)
- [VMDK Virtual Disk Format](https://www.vmware.com/app/vmdk/?src=vmdk)
- [VHD Specification](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-vhdx/83e061f8-f6e2-4b1c-a234-86b6272cf117)
- [VHDX Format Specification](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-vhdx/)
- [VDI Format (VirtualBox)](https://www.virtualbox.org/browser/vbox/trunk/src/VBox/Storage/VDI.cpp)

### Filesystem References

- [ext4 Data Structures](https://ext4.wiki.kernel.org/index.php/Ext4_Disk_Layout)
- [NTFS Documentation](https://flatcap.github.io/linux-ntfs/ntfs/)
- [XFS Algorithms & Data Structures](https://xfs.wiki.kernel.org/index.php/XFS_Filesystem_Structure)
- [Btrfs Wiki](https://btrfs.wiki.kernel.org/)
- [FAT Filesystem Specification](https://download.microsoft.com/download/1/6/1/161ba512-40e2-4cc9-843a-923143f3456c/fatgen103.doc)
- [Apple File System Reference](https://developer.apple.com/support/downloads/Apple-File-System-Reference.pdf)
- [HFS Plus Volume Format](https://developer.apple.com/library/archive/technotes/tn/tn1150.html)

### Standards

- [Package URL Specification](https://github.com/package-url/purl-spec)
- [GPT Partition Table](https://en.wikipedia.org/wiki/GUID_Partition_Table)
- [LVM2 Metadata](https://sourceware.org/lvm2/)

---

## License

Licensed under the [Apache License 2.0](LICENSE-APACHE).
