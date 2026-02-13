#!/bin/bash
# =============================================================================
# Saffron Full Test Corpus Acquisition Script
# =============================================================================
# Downloads REAL VM images in their NATIVE formats from the Internet.
#
# CRITICAL: We need images created by native tools:
#   - VMDK files created by VMware (not converted)
#   - VDI files created by VirtualBox (not converted)
#   - VHD/VHDX files created by Microsoft Hyper-V (not converted)
#   - QCOW2 files created by QEMU/KVM (not converted)
#
# Converting between formats is WRONG - it doesn't test real format parsing.
#
# Requirements:
#   - 200+ total images
#   - 100+ legacy images (2005-2010)
#   - VMDK: 50+, QCOW2: 50+, VHD: 30+, VHDX: 20+, VDI: 50+
#
# Usage:
#   ./scripts/acquire-full-corpus.sh
# =============================================================================

set -euo pipefail

CORPUS_DIR="${CORPUS_DIR:-./test-corpus}"
MANIFEST_FILE="$CORPUS_DIR/manifest.json"
LOG_FILE="$CORPUS_DIR/acquisition.log"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $*" | tee -a "$LOG_FILE"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] WARN:${NC} $*" | tee -a "$LOG_FILE"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] ERROR:${NC} $*" | tee -a "$LOG_FILE" >&2; }

# Initialize
init() {
    mkdir -p "$CORPUS_DIR"/{qcow2,vmdk,vhd,vhdx,vdi}/{legacy,modern}
    mkdir -p "$CORPUS_DIR"/scan-reports
    echo "=== Corpus Acquisition Started $(date) ===" >> "$LOG_FILE"

    if [ ! -s "$MANIFEST_FILE" ] || ! jq -e '.images' "$MANIFEST_FILE" >/dev/null 2>&1; then
        cat > "$MANIFEST_FILE" << 'EOF'
{
  "version": "1.0",
  "generated": "",
  "description": "Saffron test corpus - NATIVE format VM images only",
  "total_images": 0,
  "images": []
}
EOF
    fi
}

# Download helper with retry
download() {
    local url="$1"
    local dest="$2"
    local name="$3"

    if [ -f "$dest" ]; then
        log "Already exists: $name"
        return 0
    fi

    log "Downloading: $name"
    mkdir -p "$(dirname "$dest")"

    if wget -q --show-progress --retry-connrefused --waitretry=2 \
            --timeout=120 -t 3 -O "$dest.tmp" "$url" 2>&1; then
        mv "$dest.tmp" "$dest"
        log "Downloaded: $name ($(du -h "$dest" | cut -f1))"
        return 0
    else
        rm -f "$dest.tmp"
        error "Failed to download: $name"
        return 1
    fi
}

# Add image to manifest
catalog() {
    local id="$1"
    local path="$2"
    local format="$3"
    local era="$4"
    local year="$5"
    local url="$6"
    local license="$7"
    local os="${8:-}"
    local filesystem="${9:-}"
    local ci_tier="${10:-full}"

    local full_path="$CORPUS_DIR/$path"
    [ -f "$full_path" ] || return 1

    local sha256=$(sha256sum "$full_path" | cut -d' ' -f1)
    local size=$(stat -c%s "$full_path")

    if jq -e ".images[] | select(.id == \"$id\")" "$MANIFEST_FILE" >/dev/null 2>&1; then
        log "Already cataloged: $id"
        return 0
    fi

    local tmp=$(mktemp)
    jq --arg id "$id" \
       --arg path "$path" \
       --arg format "$format" \
       --arg era "$era" \
       --argjson year "$year" \
       --arg url "$url" \
       --arg license "$license" \
       --arg os "$os" \
       --arg fs "$filesystem" \
       --arg sha "$sha256" \
       --argjson size "$size" \
       --arg tier "$ci_tier" \
       --arg date "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
       '.images += [{
         "id": $id,
         "path": $path,
         "format": $format,
         "era": $era,
         "year": $year,
         "source_url": $url,
         "license": $license,
         "os": $os,
         "filesystem": $fs,
         "sha256": $sha,
         "actual_size_bytes": $size,
         "ci_tier": $tier,
         "native_format": true,
         "provenance": {
           "source_url": $url,
           "download_date": $date,
           "download_sha256": $sha,
           "license": $license
         }
       }] | .total_images = (.images | length) | .generated = $date' \
       "$MANIFEST_FILE" > "$tmp" && mv "$tmp" "$MANIFEST_FILE"

    log "Cataloged: $id"
}

# =============================================================================
# QCOW2 IMAGES - Created by QEMU/KVM (Target: 50+)
# =============================================================================

download_qcow2_images() {
    log "=== Downloading QCOW2 Images (Target: 50+) ==="
    log "Source: Linux cloud images - native QCOW2 format"

    # --- CirrOS (tiny test images, native QCOW2) ---
    log "--- CirrOS Images (native QCOW2 from cirros-cloud.net) ---"
    local cirros_versions=("0.6.2" "0.6.1" "0.6.0" "0.5.2" "0.5.1" "0.4.0" "0.3.6")
    for ver in "${cirros_versions[@]}"; do
        local url="https://download.cirros-cloud.net/$ver/cirros-$ver-x86_64-disk.img"
        local dest="qcow2/modern/cirros-$ver-x86_64.qcow2"
        local year=2020
        [[ "$ver" == "0.3"* ]] && year=2014
        [[ "$ver" == "0.4"* ]] && year=2017
        [[ "$ver" == "0.5"* ]] && year=2019
        download "$url" "$CORPUS_DIR/$dest" "CirrOS $ver QCOW2" && \
        catalog "cirros-$ver-qcow2" "$dest" "qcow2" "modern" "$year" "$url" "GPL-2.0" "CirrOS $ver" "ext3" "quick"
    done

    # --- Ubuntu Cloud Images (native QCOW2) ---
    log "--- Ubuntu Cloud Images (native QCOW2) ---"
    for codename in noble jammy focal bionic; do
        local version=""
        local year=""
        case $codename in
            noble) version="24.04"; year=2024 ;;
            jammy) version="22.04"; year=2022 ;;
            focal) version="20.04"; year=2020 ;;
            bionic) version="18.04"; year=2018 ;;
        esac
        local url="https://cloud-images.ubuntu.com/$codename/current/${codename}-server-cloudimg-amd64.img"
        local dest="qcow2/modern/ubuntu-$version-cloudimg-amd64.qcow2"
        download "$url" "$CORPUS_DIR/$dest" "Ubuntu $version Cloud" && \
        catalog "ubuntu-$version-cloud-qcow2" "$dest" "qcow2" "modern" "$year" "$url" "GPL" "Ubuntu $version" "ext4" "standard"
    done

    # --- Debian Cloud Images (native QCOW2) ---
    log "--- Debian Cloud Images (native QCOW2) ---"
    for ver in 12 11 10; do
        local codename=""
        local year=""
        case $ver in
            12) codename="bookworm"; year=2023 ;;
            11) codename="bullseye"; year=2021 ;;
            10) codename="buster"; year=2019 ;;
        esac
        local url="https://cloud.debian.org/images/cloud/$codename/latest/debian-$ver-generic-amd64.qcow2"
        local dest="qcow2/modern/debian-$ver-generic-amd64.qcow2"
        download "$url" "$CORPUS_DIR/$dest" "Debian $ver Cloud" && \
        catalog "debian-$ver-cloud-qcow2" "$dest" "qcow2" "modern" "$year" "$url" "DFSG" "Debian $ver" "ext4" "standard"
    done

    # --- Rocky/Alma Linux (native QCOW2) ---
    log "--- Rocky/Alma Linux Cloud Images (native QCOW2) ---"
    for ver in 9 8; do
        # Rocky Linux
        local url="https://download.rockylinux.org/pub/rocky/$ver/images/x86_64/Rocky-$ver-GenericCloud.latest.x86_64.qcow2"
        local dest="qcow2/modern/rocky-$ver-cloud-amd64.qcow2"
        download "$url" "$CORPUS_DIR/$dest" "Rocky Linux $ver Cloud" && \
        catalog "rocky-$ver-cloud-qcow2" "$dest" "qcow2" "modern" "2022" "$url" "BSD" "Rocky Linux $ver" "xfs" "standard"

        # AlmaLinux
        url="https://repo.almalinux.org/almalinux/$ver/cloud/x86_64/images/AlmaLinux-$ver-GenericCloud-latest.x86_64.qcow2"
        dest="qcow2/modern/almalinux-$ver-cloud-amd64.qcow2"
        download "$url" "$CORPUS_DIR/$dest" "AlmaLinux $ver Cloud" && \
        catalog "almalinux-$ver-cloud-qcow2" "$dest" "qcow2" "modern" "2022" "$url" "GPL" "AlmaLinux $ver" "xfs" "standard"
    done

    # --- Alpine Linux (native QCOW2) ---
    log "--- Alpine Linux (native QCOW2) ---"
    local url="https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/cloud/nocloud_alpine-3.19.0-x86_64-bios-cloudinit-r0.qcow2"
    local dest="qcow2/modern/alpine-3.19-cloud-amd64.qcow2"
    download "$url" "$CORPUS_DIR/$dest" "Alpine 3.19 Cloud" && \
    catalog "alpine-3.19-cloud-qcow2" "$dest" "qcow2" "modern" "2023" "$url" "MIT" "Alpine 3.19" "ext4" "quick"

    # --- OpenWrt (native for embedded routers) ---
    log "--- OpenWrt Images ---"
    local url="https://downloads.openwrt.org/releases/23.05.2/targets/x86/64/openwrt-23.05.2-x86-64-generic-ext4-combined.img.gz"
    local dest="qcow2/modern/openwrt-23.05.2-x86-64.qcow2"
    if [ -f "$CORPUS_DIR/$dest" ]; then
        log "Already exists: OpenWrt 23.05.2"
    elif download "$url" "$CORPUS_DIR/$dest.gz" "OpenWrt 23.05.2"; then
        gunzip -f "$CORPUS_DIR/$dest.gz" 2>/dev/null || mv "$CORPUS_DIR/$dest.gz" "$CORPUS_DIR/$dest" 2>/dev/null || true
    fi
    [ -f "$CORPUS_DIR/$dest" ] && catalog "openwrt-23.05.2-qcow2" "$dest" "qcow2" "modern" "2023" "$url" "GPL-2.0" "OpenWrt 23.05.2" "ext4" "quick"

    # --- Arch Linux (native QCOW2) ---
    log "--- Arch Linux (native QCOW2) ---"
    url="https://geo.mirror.pkgbuild.com/images/latest/Arch-Linux-x86_64-cloudimg.qcow2"
    dest="qcow2/modern/archlinux-latest-cloudimg.qcow2"
    download "$url" "$CORPUS_DIR/$dest" "Arch Linux Cloud" && \
    catalog "archlinux-latest-cloud-qcow2" "$dest" "qcow2" "modern" "2024" "$url" "GPL" "Arch Linux" "ext4" "standard"

    # --- Fedora Cloud Images (native QCOW2) ---
    log "--- Fedora Cloud Images (native QCOW2) ---"
    for ver in 40 39 38 37; do
        url="https://download.fedoraproject.org/pub/fedora/linux/releases/$ver/Cloud/x86_64/images/Fedora-Cloud-Base-Generic.x86_64-$ver-1.6.qcow2"
        dest="qcow2/modern/fedora-$ver-cloud-amd64.qcow2"
        download "$url" "$CORPUS_DIR/$dest" "Fedora $ver Cloud" && \
        catalog "fedora-$ver-cloud-qcow2" "$dest" "qcow2" "modern" "2023" "$url" "MIT" "Fedora $ver" "ext4" "standard"
    done

    # --- openSUSE Cloud Images (native QCOW2) ---
    log "--- openSUSE Cloud Images (native QCOW2) ---"
    url="https://download.opensuse.org/distribution/leap/15.5/appliances/openSUSE-Leap-15.5-Minimal-VM.x86_64-Cloud.qcow2"
    dest="qcow2/modern/opensuse-leap-15.5.qcow2"
    download "$url" "$CORPUS_DIR/$dest" "openSUSE Leap 15.5 Cloud" && \
    catalog "opensuse-15.5-cloud-qcow2" "$dest" "qcow2" "modern" "2023" "$url" "GPL" "openSUSE 15.5" "btrfs" "standard"

    # openSUSE Tumbleweed
    url="https://download.opensuse.org/tumbleweed/appliances/openSUSE-Tumbleweed-Minimal-VM.x86_64-Cloud.qcow2"
    dest="qcow2/modern/opensuse-tumbleweed-cloud.qcow2"
    download "$url" "$CORPUS_DIR/$dest" "openSUSE Tumbleweed Cloud" && \
    catalog "opensuse-tw-cloud-qcow2" "$dest" "qcow2" "modern" "2024" "$url" "GPL" "openSUSE Tumbleweed" "btrfs" "standard"

    # --- Gentoo Cloud Image ---
    log "--- Gentoo Cloud Image ---"
    url="https://gentoo.osuosl.org/experimental/amd64/openstack/gentoo-openstack-amd64-default-latest.qcow2"
    dest="qcow2/modern/gentoo-openstack-amd64.qcow2"
    download "$url" "$CORPUS_DIR/$dest" "Gentoo OpenStack" && \
    catalog "gentoo-openstack-qcow2" "$dest" "qcow2" "modern" "2024" "$url" "GPL" "Gentoo" "ext4" "full"

    # --- FreeBSD Cloud Images (native QCOW2) ---
    log "--- FreeBSD Cloud Images (native QCOW2) ---"
    for ver in 14.0 13.2; do
        url="https://download.freebsd.org/releases/VM-IMAGES/$ver-RELEASE/amd64/Latest/FreeBSD-$ver-RELEASE-amd64.qcow2.xz"
        dest="qcow2/modern/freebsd-$ver-amd64.qcow2"
        if [ ! -f "$CORPUS_DIR/$dest" ]; then
            if download "$url" "$CORPUS_DIR/$dest.xz" "FreeBSD $ver Cloud"; then
                xz -d "$CORPUS_DIR/$dest.xz" 2>/dev/null || true
            fi
        fi
        [ -f "$CORPUS_DIR/$dest" ] && \
        catalog "freebsd-$ver-qcow2" "$dest" "qcow2" "modern" "2023" "$url" "BSD" "FreeBSD $ver" "ufs" "full"
    done

    # --- Oracle Linux (native QCOW2) ---
    log "--- Oracle Linux Cloud Images ---"
    url="https://yum.oracle.com/templates/OracleLinux/OL9/u3/x86_64/OL9U3_x86_64-kvm-b220.qcow2"
    dest="qcow2/modern/oracle-linux-9-amd64.qcow2"
    download "$url" "$CORPUS_DIR/$dest" "Oracle Linux 9 Cloud" && \
    catalog "oracle-9-cloud-qcow2" "$dest" "qcow2" "modern" "2024" "$url" "GPL" "Oracle Linux 9" "xfs" "standard"

    # --- Kali Linux (native QCOW2) ---
    log "--- Kali Linux Cloud Image ---"
    url="https://cdimage.kali.org/kali-2024.3/kali-linux-2024.3-qemu-amd64.7z"
    dest="qcow2/modern/kali-2024.3-qcow2.qcow2"
    if [ ! -f "$CORPUS_DIR/$dest" ]; then
        local archive="$CORPUS_DIR/qcow2/modern/kali-2024.3.7z"
        if download "$url" "$archive" "Kali 2024.3 QCOW2"; then
            local before_extract=$(find "$CORPUS_DIR/qcow2/modern/" -name "*.qcow2" 2>/dev/null | sort)
            if command -v 7z &>/dev/null; then
                7z x -o"$CORPUS_DIR/qcow2/modern/" "$archive" -y >/dev/null 2>&1 || true
            fi
            rm -f "$archive"
            local after_extract=$(find "$CORPUS_DIR/qcow2/modern/" -name "*.qcow2" 2>/dev/null | sort)
            local qcow2_file=$(comm -13 <(echo "$before_extract") <(echo "$after_extract") | head -1)
            [ -n "$qcow2_file" ] && [ -f "$qcow2_file" ] && mv "$qcow2_file" "$CORPUS_DIR/$dest" 2>/dev/null || true
        fi
    fi
    [ -f "$CORPUS_DIR/$dest" ] && \
    catalog "kali-2024.3-qcow2" "$dest" "qcow2" "modern" "2024" "$url" "GPL" "Kali 2024.3" "ext4" "full"
}

# =============================================================================
# VMDK IMAGES - Created by VMware (Target: 50+)
# =============================================================================

download_vmdk_images() {
    log "=== Downloading VMDK Images (Target: 50+) ==="
    log "Source: SourceForge LinuxVMImages - native VMDK created by VMware"
    log "Note: These are 7z/zip compressed, need 7z or unzip to extract"

    # Check for 7z
    if ! command -v 7z &>/dev/null && ! command -v 7za &>/dev/null; then
        warn "7z not found - install p7zip-full for .7z extraction"
        warn "sudo apt-get install p7zip-full"
    fi

    # --- SourceForge LinuxVMImages VMware collection ---
    # These are NATIVE VMDK files created by VMware tools
    log "--- SourceForge LinuxVMImages (native VMDKs) ---"

    # Files are directly in letter folders OR in version subfolders
    local vmware_images=(
        # Direct files in letter folders
        "U/Ubuntu_23.10_VM.7z:ubuntu-23.10-vmware:2023:GPL:Ubuntu 23.10:ext4"
        "U/Ubuntu_23.04_VM.7z:ubuntu-23.04-vmware:2023:GPL:Ubuntu 23.04:ext4"
        "U/Ubuntu_21.10_VM.7z:ubuntu-21.10-vmware:2021:GPL:Ubuntu 21.10:ext4"
        "U/Ubuntu_21.04_VM.7z:ubuntu-21.04-vmware:2021:GPL:Ubuntu 21.04:ext4"
        # Version subfolders
        "U/24.04/Ubuntu_24.04_VM.7z:ubuntu-24.04-vmware:2024:GPL:Ubuntu 24.04:ext4"
        "U/22.04/Ubuntu_22.04_VM.7z:ubuntu-22.04-vmware:2022:GPL:Ubuntu 22.04:ext4"
        "U/20.04/Ubuntu_20.04_VM.7z:ubuntu-20.04-vmware:2020:GPL:Ubuntu 20.04:ext4"
        "U/18.04/Ubuntu_18.04_VM.7z:ubuntu-18.04-vmware:2018:GPL:Ubuntu 18.04:ext4"
        "U/16.04/Ubuntu_16.04_VM.7z:ubuntu-16.04-vmware:2016:GPL:Ubuntu 16.04:ext4"
        # Debian in subfolders
        "D/12/Debian_12_VM.7z:debian-12-vmware:2023:DFSG:Debian 12:ext4"
        "D/11/Debian_11_VM.7z:debian-11-vmware:2021:DFSG:Debian 11:ext4"
        "D/10/Debian_10_VM.7z:debian-10-vmware:2019:DFSG:Debian 10:ext4"
        "D/9/Debian_9_VM.7z:debian-9-vmware:2017:DFSG:Debian 9:ext4"
        "D/Devuan_Beowulf_3.1.0_VM.zip:devuan-3.1-vmware:2021:DFSG:Devuan 3.1:ext4"
        # Kali in kalilinux subfolder
        "K/kalilinux/KaliLinux_2024.3_VM.7z:kali-2024.3-vmware:2024:GPL:Kali 2024.3:ext4"
        "K/kalilinux/KaliLinux_2023.4_VM.7z:kali-2023.4-vmware:2023:GPL:Kali 2023.4:ext4"
        "K/KaliLinux_2021.1_VM.zip:kali-2021.1-vmware:2021:GPL:Kali 2021.1:ext4"
        "K/KaliLinux_2020.4_VM.zip:kali-2020.4-vmware:2020:GPL:Kali 2020.4:ext4"
        # CentOS/Rocky/Alma
        "C/9/CentOS_9_VM.7z:centos-9-vmware:2023:GPL:CentOS Stream 9:xfs"
        "C/8/CentOS_8_VM.7z:centos-8-vmware:2020:GPL:CentOS 8:xfs"
        "A/9/AlmaLinux_9_VM.7z:almalinux-9-vmware:2023:GPL:AlmaLinux 9:xfs"
        "R/9/Rocky_9_VM.7z:rocky-9-vmware:2023:BSD:Rocky 9:xfs"
        # Linux Mint
        "L/22/LinuxMint_22_VM.7z:linuxmint-22-vmware:2024:GPL:Linux Mint 22:ext4"
        "L/21/LinuxMint_21_VM.7z:linuxmint-21-vmware:2022:GPL:Linux Mint 21:ext4"
        # Manjaro
        "M/Manjaro_21.1.0_VM.zip:manjaro-21.1-vmware:2021:GPL:Manjaro 21.1:ext4"
        # openSUSE
        "O/openSUSE_Leap_15.2_VM.zip:opensuse-15.2-vmware:2020:GPL:openSUSE 15.2:ext4"
        # Arch
        "A/Arch_2021.06.01_VM.zip:arch-2021.06-vmware:2021:GPL:Arch Linux:ext4"
        # Zorin
        "Z/Zorin_16_VM.7z:zorin-16-vmware:2021:GPL:Zorin OS 16:ext4"
    )

    for entry in "${vmware_images[@]}"; do
        IFS=':' read -r file id year license os fs <<< "$entry"
        local url="https://sourceforge.net/projects/linuxvmimages/files/VMware/$file/download"
        local archive="$CORPUS_DIR/vmdk/modern/$id.7z"
        local dest="$CORPUS_DIR/vmdk/modern/$id.vmdk"

        if [ -f "$dest" ]; then
            log "Already exists: $os VMDK"
            catalog "$id-vmdk" "vmdk/modern/$id.vmdk" "vmdk" "modern" "$year" "$url" "$license" "$os" "$fs" "standard" || true
            continue
        fi

        if download "$url" "$archive" "$os VMDK (native VMware)"; then
            # Extract 7z
            local before_extract=$(find "$CORPUS_DIR/vmdk/modern/" -name "*.vmdk" 2>/dev/null | sort)
            if command -v 7z &>/dev/null; then
                7z x -o"$CORPUS_DIR/vmdk/modern/" "$archive" -y >/dev/null 2>&1 || true
            elif command -v 7za &>/dev/null; then
                7za x -o"$CORPUS_DIR/vmdk/modern/" "$archive" -y >/dev/null 2>&1 || true
            fi
            rm -f "$archive"

            # Find the newly extracted VMDK (one that wasn't there before)
            local after_extract=$(find "$CORPUS_DIR/vmdk/modern/" -name "*.vmdk" 2>/dev/null | sort)
            local vmdk_file=$(comm -13 <(echo "$before_extract") <(echo "$after_extract") | head -1)
            if [ -n "$vmdk_file" ] && [ -f "$vmdk_file" ]; then
                mv "$vmdk_file" "$dest" 2>/dev/null || true
                catalog "$id-vmdk" "vmdk/modern/$id.vmdk" "vmdk" "modern" "$year" "$url" "$license" "$os" "$fs" "standard"
            fi
        fi
    done

    # --- Archive.org legacy VMDK files ---
    log "--- Archive.org Legacy VMDKs ---"
    # Windows ME VMDK (real legacy from 2000)
    local url="https://archive.org/download/windows-me-vmdk/Windows%20Me.zip"
    local dest="$CORPUS_DIR/vmdk/legacy/windows-me.vmdk"
    if [ ! -f "$dest" ]; then
        if download "$url" "$CORPUS_DIR/vmdk/legacy/windows-me.zip" "Windows ME VMDK (legacy)"; then
            unzip -o -d "$CORPUS_DIR/vmdk/legacy/" "$CORPUS_DIR/vmdk/legacy/windows-me.zip" 2>/dev/null || true
            rm -f "$CORPUS_DIR/vmdk/legacy/windows-me.zip"
            local vmdk_file=$(find "$CORPUS_DIR/vmdk/legacy/" -name "*.vmdk" 2>/dev/null | head -1)
            [ -n "$vmdk_file" ] && [ -f "$vmdk_file" ] && mv "$vmdk_file" "$dest" 2>/dev/null
        fi
    fi
    [ -f "$dest" ] && catalog "windows-me-vmdk" "vmdk/legacy/windows-me.vmdk" "vmdk" "legacy" "2000" "$url" "Proprietary" "Windows ME" "fat32" "full"
}

# =============================================================================
# VDI IMAGES - Created by VirtualBox (Target: 50+)
# =============================================================================

download_vdi_images() {
    log "=== Downloading VDI Images (Target: 50+) ==="
    log "Source: SourceForge LinuxVMImages - native VDI created by VirtualBox"

    # --- SourceForge LinuxVMImages VirtualBox collection ---
    # These are NATIVE VDI files created by VirtualBox
    log "--- SourceForge LinuxVMImages (native VDIs) ---"

    # Files in version subfolders or direct in letter folders
    local vbox_images=(
        # Ubuntu in version subfolders
        "U/24.04/Ubuntu_24.04_VB.7z:ubuntu-24.04-vbox:2024:GPL:Ubuntu 24.04:ext4"
        "U/22.04/Ubuntu_22.04_VB.7z:ubuntu-22.04-vbox:2022:GPL:Ubuntu 22.04:ext4"
        "U/20.04/Ubuntu_20.04_VB.7z:ubuntu-20.04-vbox:2020:GPL:Ubuntu 20.04:ext4"
        "U/18.04/Ubuntu_18.04_VB.7z:ubuntu-18.04-vbox:2018:GPL:Ubuntu 18.04:ext4"
        "U/16.04/Ubuntu_16.04_VB.7z:ubuntu-16.04-vbox:2016:GPL:Ubuntu 16.04:ext4"
        # Direct files in U folder
        "U/Ubuntu_23.10_VB.7z:ubuntu-23.10-vbox:2023:GPL:Ubuntu 23.10:ext4"
        "U/Ubuntu_23.04_VB.7Z:ubuntu-23.04-vbox:2023:GPL:Ubuntu 23.04:ext4"
        "U/Ubuntu_21.10_VB.7z:ubuntu-21.10-vbox:2021:GPL:Ubuntu 21.10:ext4"
        "U/Ubuntu_21.04_VB.7z:ubuntu-21.04-vbox:2021:GPL:Ubuntu 21.04:ext4"
        # Debian in version subfolders
        "D/12/Debian_12_VB.7z:debian-12-vbox:2023:DFSG:Debian 12:ext4"
        "D/11/Debian_11_VB.7z:debian-11-vbox:2021:DFSG:Debian 11:ext4"
        "D/10/Debian_10_VB.7z:debian-10-vbox:2019:DFSG:Debian 10:ext4"
        "D/9/Debian_9_VB.7z:debian-9-vbox:2017:DFSG:Debian 9:ext4"
        "D/Devuan_Beowulf_3.1.0_VB.zip:devuan-3.1-vbox:2021:DFSG:Devuan 3.1:ext4"
        "D/Deepin_20.1_VB.zip:deepin-20.1-vbox:2021:GPL:Deepin 20.1:ext4"
        # Kali in kalilinux subfolder
        "K/kalilinux/KaliLinux_2024.3_VB.7z:kali-2024.3-vbox:2024:GPL:Kali 2024.3:ext4"
        "K/kalilinux/KaliLinux_2023.4_VB.7z:kali-2023.4-vbox:2023:GPL:Kali 2023.4:ext4"
        "K/KaliLinux_2021.1_VB.zip:kali-2021.1-vbox:2021:GPL:Kali 2021.1:ext4"
        "K/KaliLinux_2020.4_VB.zip:kali-2020.4-vbox:2020:GPL:Kali 2020.4:ext4"
        # CentOS/Rocky/Alma
        "C/9/CentOS_9_VB.7z:centos-9-vbox:2023:GPL:CentOS Stream 9:xfs"
        "C/8/CentOS_8_VB.7z:centos-8-vbox:2020:GPL:CentOS 8:xfs"
        "A/9/AlmaLinux_9_VB.7z:almalinux-9-vbox:2023:GPL:AlmaLinux 9:xfs"
        "R/9/Rocky_9_VB.7z:rocky-9-vbox:2023:BSD:Rocky 9:xfs"
        # Linux Mint
        "L/22/LinuxMint_22_VB.7z:linuxmint-22-vbox:2024:GPL:Linux Mint 22:ext4"
        "L/21/LinuxMint_21_VB.7z:linuxmint-21-vbox:2022:GPL:Linux Mint 21:ext4"
        # Manjaro
        "M/Manjaro_21.1.0_VB.zip:manjaro-21.1-vbox:2021:GPL:Manjaro 21.1:ext4"
        # Fedora
        "F/39/Fedora_39_VB.7z:fedora-39-vbox:2023:MIT:Fedora 39:ext4"
        "F/38/Fedora_38_VB.7z:fedora-38-vbox:2023:MIT:Fedora 38:ext4"
        # openSUSE
        "O/openSUSE_Leap_15.2_VB.zip:opensuse-15.2-vbox:2020:GPL:openSUSE 15.2:ext4"
        # Arch
        "A/Arch_2021.06.01_VB.zip:arch-2021.06-vbox:2021:GPL:Arch Linux:ext4"
        # Zorin
        "Z/Zorin_16_VB.7z:zorin-16-vbox:2021:GPL:Zorin OS 16:ext4"
        # Elementary
        "E/Elementary_6.0_VB.zip:elementary-6.0-vbox:2021:GPL:Elementary 6.0:ext4"
        # Pop!_OS
        "P/Pop!_OS_21.04_VB.zip:popos-21.04-vbox:2021:GPL:Pop!_OS 21.04:ext4"
    )

    for entry in "${vbox_images[@]}"; do
        IFS=':' read -r file id year license os fs <<< "$entry"
        local url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/$file/download"
        local archive="$CORPUS_DIR/vdi/modern/$id.7z"
        local dest="$CORPUS_DIR/vdi/modern/$id.vdi"

        if [ -f "$dest" ]; then
            log "Already exists: $os VDI"
            catalog "$id-vdi" "vdi/modern/$id.vdi" "vdi" "modern" "$year" "$url" "$license" "$os" "$fs" "standard" || true
            continue
        fi

        if download "$url" "$archive" "$os VDI (native VirtualBox)"; then
            # Extract 7z
            local before_extract=$(find "$CORPUS_DIR/vdi/modern/" -name "*.vdi" 2>/dev/null | sort)
            if command -v 7z &>/dev/null; then
                7z x -o"$CORPUS_DIR/vdi/modern/" "$archive" -y >/dev/null 2>&1 || true
            elif command -v 7za &>/dev/null; then
                7za x -o"$CORPUS_DIR/vdi/modern/" "$archive" -y >/dev/null 2>&1 || true
            fi
            rm -f "$archive"

            # Find the newly extracted VDI (one that wasn't there before)
            local after_extract=$(find "$CORPUS_DIR/vdi/modern/" -name "*.vdi" 2>/dev/null | sort)
            local vdi_file=$(comm -13 <(echo "$before_extract") <(echo "$after_extract") | head -1)
            if [ -n "$vdi_file" ] && [ -f "$vdi_file" ]; then
                mv "$vdi_file" "$dest" 2>/dev/null || true
                catalog "$id-vdi" "vdi/modern/$id.vdi" "vdi" "modern" "$year" "$url" "$license" "$os" "$fs" "standard"
            fi
        fi
    done
}

# =============================================================================
# VHD IMAGES - Created by Microsoft Hyper-V (Target: 30+)
# =============================================================================

download_vhd_images() {
    log "=== Downloading VHD Images (Target: 30+) ==="
    log "Source: Archive.org - native VHD files created by Microsoft tools"
    mkdir -p "$CORPUS_DIR/vhd/modern" "$CORPUS_DIR/vhd/legacy"

    # --- Windows Virtual PC XP Mode VHDs (1.7GB zip) ---
    log "--- Windows Virtual PC / XP Mode VHDs ---"
    local url="https://archive.org/download/windows-virtual-pc-xp-mode-and-other-vhd-collections/Windows%20Virtual%20PC%2C%20XP%20Mode%2C%20And%20Other%20VHD%20Collections.zip"
    local dest="$CORPUS_DIR/vhd/legacy/xp-mode-vhd-collection.zip"

    if [ ! -f "$dest" ] && [ ! -d "$CORPUS_DIR/vhd/legacy/xp-mode" ]; then
        if download "$url" "$dest" "Windows XP Mode VHD Collection (native Microsoft)"; then
            log "Extracting XP Mode VHD collection..."
            mkdir -p "$CORPUS_DIR/vhd/legacy/xp-mode"
            unzip -o -d "$CORPUS_DIR/vhd/legacy/xp-mode" "$dest" 2>/dev/null || true
            rm -f "$dest"
        fi
    else
        log "Already exists: XP Mode VHD Collection"
    fi

    # Catalog all VHDs from XP Mode collection
    if [ -d "$CORPUS_DIR/vhd/legacy/xp-mode" ]; then
        find "$CORPUS_DIR/vhd/legacy/xp-mode" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
            local relpath="${vhd_file#$CORPUS_DIR/}"
            local basename=$(basename "$vhd_file")
            local id="xpmode-${basename%.*}"
            id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
            catalog "$id-vhd" "$relpath" "vhd" "legacy" "2009" "$url" "MS-EULA" "Windows XP Mode" "ntfs" "full"
        done
    fi

    # --- Windows 2000 VHD Collection ---
    log "--- Windows 2000 VHD Collection ---"
    url="https://archive.org/download/Windows2000VHDCollection/Windows2000VHDCollection.zip"
    dest="$CORPUS_DIR/vhd/legacy/win2000-collection.zip"
    if [ ! -d "$CORPUS_DIR/vhd/legacy/win2000" ]; then
        if download "$url" "$dest" "Windows 2000 VHD Collection"; then
            mkdir -p "$CORPUS_DIR/vhd/legacy/win2000"
            unzip -o -d "$CORPUS_DIR/vhd/legacy/win2000" "$dest" 2>/dev/null || true
            rm -f "$dest"
        fi
    fi
    if [ -d "$CORPUS_DIR/vhd/legacy/win2000" ]; then
        find "$CORPUS_DIR/vhd/legacy/win2000" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
            local relpath="${vhd_file#$CORPUS_DIR/}"
            local basename=$(basename "$vhd_file")
            local id="win2000-${basename%.*}"
            id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
            catalog "$id-vhd" "$relpath" "vhd" "legacy" "2000" "$url" "MS-EULA" "Windows 2000" "ntfs" "full"
        done
    fi

    # --- Windows 98 VHD Collection ---
    log "--- Windows 98 VHD Collection ---"
    url="https://archive.org/download/Windows98VHDs4VPC2007/Windows98VHDs.zip"
    dest="$CORPUS_DIR/vhd/legacy/win98-collection.zip"
    if [ ! -d "$CORPUS_DIR/vhd/legacy/win98" ]; then
        if download "$url" "$dest" "Windows 98 VHD Collection"; then
            mkdir -p "$CORPUS_DIR/vhd/legacy/win98"
            unzip -o -d "$CORPUS_DIR/vhd/legacy/win98" "$dest" 2>/dev/null || true
            rm -f "$dest"
        fi
    fi
    if [ -d "$CORPUS_DIR/vhd/legacy/win98" ]; then
        find "$CORPUS_DIR/vhd/legacy/win98" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
            local relpath="${vhd_file#$CORPUS_DIR/}"
            local basename=$(basename "$vhd_file")
            local id="win98-${basename%.*}"
            id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
            catalog "$id-vhd" "$relpath" "vhd" "legacy" "1998" "$url" "MS-EULA" "Windows 98" "fat32" "full"
        done
    fi

    # --- Windows XP Professional VHD ---
    log "--- Windows XP Professional VHD ---"
    url="https://archive.org/download/xp_pc-virtual/xp_pc-virtual.zip"
    dest="$CORPUS_DIR/vhd/legacy/xp-pro.zip"
    if [ ! -d "$CORPUS_DIR/vhd/legacy/xp-pro" ]; then
        if download "$url" "$dest" "Windows XP Professional VHD"; then
            mkdir -p "$CORPUS_DIR/vhd/legacy/xp-pro"
            unzip -o -d "$CORPUS_DIR/vhd/legacy/xp-pro" "$dest" 2>/dev/null || true
            rm -f "$dest"
        fi
    fi
    if [ -d "$CORPUS_DIR/vhd/legacy/xp-pro" ]; then
        find "$CORPUS_DIR/vhd/legacy/xp-pro" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
            local relpath="${vhd_file#$CORPUS_DIR/}"
            local basename=$(basename "$vhd_file")
            local id="xppro-${basename%.*}"
            id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
            catalog "$id-vhd" "$relpath" "vhd" "legacy" "2001" "$url" "MS-EULA" "Windows XP" "ntfs" "full"
        done
    fi

    # --- Windows XP Mode Base VHD (64-bit version) ---
    log "--- Windows XP Mode Base VHD ---"
    url="https://archive.org/download/WindowsXPModeBase_64-bit/Windows%20XP%20Mode%20base.vhd"
    dest="$CORPUS_DIR/vhd/legacy/windows-xp-mode-base.vhd"
    download "$url" "$dest" "Windows XP Mode Base VHD" && \
    catalog "xpmode-base-vhd" "vhd/legacy/windows-xp-mode-base.vhd" "vhd" "legacy" "2009" "$url" "MS-EULA" "Windows XP Mode" "ntfs" "full"

    # --- Windows NT 4.0 VHD ---
    log "--- Windows NT 4.0 VHD ---"
    url="https://archive.org/download/WindowsNTWorkstation4VHD/Windows%20NT%20Workstation%204.0.vhd"
    dest="$CORPUS_DIR/vhd/legacy/windows-nt-4.0.vhd"
    download "$url" "$dest" "Windows NT 4.0 VHD" && \
    catalog "winnt40-vhd" "vhd/legacy/windows-nt-4.0.vhd" "vhd" "legacy" "1996" "$url" "MS-EULA" "Windows NT 4.0" "ntfs" "full"

    # --- Preformatted VHD collection ---
    log "--- Preformatted VHD collection ---"
    url="https://archive.org/download/vhdcollection/vhdcollection.zip"
    dest="$CORPUS_DIR/vhd/modern/preformatted.zip"
    if [ ! -d "$CORPUS_DIR/vhd/modern/preformatted" ]; then
        if download "$url" "$dest" "Preformatted VHD collection"; then
            mkdir -p "$CORPUS_DIR/vhd/modern/preformatted"
            unzip -o -d "$CORPUS_DIR/vhd/modern/preformatted" "$dest" 2>/dev/null || true
            rm -f "$dest"
        fi
    fi
    if [ -d "$CORPUS_DIR/vhd/modern/preformatted" ]; then
        find "$CORPUS_DIR/vhd/modern/preformatted" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
            local relpath="${vhd_file#$CORPUS_DIR/}"
            local basename=$(basename "$vhd_file")
            local id="preformat-${basename%.*}"
            id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
            # Determine filesystem from filename
            local fs="fat32"
            [[ "$basename" == *"ntfs"* ]] && fs="ntfs"
            [[ "$basename" == *"fat16"* ]] && fs="fat16"
            catalog "$id-vhd" "$relpath" "vhd" "modern" "2015" "$url" "Public Domain" "Blank disk" "$fs" "quick"
        done
    fi

    log "--- Additional VHD Sources (manual download may be required) ---"
    warn "Modern.IE VMs: https://archive.org/details/ModernIEWindowsHyperV2012VMCollection (~25GB)"
}

# =============================================================================
# VHDX IMAGES - Created by Microsoft Hyper-V 2012+ (Target: 20+)
# =============================================================================

download_vhdx_images() {
    log "=== Downloading VHDX Images (Target: 20+) ==="
    log "VHDX is a modern format (2012+), created by Hyper-V"
    mkdir -p "$CORPUS_DIR/vhdx/modern" "$CORPUS_DIR/vhdx/legacy"

    # --- Windows 10X VHDX (archive.org) ---
    log "--- Windows 10X VHDX ---"
    local url="https://archive.org/download/windows-10-x-build-20279/Windows%2010X%20Build%2020279.vhdx"
    local dest="$CORPUS_DIR/vhdx/modern/windows-10x-build-20279.vhdx"
    download "$url" "$dest" "Windows 10X VHDX" && \
    catalog "win10x-20279-vhdx" "vhdx/modern/windows-10x-build-20279.vhdx" "vhdx" "modern" "2020" "$url" "MS-EULA" "Windows 10X" "ntfs" "full"

    # --- Convert some VHD to VHDX for testing (if qemu-img available) ---
    # Note: This is for format testing, not native-only requirement
    # We'll skip this and only use native VHDX

    # --- Microsoft Evaluation Center (requires manual download) ---
    log "--- Microsoft Evaluation Center (Manual Download Required) ---"
    warn "Native VHDX from Microsoft requires registration:"
    warn "  - Windows Server 2022: https://www.microsoft.com/en-us/evalcenter/evaluate-windows-server-2022"
    warn "  - Windows 11 Enterprise: https://www.microsoft.com/en-us/evalcenter/evaluate-windows-11-enterprise"
    warn "  - Windows 11 Dev VM: https://developer.microsoft.com/en-us/windows/downloads/virtual-machines/"
    warn ""
    warn "Download Hyper-V versions and place in: $CORPUS_DIR/vhdx/modern/"
    warn "Then run: ./scripts/catalog-manual-downloads.sh"
}

# =============================================================================
# Statistics and Summary
# =============================================================================

print_stats() {
    log "=== Corpus Statistics ==="

    if [ ! -f "$MANIFEST_FILE" ]; then
        error "Manifest not found"
        return
    fi

    echo ""
    echo "Total images: $(jq '.total_images' "$MANIFEST_FILE")"
    echo ""
    echo "By format:"
    jq -r '.images | group_by(.format) | map("  " + .[0].format + ": " + (length | tostring)) | .[]' "$MANIFEST_FILE"
    echo ""
    echo "By era:"
    jq -r '.images | group_by(.era) | map("  " + .[0].era + ": " + (length | tostring)) | .[]' "$MANIFEST_FILE"
    echo ""
    echo "Disk usage: $(du -sh "$CORPUS_DIR" | cut -f1)"
}

check_requirements() {
    log "=== Checking Requirements ==="

    local total=$(jq '.total_images' "$MANIFEST_FILE")
    local vmdk=$(jq '[.images[] | select(.format == "vmdk")] | length' "$MANIFEST_FILE")
    local qcow2=$(jq '[.images[] | select(.format == "qcow2")] | length' "$MANIFEST_FILE")
    local vhd=$(jq '[.images[] | select(.format == "vhd")] | length' "$MANIFEST_FILE")
    local vhdx=$(jq '[.images[] | select(.format == "vhdx")] | length' "$MANIFEST_FILE")
    local vdi=$(jq '[.images[] | select(.format == "vdi")] | length' "$MANIFEST_FILE")
    local legacy=$(jq '[.images[] | select(.year >= 2005 and .year <= 2010)] | length' "$MANIFEST_FILE")

    echo ""
    echo "Requirement Check:"
    echo "  Total images: $total / 200 $([[ $total -ge 200 ]] && echo '✓' || echo '✗')"
    echo "  VMDK: $vmdk / 50 $([[ $vmdk -ge 50 ]] && echo '✓' || echo '✗')"
    echo "  QCOW2: $qcow2 / 50 $([[ $qcow2 -ge 50 ]] && echo '✓' || echo '✗')"
    echo "  VHD: $vhd / 30 $([[ $vhd -ge 30 ]] && echo '✓' || echo '✗')"
    echo "  VHDX: $vhdx / 20 $([[ $vhdx -ge 20 ]] && echo '✓' || echo '✗')"
    echo "  VDI: $vdi / 50 $([[ $vdi -ge 50 ]] && echo '✓' || echo '✗')"
    echo "  Legacy: $legacy / 100 $([[ $legacy -ge 100 ]] && echo '✓' || echo '✗')"
    echo ""
    echo "Note: Native format images only - no conversions allowed"
}

# =============================================================================
# Main
# =============================================================================

main() {
    log "=========================================="
    log "Saffron Full Test Corpus Acquisition"
    log "=========================================="
    log "Target: 200+ NATIVE format images"
    log "CRITICAL: No format conversions - native only!"
    log ""

    # Check dependencies
    for cmd in wget jq sha256sum; do
        if ! command -v $cmd &>/dev/null; then
            error "$cmd is required but not installed"
            exit 1
        fi
    done

    if ! command -v 7z &>/dev/null && ! command -v 7za &>/dev/null; then
        warn "7z/7za not found - install p7zip-full for .7z extraction"
        warn "sudo apt-get install p7zip-full"
    fi

    init

    # Download all formats
    download_qcow2_images
    download_vmdk_images
    download_vdi_images
    download_vhd_images
    download_vhdx_images

    # Print results
    print_stats
    check_requirements

    log ""
    log "=========================================="
    log "Acquisition Complete"
    log "=========================================="
    log "Log file: $LOG_FILE"
    log "Manifest: $MANIFEST_FILE"
}

main "$@"
