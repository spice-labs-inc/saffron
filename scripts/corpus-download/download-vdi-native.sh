#!/bin/bash
# Download native VDI images for Saffron test corpus
# These are images distributed in VDI format for VirtualBox
#
# Sources:
# - OS Boxes (community VDI images)
# - VirtualBox Guest Additions ISO (contains VDI tools)
# - Linux Mint (provides VDI)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/vdi/native/osboxes"
mkdir -p "$CORPUS_BASE/vdi/native/linuxmint"

download_file() {
    local url="$1"
    local target_path="$2"
    local filename="$(basename "$target_path")"

    if [[ -f "$target_path" ]]; then
        echo "SKIP: $filename (already exists)"
        return 0
    fi

    echo "Downloading: $filename"
    echo "  URL: $url"

    if curl -fSL --progress-bar -o "$target_path.tmp" "$url"; then
        mv "$target_path.tmp" "$target_path"
        echo "  OK: Downloaded $(du -h "$target_path" | cut -f1)"
        return 0
    else
        rm -f "$target_path.tmp"
        echo "  FAILED: Could not download"
        return 1
    fi
}

echo "=== Native VDI Images Download ==="
echo "Target: $CORPUS_BASE"
echo ""

success=0
failed=0

# ============================================
# OS Boxes - Community VirtualBox Images
# https://www.osboxes.org/
# Note: These require manual download due to their download system
# ============================================
echo "--- OS Boxes (Manual Download) ---"
echo ""
echo "OS Boxes provides pre-built VDI images but requires manual download:"
echo "  1. Visit: https://www.osboxes.org/virtualbox-images/"
echo "  2. Select desired OS (Ubuntu, Fedora, Debian, etc.)"
echo "  3. Download VDI file to: $CORPUS_BASE/vdi/native/osboxes/"
echo ""
echo "Popular options:"
echo "  - Ubuntu 24.04: https://www.osboxes.org/ubuntu/"
echo "  - Fedora 40: https://www.osboxes.org/fedora/"
echo "  - Debian 12: https://www.osboxes.org/debian/"
echo "  - Linux Mint 21: https://www.osboxes.org/linux-mint/"
echo ""

# ============================================
# Linux Mint (provides OVA which contains VDI)
# https://linuxmint.com/download.php
# ============================================
echo "--- Linux Mint ---"
echo ""
echo "Linux Mint provides VirtualBox images (OVA containing VDI):"
echo "  1. Visit: https://linuxmint.com/download.php"
echo "  2. Look for VirtualBox/VMware images section"
echo "  3. Download to: $CORPUS_BASE/vdi/native/linuxmint/"
echo ""

# ============================================
# Kali Linux (provides VDI directly)
# https://www.kali.org/get-kali/#kali-virtual-machines
# ============================================
echo "--- Kali Linux VirtualBox ---"

# Kali provides weekly VirtualBox images
# The URL pattern changes, so we try the current structure
KALI_BASE="https://cdimage.kali.org/kali-2024.4"
if download_file \
    "${KALI_BASE}/kali-linux-2024.4-virtualbox-amd64.7z" \
    "$CORPUS_BASE/vdi/native/kali/kali-linux-2024.4-virtualbox-amd64.7z"; then
    ((success++))
    # Extract if 7z is available
    if command -v 7z &>/dev/null && \
       [[ -f "$CORPUS_BASE/vdi/native/kali/kali-linux-2024.4-virtualbox-amd64.7z" ]] && \
       [[ ! -f "$CORPUS_BASE/vdi/native/kali/kali-linux-2024.4-amd64.vdi" ]]; then
        echo "  Extracting VDI from 7z..."
        cd "$CORPUS_BASE/vdi/native/kali"
        7z x -y kali-linux-2024.4-virtualbox-amd64.7z '*.vdi' 2>/dev/null || true
        cd - > /dev/null
    fi
else
    ((failed++))
fi

# ============================================
# FreeBSD (provides VDI)
# https://www.freebsd.org/where/
# ============================================
echo ""
echo "--- FreeBSD ---"

# FreeBSD provides VM images including VDI
if download_file \
    "https://download.freebsd.org/releases/VM-IMAGES/14.1-RELEASE/amd64/Latest/FreeBSD-14.1-RELEASE-amd64.vhd.xz" \
    "$CORPUS_BASE/vhd/native/freebsd/freebsd-14.1-amd64.vhd.xz"; then
    ((success++))
    # Note: FreeBSD uses VHD not VDI, but it's still a native format image
    if [[ -f "$CORPUS_BASE/vhd/native/freebsd/freebsd-14.1-amd64.vhd.xz" ]] && \
       [[ ! -f "$CORPUS_BASE/vhd/native/freebsd/freebsd-14.1-amd64.vhd" ]]; then
        echo "  Decompressing VHD..."
        xz -dk "$CORPUS_BASE/vhd/native/freebsd/freebsd-14.1-amd64.vhd.xz" 2>/dev/null || true
    fi
else
    ((failed++))
fi

# ============================================
# Alpine Linux (provides VDI for VirtualBox)
# https://alpinelinux.org/downloads/
# ============================================
echo ""
echo "--- Alpine Linux ---"

# Alpine provides virtual images
if download_file \
    "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-virt-3.20.3-x86_64.iso" \
    "$CORPUS_BASE/vdi/native/alpine/alpine-virt-3.20.3-x86_64.iso"; then
    ((success++))
    echo "  Note: This is an ISO, not VDI. Use for creating fresh VDI."
else
    ((failed++))
fi

echo ""
echo "=== Summary ==="
echo "Downloaded: $success"
echo "Failed: $failed"
echo ""

# List downloaded files
echo "=== Native VDI Images ==="
find "$CORPUS_BASE/vdi/native" -name "*.vdi" -type f 2>/dev/null | sort || echo "(none - see manual download instructions above)"
