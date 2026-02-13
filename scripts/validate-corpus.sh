#!/bin/bash
# =============================================================================
# Saffron Test Corpus Validation Script
# =============================================================================
# Validates the test corpus integrity and completeness.
#
# Usage:
#   ./scripts/validate-corpus.sh [options]
#
# Options:
#   --corpus-dir DIR    Corpus directory (default: ./test-corpus)
#   --check-sha256      Verify all SHA256 checksums (slow)
#   --sample-sha256 N   Verify N% of checksums (default: 10)
#   --strict            Fail on any warning
#   --quiet             Only show errors
#   --help              Show this help message
#
# Exit codes:
#   0 - Corpus is valid
#   1 - Corpus has errors
#   2 - Corpus has warnings (if --strict)
# =============================================================================

set -euo pipefail

# Configuration
CORPUS_DIR="${CORPUS_DIR:-./test-corpus}"
MANIFEST_FILE="$CORPUS_DIR/manifest.json"
CHECK_ALL_SHA256=false
SHA256_SAMPLE_PERCENT=10
STRICT_MODE=false
QUIET_MODE=false

# Counters
ERRORS=0
WARNINGS=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { [ "$QUIET_MODE" = false ] && echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; ((WARNINGS++)) || true; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; ((ERRORS++)) || true; }
log_check() { [ "$QUIET_MODE" = false ] && echo -e "${BLUE}[CHECK]${NC} $*"; }

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --corpus-dir)
            CORPUS_DIR="$2"
            MANIFEST_FILE="$CORPUS_DIR/manifest.json"
            shift 2
            ;;
        --check-sha256)
            CHECK_ALL_SHA256=true
            shift
            ;;
        --sample-sha256)
            SHA256_SAMPLE_PERCENT="$2"
            shift 2
            ;;
        --strict)
            STRICT_MODE=true
            shift
            ;;
        --quiet)
            QUIET_MODE=true
            shift
            ;;
        --help)
            head -24 "$0" | tail -22
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Check prerequisites
check_prerequisites() {
    log_check "Checking prerequisites..."

    if ! command -v jq &> /dev/null; then
        log_error "jq is required but not installed"
        exit 1
    fi

    if [ ! -d "$CORPUS_DIR" ]; then
        log_error "Corpus directory not found: $CORPUS_DIR"
        exit 1
    fi

    if [ ! -f "$MANIFEST_FILE" ]; then
        log_error "Manifest not found: $MANIFEST_FILE"
        exit 1
    fi

    log_info "Prerequisites OK"
}

# Validate manifest JSON syntax
validate_manifest_syntax() {
    log_check "Validating manifest syntax..."

    if ! jq empty "$MANIFEST_FILE" 2>/dev/null; then
        log_error "Invalid JSON in manifest.json"
        return 1
    fi

    # Check required fields
    if ! jq -e '.version' "$MANIFEST_FILE" > /dev/null 2>&1; then
        log_error "Missing 'version' field in manifest"
    fi

    if ! jq -e '.images' "$MANIFEST_FILE" > /dev/null 2>&1; then
        log_error "Missing 'images' array in manifest"
    fi

    log_info "Manifest syntax OK"
}

# Check for missing files
check_missing_files() {
    log_check "Checking for missing files..."

    local missing=0
    local total
    total=$(jq '.images | length' "$MANIFEST_FILE")

    while IFS= read -r path; do
        if [ ! -f "$CORPUS_DIR/$path" ]; then
            log_error "MISSING: $path"
            ((missing++)) || true
        fi
    done < <(jq -r '.images[].path' "$MANIFEST_FILE")

    if [ "$missing" -gt 0 ]; then
        log_error "$missing of $total files are missing"
    else
        log_info "All $total files present"
    fi
}

# Check for orphaned files (files not in manifest)
check_orphaned_files() {
    log_check "Checking for orphaned files..."

    local orphans=0

    while IFS= read -r file; do
        local relpath="${file#$CORPUS_DIR/}"

        # Skip non-image files
        case "$relpath" in
            *.qcow2|*.vmdk|*.vhd|*.vhdx|*.vdi)
                if ! jq -e ".images[] | select(.path == \"$relpath\")" "$MANIFEST_FILE" > /dev/null 2>&1; then
                    log_warn "ORPHAN: $relpath (not in manifest)"
                    ((orphans++)) || true
                fi
                ;;
        esac
    done < <(find "$CORPUS_DIR" -type f \( -name "*.qcow2" -o -name "*.vmdk" -o -name "*.vhd" -o -name "*.vhdx" -o -name "*.vdi" \))

    if [ "$orphans" -gt 0 ]; then
        log_warn "$orphans orphaned files found"
    else
        log_info "No orphaned files"
    fi
}

# Verify SHA256 checksums
verify_checksums() {
    log_check "Verifying checksums..."

    local total
    total=$(jq '.images | length' "$MANIFEST_FILE")

    if [ "$total" -eq 0 ]; then
        log_info "No images to verify"
        return
    fi

    local to_check
    if [ "$CHECK_ALL_SHA256" = true ]; then
        to_check=$total
        log_info "Verifying all $total checksums..."
    else
        to_check=$((total * SHA256_SAMPLE_PERCENT / 100))
        [ "$to_check" -lt 1 ] && to_check=1
        [ "$to_check" -gt "$total" ] && to_check=$total
        log_info "Verifying $to_check of $total checksums ($SHA256_SAMPLE_PERCENT% sample)..."
    fi

    local verified=0
    local failed=0

    while IFS=$'\t' read -r path expected_sha256; do
        if [ ! -f "$CORPUS_DIR/$path" ]; then
            continue
        fi

        local actual_sha256
        actual_sha256=$(sha256sum "$CORPUS_DIR/$path" | cut -d' ' -f1)

        if [ "$actual_sha256" != "$expected_sha256" ]; then
            log_error "CHECKSUM MISMATCH: $path"
            log_error "  Expected: $expected_sha256"
            log_error "  Actual:   $actual_sha256"
            ((failed++)) || true
        else
            ((verified++)) || true
        fi

        if [ "$verified" -ge "$to_check" ]; then
            break
        fi
    done < <(jq -r '.images[] | [.path, .sha256] | @tsv' "$MANIFEST_FILE" | shuf)

    if [ "$failed" -gt 0 ]; then
        log_error "$failed checksum failures"
    else
        log_info "$verified checksums verified"
    fi
}

# Check corpus requirements
check_requirements() {
    log_check "Checking corpus requirements..."

    local total
    total=$(jq '.images | length' "$MANIFEST_FILE")

    # Total images
    if [ "$total" -lt 200 ]; then
        log_warn "Total images ($total) below minimum (200)"
    else
        log_info "Total images: $total (>= 200)"
    fi

    # Format counts
    local vmdk qcow2 vhd vhdx vdi
    vmdk=$(jq '[.images[] | select(.format == "vmdk")] | length' "$MANIFEST_FILE")
    qcow2=$(jq '[.images[] | select(.format == "qcow2")] | length' "$MANIFEST_FILE")
    vhd=$(jq '[.images[] | select(.format == "vhd")] | length' "$MANIFEST_FILE")
    vhdx=$(jq '[.images[] | select(.format == "vhdx")] | length' "$MANIFEST_FILE")
    vdi=$(jq '[.images[] | select(.format == "vdi")] | length' "$MANIFEST_FILE")

    [ "$vmdk" -lt 50 ] && log_warn "VMDK count ($vmdk) below minimum (50)"
    [ "$qcow2" -lt 50 ] && log_warn "QCOW2 count ($qcow2) below minimum (50)"
    [ "$vhd" -lt 30 ] && log_warn "VHD count ($vhd) below minimum (30)"
    [ "$vhdx" -lt 20 ] && log_warn "VHDX count ($vhdx) below minimum (20)"
    [ "$vdi" -lt 50 ] && log_warn "VDI count ($vdi) below minimum (50)"

    log_info "Format distribution: VMDK=$vmdk, QCOW2=$qcow2, VHD=$vhd, VHDX=$vhdx, VDI=$vdi"

    # Legacy count
    local legacy
    legacy=$(jq '[.images[] | select(.year >= 2005 and .year <= 2010)] | length' "$MANIFEST_FILE")

    if [ "$legacy" -lt 100 ]; then
        log_warn "Legacy images ($legacy) below minimum (100)"
    else
        log_info "Legacy images: $legacy (>= 100)"
    fi
}

# Check GPG signature
check_signature() {
    log_check "Checking manifest signature..."

    local sig_file="$MANIFEST_FILE.sig"

    if [ ! -f "$sig_file" ]; then
        log_warn "Manifest signature not found: $sig_file"
        log_warn "Run './scripts/sign-manifest.sh' to sign the manifest"
        return
    fi

    if ! command -v gpg &> /dev/null; then
        log_warn "GPG not installed - cannot verify signature"
        return
    fi

    if gpg --verify "$sig_file" "$MANIFEST_FILE" 2>/dev/null; then
        log_info "Manifest signature valid"
    else
        log_error "Manifest signature INVALID or untrusted"
    fi
}

# Check malware scan status
check_malware_scans() {
    log_check "Checking malware scan status..."

    local scanned=0
    local unscanned=0

    while IFS= read -r path; do
        local scan_report="$CORPUS_DIR/scan-reports/$(sha256sum "$CORPUS_DIR/$path" 2>/dev/null | cut -d' ' -f1).json"

        if [ -f "$scan_report" ]; then
            local status
            status=$(jq -r '.status' "$scan_report" 2>/dev/null) || status="unknown"

            if [ "$status" = "infected" ]; then
                log_error "INFECTED: $path"
            else
                ((scanned++)) || true
            fi
        else
            ((unscanned++)) || true
        fi
    done < <(jq -r '.images[].path' "$MANIFEST_FILE")

    if [ "$unscanned" -gt 0 ]; then
        log_warn "$unscanned images not scanned for malware"
    fi

    log_info "$scanned images have clean scan reports"
}

# Print summary
print_summary() {
    echo ""
    echo "=== Validation Summary ==="
    echo "Errors:   $ERRORS"
    echo "Warnings: $WARNINGS"
    echo ""

    if [ "$ERRORS" -gt 0 ]; then
        echo -e "${RED}VALIDATION FAILED${NC}"
        return 1
    elif [ "$WARNINGS" -gt 0 ] && [ "$STRICT_MODE" = true ]; then
        echo -e "${YELLOW}VALIDATION FAILED (strict mode)${NC}"
        return 2
    elif [ "$WARNINGS" -gt 0 ]; then
        echo -e "${YELLOW}VALIDATION PASSED WITH WARNINGS${NC}"
        return 0
    else
        echo -e "${GREEN}VALIDATION PASSED${NC}"
        return 0
    fi
}

# Main validation flow
main() {
    log_info "=== Saffron Test Corpus Validation ==="
    log_info "Corpus: $CORPUS_DIR"

    check_prerequisites
    validate_manifest_syntax
    check_missing_files
    check_orphaned_files
    verify_checksums
    check_requirements
    check_signature
    check_malware_scans

    print_summary
}

main "$@"
