# Cloud VM Test Corpus Expansion Plan

## Overview

This plan outlines how to expand the Saffron test corpus to include VM images from major cloud providers (AWS, Google Cloud, Azure) to ensure comprehensive testing of all supported filesystem types across all VM formats.

## Current Format Support Status

| Format | Status | Extension(s) |
|--------|--------|--------------|
| QCOW2 | ✅ Supported | .qcow2 |
| VMDK | ✅ Supported | .vmdk |
| VHD | ✅ Supported | .vhd |
| VHDX | ✅ Supported | .vhdx |
| VDI | ✅ Supported | .vdi |
| RAW | ⚠️ Partial | .img, .raw |

## Cloud Provider Format Analysis

### Amazon AWS (EC2/AMI)
- **Native Format**: Proprietary (EBS snapshots)
- **Export Formats**: VMDK, VHD, VHDX, RAW
- **Download Availability**: Cannot download public AMIs directly; must export your own
- **Primary Filesystems**: XFS (Amazon Linux 2+), ext4 (older)

### Google Cloud Platform (GCE)
- **Native Format**: disk.raw in tar.gz
- **Export Formats**: VMDK, VHDX, VHD, VDI, QCOW2, RAW
- **Download Availability**: Can export public images via `gcloud`
- **Primary Filesystems**: ext4, XFS

### Microsoft Azure
- **Native Format**: Fixed VHD (already supported!)
- **Export Formats**: VHD only
- **Download Availability**: Can download your own disks
- **Primary Filesystems**: ext4, XFS, NTFS

## Key Finding

**Cloud providers do not require new format implementations** - they all export to formats Saffron already supports. The focus should be on:
1. Obtaining representative cloud images in supported formats
2. Testing various filesystem types used by cloud providers

## Test Corpus Acquisition Plan

### Phase 1: Direct Download Sources (No Account Required)

These sources provide VM images that can be downloaded directly:

#### Ubuntu Cloud Images (cloud-images.ubuntu.com)
Target: 10 images in multiple formats
- Ubuntu 24.04 LTS (QCOW2, VMDK, VHD)
- Ubuntu 22.04 LTS (QCOW2, VMDK, VHD)
- Ubuntu 20.04 LTS (QCOW2, VMDK, VHD)
- Ubuntu 18.04 LTS (QCOW2)

#### Debian Cloud Images (cloud.debian.org)
Target: 5 images
- Debian 12 (QCOW2, RAW)
- Debian 11 (QCOW2, RAW)

#### CentOS/Rocky/Alma Cloud Images
Target: 10 images
- AlmaLinux 9 (QCOW2, VMDK, VHD)
- Rocky Linux 9 (QCOW2, VMDK)
- CentOS Stream 9 (QCOW2)

#### Fedora Cloud Images
Target: 5 images
- Fedora 39/40 Cloud (QCOW2, RAW)

### Phase 2: GCP Export (Requires GCP Account)

Export public images using:
```bash
gcloud compute images export \
  --image-project=ubuntu-os-cloud \
  --image=ubuntu-2204-jammy-v20240xxx \
  --destination-uri=gs://bucket/ubuntu-2204.vmdk \
  --export-format=vmdk
```

Target: 15 images across formats
- Ubuntu variants (VMDK, VHD, VDI)
- Debian variants (VMDK, QCOW2)
- CentOS/RHEL variants (VMDK, VHD)
- Windows Server (VMDK) - NTFS filesystem

### Phase 3: Azure VHD Collection (Requires Azure Account)

Export VHDs from deployed VMs:
```bash
az disk grant-access --resource-group myRG --name myDisk --duration-in-seconds 3600 --access-level Read
```

Target: 10 VHD images
- Ubuntu Azure-optimized images
- Windows Server images (NTFS)
- RHEL/CentOS Azure images

### Phase 4: AWS Export (Requires AWS Account)

Export AMIs to S3:
```bash
aws ec2 export-image \
  --image-id ami-xxxxxxxxx \
  --disk-image-format VMDK \
  --s3-export-location S3Bucket=mybucket,S3Prefix=exports/
```

Target: 10 images
- Amazon Linux 2023 (XFS filesystem)
- Amazon Linux 2 (XFS filesystem)
- Ubuntu AMIs (ext4)
- Windows AMIs (NTFS)

## Filesystem Coverage Matrix

| Filesystem | Linux Distros | Cloud Provider Focus |
|------------|---------------|---------------------|
| ext4 | Ubuntu, Debian, older Amazon Linux | All providers |
| XFS | Amazon Linux 2+, RHEL, Rocky, Alma | AWS, GCP |
| NTFS | Windows Server | Azure, AWS, GCP |
| btrfs | openSUSE, Fedora | GCP |

## Recommended Minimum Corpus (30+ per format type)

### QCOW2 (Already well-covered, expand to 40+)
- 15 existing modern Linux images
- 10 additional Ubuntu/Debian cloud images
- 10 CentOS/Rocky/Alma images
- 5 Fedora images

### VMDK (Target: 35 images)
- 10 from direct download sources
- 15 from GCP export
- 10 from AWS export

### VHD (Target: 35 images)
- 10 from direct download (Ubuntu, etc.)
- 10 from Azure export
- 10 from GCP export
- 5 legacy Windows VHDs (existing corpus)

### VHDX (Target: 30 images)
- 10 from GCP export
- 10 converted from other formats using qemu-img
- 10 Hyper-V specific images

### VDI (Target: 30 images)
- 10 from direct download
- 10 from GCP export
- 10 VirtualBox appliances from osboxes.org

## Conversion Tools

For creating additional format variants:
```bash
# QCOW2 to VMDK
qemu-img convert -f qcow2 -O vmdk input.qcow2 output.vmdk

# QCOW2 to VHD (fixed)
qemu-img convert -f qcow2 -O vpc -o subformat=fixed input.qcow2 output.vhd

# QCOW2 to VHDX
qemu-img convert -f qcow2 -O vhdx input.qcow2 output.vhdx

# QCOW2 to VDI
qemu-img convert -f qcow2 -O vdi input.qcow2 output.vdi
```

## Implementation Steps

1. **Create acquisition scripts** in `scripts/corpus-download/`:
   - `download-ubuntu-cloud.sh`
   - `download-debian-cloud.sh`
   - `download-centos-cloud.sh`
   - `export-gcp-images.sh`
   - `export-azure-vhds.sh`
   - `export-aws-amis.sh`

2. **Organize corpus structure**:
   ```
   test-corpus/
   ├── qcow2/
   │   ├── modern/          # Current images
   │   └── cloud/           # New cloud images
   ├── vmdk/
   │   ├── cloud-gcp/
   │   └── cloud-aws/
   ├── vhd/
   │   ├── legacy/          # Current images
   │   └── cloud-azure/
   ├── vhdx/
   │   └── cloud/
   └── vdi/
       └── cloud/
   ```

3. **Generate verification data** for all new images using existing `CorpusVerificationGenerator`

4. **Add filesystem type validation** to ensure coverage of ext4, XFS, NTFS, btrfs

## Timeline Estimate

- Phase 1 (Direct downloads): 1-2 days
- Phase 2 (GCP export): 2-3 days
- Phase 3 (Azure export): 2-3 days
- Phase 4 (AWS export): 2-3 days
- Verification data generation: 1 day
- Test validation: 1-2 days

**Total: ~2 weeks**

## Success Criteria

- [ ] 30+ QCOW2 images with verification data
- [ ] 30+ VMDK images with verification data
- [ ] 30+ VHD images with verification data
- [ ] 30+ VHDX images with verification data
- [ ] 30+ VDI images with verification data
- [ ] All major filesystems tested (ext4, XFS, NTFS, btrfs)
- [ ] At least 5 images from each cloud provider export
- [ ] All corpus verification tests passing
