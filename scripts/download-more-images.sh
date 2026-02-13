#!/bin/bash
# Download more VM images
set -euo pipefail

CORPUS_DIR="${CORPUS_DIR:-./test-corpus}"
MANIFEST_FILE="$CORPUS_DIR/manifest.json"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $*"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] ERROR:${NC} $*" >&2; }

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

    if curl -L -f --retry 3 --retry-delay 5 --connect-timeout 30 -o "$dest.tmp" "$url" 2>/dev/null; then
        local size=$(stat -c%s "$dest.tmp" 2>/dev/null || echo "0")
        if [ "$size" -gt 1024 ]; then
            mv "$dest.tmp" "$dest"
            log "Downloaded: $name ($(du -h "$dest" | cut -f1))"
            return 0
        fi
    fi
    rm -f "$dest.tmp"
    error "Failed: $name"
    return 1
}

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

    local full_path="$CORPUS_DIR/$path"
    [ -f "$full_path" ] || return 1

    if jq -e ".images[] | select(.id == \"$id\")" "$MANIFEST_FILE" >/dev/null 2>&1; then
        return 0
    fi

    local sha256=$(sha256sum "$full_path" | cut -d' ' -f1)
    local size=$(stat -c%s "$full_path")

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
         "native_format": true
       }] | .total_images = (.images | length) | .generated = $date' \
       "$MANIFEST_FILE" > "$tmp" && mv "$tmp" "$MANIFEST_FILE"

    log "Cataloged: $id"
}

mkdir -p "$CORPUS_DIR"/{qcow2,vmdk,vhd,vhdx,vdi}/{legacy,modern}

# ============================================================================
# MORE QCOW2 IMAGES
# ============================================================================

log "=== More QCOW2 Downloads ==="

# Fedora Cloud - use alt.fedoraproject.org redirector
for ver in 41 40 39 38; do
    url="https://download.fedoraproject.org/pub/fedora/linux/releases/$ver/Cloud/x86_64/images/"
    dest="$CORPUS_DIR/qcow2/modern/fedora-$ver-cloud.qcow2"
    # Skip for now, URLs need specific filenames
done

# Amazon Linux 2 QCOW2
url="https://cdn.amazonlinux.com/os-images/2.0.20240124.0/kvm/amzn2-kvm-2.0.20240124.0-x86_64.xfs.gpt.qcow2"
dest="$CORPUS_DIR/qcow2/modern/amazonlinux-2-kvm.qcow2"
download "$url" "$dest" "Amazon Linux 2 QCOW2" && \
catalog "amazonlinux-2-qcow2" "qcow2/modern/amazonlinux-2-kvm.qcow2" "qcow2" "modern" "2024" "$url" "Amazon" "Amazon Linux 2" "xfs"

# Amazon Linux 2023 QCOW2
url="https://cdn.amazonlinux.com/al2023/os-images/2023.3.20240312.0/kvm/al2023-kvm-2023.3.20240312.0-kernel-6.1-x86_64.xfs.gpt.qcow2"
dest="$CORPUS_DIR/qcow2/modern/amazonlinux-2023-kvm.qcow2"
download "$url" "$dest" "Amazon Linux 2023 QCOW2" && \
catalog "amazonlinux-2023-qcow2" "qcow2/modern/amazonlinux-2023-kvm.qcow2" "qcow2" "modern" "2024" "$url" "Amazon" "Amazon Linux 2023" "xfs"

# NixOS Cloud
url="https://channels.nixos.org/nixos-24.05/latest-nixos-minimal-x86_64-linux.iso"
# NixOS provides ISO, not QCOW2 directly

# Void Linux Cloud
url="https://repo-fastly.voidlinux.org/live/current/void-live-x86_64-20240314-base.iso"
# Void provides ISO

# Ubuntu Minimal Cloud
for ver in noble jammy; do
    version=""
    year=""
    case $ver in
        noble) version="24.04"; year=2024 ;;
        jammy) version="22.04"; year=2022 ;;
    esac
    url="https://cloud-images.ubuntu.com/minimal/releases/$ver/release/ubuntu-$version-minimal-cloudimg-amd64.img"
    dest="$CORPUS_DIR/qcow2/modern/ubuntu-$version-minimal-cloudimg.qcow2"
    download "$url" "$dest" "Ubuntu $version Minimal Cloud" && \
    catalog "ubuntu-$version-minimal-qcow2" "qcow2/modern/ubuntu-$version-minimal-cloudimg.qcow2" "qcow2" "modern" "$year" "$url" "GPL" "Ubuntu $version Minimal" "ext4"
done

# CentOS Stream
for ver in 9; do
    url="https://cloud.centos.org/centos/$ver-stream/x86_64/images/CentOS-Stream-GenericCloud-$ver-latest.x86_64.qcow2"
    dest="$CORPUS_DIR/qcow2/modern/centos-stream-$ver-cloud.qcow2"
    download "$url" "$dest" "CentOS Stream $ver Cloud" && \
    catalog "centos-stream-$ver-qcow2" "qcow2/modern/centos-stream-$ver-cloud.qcow2" "qcow2" "modern" "2024" "$url" "GPL" "CentOS Stream $ver" "xfs"
done

# ============================================================================
# MORE VHD DOWNLOADS from Archive.org
# ============================================================================

log "=== More VHD Downloads ==="

# Windows 2000 VHD Collection
url="https://archive.org/download/Windows2000VHDCollection/Windows2000VHDCollection.zip"
dest="$CORPUS_DIR/vhd/legacy/win2000-collection.zip"
if [ ! -d "$CORPUS_DIR/vhd/legacy/win2000" ]; then
    if download "$url" "$dest" "Windows 2000 VHD Collection"; then
        mkdir -p "$CORPUS_DIR/vhd/legacy/win2000"
        unzip -o -d "$CORPUS_DIR/vhd/legacy/win2000" "$dest" 2>/dev/null || true
        rm -f "$dest"
    fi
fi

# Catalog Win2000 VHDs
if [ -d "$CORPUS_DIR/vhd/legacy/win2000" ]; then
    find "$CORPUS_DIR/vhd/legacy/win2000" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
        relpath="${vhd_file#$CORPUS_DIR/}"
        basename=$(basename "$vhd_file")
        id="win2000-${basename%.*}"
        id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
        catalog "$id-vhd" "$relpath" "vhd" "legacy" "2000" "$url" "MS-EULA" "Windows 2000" "ntfs"
    done
fi

# Windows 98 VHD Collection
url="https://archive.org/download/Windows98VHDs4VPC2007/Windows98VHDs.zip"
dest="$CORPUS_DIR/vhd/legacy/win98-collection.zip"
if [ ! -d "$CORPUS_DIR/vhd/legacy/win98" ]; then
    if download "$url" "$dest" "Windows 98 VHD Collection"; then
        mkdir -p "$CORPUS_DIR/vhd/legacy/win98"
        unzip -o -d "$CORPUS_DIR/vhd/legacy/win98" "$dest" 2>/dev/null || true
        rm -f "$dest"
    fi
fi

# Catalog Win98 VHDs
if [ -d "$CORPUS_DIR/vhd/legacy/win98" ]; then
    find "$CORPUS_DIR/vhd/legacy/win98" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
        relpath="${vhd_file#$CORPUS_DIR/}"
        basename=$(basename "$vhd_file")
        id="win98-${basename%.*}"
        id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
        catalog "$id-vhd" "$relpath" "vhd" "legacy" "1998" "$url" "MS-EULA" "Windows 98" "fat32"
    done
fi

# Windows XP Mode Base (direct VHD)
url="https://archive.org/download/WindowsXPModeBase_64-bit/Windows%20XP%20Mode%20base.vhd"
dest="$CORPUS_DIR/vhd/legacy/windows-xp-mode-base.vhd"
download "$url" "$dest" "Windows XP Mode Base VHD" && \
catalog "xpmode-base-vhd" "vhd/legacy/windows-xp-mode-base.vhd" "vhd" "legacy" "2009" "$url" "MS-EULA" "Windows XP Mode" "ntfs"

# Windows NT 4.0 (direct VHD)
url="https://archive.org/download/WindowsNTWorkstation4VHD/Windows%20NT%20Workstation%204.0.vhd"
dest="$CORPUS_DIR/vhd/legacy/windows-nt-4.0.vhd"
download "$url" "$dest" "Windows NT 4.0 VHD" && \
catalog "winnt40-vhd" "vhd/legacy/windows-nt-4.0.vhd" "vhd" "legacy" "1996" "$url" "MS-EULA" "Windows NT 4.0" "ntfs"

# Preformatted VHD collection (blank disks - good for testing format parsing)
url="https://archive.org/download/vhdcollection/vhdcollection.zip"
dest="$CORPUS_DIR/vhd/modern/preformatted.zip"
if [ ! -d "$CORPUS_DIR/vhd/modern/preformatted" ]; then
    if download "$url" "$dest" "Preformatted VHD Collection"; then
        mkdir -p "$CORPUS_DIR/vhd/modern/preformatted"
        unzip -o -d "$CORPUS_DIR/vhd/modern/preformatted" "$dest" 2>/dev/null || true
        rm -f "$dest"
    fi
fi

# Catalog preformatted VHDs
if [ -d "$CORPUS_DIR/vhd/modern/preformatted" ]; then
    find "$CORPUS_DIR/vhd/modern/preformatted" -name "*.vhd" -o -name "*.VHD" 2>/dev/null | while read vhd_file; do
        relpath="${vhd_file#$CORPUS_DIR/}"
        basename=$(basename "$vhd_file")
        id="preformat-${basename%.*}"
        id=$(echo "$id" | tr ' ' '-' | tr '[:upper:]' '[:lower:]')
        fs="fat32"
        [[ "$basename" == *"ntfs"* || "$basename" == *"NTFS"* ]] && fs="ntfs"
        [[ "$basename" == *"fat16"* || "$basename" == *"FAT16"* ]] && fs="fat16"
        catalog "$id-vhd" "$relpath" "vhd" "modern" "2015" "$url" "Public Domain" "Blank disk" "$fs"
    done
fi

# ============================================================================
# VHDX DOWNLOADS
# ============================================================================

log "=== VHDX Downloads ==="

# Windows 10X VHDX
url="https://archive.org/download/windows-10-x-build-20279/Windows%2010X%20Build%2020279.vhdx"
dest="$CORPUS_DIR/vhdx/modern/windows-10x-build-20279.vhdx"
download "$url" "$dest" "Windows 10X VHDX" && \
catalog "win10x-20279-vhdx" "vhdx/modern/windows-10x-build-20279.vhdx" "vhdx" "modern" "2020" "$url" "MS-EULA" "Windows 10X" "ntfs"

# ============================================================================
# Summary
# ============================================================================

log "=== Download Summary ==="
echo ""
echo "Total images: $(jq '.total_images' "$MANIFEST_FILE")"
echo ""
echo "By format:"
jq -r '.images | group_by(.format) | map("  " + .[0].format + ": " + (length | tostring)) | .[]' "$MANIFEST_FILE"

log "Done"
