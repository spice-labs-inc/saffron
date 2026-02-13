#!/bin/bash
# Download native VMDK images for Saffron test corpus
# These are images distributed in VMDK format, not converted
#
# Sources:
# - VMware Photon OS (official VMDK releases)
# - openSUSE (provides VMDK for VMware)
# - Bitnami VMs (VMDK format)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Create target directories
mkdir -p "$CORPUS_BASE/vmdk/native/photon"
mkdir -p "$CORPUS_BASE/vmdk/native/opensuse"
mkdir -p "$CORPUS_BASE/vmdk/native/turnkey"

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

echo "=== Native VMDK Images Download ==="
echo "Target: $CORPUS_BASE"
echo ""

success=0
failed=0

# ============================================
# VMware Photon OS (native VMDK)
# https://github.com/vmware/photon/wiki/Downloading-Photon-OS
# ============================================
echo "--- VMware Photon OS ---"

# Photon OS 5.0 (uses ext4)
if download_file \
    "https://packages.vmware.com/photon/5.0/GA/ova/photon-hw15-5.0-dde71ec57.x86_64.ova" \
    "$CORPUS_BASE/vmdk/native/photon/photon-5.0-x86_64.ova"; then
    ((success++))
    # Extract VMDK from OVA (OVA is a tar containing VMDK)
    if [[ -f "$CORPUS_BASE/vmdk/native/photon/photon-5.0-x86_64.ova" ]] && \
       [[ ! -f "$CORPUS_BASE/vmdk/native/photon/photon-5.0-x86_64.vmdk" ]]; then
        echo "  Extracting VMDK from OVA..."
        cd "$CORPUS_BASE/vmdk/native/photon"
        tar -xf photon-5.0-x86_64.ova '*.vmdk' 2>/dev/null || true
        # Rename extracted VMDK if found
        for f in *.vmdk; do
            if [[ "$f" != "photon-5.0-x86_64.vmdk" ]] && [[ -f "$f" ]]; then
                mv "$f" "photon-5.0-x86_64.vmdk"
                break
            fi
        done
        cd - > /dev/null
    fi
else
    ((failed++))
fi

# Photon OS 4.0
if download_file \
    "https://packages.vmware.com/photon/4.0/Rev2/ova/photon-ova-4.0-c001795b80.x86_64.ova" \
    "$CORPUS_BASE/vmdk/native/photon/photon-4.0-x86_64.ova"; then
    ((success++))
    if [[ -f "$CORPUS_BASE/vmdk/native/photon/photon-4.0-x86_64.ova" ]] && \
       [[ ! -f "$CORPUS_BASE/vmdk/native/photon/photon-4.0-x86_64.vmdk" ]]; then
        echo "  Extracting VMDK from OVA..."
        cd "$CORPUS_BASE/vmdk/native/photon"
        tar -xf photon-4.0-x86_64.ova '*.vmdk' 2>/dev/null || true
        for f in *.vmdk; do
            if [[ "$f" != "photon-4.0-x86_64.vmdk" ]] && [[ "$f" != "photon-5.0-x86_64.vmdk" ]] && [[ -f "$f" ]]; then
                mv "$f" "photon-4.0-x86_64.vmdk"
                break
            fi
        done
        cd - > /dev/null
    fi
else
    ((failed++))
fi

# ============================================
# openSUSE (native VMDK for VMware)
# https://get.opensuse.org/leap/
# ============================================
echo ""
echo "--- openSUSE ---"

# openSUSE Leap 15.6 VMware image
if download_file \
    "https://download.opensuse.org/distribution/leap/15.6/appliances/openSUSE-Leap-15.6-Minimal-VM.x86_64-Cloud.vmdk" \
    "$CORPUS_BASE/vmdk/native/opensuse/opensuse-leap-15.6-minimal-amd64.vmdk"; then
    ((success++))
else
    ((failed++))
fi

# ============================================
# TurnKey Linux (provides VMDK)
# https://www.turnkeylinux.org/
# ============================================
echo ""
echo "--- TurnKey Linux ---"

# TurnKey Core (minimal Debian-based, native VMDK)
if download_file \
    "https://releases.turnkeylinux.org/turnkey-core/18.0-bookworm-amd64/turnkey-core-18.0-bookworm-amd64.vmdk.zip" \
    "$CORPUS_BASE/vmdk/native/turnkey/turnkey-core-18.0-amd64.vmdk.zip"; then
    ((success++))
    # Extract if needed
    if [[ -f "$CORPUS_BASE/vmdk/native/turnkey/turnkey-core-18.0-amd64.vmdk.zip" ]] && \
       [[ ! -f "$CORPUS_BASE/vmdk/native/turnkey/turnkey-core-18.0-amd64.vmdk" ]]; then
        echo "  Extracting VMDK from ZIP..."
        cd "$CORPUS_BASE/vmdk/native/turnkey"
        unzip -o turnkey-core-18.0-amd64.vmdk.zip '*.vmdk' 2>/dev/null || true
        cd - > /dev/null
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
echo "=== Native VMDK Images ==="
find "$CORPUS_BASE/vmdk/native" -name "*.vmdk" -type f 2>/dev/null | sort
