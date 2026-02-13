#!/bin/bash
# Batch 2: Download more VDI and VMDK images from SourceForge
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
        if [ "$size" -gt 10000 ]; then
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

extract_and_catalog() {
    local archive="$1"
    local dest_dir="$2"
    local id="$3"
    local format="$4"
    local year="$5"
    local url="$6"
    local license="$7"
    local os="$8"
    local fs="$9"

    local ext="${archive##*.}"
    local before_extract=$(find "$dest_dir" -name "*.$format" 2>/dev/null | sort)

    log "Extracting: $(basename $archive)"

    case "$ext" in
        7z|7Z)
            7z x -o"$dest_dir" "$archive" -y >/dev/null 2>&1 || true
            ;;
        zip|ZIP)
            unzip -o -d "$dest_dir" "$archive" >/dev/null 2>&1 || true
            ;;
    esac

    rm -f "$archive"

    local after_extract=$(find "$dest_dir" -name "*.$format" 2>/dev/null | sort)
    local extracted_file=$(comm -13 <(echo "$before_extract") <(echo "$after_extract") | head -1)

    if [ -n "$extracted_file" ] && [ -f "$extracted_file" ]; then
        local dest="$dest_dir/$id.$format"
        mv "$extracted_file" "$dest" 2>/dev/null || true
        local relpath="${dest#$CORPUS_DIR/}"
        catalog "$id-$format" "$relpath" "$format" "modern" "$year" "$url" "$license" "$os" "$fs"
    fi
}

mkdir -p "$CORPUS_DIR"/{qcow2,vmdk,vhd,vhdx,vdi}/{legacy,modern}

# ============================================================================
# MORE VDI FROM SOURCEFORGE
# ============================================================================

log "=== More VDI Downloads ==="

# Fedora VDI
for ver in 39 38; do
    url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/F/$ver/Fedora_${ver}_VB.7z/download"
    archive="$CORPUS_DIR/vdi/modern/fedora-$ver-vbox.7z"
    dest="$CORPUS_DIR/vdi/modern/fedora-$ver-vbox.vdi"
    [ -f "$dest" ] || { download "$url" "$archive" "Fedora $ver VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "fedora-$ver-vbox" "vdi" "2023" "$url" "MIT" "Fedora $ver" "ext4"; }
done

# CentOS VDI
for ver in 8 9; do
    url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/C/$ver/CentOS_${ver}_VB.7z/download"
    archive="$CORPUS_DIR/vdi/modern/centos-$ver-vbox.7z"
    dest="$CORPUS_DIR/vdi/modern/centos-$ver-vbox.vdi"
    [ -f "$dest" ] || { download "$url" "$archive" "CentOS $ver VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "centos-$ver-vbox" "vdi" "2021" "$url" "GPL" "CentOS $ver" "xfs"; }
done

# Rocky Linux VDI
url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/R/9/Rocky_Linux_9_VB.7z/download"
archive="$CORPUS_DIR/vdi/modern/rocky-9-vbox.7z"
dest="$CORPUS_DIR/vdi/modern/rocky-9-vbox.vdi"
[ -f "$dest" ] || { download "$url" "$archive" "Rocky 9 VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "rocky-9-vbox" "vdi" "2023" "$url" "BSD" "Rocky 9" "xfs"; }

# Mint VDI
for ver in 21 22; do
    url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/L/$ver/LinuxMint_${ver}_VB.7z/download"
    archive="$CORPUS_DIR/vdi/modern/linuxmint-$ver-vbox.7z"
    dest="$CORPUS_DIR/vdi/modern/linuxmint-$ver-vbox.vdi"
    [ -f "$dest" ] || { download "$url" "$archive" "Linux Mint $ver VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "linuxmint-$ver-vbox" "vdi" "2023" "$url" "GPL" "Linux Mint $ver" "ext4"; }
done

# ============================================================================
# MORE VMDK FROM SOURCEFORGE
# ============================================================================

log "=== More VMDK Downloads ==="

# CentOS/Rocky VMDK
for ver in 8 9; do
    url="https://sourceforge.net/projects/linuxvmimages/files/VMware/C/$ver/CentOS_${ver}_VM.7z/download"
    archive="$CORPUS_DIR/vmdk/modern/centos-$ver-vmware.7z"
    dest="$CORPUS_DIR/vmdk/modern/centos-$ver-vmware.vmdk"
    [ -f "$dest" ] || { download "$url" "$archive" "CentOS $ver VMDK" && extract_and_catalog "$archive" "$CORPUS_DIR/vmdk/modern" "centos-$ver-vmware" "vmdk" "2021" "$url" "GPL" "CentOS $ver" "xfs"; }
done

# Debian VMDK
for ver in 11 12; do
    url="https://sourceforge.net/projects/linuxvmimages/files/VMware/D/$ver/Debian_${ver}_VM.7z/download"
    archive="$CORPUS_DIR/vmdk/modern/debian-$ver-vmware.7z"
    dest="$CORPUS_DIR/vmdk/modern/debian-$ver-vmware.vmdk"
    [ -f "$dest" ] || { download "$url" "$archive" "Debian $ver VMDK" && extract_and_catalog "$archive" "$CORPUS_DIR/vmdk/modern" "debian-$ver-vmware" "vmdk" "2023" "$url" "DFSG" "Debian $ver" "ext4"; }
done

# Kali VMDK
url="https://sourceforge.net/projects/linuxvmimages/files/VMware/K/kalilinux/KaliLinux_2024.3_VM.7z/download"
archive="$CORPUS_DIR/vmdk/modern/kali-2024.3-vmware.7z"
dest="$CORPUS_DIR/vmdk/modern/kali-2024.3-vmware.vmdk"
[ -f "$dest" ] || { download "$url" "$archive" "Kali 2024.3 VMDK" && extract_and_catalog "$archive" "$CORPUS_DIR/vmdk/modern" "kali-2024.3-vmware" "vmdk" "2024" "$url" "GPL" "Kali 2024.3" "ext4"; }

# ============================================================================
# MORE QCOW2
# ============================================================================

log "=== More QCOW2 Downloads ==="

# Debian cloud images for older versions
for ver in 9; do
    url="https://cloud.debian.org/images/cloud/OpenStack/archive/$ver/debian-$ver-openstack-amd64.qcow2"
    dest="$CORPUS_DIR/qcow2/modern/debian-$ver-openstack-amd64.qcow2"
    download "$url" "$dest" "Debian $ver OpenStack QCOW2" && \
    catalog "debian-$ver-openstack-qcow2" "qcow2/modern/debian-$ver-openstack-amd64.qcow2" "qcow2" "modern" "2017" "$url" "DFSG" "Debian $ver" "ext4"
done

# NetBSD cloud
url="https://cdn.netbsd.org/pub/NetBSD/images/10.0/NetBSD-10.0-amd64.qcow2"
dest="$CORPUS_DIR/qcow2/modern/netbsd-10.0-amd64.qcow2"
download "$url" "$dest" "NetBSD 10.0 QCOW2" && \
catalog "netbsd-10.0-qcow2" "qcow2/modern/netbsd-10.0-amd64.qcow2" "qcow2" "modern" "2024" "$url" "BSD" "NetBSD 10.0" "ffs"

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
