#!/bin/bash
# =============================================================================
# Saffron Test Resource Generator
# =============================================================================
# Generates minimal test resources for unit testing.
# These are NOT full VM images - they're small files with valid magic bytes
# for testing format detection and basic header parsing.
#
# Usage:
#   ./scripts/generate-test-resources.sh [options]
#
# Options:
#   --output-dir DIR    Output directory (default: src/test/resources)
#   --with-qemu         Generate real images using qemu-img (larger files)
#   --help              Show this help message
# =============================================================================

set -euo pipefail

OUTPUT_DIR="${OUTPUT_DIR:-src/test/resources}"
WITH_QEMU=false

log_info() { echo "[INFO] $*"; }
log_warn() { echo "[WARN] $*"; }

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --with-qemu)
            WITH_QEMU=true
            shift
            ;;
        --help)
            head -18 "$0" | tail -16
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

mkdir -p "$OUTPUT_DIR"/{vmdk,qcow2,vhd,vhdx,vdi}

# Generate QCOW2 magic header (minimal, just for detection)
generate_qcow2_magic() {
    log_info "Generating QCOW2 magic header..."
    local file="$OUTPUT_DIR/qcow2/magic-only.qcow2"

    # QCOW2 magic: "QFI\xfb" (0x514649fb big-endian)
    # Followed by version (4 bytes, big-endian)
    printf '\x51\x46\x49\xfb' > "$file"           # Magic
    printf '\x00\x00\x00\x03' >> "$file"          # Version 3
    printf '\x00\x00\x00\x00\x00\x00\x00\x00' >> "$file"  # Backing file offset (0)
    printf '\x00\x00\x00\x00' >> "$file"          # Backing file size (0)
    printf '\x00\x00\x00\x10' >> "$file"          # Cluster bits (16 = 64KB clusters)
    printf '\x00\x00\x00\x00\x00\x10\x00\x00' >> "$file"  # Virtual size (1MB)
    printf '\x00\x00\x00\x00' >> "$file"          # Encryption method (none)
    printf '\x00\x00\x00\x01' >> "$file"          # L1 size
    printf '\x00\x00\x00\x00\x00\x00\x40\x00' >> "$file"  # L1 table offset
    printf '\x00\x00\x00\x00\x00\x00\x50\x00' >> "$file"  # Refcount table offset
    printf '\x00\x00\x00\x01' >> "$file"          # Refcount table clusters
    printf '\x00\x00\x00\x00' >> "$file"          # Nb snapshots
    printf '\x00\x00\x00\x00\x00\x00\x00\x00' >> "$file"  # Snapshots offset

    # Pad to 512 bytes
    dd if=/dev/zero bs=1 count=$((512 - $(stat -c%s "$file"))) >> "$file" 2>/dev/null

    log_info "Created: $file ($(stat -c%s "$file") bytes)"
}

# Generate VMDK sparse magic header
generate_vmdk_magic() {
    log_info "Generating VMDK sparse magic header..."
    local file="$OUTPUT_DIR/vmdk/magic-only.vmdk"

    # VMDK sparse magic: "KDMV" (0x564d444b in file, "VMDK" reversed)
    printf 'KDMV' > "$file"                       # Magic
    printf '\x01\x00\x00\x00' >> "$file"          # Version 1
    printf '\x03\x00\x00\x00' >> "$file"          # Flags
    printf '\x00\x00\x00\x00\x00\x00\x00\x00' >> "$file"  # Capacity (sectors)
    printf '\x80\x00\x00\x00\x00\x00\x00\x00' >> "$file"  # Grain size (128 sectors)
    printf '\x00\x00\x00\x00\x00\x00\x00\x00' >> "$file"  # Descriptor offset
    printf '\x00\x00\x00\x00\x00\x00\x00\x00' >> "$file"  # Descriptor size

    # Pad to 512 bytes
    dd if=/dev/zero bs=1 count=$((512 - $(stat -c%s "$file"))) >> "$file" 2>/dev/null

    log_info "Created: $file ($(stat -c%s "$file") bytes)"
}

# Generate VHD footer (VHD has footer at end, but we create a minimal file)
generate_vhd_magic() {
    log_info "Generating VHD footer..."
    local file="$OUTPUT_DIR/vhd/magic-only.vhd"

    # VHD has footer at the last 512 bytes, starting with "conectix"
    # Create 1KB file with footer at the end

    # Pad first 512 bytes
    dd if=/dev/zero bs=512 count=1 > "$file" 2>/dev/null

    # Footer starts with "conectix"
    printf 'conectix' >> "$file"                  # Cookie
    printf '\x00\x00\x00\x02' >> "$file"          # Features (0x02 = reserved)
    printf '\x00\x01\x00\x00' >> "$file"          # File format version (1.0)
    printf '\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFF' >> "$file"  # Data offset (fixed = -1)
    printf '\x00\x00\x00\x00' >> "$file"          # Timestamp
    printf 'vpc ' >> "$file"                      # Creator application
    printf '\x00\x05\x00\x03' >> "$file"          # Creator version
    printf 'Wi2k' >> "$file"                      # Creator host OS (Windows)
    printf '\x00\x00\x00\x00\x00\x10\x00\x00' >> "$file"  # Original size (1MB)
    printf '\x00\x00\x00\x00\x00\x10\x00\x00' >> "$file"  # Current size
    printf '\x00\x3D\x10\x11' >> "$file"          # Disk geometry (CHS)
    printf '\x00\x00\x00\x02' >> "$file"          # Disk type (2 = fixed)

    # Pad rest of footer
    dd if=/dev/zero bs=1 count=$((512 - 84)) >> "$file" 2>/dev/null

    log_info "Created: $file ($(stat -c%s "$file") bytes)"
}

# Generate VHDX magic header
generate_vhdx_magic() {
    log_info "Generating VHDX magic header..."
    local file="$OUTPUT_DIR/vhdx/magic-only.vhdx"

    # VHDX magic: "vhdxfile" at start
    printf 'vhdxfile' > "$file"                   # File type identifier
    printf '\x00\x00\x00\x00\x00\x00\x00\x00' >> "$file"  # Creator (empty)

    # Pad to 512 bytes
    dd if=/dev/zero bs=1 count=$((512 - $(stat -c%s "$file"))) >> "$file" 2>/dev/null

    log_info "Created: $file ($(stat -c%s "$file") bytes)"
}

# Generate VDI magic header
generate_vdi_magic() {
    log_info "Generating VDI magic header..."
    local file="$OUTPUT_DIR/vdi/magic-only.vdi"

    # VDI has signature at offset 0x40: 0x7f 0x10 0xda 0xbe

    # First 64 bytes (header preamble)
    printf '<<< Oracle VM VirtualBox Disk Image >>>\n' > "$file"
    # Pad to offset 0x40 (64 bytes)
    dd if=/dev/zero bs=1 count=$((64 - $(stat -c%s "$file"))) >> "$file" 2>/dev/null

    # Signature at offset 0x40
    printf '\x7f\x10\xda\xbe' >> "$file"          # VDI signature

    # Version (1.1)
    printf '\x01\x00\x01\x00' >> "$file"          # Major.minor version

    # Pad to 512 bytes
    dd if=/dev/zero bs=1 count=$((512 - $(stat -c%s "$file"))) >> "$file" 2>/dev/null

    log_info "Created: $file ($(stat -c%s "$file") bytes)"
}

# Generate invalid/corrupted files for negative testing
generate_invalid_files() {
    log_info "Generating invalid test files..."

    # Empty file
    touch "$OUTPUT_DIR/invalid-empty.bin"

    # Too small file
    echo "x" > "$OUTPUT_DIR/invalid-too-small.bin"

    # Random bytes (no magic)
    dd if=/dev/urandom bs=512 count=1 of="$OUTPUT_DIR/invalid-random.bin" 2>/dev/null

    # Truncated QCOW2 (magic only, no header)
    printf '\x51\x46\x49\xfb' > "$OUTPUT_DIR/qcow2/truncated.qcow2"

    log_info "Created invalid test files"
}

# Generate real images using qemu-img (if available and requested)
generate_qemu_images() {
    if [ "$WITH_QEMU" != true ]; then
        return
    fi

    if ! command -v qemu-img &> /dev/null; then
        log_warn "qemu-img not installed - skipping real image generation"
        return
    fi

    log_info "Generating real images with qemu-img..."

    # QCOW2 v2 and v3
    qemu-img create -f qcow2 -o compat=0.10 "$OUTPUT_DIR/qcow2/real-v2.qcow2" 1M 2>/dev/null
    qemu-img create -f qcow2 -o compat=1.1 "$OUTPUT_DIR/qcow2/real-v3.qcow2" 1M 2>/dev/null

    # VMDK
    qemu-img create -f vmdk "$OUTPUT_DIR/vmdk/real-sparse.vmdk" 1M 2>/dev/null

    # VHD
    qemu-img create -f vpc "$OUTPUT_DIR/vhd/real-dynamic.vhd" 1M 2>/dev/null

    # VDI
    qemu-img create -f vdi "$OUTPUT_DIR/vdi/real-dynamic.vdi" 1M 2>/dev/null

    log_info "Created real VM images"
}

# Main
main() {
    log_info "=== Saffron Test Resource Generator ==="
    log_info "Output: $OUTPUT_DIR"

    generate_qcow2_magic
    generate_vmdk_magic
    generate_vhd_magic
    generate_vhdx_magic
    generate_vdi_magic
    generate_invalid_files
    generate_qemu_images

    echo ""
    log_info "=== Generated Test Resources ==="
    find "$OUTPUT_DIR" -type f -name "*.qcow2" -o -name "*.vmdk" -o -name "*.vhd" -o -name "*.vhdx" -o -name "*.vdi" -o -name "*.bin" | sort

    echo ""
    log_info "Done!"
}

main "$@"
