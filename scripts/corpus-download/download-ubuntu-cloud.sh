#!/bin/bash
# Download Ubuntu Cloud Images for Saffron test corpus
# Source: https://cloud-images.ubuntu.com/
#
# This script downloads official Ubuntu cloud images in multiple formats
# to test QCOW2, VMDK, and OVA format reading.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/qcow2/cloud/ubuntu"
mkdir -p "$CORPUS_BASE/vmdk/cloud/ubuntu"
mkdir -p "$CORPUS_BASE/ova/cloud/ubuntu"

# Base URL for Ubuntu cloud images
UBUNTU_BASE="https://cloud-images.ubuntu.com"

# Define images to download
# Format: "release|version|arch|formats"
# formats: comma-separated list of qcow2,vmdk,ova
declare -a IMAGES=(
    "noble|24.04|amd64|qcow2"
    "jammy|22.04|amd64|qcow2"
    "focal|20.04|amd64|qcow2"
    "bionic|18.04|amd64|qcow2"
)

download_image() {
    local release="$1"
    local version="$2"
    local arch="$3"
    local format="$4"

    local url=""
    local filename=""
    local target_dir=""

    case "$format" in
        qcow2)
            # Ubuntu uses .img extension for QCOW2 files
            url="$UBUNTU_BASE/$release/current/${release}-server-cloudimg-${arch}.img"
            filename="ubuntu-${version}-server-cloudimg-${arch}.qcow2"
            target_dir="$CORPUS_BASE/qcow2/cloud/ubuntu"
            ;;
        vmdk)
            url="$UBUNTU_BASE/$release/current/${release}-server-cloudimg-${arch}.vmdk"
            filename="ubuntu-${version}-server-cloudimg-${arch}.vmdk"
            target_dir="$CORPUS_BASE/vmdk/cloud/ubuntu"
            ;;
        ova)
            url="$UBUNTU_BASE/$release/current/${release}-server-cloudimg-${arch}.ova"
            filename="ubuntu-${version}-server-cloudimg-${arch}.ova"
            target_dir="$CORPUS_BASE/ova/cloud/ubuntu"
            ;;
        *)
            echo "Unknown format: $format"
            return 1
            ;;
    esac

    local target_path="$target_dir/$filename"

    if [[ -f "$target_path" ]]; then
        echo "SKIP: $filename (already exists)"
        return 0
    fi

    echo "Downloading: $filename"
    echo "  URL: $url"

    if curl -fSL --progress-bar -o "$target_path.tmp" "$url"; then
        mv "$target_path.tmp" "$target_path"
        echo "  OK: Downloaded $(du -h "$target_path" | cut -f1)"
    else
        rm -f "$target_path.tmp"
        echo "  FAILED: Could not download $url"
        return 1
    fi
}

echo "=== Ubuntu Cloud Images Download ==="
echo "Target: $CORPUS_BASE"
echo ""

success=0
failed=0

for image_spec in "${IMAGES[@]}"; do
    IFS='|' read -r release version arch formats <<< "$image_spec"

    IFS=',' read -ra format_list <<< "$formats"
    for format in "${format_list[@]}"; do
        if download_image "$release" "$version" "$arch" "$format"; then
            ((success++))
        else
            ((failed++))
        fi
    done
done

echo ""
echo "=== Summary ==="
echo "Downloaded: $success"
echo "Failed: $failed"
echo ""

# List downloaded files
echo "=== Downloaded Ubuntu Images ==="
find "$CORPUS_BASE" -path "*/cloud/ubuntu/*" -type f -name "*.qcow2" -o -name "*.vmdk" -o -name "*.ova" 2>/dev/null | sort
