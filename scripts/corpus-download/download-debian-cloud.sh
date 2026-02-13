#!/bin/bash
# Download Debian Cloud Images for Saffron test corpus
# Source: https://cloud.debian.org/images/cloud/
#
# This script downloads official Debian cloud images in QCOW2 and RAW formats.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/qcow2/cloud/debian"
mkdir -p "$CORPUS_BASE/raw/cloud/debian"

# Base URL for Debian cloud images
DEBIAN_BASE="https://cloud.debian.org/images/cloud"

# Define images to download
# Format: "codename|version|arch|variant|formats"
# variant: generic, genericcloud, nocloud
# formats: qcow2,raw
declare -a IMAGES=(
    "bookworm|12|amd64|generic|qcow2"
    "bookworm|12|amd64|genericcloud|qcow2"
    "bookworm|12|amd64|nocloud|qcow2"
    "bullseye|11|amd64|generic|qcow2"
    "bullseye|11|amd64|genericcloud|qcow2"
    "buster|10|amd64|generic|qcow2"
)

download_image() {
    local codename="$1"
    local version="$2"
    local arch="$3"
    local variant="$4"
    local format="$5"

    local url=""
    local filename=""
    local target_dir=""

    # Debian uses dated subdirectories, we'll use "latest" symlink
    case "$format" in
        qcow2)
            url="$DEBIAN_BASE/$codename/latest/debian-$version-$variant-$arch.qcow2"
            filename="debian-${version}-${variant}-${arch}.qcow2"
            target_dir="$CORPUS_BASE/qcow2/cloud/debian"
            ;;
        raw)
            url="$DEBIAN_BASE/$codename/latest/debian-$version-$variant-$arch.raw"
            filename="debian-${version}-${variant}-${arch}.raw"
            target_dir="$CORPUS_BASE/raw/cloud/debian"
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

echo "=== Debian Cloud Images Download ==="
echo "Target: $CORPUS_BASE"
echo ""

success=0
failed=0

for image_spec in "${IMAGES[@]}"; do
    IFS='|' read -r codename version arch variant formats <<< "$image_spec"

    IFS=',' read -ra format_list <<< "$formats"
    for format in "${format_list[@]}"; do
        if download_image "$codename" "$version" "$arch" "$variant" "$format"; then
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
echo "=== Downloaded Debian Images ==="
find "$CORPUS_BASE" -path "*/cloud/debian/*" -type f \( -name "*.qcow2" -o -name "*.raw" \) 2>/dev/null | sort
