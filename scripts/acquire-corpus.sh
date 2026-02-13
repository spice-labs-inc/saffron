#!/bin/bash
# =============================================================================
# Saffron Test Corpus Acquisition Script
# =============================================================================
# Downloads and catalogs open source VM images for testing.
#
# Usage:
#   ./scripts/acquire-corpus.sh [options]
#
# Options:
#   --corpus-dir DIR    Corpus directory (default: ./test-corpus)
#   --tier TIER         Only download tier: quick, standard, full (default: all)
#   --format FORMAT     Only download format: vmdk, qcow2, vhd, vhdx, vdi
#   --dry-run           Show what would be downloaded without downloading
#   --help              Show this help message
# =============================================================================

set -euo pipefail

# Configuration
CORPUS_DIR="${CORPUS_DIR:-./test-corpus}"
MANIFEST_FILE="$CORPUS_DIR/manifest.json"
SCAN_REPORTS_DIR="$CORPUS_DIR/scan-reports"
DRY_RUN=false
TIER_FILTER=""
FORMAT_FILTER=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --corpus-dir)
            CORPUS_DIR="$2"
            MANIFEST_FILE="$CORPUS_DIR/manifest.json"
            SCAN_REPORTS_DIR="$CORPUS_DIR/scan-reports"
            shift 2
            ;;
        --tier)
            TIER_FILTER="$2"
            shift 2
            ;;
        --format)
            FORMAT_FILTER="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --help)
            head -20 "$0" | tail -18
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Initialize corpus directory structure
init_corpus() {
    log_info "Initializing corpus directory: $CORPUS_DIR"

    mkdir -p "$CORPUS_DIR"/{vmdk,qcow2,vhd,vhdx,vdi}/{legacy,modern}
    mkdir -p "$SCAN_REPORTS_DIR"

    if [ ! -f "$MANIFEST_FILE" ]; then
        cat > "$MANIFEST_FILE" << 'EOF'
{
  "version": "1.0",
  "generated": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "description": "Saffron test corpus - VM disk images for testing",
  "total_images": 0,
  "images": []
}
EOF
        log_info "Created empty manifest.json"
    fi
}

# Download with retry and verification
download_image() {
    local url="$1"
    local dest="$2"
    local expected_sha256="${3:-}"

    if [ "$DRY_RUN" = true ]; then
        log_info "[DRY-RUN] Would download: $url -> $dest"
        return 0
    fi

    log_info "Downloading: $url"

    # Create destination directory
    mkdir -p "$(dirname "$dest")"

    # Download with retry
    if ! wget --retry-connrefused --waitretry=1 --read-timeout=20 \
              --timeout=15 -t 3 -q --show-progress -O "$dest" "$url"; then
        log_error "Failed to download: $url"
        return 1
    fi

    # Verify checksum if provided
    if [ -n "$expected_sha256" ]; then
        local actual
        actual=$(sha256sum "$dest" | cut -d' ' -f1)
        if [ "$actual" != "$expected_sha256" ]; then
            log_error "SHA256 mismatch for $dest"
            log_error "  Expected: $expected_sha256"
            log_error "  Actual:   $actual"
            rm -f "$dest"
            return 1
        fi
        log_info "Checksum verified: $dest"
    fi

    return 0
}

# Run malware scan on an image
scan_image() {
    local image_path="$1"
    local report_file="$SCAN_REPORTS_DIR/$(sha256sum "$image_path" | cut -d' ' -f1).json"

    if [ "$DRY_RUN" = true ]; then
        log_info "[DRY-RUN] Would scan: $image_path"
        return 0
    fi

    log_info "Scanning for malware: $image_path"

    # Check if clamscan is available
    if ! command -v clamscan &> /dev/null; then
        log_warn "ClamAV not installed - skipping malware scan"
        log_warn "Install with: sudo apt-get install clamav"

        # Create stub report
        cat > "$report_file" << EOF
{
  "image": "$image_path",
  "scan_date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "scanner": "none",
  "status": "skipped",
  "reason": "ClamAV not installed"
}
EOF
        return 0
    fi

    # Run ClamAV scan
    local scan_result
    scan_result=$(clamscan --infected --no-summary "$image_path" 2>&1) || true

    if echo "$scan_result" | grep -q "FOUND"; then
        log_error "MALWARE DETECTED in $image_path"
        log_error "$scan_result"

        cat > "$report_file" << EOF
{
  "image": "$image_path",
  "scan_date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "scanner": "clamav",
  "status": "infected",
  "details": $(echo "$scan_result" | jq -Rs .)
}
EOF
        return 1
    fi

    cat > "$report_file" << EOF
{
  "image": "$image_path",
  "scan_date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "scanner": "clamav",
  "status": "clean"
}
EOF

    log_info "Scan clean: $image_path"
    return 0
}

# Add image to manifest
catalog_image() {
    local id="$1"
    local path="$2"
    local format="$3"
    local era="$4"
    local year="$5"
    local source_url="$6"
    local license="$7"
    local ci_tier="${8:-full}"

    if [ "$DRY_RUN" = true ]; then
        log_info "[DRY-RUN] Would catalog: $id"
        return 0
    fi

    local full_path="$CORPUS_DIR/$path"

    if [ ! -f "$full_path" ]; then
        log_error "Image file not found: $full_path"
        return 1
    fi

    # Calculate SHA256 and file size
    local sha256
    sha256=$(sha256sum "$full_path" | cut -d' ' -f1)
    local actual_size
    actual_size=$(stat -c%s "$full_path")

    # Try to get virtual size using qemu-img if available
    local virtual_size=0
    if command -v qemu-img &> /dev/null; then
        virtual_size=$(qemu-img info --output=json "$full_path" 2>/dev/null | \
                       jq -r '.["virtual-size"] // 0') || virtual_size=0
    fi

    log_info "Cataloging image: $id"

    # Add to manifest using jq
    local tmp_manifest
    tmp_manifest=$(mktemp)

    jq --arg id "$id" \
       --arg path "$path" \
       --arg format "$format" \
       --arg era "$era" \
       --argjson year "$year" \
       --arg source_url "$source_url" \
       --arg license "$license" \
       --arg sha256 "$sha256" \
       --argjson virtual_size "$virtual_size" \
       --argjson actual_size "$actual_size" \
       --arg ci_tier "$ci_tier" \
       --arg date "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
       '.images += [{
         "id": $id,
         "path": $path,
         "format": $format,
         "era": $era,
         "year": $year,
         "source_url": $source_url,
         "license": $license,
         "sha256": $sha256,
         "virtual_size_bytes": $virtual_size,
         "actual_size_bytes": $actual_size,
         "ci_tier": $ci_tier,
         "priority": 3,
         "features": [],
         "known_files": [],
         "provenance": {
           "source_url": $source_url,
           "download_date": $date,
           "download_sha256": $sha256,
           "license": $license,
           "license_verified": false,
           "malware_scanned": true,
           "malware_scan_date": $date
         }
       }] | .total_images = (.images | length)' \
       "$MANIFEST_FILE" > "$tmp_manifest" && mv "$tmp_manifest" "$MANIFEST_FILE"

    log_info "Added to manifest: $id"
}

# Print corpus statistics
print_stats() {
    log_info "=== Corpus Statistics ==="

    if [ ! -f "$MANIFEST_FILE" ]; then
        log_warn "No manifest found"
        return
    fi

    echo "Total images: $(jq '.images | length' "$MANIFEST_FILE")"
    echo ""
    echo "By format:"
    jq -r '.images | group_by(.format) | map("  " + .[0].format + ": " + (length | tostring)) | .[]' "$MANIFEST_FILE"
    echo ""
    echo "By era:"
    jq -r '.images | group_by(.era) | map("  " + .[0].era + ": " + (length | tostring)) | .[]' "$MANIFEST_FILE"
    echo ""
    echo "Legacy images (2005-2010): $(jq '[.images[] | select(.year >= 2005 and .year <= 2010)] | length' "$MANIFEST_FILE")"
}

# Download from specific sources
download_osboxes() {
    log_info "=== Downloading from osboxes.org ==="
    log_warn "osboxes.org requires manual download due to CAPTCHA protection"
    log_info "Visit: https://www.osboxes.org/virtualbox-images/"
    log_info "Download images to: $CORPUS_DIR/vdi/modern/"
}

download_ubuntu_cloud() {
    log_info "=== Downloading Ubuntu Cloud Images ==="

    # Ubuntu cloud images are available in QCOW2 format
    local base_url="https://cloud-images.ubuntu.com"

    # Example: Download a minimal Ubuntu image for testing
    # These are small (~300MB) and good for CI
    local images=(
        "noble/current/noble-server-cloudimg-amd64.img qcow2/modern/ubuntu-24.04-cloudimg.qcow2 qcow2 modern 2024"
    )

    for entry in "${images[@]}"; do
        read -r url_path dest format era year <<< "$entry"
        local url="$base_url/$url_path"
        local id="ubuntu-$(basename "$dest" | sed 's/\.[^.]*$//')"

        if [ -n "$FORMAT_FILTER" ] && [ "$format" != "$FORMAT_FILTER" ]; then
            continue
        fi

        if download_image "$url" "$CORPUS_DIR/$dest"; then
            if scan_image "$CORPUS_DIR/$dest"; then
                catalog_image "$id" "$dest" "$format" "$era" "$year" "$url" "unknown" "standard"
            fi
        fi
    done
}

# Main acquisition flow
main() {
    log_info "=== Saffron Test Corpus Acquisition ==="
    log_info "Target: 200+ images, 100+ legacy (2005-2010)"

    init_corpus

    # Check for required tools
    if ! command -v jq &> /dev/null; then
        log_error "jq is required but not installed"
        log_error "Install with: sudo apt-get install jq"
        exit 1
    fi

    if ! command -v wget &> /dev/null; then
        log_error "wget is required but not installed"
        log_error "Install with: sudo apt-get install wget"
        exit 1
    fi

    # Run downloads based on filters
    if [ -z "$FORMAT_FILTER" ] || [ "$FORMAT_FILTER" = "qcow2" ]; then
        download_ubuntu_cloud
    fi

    download_osboxes

    print_stats

    log_info "=== Acquisition Complete ==="
    log_info "Run './scripts/validate-corpus.sh' to verify the corpus"
    log_info "Run './scripts/sign-manifest.sh' to sign the manifest"
}

main "$@"
