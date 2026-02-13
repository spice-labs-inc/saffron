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

build_ci_list() {
    local formats
    formats=$(jq -r '[.images[].format] | unique | .[]' "$MANIFEST")

    for fmt in $formats; do
        local count select_n
        count=$(jq --arg f "$fmt" '[.images[] | select(.format == $f)] | length' "$MANIFEST")
        select_n=$(( (count * 5 + 99) / 100 ))  # ceil(5%)
        [[ $select_n -lt 1 ]] && select_n=1

        jq -r --arg f "$fmt" '.images[] | select(.format == $f) | .path' "$MANIFEST" |
        while IFS= read -r path; do
            local hash
            hash=$(printf '%s:%s' "$SEED" "$path" | $SHA256CMD | cut -c1-16)
            printf '%s %s\n' "$hash" "$path"
        done | sort | head -n "$select_n" | cut -d' ' -f2-
    done
}

DOWNLOAD_LIST=""
if [[ "$MODE" == "ci" ]]; then
    echo "CI mode: selecting stratified 5% sample (seed=$SEED)"
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
    url="$S3_BASE_URL/$image_path"
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
