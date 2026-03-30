# Corpus Scanner

Generates ground truth JSON files for Saffron verification tests by scanning VM disk images using libguestfs.

## Overview

The scanner:
1. Opens each VM disk image (VDI, VMDK, QCOW2, etc.)
2. Discovers all filesystems (partitions, LVM logical volumes)
3. Classifies each filesystem (root, boot, home, swap, etc.)
4. Walks each filesystem to count files and directories
5. Selects 20 random sample files and computes SHA256 hashes
6. Writes a JSON ground truth file

## Usage

### Quick Scan (Single Image)

```bash
./run.sh ubuntu-22.04-vbox.vdi
```

### Full Corpus Scan (All Images)

```bash
./regenerate-all.sh
```

### Sample Scan (N Random Images)

```bash
./regenerate-all.sh --sample 5
```

### Verify Existing JSON

```bash
./regenerate-all.sh --verify
```

## JSON Output Format

```json
{
  "imagePath": "/corpus/vdi/modern/ubuntu-22.04-vbox.vdi",
  "imageBasename": "ubuntu-22.04-vbox.vdi",
  "filesystemCount": 3,
  "totalFiles": 128783,
  "totalDirectories": 15621,
  "filesystems": [
    {
      "device": "/dev/sda2",
      "fstype": "vfat",
      "fileCount": 8,
      "directoryCount": 3,
      "purpose": "boot",
      "isMountable": true,
      "mountPoint": "/boot/efi",
      "expectedPaths": ["/EFI", "/EFI/BOOT"],
      "sampleFiles": [...]
    },
    {
      "device": "/dev/vgubuntu/root",
      "fstype": "ext4",
      "fileCount": 128775,
      "directoryCount": 15618,
      "purpose": "root",
      "isMountable": true,
      "mountPoint": "/",
      "expectedPaths": ["/etc", "/bin", "/usr", "/etc/debian_version"],
      "sampleFiles": [...]
    },
    {
      "device": "/dev/vgubuntu/swap_1",
      "fstype": "swap",
      "fileCount": 0,
      "directoryCount": 0,
      "purpose": "swap",
      "isMountable": false,
      "mountPoint": null,
      "expectedPaths": [],
      "sampleFiles": []
    }
  ]
}
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `device` | string | Device path (e.g., "/dev/sda2", "/dev/vgubuntu/root") |
| `fstype` | string | Filesystem type (ext4, vfat, xfs, swap, etc.) |
| `fileCount` | int | Number of regular files |
| `directoryCount` | int | Number of directories |
| `purpose` | string | Classification: root, boot, home, var, opt, data, swap, encrypted, unknown |
| `isMountable` | boolean | Whether filesystem can be mounted (false for swap, LUKS) |
| `mountPoint` | string | Expected mount point ("/", "/boot/efi", "/home", etc.) |
| `expectedPaths` | array | Paths that must exist (e.g., "/etc", "/bin" for root) |
| `sampleFiles` | array | Random sample files with SHA256 for verification |

## Filesystem Classification

The scanner classifies filesystems by inspecting their contents:

| Purpose | Detection Criteria |
|---------|-------------------|
| root | Has /etc, /bin, /usr directories |
| boot | vfat with /EFI or /boot directory |
| home | Has /home with user directories, no /etc |
| var | Has /var/log or /var/lib, no /usr |
| opt | Has /opt with content, no /etc |
| data | No system directories |
| swap | fstype == "swap" |
| encrypted | fstype == "crypto_LUKS" |

## Requirements

- Docker (the scanner runs in a container with libguestfs)
- KVM device access for acceleration (optional)
- Corpus directory at `../../test-corpus`

## Docker Image

The Dockerfile installs:
- libguestfs-tools (for guestfs Python bindings)
- Python 3 with guestfs module

Build:
```bash
docker build -t saffron-corpus-scanner .
```

## Performance

Scanning 76 images (~150GB) takes approximately:
- With KVM: 2-3 hours
- Without KVM: 6-8 hours

## Testing

Run unit tests for classification logic:

```bash
python3 test_scanner.py
```

## Troubleshooting

### Permission Denied on /dev/kvm

The scanner works without KVM but is slower. To use KVM:
```bash
sudo chmod 666 /dev/kvm
```

### Image Format Not Recognized

Some older or unusual image formats may not be recognized by libguestfs. These will be skipped with an error message.

### Out of Memory

Large images may cause memory issues. The scanner limits sample files to 256MB to avoid this.
