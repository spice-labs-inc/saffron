#!/bin/bash
# Download native VHD/VHDX images for Saffron test corpus
# These are images distributed in VHD/VHDX format, not converted
#
# Sources:
# - Microsoft Windows Server Evaluation (native VHD)
# - Ubuntu Hyper-V images (native VHDX)
# - Azure-tuned images from vendors

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/vhd/native/ubuntu"
mkdir -p "$CORPUS_BASE/vhd/native/microsoft"
mkdir -p "$CORPUS_BASE/vhd/native/debian"
mkdir -p "$CORPUS_BASE/vhd/native/freebsd"
mkdir -p "$CORPUS_BASE/vhdx/native/ubuntu"
mkdir -p "$CORPUS_BASE/vhdx/native/microsoft"

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

echo "=== Native VHD/VHDX Images Download ==="
echo "Target: $CORPUS_BASE"
echo ""

success=0
failed=0

# ============================================
# Ubuntu Azure VHD Images
# https://cloud-images.ubuntu.com/
# Ubuntu provides Azure-optimized images in VHD format
# ============================================
echo "--- Ubuntu Azure (VHD) ---"

# Ubuntu 24.04 Azure VHD
if download_file \
    "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64-azure.vhd.tar.gz" \
    "$CORPUS_BASE/vhd/native/ubuntu/ubuntu-24.04-azure-amd64.vhd.tar.gz"; then
    ((success++))
    # Extract VHD
    if [[ -f "$CORPUS_BASE/vhd/native/ubuntu/ubuntu-24.04-azure-amd64.vhd.tar.gz" ]] && \
       [[ ! -f "$CORPUS_BASE/vhd/native/ubuntu/ubuntu-24.04-azure-amd64.vhd" ]]; then
        echo "  Extracting VHD..."
        cd "$CORPUS_BASE/vhd/native/ubuntu"
        tar -xzf ubuntu-24.04-azure-amd64.vhd.tar.gz 2>/dev/null || true
        # Rename to standard name
        for f in *.vhd; do
            if [[ -f "$f" ]] && [[ "$f" != "ubuntu-24.04-azure-amd64.vhd" ]]; then
                mv "$f" "ubuntu-24.04-azure-amd64.vhd"
                break
            fi
        done
        cd - > /dev/null
    fi
else
    ((failed++))
fi

# Ubuntu 22.04 Azure VHD
if download_file \
    "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64-azure.vhd.tar.gz" \
    "$CORPUS_BASE/vhd/native/ubuntu/ubuntu-22.04-azure-amd64.vhd.tar.gz"; then
    ((success++))
    if [[ -f "$CORPUS_BASE/vhd/native/ubuntu/ubuntu-22.04-azure-amd64.vhd.tar.gz" ]] && \
       [[ ! -f "$CORPUS_BASE/vhd/native/ubuntu/ubuntu-22.04-azure-amd64.vhd" ]]; then
        echo "  Extracting VHD..."
        cd "$CORPUS_BASE/vhd/native/ubuntu"
        tar -xzf ubuntu-22.04-azure-amd64.vhd.tar.gz 2>/dev/null || true
        for f in *.vhd; do
            if [[ -f "$f" ]] && [[ "$f" != "ubuntu-22.04-azure-amd64.vhd" ]] && [[ "$f" != "ubuntu-24.04-azure-amd64.vhd" ]]; then
                mv "$f" "ubuntu-22.04-azure-amd64.vhd"
                break
            fi
        done
        cd - > /dev/null
    fi
else
    ((failed++))
fi

# ============================================
# Microsoft Windows Server Evaluation
# Note: Microsoft requires manual download with license acceptance
# These URLs may not work without browser session
# ============================================
echo ""
echo "--- Microsoft Windows (Manual Download Required) ---"
echo ""
echo "Windows Server evaluation VHDs require manual download:"
echo "  1. Visit: https://www.microsoft.com/en-us/evalcenter/evaluate-windows-server"
echo "  2. Select 'VHD' format"
echo "  3. Download to: $CORPUS_BASE/vhd/native/microsoft/"
echo ""
echo "Windows 11 development VHD:"
echo "  1. Visit: https://developer.microsoft.com/en-us/windows/downloads/virtual-machines/"
echo "  2. Select 'Hyper-V' format"
echo "  3. Download to: $CORPUS_BASE/vhdx/native/microsoft/"
echo ""

# ============================================
# Debian Azure/Hyper-V (if available)
# ============================================
echo "--- Debian Azure Images ---"

# Debian provides Azure-specific images but they're typically in their generic cloud format
# Check if there's a VHD variant
if download_file \
    "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.raw" \
    "$CORPUS_BASE/vhd/native/debian/debian-12-genericcloud-amd64.raw"; then
    ((success++))
    echo "  Note: This is a raw image, not VHD. Azure converts on import."
else
    ((failed++))
fi

echo ""
echo "=== Summary ==="
echo "Downloaded: $success"
echo "Failed: $failed"
echo ""

# List downloaded files
echo "=== Native VHD Images ==="
find "$CORPUS_BASE/vhd/native" -name "*.vhd" -type f 2>/dev/null | sort || echo "(none)"

echo ""
echo "=== Native VHDX Images ==="
find "$CORPUS_BASE/vhdx/native" -name "*.vhdx" -type f 2>/dev/null | sort || echo "(none)"
