#!/bin/bash
# Download FreeBSD VM images in multiple formats
# FreeBSD provides native images in QCOW2, VMDK, VHD, and raw formats
# Filesystem: UFS (and ZFS on some images)
#
# https://www.freebsd.org/where/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/qcow2/native/freebsd"
mkdir -p "$CORPUS_BASE/vmdk/native/freebsd"
mkdir -p "$CORPUS_BASE/vhd/native/freebsd"
mkdir -p "$CORPUS_BASE/raw/native/freebsd"

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

decompress_xz() {
    local file="$1"
    local target="${file%.xz}"

    if [[ -f "$target" ]]; then
        echo "  SKIP: Already decompressed"
        return 0
    fi

    echo "  Decompressing..."
    xz -dk "$file" 2>/dev/null || true
}

echo "=== FreeBSD VM Images Download ==="
echo "Target: $CORPUS_BASE"
echo "Filesystem: UFS"
echo ""

success=0
failed=0

FREEBSD_VERSION="14.1"
FREEBSD_BASE="https://download.freebsd.org/releases/VM-IMAGES/${FREEBSD_VERSION}-RELEASE/amd64/Latest"

# ============================================
# FreeBSD QCOW2
# ============================================
echo "--- FreeBSD ${FREEBSD_VERSION} QCOW2 ---"

if download_file \
    "${FREEBSD_BASE}/FreeBSD-${FREEBSD_VERSION}-RELEASE-amd64.qcow2.xz" \
    "$CORPUS_BASE/qcow2/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.qcow2.xz"; then
    ((success++))
    decompress_xz "$CORPUS_BASE/qcow2/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.qcow2.xz"
else
    ((failed++))
fi

# ============================================
# FreeBSD VMDK
# ============================================
echo ""
echo "--- FreeBSD ${FREEBSD_VERSION} VMDK ---"

if download_file \
    "${FREEBSD_BASE}/FreeBSD-${FREEBSD_VERSION}-RELEASE-amd64.vmdk.xz" \
    "$CORPUS_BASE/vmdk/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.vmdk.xz"; then
    ((success++))
    decompress_xz "$CORPUS_BASE/vmdk/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.vmdk.xz"
else
    ((failed++))
fi

# ============================================
# FreeBSD VHD
# ============================================
echo ""
echo "--- FreeBSD ${FREEBSD_VERSION} VHD ---"

if download_file \
    "${FREEBSD_BASE}/FreeBSD-${FREEBSD_VERSION}-RELEASE-amd64.vhd.xz" \
    "$CORPUS_BASE/vhd/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.vhd.xz"; then
    ((success++))
    decompress_xz "$CORPUS_BASE/vhd/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.vhd.xz"
else
    ((failed++))
fi

# ============================================
# FreeBSD RAW
# ============================================
echo ""
echo "--- FreeBSD ${FREEBSD_VERSION} RAW ---"

if download_file \
    "${FREEBSD_BASE}/FreeBSD-${FREEBSD_VERSION}-RELEASE-amd64.raw.xz" \
    "$CORPUS_BASE/raw/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.raw.xz"; then
    ((success++))
    decompress_xz "$CORPUS_BASE/raw/native/freebsd/freebsd-${FREEBSD_VERSION}-amd64.raw.xz"
else
    ((failed++))
fi

# ============================================
# FreeBSD 13.3 (older version for variety)
# ============================================
FREEBSD_OLD="13.3"
FREEBSD_OLD_BASE="https://download.freebsd.org/releases/VM-IMAGES/${FREEBSD_OLD}-RELEASE/amd64/Latest"

echo ""
echo "--- FreeBSD ${FREEBSD_OLD} QCOW2 ---"

if download_file \
    "${FREEBSD_OLD_BASE}/FreeBSD-${FREEBSD_OLD}-RELEASE-amd64.qcow2.xz" \
    "$CORPUS_BASE/qcow2/native/freebsd/freebsd-${FREEBSD_OLD}-amd64.qcow2.xz"; then
    ((success++))
    decompress_xz "$CORPUS_BASE/qcow2/native/freebsd/freebsd-${FREEBSD_OLD}-amd64.qcow2.xz"
else
    ((failed++))
fi

echo ""
echo "=== Summary ==="
echo "Downloaded: $success"
echo "Failed: $failed"
echo ""

echo "=== FreeBSD Images ==="
find "$CORPUS_BASE" -path "*freebsd*" \( -name "*.qcow2" -o -name "*.vmdk" -o -name "*.vhd" -o -name "*.raw" \) -type f 2>/dev/null | sort
