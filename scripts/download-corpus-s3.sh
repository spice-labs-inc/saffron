#!/usr/bin/env bash
# download-corpus-s3.sh — Download test corpus images from S3
#
# Usage:
#   scripts/download-corpus-s3.sh [OPTIONS]
#     --corpus-dir DIR    Target directory (default: <project-root>/test-corpus)
#     --mode full|ci      Download mode (default: full; auto-detects CI env)
#     --seed N            PRNG seed for CI reproducibility (default: $SAFFRON_CORPUS_SEED or $GITHUB_RUN_ID or timestamp)
#     --dry-run           Print what would be downloaded, don't download
#     --help              Show usage

set -euo pipefail

S3_BASE_URL="https://public-test-data.spice-labs.dev/saffron_vm_images"

# ── Resolve project root from script location ────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Defaults ──────────────────────────────────────────────────────────────────
CORPUS_DIR="$PROJECT_ROOT/test-corpus"
MODE=""
SEED=""
DRY_RUN=false

# ── Parse arguments ───────────────────────────────────────────────────────────
usage() {
    sed -n '3,11p' "$0" | sed 's/^# \?//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --corpus-dir)  CORPUS_DIR="$2"; shift 2 ;;
        --mode)        MODE="$2"; shift 2 ;;
        --seed)        SEED="$2"; shift 2 ;;
        --dry-run)     DRY_RUN=true; shift ;;
        --help)        usage ;;
        *)             echo "Unknown option: $1" >&2; usage ;;
    esac
done

# ── Resolve mode ──────────────────────────────────────────────────────────────
if [[ -z "$MODE" ]]; then
    if [[ "${CI:-}" == "true" ]]; then
        MODE="ci"
    else
        MODE="full"
    fi
fi

# ── Resolve seed ──────────────────────────────────────────────────────────────
if [[ -z "$SEED" ]]; then
    SEED="${SAFFRON_CORPUS_SEED:-${GITHUB_RUN_ID:-$(date +%s)}}"
fi

# ── Check dependencies ───────────────────────────────────────────────────────
for cmd in curl jq; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: required command '$cmd' not found" >&2
        exit 0  # exit 0 — tests gracefully handle missing images
    fi
done

# sha256sum on Linux, shasum on macOS
if command -v sha256sum &>/dev/null; then
    SHA256CMD="sha256sum"
elif command -v shasum &>/dev/null; then
    SHA256CMD="shasum -a 256"
else
    echo "ERROR: neither sha256sum nor shasum found" >&2
    exit 0
fi

# ── URL encoding helper ──────────────────────────────────────────────────────
# URL-encode a string using Python (most reliable) or fall back to curl
url_encode() {
    local input="$1"
    if command -v python3 &>/dev/null; then
        python3 -c "import urllib.parse; print(urllib.parse.quote('$input', safe='/'))"
    elif command -v python &>/dev/null; then
        python -c "import urllib.parse; print(urllib.parse.quote('$input', safe='/'))"
    else
        # Fallback: use curl to encode, but this only works for the filename portion
        # For full paths with special chars, Python is required
        echo "$input"
    fi
}

# ── Download manifest ────────────────────────────────────────────────────────
mkdir -p "$CORPUS_DIR"
MANIFEST="$CORPUS_DIR/manifest.json"

echo "Downloading manifest from $S3_BASE_URL/manifest.json ..."
if ! curl -fsSL --retry 3 --retry-delay 2 -o "$MANIFEST.tmp" "$S3_BASE_URL/manifest.json"; then
    echo "WARNING: Failed to download manifest. Skipping corpus download." >&2
    exit 0
fi
mv "$MANIFEST.tmp" "$MANIFEST"

IMAGE_COUNT=$(jq '.images | length' "$MANIFEST")
echo "Manifest contains $IMAGE_COUNT images."

# ── Build download list ──────────────────────────────────────────────────────
build_full_list() {
    jq -r '.images[].path' "$MANIFEST"
}

# ── Smart CI sampling with anchor images and code path coverage ──────────────
# Option 7: Always include anchor images (ci_tier == "quick")
# Option 3: Stratify by both format AND filesystem for maximum code path coverage
build_ci_list() {
    local temp_dir=$(mktemp -d)
    trap "rm -rf $temp_dir" EXIT

    # Step 1: Get anchor images (ci_tier == "quick") - always included
    jq -r '.images[] | select(.ci_tier == "quick") | .path' "$MANIFEST" | sort -u > "$temp_dir/anchors"
    local anchor_count=$(wc -l < "$temp_dir/anchors")

    # Step 2: Build format-stratified samples (exclude anchors)
    local formats
    formats=$(jq -r '[.images[].format] | unique | .[]' "$MANIFEST")

    for fmt in $formats; do
        # Get all images for this format, excluding anchors
        jq -r --arg f "$fmt" '.images[] | select(.format == $f and .ci_tier != "quick") | .path' "$MANIFEST" > "$temp_dir/fmt_$fmt"

        local count=$(wc -l < "$temp_dir/fmt_$fmt")
        if [[ $count -eq 0 ]]; then
            continue
        fi

        # Sample 10% from each format (higher than old 5% to ensure coverage)
        local select_n=$(( (count * 10 + 99) / 100 ))  # ceil(10%)
        [[ $select_n -lt 1 ]] && select_n=1
        [[ $select_n -gt 3 ]] && select_n=3  # Cap at 3 per format to limit total

        while IFS= read -r path; do
            local hash
            hash=$(printf '%s:%s' "$SEED" "$path" | $SHA256CMD | cut -c1-16)
            printf '%s %s\n' "$hash" "$path"
        done < "$temp_dir/fmt_$fmt" | sort | head -n "$select_n" | cut -d' ' -f2- >> "$temp_dir/format_samples"
    done

    # Step 3: Build filesystem-stratified samples (exclude anchors)
    local filesystems
    filesystems=$(jq -r '[.images[].filesystem // empty] | unique | .[]' "$MANIFEST")

    for fs in $filesystems; do
        # Get all images for this filesystem, excluding anchors
        jq -r --arg f "$fs" '.images[] | select(.filesystem == $f and .ci_tier != "quick") | .path' "$MANIFEST" > "$temp_dir/fs_$fs"

        local count=$(wc -l < "$temp_dir/fs_$fs")
        if [[ $count -eq 0 ]]; then
            continue
        fi

        # Sample at least 1 from each filesystem type (up to 2)
        local select_n=1
        [[ $count -gt 5 ]] && select_n=2

        while IFS= read -r path; do
            local hash
            hash=$(printf '%s:%s' "$SEED" "$path" | $SHA256CMD | cut -c1-16)
            printf '%s %s\n' "$hash" "$path"
        done < "$temp_dir/fs_$fs" | sort | head -n "$select_n" | cut -d' ' -f2- >> "$temp_dir/fs_samples"
    done

    # Step 4: Combine all, remove duplicates, output
    {
        cat "$temp_dir/anchors" 2>/dev/null || true
        cat "$temp_dir/format_samples" 2>/dev/null || true
        cat "$temp_dir/fs_samples" 2>/dev/null || true
    } | sort -u

    rm -rf "$temp_dir"
    trap - EXIT
}

DOWNLOAD_LIST=""
if [[ "$MODE" == "ci" ]]; then
    echo "CI mode: smart sampling with anchor images + format/filesystem stratification (seed=$SEED)"
    DOWNLOAD_LIST=$(build_ci_list)
else
    echo "Full mode: downloading all images"
    DOWNLOAD_LIST=$(build_full_list)
fi

TOTAL=$(echo "$DOWNLOAD_LIST" | grep -c . || true)
echo "Selected $TOTAL images for download."

# ── Compare manifest to local files, skip what already exists ────────────────
MISSING_LIST=""
SKIPPED=0

while IFS= read -r image_path; do
    [[ -z "$image_path" ]] && continue

    local_path="$CORPUS_DIR/$image_path"
    if [[ -f "$local_path" ]]; then
        SKIPPED=$((SKIPPED + 1))
    else
        MISSING_LIST="${MISSING_LIST:+$MISSING_LIST
}$image_path"
    fi
done <<< "$DOWNLOAD_LIST"

MISSING_COUNT=$(echo "$MISSING_LIST" | grep -c . || true)

if [[ $SKIPPED -gt 0 ]]; then
    echo "Skipping $SKIPPED images already on disk."
fi

if [[ $MISSING_COUNT -eq 0 ]]; then
    echo "All $TOTAL images already present. Nothing to download."
    exit 0
fi

echo "Need to download $MISSING_COUNT images."

if $DRY_RUN; then
    echo ""
    echo "=== DRY RUN — would download: ==="
    echo "$MISSING_LIST"
    echo ""
    echo "Total: $MISSING_COUNT images (mode=$MODE, seed=$SEED)"
    exit 0
fi

# ── Download missing images ──────────────────────────────────────────────────
DOWNLOADED=0
FAILED=0
TOTAL_BYTES=0

while IFS= read -r image_path; do
    [[ -z "$image_path" ]] && continue

    local_path="$CORPUS_DIR/$image_path"
    encoded_path=$(url_encode "$image_path")
    url="$S3_BASE_URL/$encoded_path"
    mkdir -p "$(dirname "$local_path")"
    echo "  Downloading $image_path ..."

    if ! curl -fsSL --retry 3 --retry-delay 2 -o "$local_path.tmp" "$url"; then
        echo "  WARNING: Failed to download $image_path" >&2
        rm -f "$local_path.tmp"
        FAILED=$((FAILED + 1))
        continue
    fi

    mv "$local_path.tmp" "$local_path"
    file_size=$(stat -c%s "$local_path" 2>/dev/null || stat -f%z "$local_path" 2>/dev/null || echo 0)
    TOTAL_BYTES=$((TOTAL_BYTES + file_size))
    DOWNLOADED=$((DOWNLOADED + 1))

done <<< "$MISSING_LIST"

# ── Summary ──────────────────────────────────────────────────────────────────
if [[ $TOTAL_BYTES -gt $((1024*1024*1024)) ]]; then
    SIZE_STR="$(( TOTAL_BYTES / (1024*1024*1024) )) GB"
elif [[ $TOTAL_BYTES -gt $((1024*1024)) ]]; then
    SIZE_STR="$(( TOTAL_BYTES / (1024*1024) )) MB"
elif [[ $TOTAL_BYTES -gt 0 ]]; then
    SIZE_STR="$(( TOTAL_BYTES / 1024 )) KB"
else
    SIZE_STR="0 bytes"
fi

echo ""
echo "=== Corpus download summary ==="
echo "  Mode:       $MODE"
echo "  Seed:       $SEED"
echo "  Downloaded: $DOWNLOADED"
echo "  Skipped:    $SKIPPED (already on disk)"
echo "  Failed:     $FAILED"
echo "  Total size: $SIZE_STR (downloaded this run)"
echo ""

# Always exit 0 — tests gracefully handle missing images
exit 0
