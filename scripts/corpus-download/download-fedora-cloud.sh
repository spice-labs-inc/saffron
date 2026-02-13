#!/bin/bash
# Download Fedora Cloud Images for Saffron test corpus
# Source: https://fedoraproject.org/cloud/download
#
# Fedora uses various filesystems including btrfs in some spins.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/qcow2/cloud/fedora"
mkdir -p "$CORPUS_BASE/raw/cloud/fedora"

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

echo "=== Fedora Cloud Images Download ==="
echo "Target: $CORPUS_BASE"
echo ""

success=0
failed=0

# Fedora download URLs follow pattern:
# https://download.fedoraproject.org/pub/fedora/linux/releases/VERSION/Cloud/x86_64/images/

# Fedora 40 Cloud Base (archived)
if download_file \
    "https://archives.fedoraproject.org/pub/archive/fedora/linux/releases/40/Cloud/x86_64/images/Fedora-Cloud-Base-Generic.x86_64-40-1.14.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/fedora/fedora-40-cloud-base-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# Fedora 39 Cloud Base
if download_file \
    "https://download.fedoraproject.org/pub/fedora/linux/releases/39/Cloud/x86_64/images/Fedora-Cloud-Base-39-1.5.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/fedora/fedora-39-cloud-base-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# Fedora 38 Cloud Base (may be archived)
if download_file \
    "https://archives.fedoraproject.org/pub/archive/fedora/linux/releases/38/Cloud/x86_64/images/Fedora-Cloud-Base-38-1.6.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/fedora/fedora-38-cloud-base-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# Fedora CoreOS (uses different structure - ignition-based)
# https://fedoraproject.org/coreos/download
echo ""
echo "--- Fedora CoreOS ---"
if download_file \
    "https://builds.coreos.fedoraproject.org/prod/streams/stable/builds/40.20240519.3.0/x86_64/fedora-coreos-40.20240519.3.0-qemu.x86_64.qcow2.xz" \
    "$CORPUS_BASE/qcow2/cloud/fedora/fedora-coreos-40-qemu-amd64.qcow2.xz"; then
    ((success++))
    # Decompress if needed
    if [[ -f "$CORPUS_BASE/qcow2/cloud/fedora/fedora-coreos-40-qemu-amd64.qcow2.xz" ]]; then
        echo "  Decompressing..."
        xz -dk "$CORPUS_BASE/qcow2/cloud/fedora/fedora-coreos-40-qemu-amd64.qcow2.xz" 2>/dev/null || true
    fi
else
    ((failed++))
fi

echo ""
echo "=== Summary ==="
echo "Downloaded: $success"
echo "Failed: $failed"
echo ""

# List downloaded files
echo "=== Downloaded Fedora Images ==="
find "$CORPUS_BASE" -path "*/cloud/fedora/*" -type f \( -name "*.qcow2" -o -name "*.raw" \) 2>/dev/null | sort
