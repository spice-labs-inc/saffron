# Cloud VM Corpus Download Plan

## Goal
Download at least 30 native format images for each supported VM type, covering all supported filesystems.

## Current Status

| Format | Current | Target | Gap |
|--------|---------|--------|-----|
| QCOW2  | 41      | 30+    | ✓ Met |
| VMDK   | 9       | 30+    | Need 21+ |
| VHD    | 6       | 30+    | Need 24+ |
| VHDX   | 0       | 30+    | Need 30+ |
| VDI    | 10      | 30+    | Need 20+ |

## Supported Filesystems to Cover

| Filesystem | Common Sources |
|------------|----------------|
| ext4       | Ubuntu, Debian, most Linux |
| ext3/ext2  | Older Linux, legacy images |
| XFS        | RHEL, CentOS, Amazon Linux, Rocky, AlmaLinux |
| NTFS       | Windows Server, Windows Desktop |
| FAT32      | EFI partitions, DOS/Windows 9x |
| exFAT      | Large removable media |
| btrfs      | Fedora, openSUSE |

## Native Format Sources by VM Type

### QCOW2 (Target: 30+ ✓ Already Met)

Direct download sources (no conversion):

| Source | Filesystem | URL Pattern |
|--------|------------|-------------|
| Ubuntu Cloud | ext4 | cloud-images.ubuntu.com |
| Debian Cloud | ext4 | cloud.debian.org |
| Fedora Cloud | ext4/btrfs | fedoraproject.org |
| CentOS Stream | XFS | cloud.centos.org |
| AlmaLinux | XFS | repo.almalinux.org |
| Rocky Linux | XFS | dl.rockylinux.org |
| Amazon Linux 2/2023 | XFS | cdn.amazonlinux.com |
| openSUSE | btrfs/XFS | download.opensuse.org |
| Alpine Linux | ext4 | alpinelinux.org |
| Arch Linux | ext4 | gitlab.archlinux.org |
| Gentoo | ext4 | gentoo.org |
| FreeBSD | UFS/ZFS | freebsd.org |

### VMDK (Target: 30+, Need 21+)

Native VMDK sources (distributed as VMDK, not converted):

| Source | Filesystem | Notes |
|--------|------------|-------|
| **VMware Photon OS** | ext4 | Official VMware Linux - OVA contains VMDK |
| **openSUSE VMware** | btrfs/XFS | Native VMDK variant available |
| **TurnKey Linux** | ext4 | Debian-based appliances in VMDK |
| **Bitnami VMs** | ext4/XFS | Application stacks in OVA/VMDK |
| **GCP Image Export** | ext4/XFS | Export public images to VMDK |
| **AWS AMI Export** | XFS/ext4 | Export to VMDK format |
| **pfSense** | UFS | Firewall appliance VMDK |
| **OPNsense** | UFS | Firewall appliance VMDK |
| **VyOS** | ext4 | Router appliance VMDK |
| **Kali Linux** | ext4 | Security distro VMware image |
| **Parrot OS** | ext4 | Security distro VMware image |
| **Ubuntu VMware** | ext4 | osboxes.org native VMDK |

### VHD (Target: 30+, Need 24+)

Native VHD sources:

| Source | Filesystem | Notes |
|--------|------------|-------|
| **Ubuntu Azure** | ext4 | cloud-images.ubuntu.com/*-azure.vhd |
| **Debian Azure** | ext4 | Azure marketplace |
| **RHEL Azure** | XFS | Azure marketplace |
| **CentOS Azure** | XFS | Azure marketplace |
| **Windows Server Eval** | NTFS | Microsoft Evaluation Center |
| **Windows 10/11 Dev** | NTFS | developer.microsoft.com |
| **FreeBSD** | UFS | freebsd.org releases VHD |
| **Azure Linux (CBL-Mariner)** | ext4 | Microsoft's Linux distro |
| **Flatcar Linux** | ext4 | Azure variant |
| **Legacy Windows** | FAT32/NTFS | Archive.org, WinWorld |

### VHDX (Target: 30+, Need 30+)

Native VHDX sources (Hyper-V format):

| Source | Filesystem | Notes |
|--------|------------|-------|
| **Windows Server Eval** | NTFS | Microsoft Evaluation Center - Hyper-V format |
| **Windows 11 Dev** | NTFS | developer.microsoft.com - Hyper-V |
| **Ubuntu Hyper-V** | ext4 | cloud-images.ubuntu.com (if available) |
| **Debian Hyper-V** | ext4 | Azure/Hyper-V optimized |
| **GCP Export** | various | Export to VHDX format |
| **Kali Hyper-V** | ext4 | kali.org Hyper-V image |
| **FreeBSD Hyper-V** | UFS | freebsd.org Hyper-V variant |

### VDI (Target: 30+, Need 20+)

Native VDI sources (VirtualBox format):

| Source | Filesystem | Notes |
|--------|------------|-------|
| **osboxes.org** | various | Pre-built VDI for most distros |
| **Kali VirtualBox** | ext4 | kali.org VirtualBox image |
| **Parrot OS** | ext4 | VirtualBox image |
| **Tails** | ext4 | Privacy-focused OS |
| **Whonix** | ext4 | VirtualBox-native |
| **ReactOS** | FAT32 | Open-source Windows clone |
| **Haiku** | BFS | BeOS successor |
| **FreeDOS** | FAT16/32 | DOS-compatible |

---

## Download Strategy by Cloud Provider

### Amazon Web Services (AWS)

**Direct Downloads (no account needed):**
- Amazon Linux 2: `https://cdn.amazonlinux.com/os-images/2.0.*/kvm/*.qcow2`
- Amazon Linux 2023: `https://cdn.amazonlinux.com/al2023/os-images/*/*.qcow2`

**AMI Export (requires AWS account):**
1. Copy public AMI to your account
2. Create instance, stop it
3. Export using VM Import/Export to S3
4. Formats: VMDK, VHD, RAW

Target AMIs for export:
- Amazon Linux 2 (XFS)
- Amazon Linux 2023 (XFS)
- Ubuntu variants (ext4)
- RHEL variants (XFS)
- Windows Server (NTFS)

### Google Cloud Platform (GCP)

**Image Export (requires GCP account):**
```bash
gcloud compute images export \
  --image=IMAGE_NAME \
  --destination-uri=gs://BUCKET/image.vmdk \
  --export-format=vmdk  # or vhdx, vpc (VHD), qcow2
```

Target images:
- `ubuntu-os-cloud/ubuntu-2404-lts` (ext4)
- `debian-cloud/debian-12` (ext4)
- `centos-cloud/centos-stream-9` (XFS)
- `rocky-linux-cloud/rocky-linux-9` (XFS)
- `rhel-cloud/rhel-9` (XFS)
- `windows-cloud/windows-server-2022-dc` (NTFS)
- `windows-cloud/windows-server-2019-dc` (NTFS)

Export formats available: VMDK, VHDX, VHD (vpc), QCOW2

### Microsoft Azure

**Native VHD Sources:**
- Ubuntu Azure images from Canonical
- All Azure Marketplace images are VHD internally

**Manual Download (Evaluation Center):**
- Windows Server 2022 Evaluation VHD
- Windows Server 2019 Evaluation VHD
- Windows 11 Enterprise Evaluation VHD

**Azure CLI Export:**
```bash
# Create managed disk from marketplace image
az disk create --name export-disk --image-reference Publisher:Offer:Sku:latest

# Grant SAS access
az disk grant-access --name export-disk --duration-in-seconds 3600

# Download VHD via SAS URL
```

---

## Filesystem Coverage Matrix

Target: Each filesystem tested in at least 3 different VM formats

| Filesystem | QCOW2 | VMDK | VHD | VHDX | VDI |
|------------|-------|------|-----|------|-----|
| ext4 | Ubuntu, Debian, Fedora | Photon, TurnKey | Ubuntu Azure | Ubuntu Hyper-V | osboxes Ubuntu |
| XFS | Amazon Linux, Rocky, Alma | GCP export | RHEL Azure | GCP export | osboxes CentOS |
| NTFS | - | Windows VMware | Windows Server | Windows Server | Windows VDI |
| btrfs | Fedora, openSUSE | openSUSE VMware | - | - | osboxes Fedora |
| FAT32 | (EFI partitions) | FreeDOS | Legacy Windows | - | FreeDOS |
| UFS | FreeBSD | pfSense | FreeBSD | FreeBSD Hyper-V | - |
| exFAT | - | - | - | - | - |

---

## Download Scripts Required

### Existing Scripts
- `download-ubuntu-cloud.sh` - Ubuntu QCOW2
- `download-debian-cloud.sh` - Debian QCOW2
- `download-fedora-cloud.sh` - Fedora QCOW2
- `download-rhel-clones-cloud.sh` - Rocky, Alma, CentOS QCOW2
- `download-vmdk-native.sh` - Photon, TurnKey VMDK
- `download-vhd-native.sh` - Ubuntu Azure VHD
- `export-gcp-images.sh` - GCP exports
- `export-azure-vhds.sh` - Azure exports
- `export-aws-amis.sh` - AWS exports

### Scripts to Create
1. `download-vmdk-appliances.sh` - pfSense, OPNsense, VyOS, Bitnami
2. `download-vhd-windows.sh` - Instructions for Windows Eval VHDs
3. `download-vhdx-hyperv.sh` - Hyper-V images, Windows VHDX
4. `download-vdi-virtualbox.sh` - osboxes, Kali, security distros
5. `download-freebsd.sh` - FreeBSD in all formats
6. `download-security-distros.sh` - Kali, Parrot in multiple formats

---

## Estimated Final Counts

| Format | Sources | Estimated Count |
|--------|---------|-----------------|
| QCOW2 | Cloud images + FreeBSD | 45+ |
| VMDK | Photon + appliances + GCP exports + osboxes | 35+ |
| VHD | Ubuntu/Debian Azure + Windows + FreeBSD | 30+ |
| VHDX | GCP exports + Windows Hyper-V + Kali | 30+ |
| VDI | osboxes + security distros | 35+ |

---

## Priority Download Order

### Phase 1: Free Direct Downloads (No Account)
1. ✓ Ubuntu/Debian/Fedora/RHEL-clones QCOW2
2. ✓ VMware Photon VMDK
3. ✓ Ubuntu Azure VHD
4. FreeBSD (all formats)
5. Security distros (Kali, Parrot - multiple formats)
6. Network appliances (pfSense, OPNsense, VyOS)
7. osboxes.org VDI images (manual download)

### Phase 2: Manual Downloads (Free, requires registration)
1. Windows Server Evaluation VHD/VHDX
2. Windows 11 Dev VHD/VHDX
3. Bitnami application stacks

### Phase 3: Cloud Provider Exports (Requires Account + May Cost $)
1. GCP image exports (VMDK, VHDX, VHD)
2. AWS AMI exports (VMDK, VHD)
3. Azure marketplace exports

---

## Notes

- All images should be **native format** - no synthetic conversions
- Focus on real-world images that users would actually encounter
- Include both modern and legacy images where available
- Windows images essential for NTFS testing
- Cloud provider exports provide additional format diversity
