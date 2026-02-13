#!/bin/bash
# Download VM images from official cloud provider CDNs
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

    # Use wget with better retry logic
    if wget -q --show-progress --tries=3 --timeout=60 -O "$dest.tmp" "$url" 2>&1; then
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

mkdir -p "$CORPUS_DIR"/{qcow2,vmdk,vhd,vhdx,vdi}/{legacy,modern}

# ============================================================================
# QCOW2 FROM OFFICIAL SOURCES
# ============================================================================

log "=== Official QCOW2 Downloads ==="

# FreeBSD Cloud Images
url="https://download.freebsd.org/releases/VM-IMAGES/14.0-RELEASE/amd64/Latest/FreeBSD-14.0-RELEASE-amd64.qcow2.xz"
dest="$CORPUS_DIR/qcow2/modern/freebsd-14.0-amd64.qcow2.xz"
final="$CORPUS_DIR/qcow2/modern/freebsd-14.0-amd64.qcow2"
if [ ! -f "$final" ]; then
    download "$url" "$dest" "FreeBSD 14.0 QCOW2" && {
        log "Decompressing FreeBSD..."
        xz -dk "$dest" && rm "$dest"
        catalog "freebsd-14.0-qcow2" "qcow2/modern/freebsd-14.0-amd64.qcow2" "qcow2" "modern" "2023" "$url" "BSD" "FreeBSD 14.0" "ufs"
    }
fi

# OpenBSD Cloud Images
url="https://cdn.openbsd.org/pub/OpenBSD/7.4/amd64/miniroot74.img"
dest="$CORPUS_DIR/qcow2/modern/openbsd-7.4-miniroot.img"
download "$url" "$dest" "OpenBSD 7.4 Cloud" && \
catalog "openbsd-7.4-qcow2" "qcow2/modern/openbsd-7.4-miniroot.img" "qcow2" "modern" "2023" "$url" "BSD" "OpenBSD 7.4" "ffs"

# Fedora Cloud (official)
url="https://download.fedoraproject.org/pub/fedora/linux/releases/40/Cloud/x86_64/images/Fedora-Cloud-Base-Generic.x86_64-40-1.14.qcow2"
dest="$CORPUS_DIR/qcow2/modern/fedora-40-cloud.qcow2"
download "$url" "$dest" "Fedora 40 Cloud" && \
catalog "fedora-40-qcow2" "qcow2/modern/fedora-40-cloud.qcow2" "qcow2" "modern" "2024" "$url" "MIT" "Fedora 40" "btrfs"

# Flatcar Linux
url="https://stable.release.flatcar-linux.net/amd64-usr/current/flatcar_production_qemu_image.img.bz2"
dest="$CORPUS_DIR/qcow2/modern/flatcar-stable.img.bz2"
final="$CORPUS_DIR/qcow2/modern/flatcar-stable.qcow2"
if [ ! -f "$final" ]; then
    download "$url" "$dest" "Flatcar Linux" && {
        log "Decompressing Flatcar..."
        bzip2 -dk "$dest" && mv "${dest%.bz2}" "$final" && rm "$dest"
        catalog "flatcar-stable-qcow2" "qcow2/modern/flatcar-stable.qcow2" "qcow2" "modern" "2024" "$url" "Apache" "Flatcar Linux" "ext4"
    }
fi

# TinyCore Linux
url="https://distro.ibiblio.org/tinycorelinux/15.x/x86_64/release/TinyCorePure64-15.0.iso"
dest="$CORPUS_DIR/qcow2/modern/tinycore-15.0.iso"
download "$url" "$dest" "TinyCore 15.0" && \
catalog "tinycore-15.0-iso" "qcow2/modern/tinycore-15.0.iso" "iso" "modern" "2024" "$url" "GPL" "TinyCore 15.0" "ext2"

# Alpine extended
url="https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-virt-3.19.1-x86_64.iso"
dest="$CORPUS_DIR/qcow2/modern/alpine-virt-3.19.1.iso"
download "$url" "$dest" "Alpine 3.19.1 Virt" && \
catalog "alpine-virt-3.19.1-iso" "qcow2/modern/alpine-virt-3.19.1.iso" "iso" "modern" "2024" "$url" "MIT" "Alpine 3.19.1" "ext4"

# ============================================================================
# VHD FROM AZURE (Azure provides free VHDs)
# ============================================================================

log "=== Azure VHD Downloads ==="

# Azure Ubuntu 22.04 (already exists, just verify)
url="https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-amd64-azure.vhd.tar.gz"
dest="$CORPUS_DIR/vhd/modern/ubuntu-22.04-azure.vhd.tar.gz"
final="$CORPUS_DIR/vhd/modern/ubuntu-22.04-azure.vhd"
if [ ! -f "$final" ]; then
    download "$url" "$dest" "Ubuntu 22.04 Azure VHD" && {
        log "Extracting Ubuntu Azure VHD..."
        tar -xzf "$dest" -C "$(dirname "$dest")" && rm "$dest"
        mv "$CORPUS_DIR/vhd/modern/"*.vhd "$final" 2>/dev/null || true
        catalog "ubuntu-22.04-azure-vhd" "vhd/modern/ubuntu-22.04-azure.vhd" "vhd" "modern" "2022" "$url" "GPL" "Ubuntu 22.04" "ext4"
    }
fi

# Azure Ubuntu 24.04
url="https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-amd64-azure.vhd.tar.gz"
dest="$CORPUS_DIR/vhd/modern/ubuntu-24.04-azure.vhd.tar.gz"
final="$CORPUS_DIR/vhd/modern/ubuntu-24.04-azure.vhd"
if [ ! -f "$final" ]; then
    download "$url" "$dest" "Ubuntu 24.04 Azure VHD" && {
        log "Extracting Ubuntu 24.04 Azure VHD..."
        tar -xzf "$dest" -C "$(dirname "$dest")" && rm "$dest"
        mv "$CORPUS_DIR/vhd/modern/"*24.04*.vhd "$final" 2>/dev/null || true
        catalog "ubuntu-24.04-azure-vhd" "vhd/modern/ubuntu-24.04-azure.vhd" "vhd" "modern" "2024" "$url" "GPL" "Ubuntu 24.04" "ext4"
    }
fi

# ============================================================================
# VMDK FROM OFFICIAL SOURCES
# ============================================================================

log "=== VMDK Downloads ==="

# Photon OS (VMware's own Linux distro)
url="https://packages.vmware.com/photon/5.0/GA/ova/photon-ova-5.0-dde71ec57.x86_64.ova"
dest="$CORPUS_DIR/vmdk/modern/photon-5.0.ova"
final="$CORPUS_DIR/vmdk/modern/photon-5.0.vmdk"
if [ ! -f "$final" ]; then
    download "$url" "$dest" "Photon OS 5.0 OVA" && {
        log "Extracting VMDK from OVA..."
        cd "$CORPUS_DIR/vmdk/modern" && tar -xf photon-5.0.ova '*.vmdk' 2>/dev/null && rm photon-5.0.ova
        mv *.vmdk photon-5.0.vmdk 2>/dev/null || true
        cd -
        catalog "photon-5.0-vmdk" "vmdk/modern/photon-5.0.vmdk" "vmdk" "modern" "2023" "$url" "Apache" "Photon OS 5.0" "ext4"
    }
fi

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
