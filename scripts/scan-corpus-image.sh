#!/bin/bash
# =============================================================================
# Saffron Corpus Image Malware Scanner
# =============================================================================
# Scans VM disk images for malware before adding to the test corpus.
#
# Usage:
#   ./scripts/scan-corpus-image.sh <image-path> [options]
#
# Options:
#   --output-dir DIR    Directory for scan reports (default: ./test-corpus/scan-reports)
#   --mount-scan        Also mount and scan contained filesystems (requires root)
#   --yara-rules FILE   Custom YARA rules file
#   --quarantine-days N Days to quarantine before promotion (default: 7)
#   --help              Show this help message
#
# Prerequisites:
#   - ClamAV (clamscan)
#   - jq
#   - Optional: yara (for custom rules)
#   - Optional: qemu-nbd, mount (for mount-scan)
#
# Exit codes:
#   0 - Image is clean
#   1 - Malware detected
#   2 - Scan error
# =============================================================================

set -euo pipefail

# Configuration
OUTPUT_DIR="${OUTPUT_DIR:-./test-corpus/scan-reports}"
MOUNT_SCAN=false
YARA_RULES=""
QUARANTINE_DAYS=7

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# Parse command line arguments
IMAGE_PATH=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --mount-scan)
            MOUNT_SCAN=true
            shift
            ;;
        --yara-rules)
            YARA_RULES="$2"
            shift 2
            ;;
        --quarantine-days)
            QUARANTINE_DAYS="$2"
            shift 2
            ;;
        --help)
            head -28 "$0" | tail -26
            exit 0
            ;;
        -*)
            log_error "Unknown option: $1"
            exit 2
            ;;
        *)
            IMAGE_PATH="$1"
            shift
            ;;
    esac
done

if [ -z "$IMAGE_PATH" ]; then
    log_error "Usage: $0 <image-path> [options]"
    exit 2
fi

if [ ! -f "$IMAGE_PATH" ]; then
    log_error "Image not found: $IMAGE_PATH"
    exit 2
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Generate report filename based on image hash
IMAGE_HASH=$(sha256sum "$IMAGE_PATH" | cut -d' ' -f1)
REPORT_FILE="$OUTPUT_DIR/${IMAGE_HASH}.json"

log_info "=== Scanning: $IMAGE_PATH ==="
log_info "Hash: $IMAGE_HASH"
log_info "Report: $REPORT_FILE"

# Initialize report
SCAN_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
SCAN_RESULTS=()
OVERALL_STATUS="clean"

# ClamAV scan
run_clamav_scan() {
    log_info "Running ClamAV scan..."

    if ! command -v clamscan &> /dev/null; then
        log_warn "ClamAV not installed - skipping"
        SCAN_RESULTS+=("clamav:skipped:not_installed")
        return
    fi

    # Update virus definitions if possible
    if command -v freshclam &> /dev/null; then
        log_info "Updating ClamAV definitions..."
        sudo freshclam --quiet 2>/dev/null || log_warn "Could not update definitions"
    fi

    local result
    result=$(clamscan --infected --no-summary "$IMAGE_PATH" 2>&1) || true

    if echo "$result" | grep -q "FOUND"; then
        log_error "ClamAV DETECTED malware:"
        echo "$result" | grep "FOUND"
        SCAN_RESULTS+=("clamav:infected:$(echo "$result" | grep 'FOUND' | head -1)")
        OVERALL_STATUS="infected"
    else
        log_info "ClamAV: Clean"
        SCAN_RESULTS+=("clamav:clean")
    fi
}

# YARA scan
run_yara_scan() {
    if [ -z "$YARA_RULES" ]; then
        return
    fi

    log_info "Running YARA scan..."

    if ! command -v yara &> /dev/null; then
        log_warn "YARA not installed - skipping"
        SCAN_RESULTS+=("yara:skipped:not_installed")
        return
    fi

    if [ ! -f "$YARA_RULES" ]; then
        log_warn "YARA rules not found: $YARA_RULES"
        SCAN_RESULTS+=("yara:skipped:rules_not_found")
        return
    fi

    local result
    result=$(yara "$YARA_RULES" "$IMAGE_PATH" 2>&1) || true

    if [ -n "$result" ]; then
        log_error "YARA matched rules:"
        echo "$result"
        SCAN_RESULTS+=("yara:matched:$(echo "$result" | head -1)")
        OVERALL_STATUS="suspicious"
    else
        log_info "YARA: No matches"
        SCAN_RESULTS+=("yara:clean")
    fi
}

# Mount and scan contained filesystem (requires root)
run_mount_scan() {
    if [ "$MOUNT_SCAN" != true ]; then
        return
    fi

    log_info "Running mounted filesystem scan..."

    if [ "$EUID" -ne 0 ]; then
        log_warn "Mount scan requires root - skipping"
        SCAN_RESULTS+=("mount_scan:skipped:not_root")
        return
    fi

    if ! command -v qemu-nbd &> /dev/null; then
        log_warn "qemu-nbd not installed - skipping mount scan"
        SCAN_RESULTS+=("mount_scan:skipped:qemu_nbd_not_installed")
        return
    fi

    local nbd_device="/dev/nbd0"
    local mount_point
    mount_point=$(mktemp -d)

    # Load nbd module
    modprobe nbd max_part=8 2>/dev/null || true

    # Connect image to nbd
    log_info "Connecting image to NBD..."
    qemu-nbd --connect="$nbd_device" --read-only "$IMAGE_PATH"

    # Wait for device
    sleep 2

    # Try to mount first partition
    local mounted=false
    for part in "${nbd_device}"p1 "${nbd_device}"; do
        if [ -b "$part" ]; then
            if mount -o ro,noexec,nosuid "$part" "$mount_point" 2>/dev/null; then
                mounted=true
                log_info "Mounted $part at $mount_point"
                break
            fi
        fi
    done

    if [ "$mounted" = true ]; then
        # Scan mounted filesystem
        local result
        result=$(clamscan --infected --recursive --no-summary "$mount_point" 2>&1) || true

        if echo "$result" | grep -q "FOUND"; then
            log_error "ClamAV found malware in filesystem:"
            echo "$result" | grep "FOUND"
            SCAN_RESULTS+=("mount_scan:infected:$(echo "$result" | grep 'FOUND' | wc -l)_files")
            OVERALL_STATUS="infected"
        else
            log_info "Mount scan: Clean"
            SCAN_RESULTS+=("mount_scan:clean")
        fi

        # Unmount
        umount "$mount_point"
    else
        log_warn "Could not mount any partition"
        SCAN_RESULTS+=("mount_scan:skipped:mount_failed")
    fi

    # Disconnect NBD
    qemu-nbd --disconnect "$nbd_device"
    rmdir "$mount_point"
}

# Check file characteristics
check_file_characteristics() {
    log_info "Checking file characteristics..."

    local file_size
    file_size=$(stat -c%s "$IMAGE_PATH")

    # Check for suspiciously small files
    if [ "$file_size" -lt 1024 ]; then
        log_warn "File is suspiciously small: $file_size bytes"
        SCAN_RESULTS+=("size_check:suspicious:too_small")
    fi

    # Check for embedded executables (simple heuristic)
    if file "$IMAGE_PATH" | grep -q "executable"; then
        log_warn "File contains executable content"
        SCAN_RESULTS+=("file_type:suspicious:executable_content")
    fi
}

# Generate report
generate_report() {
    log_info "Generating scan report..."

    local results_json="[]"
    for result in "${SCAN_RESULTS[@]}"; do
        local scanner status details
        scanner=$(echo "$result" | cut -d: -f1)
        status=$(echo "$result" | cut -d: -f2)
        details=$(echo "$result" | cut -d: -f3-)

        results_json=$(echo "$results_json" | jq \
            --arg scanner "$scanner" \
            --arg status "$status" \
            --arg details "$details" \
            '. += [{"scanner": $scanner, "status": $status, "details": $details}]')
    done

    local quarantine_until=""
    if [ "$OVERALL_STATUS" != "clean" ]; then
        quarantine_until=$(date -u -d "+$QUARANTINE_DAYS days" +%Y-%m-%dT%H:%M:%SZ)
    fi

    cat > "$REPORT_FILE" << EOF
{
  "image_path": "$IMAGE_PATH",
  "image_hash": "$IMAGE_HASH",
  "scan_date": "$SCAN_DATE",
  "overall_status": "$OVERALL_STATUS",
  "quarantine_until": "$quarantine_until",
  "scan_results": $results_json
}
EOF

    log_info "Report written to: $REPORT_FILE"
}

# Main
main() {
    run_clamav_scan
    run_yara_scan
    check_file_characteristics
    run_mount_scan
    generate_report

    echo ""
    echo "=== Scan Summary ==="
    echo "Image:  $IMAGE_PATH"
    echo "Status: $OVERALL_STATUS"
    echo ""

    if [ "$OVERALL_STATUS" = "clean" ]; then
        log_info "Image is CLEAN and ready for corpus"
        exit 0
    elif [ "$OVERALL_STATUS" = "suspicious" ]; then
        log_warn "Image is SUSPICIOUS - review before adding to corpus"
        exit 1
    else
        log_error "Image is INFECTED - DO NOT add to corpus"
        exit 1
    fi
}

main
