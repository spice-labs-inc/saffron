#!/bin/bash
# Download RHEL-clone Cloud Images (AlmaLinux, Rocky Linux, CentOS Stream)
# for Saffron test corpus
#
# These use XFS filesystem by default, important for cloud provider testing.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/qcow2/cloud/almalinux"
mkdir -p "$CORPUS_BASE/qcow2/cloud/rocky"
mkdir -p "$CORPUS_BASE/qcow2/cloud/centos-stream"
mkdir -p "$CORPUS_BASE/vmdk/cloud/almalinux"
mkdir -p "$CORPUS_BASE/vmdk/cloud/rocky"

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

echo "=== RHEL-Clone Cloud Images Download ==="
echo "Target: $CORPUS_BASE"
echo ""

success=0
failed=0

# ============================================
# AlmaLinux Cloud Images
# https://repo.almalinux.org/almalinux/
# ============================================
echo "--- AlmaLinux ---"

# AlmaLinux 9
if download_file \
    "https://repo.almalinux.org/almalinux/9/cloud/x86_64/images/AlmaLinux-9-GenericCloud-latest.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/almalinux/almalinux-9-genericcloud-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# AlmaLinux 8
if download_file \
    "https://repo.almalinux.org/almalinux/8/cloud/x86_64/images/AlmaLinux-8-GenericCloud-latest.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/almalinux/almalinux-8-genericcloud-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# ============================================
# Rocky Linux Cloud Images
# https://dl.rockylinux.org/pub/rocky/
# ============================================
echo ""
echo "--- Rocky Linux ---"

# Rocky Linux 9
if download_file \
    "https://dl.rockylinux.org/pub/rocky/9/images/x86_64/Rocky-9-GenericCloud-Base.latest.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/rocky/rocky-9-genericcloud-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# Rocky Linux 8
if download_file \
    "https://dl.rockylinux.org/pub/rocky/8/images/x86_64/Rocky-8-GenericCloud-Base.latest.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/rocky/rocky-8-genericcloud-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# ============================================
# CentOS Stream Cloud Images
# https://cloud.centos.org/centos/
# ============================================
echo ""
echo "--- CentOS Stream ---"

# CentOS Stream 9
if download_file \
    "https://cloud.centos.org/centos/9-stream/x86_64/images/CentOS-Stream-GenericCloud-9-latest.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/centos-stream/centos-stream-9-genericcloud-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

# CentOS Stream 8 (if still available)
if download_file \
    "https://cloud.centos.org/centos/8-stream/x86_64/images/CentOS-Stream-GenericCloud-8-latest.x86_64.qcow2" \
    "$CORPUS_BASE/qcow2/cloud/centos-stream/centos-stream-8-genericcloud-amd64.qcow2"; then
    ((success++))
else
    ((failed++))
fi

echo ""
echo "=== Summary ==="
echo "Downloaded: $success"
echo "Failed: $failed"
echo ""

# List downloaded files
echo "=== Downloaded RHEL-Clone Images ==="
find "$CORPUS_BASE" -path "*/cloud/*" -type f \( -name "*alma*" -o -name "*rocky*" -o -name "*centos*" \) 2>/dev/null | sort
