#!/bin/bash
# Master script to download all cloud VM images for Saffron test corpus
#
# This script orchestrates downloading images from multiple sources and
# converting them to various formats for comprehensive testing.
#
# Usage:
#   ./download-all.sh              # Download all images
#   ./download-all.sh --skip-convert  # Skip format conversion
#   ./download-all.sh --only-convert  # Only run conversions

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Parse arguments
for arg in "$@"; do
    case $arg in
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --help           Show this help"
            echo ""
            echo "Downloads native format images from cloud providers."
            echo "No synthetic conversions - only real-world images."
            exit 0
            ;;
    esac
done

echo "=============================================="
echo "  Saffron Cloud VM Image Corpus Download"
echo "=============================================="
echo ""
echo "Target directory: $CORPUS_BASE"
echo "Date: $(date)"
echo ""

# Create base corpus directory structure
mkdir -p "$CORPUS_BASE"/{qcow2,vmdk,vhd,vhdx,vdi}/cloud

# ============================================
# Download from direct sources
# ============================================

echo ""
echo "=============================================="
echo "  Downloading Native Format Cloud Images"
echo "=============================================="

    # Ubuntu Cloud Images
    echo ""
    echo ">>> Ubuntu Cloud Images"
    if [[ -x "$SCRIPT_DIR/download-ubuntu-cloud.sh" ]]; then
        bash "$SCRIPT_DIR/download-ubuntu-cloud.sh" || echo "WARNING: Some Ubuntu downloads failed"
    else
        echo "SKIP: download-ubuntu-cloud.sh not executable"
    fi

    # Debian Cloud Images
    echo ""
    echo ">>> Debian Cloud Images"
    if [[ -x "$SCRIPT_DIR/download-debian-cloud.sh" ]]; then
        bash "$SCRIPT_DIR/download-debian-cloud.sh" || echo "WARNING: Some Debian downloads failed"
    else
        echo "SKIP: download-debian-cloud.sh not executable"
    fi

    # RHEL Clones (AlmaLinux, Rocky, CentOS Stream)
    echo ""
    echo ">>> RHEL Clone Cloud Images"
    if [[ -x "$SCRIPT_DIR/download-rhel-clones-cloud.sh" ]]; then
        bash "$SCRIPT_DIR/download-rhel-clones-cloud.sh" || echo "WARNING: Some RHEL clone downloads failed"
    else
        echo "SKIP: download-rhel-clones-cloud.sh not executable"
    fi

    # Fedora Cloud Images
    echo ""
    echo ">>> Fedora Cloud Images"
    if [[ -x "$SCRIPT_DIR/download-fedora-cloud.sh" ]]; then
        bash "$SCRIPT_DIR/download-fedora-cloud.sh" || echo "WARNING: Some Fedora downloads failed"
    else
        echo "SKIP: download-fedora-cloud.sh not executable"
    fi

# Native VMDK images (VMware, cloud exports)
echo ""
echo ">>> Native VMDK Images"
if [[ -x "$SCRIPT_DIR/download-vmdk-native.sh" ]]; then
    bash "$SCRIPT_DIR/download-vmdk-native.sh" || echo "WARNING: Some VMDK downloads failed"
else
    echo "SKIP: download-vmdk-native.sh not found (create for VMware/cloud native VMDKs)"
fi

# Native VHD/VHDX images (Azure, Hyper-V)
echo ""
echo ">>> Native VHD/VHDX Images"
if [[ -x "$SCRIPT_DIR/download-vhd-native.sh" ]]; then
    bash "$SCRIPT_DIR/download-vhd-native.sh" || echo "WARNING: Some VHD downloads failed"
else
    echo "SKIP: download-vhd-native.sh not found (create for Azure/Hyper-V native VHDs)"
fi

# Native VDI images (VirtualBox)
echo ""
echo ">>> Native VDI Images"
if [[ -x "$SCRIPT_DIR/download-vdi-native.sh" ]]; then
    bash "$SCRIPT_DIR/download-vdi-native.sh" || echo "WARNING: Some VDI downloads failed"
else
    echo "SKIP: download-vdi-native.sh not found (create for VirtualBox native VDIs)"
fi

# ============================================
# Summary
# ============================================

echo ""
echo "=============================================="
echo "  Corpus Summary"
echo "=============================================="
echo ""

echo "QCOW2 images:"
find "$CORPUS_BASE/qcow2" -name "*.qcow2" -type f 2>/dev/null | wc -l

echo "VMDK images:"
find "$CORPUS_BASE/vmdk" -name "*.vmdk" -type f 2>/dev/null | wc -l

echo "VHD images:"
find "$CORPUS_BASE/vhd" -name "*.vhd" -type f 2>/dev/null | wc -l

echo "VHDX images:"
find "$CORPUS_BASE/vhdx" -name "*.vhdx" -type f 2>/dev/null | wc -l

echo "VDI images:"
find "$CORPUS_BASE/vdi" -name "*.vdi" -type f 2>/dev/null | wc -l

echo ""
echo "Total disk usage:"
du -sh "$CORPUS_BASE" 2>/dev/null || echo "(unable to calculate)"

echo ""
echo "=============================================="
echo "  Next Steps"
echo "=============================================="
echo ""
echo "For cloud provider exports (requires accounts):"
echo "  - GCP:   ./export-gcp-images.sh"
echo "  - Azure: ./export-azure-vhds.sh"
echo "  - AWS:   ./export-aws-amis.sh"
echo ""
echo "Run corpus verification tests:"
echo "  mvn test -Dtest=CorpusFullVerificationTest"
echo ""
echo "Done!"
