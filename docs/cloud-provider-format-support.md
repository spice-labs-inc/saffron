# Cloud Provider VM Format Support in Saffron

## Summary

**Cloud providers use specialized formats that are NOT fully supported by Saffron.** New implementations are required.

## Saffron Currently Supported Formats

| Format | Status | Description |
|--------|--------|-------------|
| QCOW2 | ✅ Supported | QEMU Copy-On-Write v2/v3 |
| VMDK | ✅ Supported | VMware Virtual Machine Disk |
| VHD | ✅ Supported | Virtual Hard Disk (fixed & dynamic) |
| VHDX | ✅ Supported | Virtual Hard Disk v2 |
| VDI | ✅ Supported | VirtualBox Disk Image |
| **RAW** | ❌ Not Supported | Raw disk image (no container) |
| **AMI** | ❌ Not Supported | Amazon Machine Image |
| **GCP tar.gz** | ❌ Not Supported | GCP disk image container |

---

## Cloud Provider Format Analysis

### Amazon Machine Image (AMI)

**Source:** [Wikipedia - Amazon Machine Image](https://en.wikipedia.org/wiki/Amazon_Machine_Image)

AMI is a **container format**, not just a disk image. Structure:

| Component | Description |
|-----------|-------------|
| **XML Manifest** | `image.manifest.xml` containing metadata |
| **Chunked Image** | Disk split into ~10MB encrypted chunks (`image.part.xx`) |
| **Encryption** | AES encryption with keys in manifest |
| **Compression** | Chunks are compressed |
| **Signing** | Digital signatures for integrity |
| **AKI** | Amazon Kernel Image (paravirtualized) |
| **ARI** | Amazon Ramdisk Image |

Manifest XML contains:
- Name, version, architecture
- Default kernel ID
- Decryption keys (encrypted with user's key)
- SHA-1 digests for each chunk
- Block device mappings

**Saffron Support Status: ❌ NOT SUPPORTED**

### Google Cloud Platform (GCP) Image Format

**Source:** [GCP Export Documentation](https://docs.google.com/compute/docs/images/export-image)

GCP uses a **tar.gz container** around a raw disk:

| Component | Description |
|-----------|-------------|
| **Container** | `.tar.gz` archive |
| **Tar format** | Must use `--format=oldgnu` |
| **Inner file** | Must be named `disk.raw` |
| **Compression** | gzip |
| **Size alignment** | Must be multiple of 1 GB |

**Saffron Support Status: ❌ NOT SUPPORTED** (requires tar.gz extraction + RAW disk support)

### Microsoft Azure

**Source:** [Azure VHD Requirements](https://learn.microsoft.com/en-us/azure/virtual-machines/windows/prepare-for-upload-vhd-image)

Azure uses **standard VHD format** with specific requirements:

| Requirement | Description |
|-------------|-------------|
| **Format** | VHD (not VHDX for Gen1) |
| **Type** | Must be fixed-size (not dynamic) |
| **Size alignment** | Must be multiple of 1 MiB |
| **Footer** | 512-byte VHD footer |
| **Gen2 VMs** | Can use VHDX |

**Saffron Support Status: ✅ SUPPORTED** (VHD already implemented with fixed/dynamic support)

---

## Tasks Required for Full Cloud Support

### Task 1: Implement RAW Disk Image Support

**Priority: HIGH** (required for GCP support)

RAW disk images are unformatted byte-for-byte copies of a disk with no container metadata.

Subtasks:
1. Create `RawDiskImpl.java` implementing `VirtualDisk` interface
2. Add `RAW` to `DiskFormat` enum
3. Implement detection (no magic bytes - detect by exclusion or extension `.raw`, `.img`)
4. RAW disks may have partition tables (MBR/GPT) directly at offset 0
5. Add unit tests with raw disk images

Complexity: **LOW** - straightforward passthrough, no header parsing needed

### Task 2: Implement GCP tar.gz Container Support

**Priority: HIGH**

GCP images are `disk.raw` inside a `.tar.gz` archive.

Subtasks:
1. Create `GcpImageReader.java` to extract tar.gz containers
2. Verify tar format is `oldgnu`
3. Extract `disk.raw` to temp file or stream
4. Delegate to RAW disk reader (Task 1)
5. Handle size validation (1 GB alignment)
6. Add unit tests with GCP-format images

Complexity: **MEDIUM** - tar.gz extraction + delegation

### Task 3: Implement AMI Format Support

**Priority: MEDIUM** (AMIs can be exported to VMDK/VHD alternatively)

AMI is a complex encrypted/chunked format.

Subtasks:
1. Create `ami` package under `io.spicelabs.saffron.ami`
2. Implement `AmiManifest.java` - XML manifest parser
   - Parse `image.manifest.xml`
   - Extract chunk list, sizes, digests
   - Extract encryption info
3. Implement `AmiChunkAssembler.java` - reassemble chunks
   - Read `image.part.xx` files
   - Verify SHA-1 digests
   - Concatenate in order
4. Implement `AmiDecryptor.java` - decrypt chunks
   - AES decryption
   - Key handling (may require user-provided key)
5. Implement `AmiDecompressor.java` - decompress chunks
6. Implement `AmiDiskImpl.java` - main entry point
7. Add `AMI` to `DiskFormat` enum
8. Add comprehensive unit tests

Complexity: **HIGH** - encryption, chunking, XML parsing, reassembly

**Alternative:** Users can export AMIs to VMDK/VHD using AWS tools, avoiding need for native AMI support.

### Task 4: Add Cloud-Specific Filesystem Detection

**Priority: LOW** (filesystems already supported)

Cloud images use standard filesystems that Saffron already supports:
- ext4 (Ubuntu, Debian)
- XFS (Amazon Linux, RHEL)
- NTFS (Windows)
- btrfs (Fedora)

No new filesystem implementations needed.

---

## Implementation Priority

| Task | Priority | Complexity | Enables |
|------|----------|------------|---------|
| RAW disk support | HIGH | LOW | GCP images, general use |
| GCP tar.gz support | HIGH | MEDIUM | GCP public images |
| AMI support | MEDIUM | HIGH | AWS native images |

**Recommended order:** RAW → GCP tar.gz → AMI (optional)

---

## Alternative: Export-Based Workflow

Instead of implementing native AMI/GCP support, users can:

1. **AWS AMIs**: Export to VMDK or VHD using `aws ec2 create-instance-export-task`
2. **GCP Images**: Export to VMDK, VHD, VHDX, or QCOW2 using `gcloud compute images export --export-format=vmdk`
3. **Azure**: Already uses VHD natively ✅

This avoids implementing complex formats but requires cloud provider accounts and export steps.

---

## Summary Table

| Provider | Native Format | Saffron Support | Alternative |
|----------|---------------|-----------------|-------------|
| AWS | AMI (encrypted chunks + manifest) | ❌ Not supported | Export to VMDK/VHD |
| GCP | tar.gz(disk.raw) | ❌ Not supported | Export to VMDK/VHD/QCOW2 |
| Azure | VHD | ✅ Supported | N/A |

---

## Sources

- [Amazon Machine Image - Wikipedia](https://en.wikipedia.org/wiki/Amazon_Machine_Image)
- [AWS VM Import Manifest](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/manifest.html)
- [GCP Export Image Documentation](https://docs.google.com/compute/docs/images/export-image)
- [GCP Import Virtual Disks](https://cloud.google.com/compute/docs/import/importing-virtual-disks)
- [Azure VHD Preparation](https://learn.microsoft.com/en-us/azure/virtual-machines/windows/prepare-for-upload-vhd-image)
- [Azure Generation 2 VMs](https://learn.microsoft.com/en-us/azure/virtual-machines/generation-2)
