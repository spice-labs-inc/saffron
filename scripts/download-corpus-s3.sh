#!/usr/bin/env bash
# download-corpus-s3.sh — Download test corpus images from S3
#
# Usage:
#   scripts/download-corpus-s3.sh [OPTIONS]
#     --corpus-dir DIR      Target directory (default: <project-root>/test-corpus)
#     --mode full|ci|small  Download mode (default: full; auto-detects CI env)
#     --max-size-gb N       Maximum total size to download in GB (default: 1 for ci mode)
#     --seed N              PRNG seed for CI reproducibility (default: $SAFFRON_CORPUS_SEED or $GITHUB_RUN_ID or timestamp)
#     --dry-run             Print what would be downloaded, don't download
#     --help                Show usage
#
# Mode descriptions:
#   full    - Download all images in the manifest
#   ci      - Download images for CI with filesystem coverage (respects --max-size-gb)
#   small   - Download minimal set (< 1GB) with full filesystem coverage, deterministic selection

set -euo pipefail

S3_BASE_URL="https://public-test-data.spice-labs.dev/saffron_vm_images"

# ── Resolve project root from script location ────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Defaults ──────────────────────────────────────────────────────────────────
CORPUS_DIR="$PROJECT_ROOT/test-corpus"
MODE=""
MAX_SIZE_GB=""
SEED=""
DRY_RUN=false

# ── Parse arguments ───────────────────────────────────────────────────────────
usage() {
    sed -n '3,18p' "$0" | sed 's/^# \?//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --corpus-dir)  CORPUS_DIR="$2"; shift 2 ;;
        --mode)        MODE="$2"; shift 2 ;;
        --max-size-gb) MAX_SIZE_GB="$2"; shift 2 ;;
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

# ── Resolve max size ──────────────────────────────────────────────────────────
if [[ -z "$MAX_SIZE_GB" ]]; then
    if [[ "$MODE" == "small" ]]; then
        MAX_SIZE_GB=1
    elif [[ "$MODE" == "ci" ]]; then
        MAX_SIZE_GB=2  # Default 2GB for CI mode
    else
        MAX_SIZE_GB=0  # No limit for full mode
    fi
fi
MAX_SIZE_BYTES=$((MAX_SIZE_GB * 1024 * 1024 * 1024))

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
url_encode() {
    local input="$1"
    if command -v python3 &>/dev/null; then
        python3 -c "import urllib.parse; print(urllib.parse.quote('$input', safe='/'))"
    elif command -v python &>/dev/null; then
        python -c "import urllib.parse; print(urllib.parse.quote('$input', safe='/'))"
    else
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

# ── Helper: Get image size from manifest or local file ───────────────────────
get_image_size() {
    local path="$1"
    local manifest_size=$(jq -r --arg p "$path" '.images[] | select(.path == $p) | .actual_size_bytes // 0' "$MANIFEST")

    # If manifest doesn't have size, check local file
    if [[ "$manifest_size" == "0" || -z "$manifest_size" ]]; then
        local local_path="$CORPUS_DIR/$path"
        if [[ -f "$local_path" ]]; then
            stat -c%s "$local_path" 2>/dev/null || stat -f%z "$local_path" 2>/dev/null || echo 0
        else
            echo 0
        fi
    else
        echo "$manifest_size"
    fi
}

# ── Helper: Get image filesystem from manifest ───────────────────────────────
# Uses path heuristics as fallback when manifest doesn't have filesystem info
get_image_filesystem() {
    local path="$1"
    local fs=$(jq -r --arg p "$path" '.images[] | select(.path == $p) | .filesystem // empty' "$MANIFEST")

    # If filesystem is not set, try to infer from path
    if [[ -z "$fs" ]]; then
        if [[ "$path" == *"Windows XP"* ]] || [[ "$path" == *"windows-xp"* ]]; then
            fs="ntfs"
        elif [[ "$path" == *"Windows NT"* ]] || [[ "$path" == *"windows-nt"* ]]; then
            fs="ntfs"
        elif [[ "$path" == *"Windows 2000"* ]] || [[ "$path" == *"windows-2000"* ]]; then
            fs="ntfs"
        elif [[ "$path" == *"Windows"* && "$path" == *".vhd"* ]]; then
            # Other Windows images are likely FAT
            fs="fat16"
        fi
    fi

    echo "${fs:-unknown}"
}

# ── Helper: Get image format from manifest ───────────────────────────────────
get_image_format() {
    local path="$1"
    jq -r --arg p "$path" '.images[] | select(.path == $p) | .format // "unknown"' "$MANIFEST"
}

# ── Build download list ──────────────────────────────────────────────────────
build_full_list() {
    jq -r '.images[].path' "$MANIFEST"
}

# ── Small mode: Deterministic minimal set with full coverage ──────────────────
# Selects smallest images first to stay under size limit while ensuring
# all required filesystems and formats are covered
build_small_list() {
    local temp_dir=$(mktemp -d)
    trap "rm -rf $temp_dir" EXIT

    local max_bytes=$((MAX_SIZE_GB * 1024 * 1024 * 1024))
    local current_bytes=0

    # Define required coverage
    local required_filesystems=("ext4" "xfs" "btrfs" "ntfs" "fat32" "fat16")
    local required_formats=("qcow2" "vmdk" "vhd" "vdi")

    # Build list of all images with their sizes, sorted by size (smallest first)
    # For images without size in manifest, check local file
    jq -r '.images[] | [.actual_size_bytes // 0, .path] | @tsv' "$MANIFEST" | \
        awk -F'\t' -v corpus_dir="$CORPUS_DIR" '
        {
            size = $1
            path = $2
            if (size == 0 || size == "null" || size == "") {
                # Try to get size from local file
                local_path = corpus_dir "/" path
                cmd = "stat -c%s '" local_path "' 2>/dev/null || stat -f%z '" local_path "' 2>/dev/null || echo 999999999999"
                cmd | getline size
                close(cmd)
            }
            print size, path
        }
        ' | sort -n | cut -d' ' -f2- > "$temp_dir/all_images_sorted"

    # Track what we've covered
    declare -A covered_filesystems
    declare -A covered_formats

    # Step 1: Select smallest image for each required filesystem
    echo "  [small mode] Selecting smallest image per required filesystem..." >&2
    for fs in "${required_filesystems[@]}"; do
        while IFS= read -r path; do
            [[ -z "$path" ]] && continue
            local img_fs=$(get_image_filesystem "$path")
            local img_size=$(get_image_size "$path")

            if [[ "$img_fs" == "$fs" ]]; then
                # Check if already selected
                if ! grep -q "^${path}$" "$temp_dir/selected" 2>/dev/null; then
                    # Check size limit
                    local new_total=$((current_bytes + img_size))
                    if [[ $new_total -le $max_bytes ]]; then
                        echo "$path" >> "$temp_dir/selected"
                        current_bytes=$new_total
                        covered_filesystems[$fs]=1
                        echo "    + $fs: $path ($(numfmt --to=iec $img_size))" >&2
                        break
                    else
                        echo "    ! $fs: smallest image too large ($(numfmt --to=iec $img_size))" >&2
                        break
                    fi
                fi
            fi
        done < "$temp_dir/all_images_sorted"
    done

    # Step 2: Select smallest image for each required format (if not already covered)
    echo "  [small mode] Selecting smallest image per required format..." >&2
    for fmt in "${required_formats[@]}"; do
        while IFS= read -r path; do
            [[ -z "$path" ]] && continue
            local img_fmt=$(get_image_format "$path")
            local img_size=$(get_image_size "$path")

            if [[ "$img_fmt" == "$fmt" ]]; then
                if ! grep -q "^${path}$" "$temp_dir/selected" 2>/dev/null; then
                    local new_total=$((current_bytes + img_size))
                    if [[ $new_total -le $max_bytes ]]; then
                        echo "$path" >> "$temp_dir/selected"
                        current_bytes=$new_total
                        covered_formats[$fmt]=1
                        echo "    + $fmt: $path ($(numfmt --to=iec $img_size))" >&2
                        break
                    else
                        echo "    ! $fmt: smallest image too large ($(numfmt --to=iec $img_size))" >&2
                        break
                    fi
                fi
            fi
        done < "$temp_dir/all_images_sorted"
    done

    # Step 3: Fill remaining space with quick tier images (smallest first)
    echo "  [small mode] Filling remaining space with quick tier images..." >&2
    while IFS= read -r path; do
        [[ -z "$path" ]] && continue

        # Skip if already selected
        if grep -q "^${path}$" "$temp_dir/selected" 2>/dev/null; then
            continue
        fi

        local img_size=$(get_image_size "$path")
        local img_tier=$(jq -r --arg p "$path" '.images[] | select(.path == $p) | .ci_tier // "full"' "$MANIFEST")

        # Prefer quick tier images
        if [[ "$img_tier" == "quick" ]]; then
            local new_total=$((current_bytes + img_size))
            if [[ $new_total -le $max_bytes ]]; then
                echo "$path" >> "$temp_dir/selected"
                current_bytes=$new_total
                echo "    + quick tier: $path ($(numfmt --to=iec $img_size))" >&2
            fi
        fi
    done < "$temp_dir/all_images_sorted"

    # Report coverage
    echo "  [small mode] Filesystem coverage:" >&2
    for fs in "${required_filesystems[@]}"; do
        if [[ -n "${covered_filesystems[$fs]:-}" ]]; then
            echo "    ✓ $fs" >&2
        else
            echo "    ✗ $fs (not covered - no suitable image found)" >&2
        fi
    done

    echo "  [small mode] Total selected: $(wc -l < "$temp_dir/selected" 2>/dev/null || echo 0) images ($(numfmt --to=iec $current_bytes))" >&2

    # Output sorted list
    sort "$temp_dir/selected" 2>/dev/null || true

    rm -rf "$temp_dir"
    trap - EXIT
}

# ── CI mode: Smart sampling with anchor images and code path coverage ────────
build_ci_list() {
    local temp_dir=$(mktemp -d)
    trap "rm -rf $temp_dir" EXIT

    local max_bytes=$((MAX_SIZE_GB * 1024 * 1024 * 1024))
    local current_bytes=0

    # Step 1: Get anchor images (ci_tier == "quick") - always include smallest ones first
    echo "  [ci mode] Selecting anchor images (quick tier)..." >&2
    jq -r '.images[] | select(.ci_tier == "quick") | "\(.actual_size_bytes // 0) \(.path)"' "$MANIFEST" | \
        sort -n | cut -d' ' -f2- > "$temp_dir/anchors"

    while IFS= read -r path; do
        [[ -z "$path" ]] && continue
        local img_size=$(get_image_size "$path")
        local new_total=$((current_bytes + img_size))

        if [[ $new_total -le $max_bytes ]]; then
            echo "$path" >> "$temp_dir/selected"
            current_bytes=$new_total
        else
            echo "    ! Skipping anchor $path (would exceed size limit)" >&2
        fi
    done < "$temp_dir/anchors"

    # Step 2: Build format-stratified samples (deterministic - smallest first)
    echo "  [ci mode] Selecting format samples..." >&2
    local formats
    formats=$(jq -r '[.images[].format] | unique | .[]' "$MANIFEST")

    for fmt in $formats; do
        # Get images for this format, sorted by size
        jq -r --arg f "$fmt" '.images[] | select(.format == $f and (.ci_tier != "quick" or .ci_tier == null)) | "\(.actual_size_bytes // 0) \(.path)"' "$MANIFEST" | \
            sort -n | cut -d' ' -f2- > "$temp_dir/fmt_$fmt"

        local count=$(wc -l < "$temp_dir/fmt_$fmt" 2>/dev/null || echo 0)
        if [[ $count -eq 0 ]]; then
            continue
        fi

        # Select up to 2 smallest images per format
        local select_n=2
        [[ $count -lt $select_n ]] && select_n=$count

        local added=0
        while IFS= read -r path && [[ $added -lt $select_n ]]; do
            [[ -z "$path" ]] && continue

            # Skip if already selected
            if grep -q "^${path}$" "$temp_dir/selected" 2>/dev/null; then
                continue
            fi

            local img_size=$(get_image_size "$path")
            local new_total=$((current_bytes + img_size))

            if [[ $new_total -le $max_bytes ]]; then
                echo "$path" >> "$temp_dir/selected"
                current_bytes=$new_total
                ((added++))
            fi
        done < "$temp_dir/fmt_$fmt"
    done

    # Step 3: Build filesystem-stratified samples (deterministic - smallest first)
    echo "  [ci mode] Selecting filesystem samples..." >&2
    local filesystems
    filesystems=$(jq -r '[.images[].filesystem // empty] | unique | .[]' "$MANIFEST")

    for fs in $filesystems; do
        # Get images for this filesystem, sorted by size
        jq -r --arg f "$fs" '.images[] | select(.filesystem == $f and (.ci_tier != "quick" or .ci_tier == null)) | "\(.actual_size_bytes // 0) \(.path)"' "$MANIFEST" | \
            sort -n | cut -d' ' -f2- > "$temp_dir/fs_$fs"

        local count=$(wc -l < "$temp_dir/fs_$fs" 2>/dev/null || echo 0)
        if [[ $count -eq 0 ]]; then
            continue
        fi

        # Select up to 2 smallest images per filesystem
        local select_n=2
        [[ $count -lt $select_n ]] && select_n=$count

        local added=0
        while IFS= read -r path && [[ $added -lt $select_n ]]; do
            [[ -z "$path" ]] && continue

            if grep -q "^${path}$" "$temp_dir/selected" 2>/dev/null; then
                continue
            fi

            local img_size=$(get_image_size "$path")
            local new_total=$((current_bytes + img_size))

            if [[ $new_total -le $max_bytes ]]; then
                echo "$path" >> "$temp_dir/selected"
                current_bytes=$new_total
                ((added++))
            fi
        done < "$temp_dir/fs_$fs"
    done

    # Step 4: Guarantee filesystem coverage - ensure critical filesystems are represented
    echo "  [ci mode] Ensuring filesystem coverage..." >&2
    local required_filesystems=("ext4" "xfs" "btrfs" "ntfs" "fat32" "fat16")
    for fs in "${required_filesystems[@]}"; do
        # Check if this filesystem is already in our selection
        local has_fs=false
        while IFS= read -r path; do
            [[ -z "$path" ]] && continue
            local img_fs=$(get_image_filesystem "$path")
            if [[ "$img_fs" == "$fs" ]]; then
                has_fs=true
                break
            fi
        done < <(cat "$temp_dir/selected" 2>/dev/null)

        if [[ "$has_fs" == "false" ]]; then
            # Add the smallest available image with this filesystem
            jq -r --arg f "$fs" '.images[] | select(.filesystem == $f) | "\(.actual_size_bytes // 0) \(.path)"' "$MANIFEST" | \
                sort -n | cut -d' ' -f2- > "$temp_dir/fs_needed_$fs"

            while IFS= read -r path; do
                [[ -z "$path" ]] && continue

                local img_size=$(get_image_size "$path")
                local new_total=$((current_bytes + img_size))

                if [[ $new_total -le $max_bytes ]]; then
                    echo "$path" >> "$temp_dir/selected"
                    current_bytes=$new_total
                    echo "    + Added $fs coverage: $path ($(numfmt --to=iec $img_size))" >&2
                    break
                else
                    echo "    ! Cannot add $fs coverage: smallest image too large ($(numfmt --to=iec $img_size))" >&2
                    break
                fi
            done < "$temp_dir/fs_needed_$fs"
        fi
    done

    echo "  [ci mode] Total selected: $(wc -l < "$temp_dir/selected" 2>/dev/null || echo 0) images ($(numfmt --to=iec $current_bytes))" >&2

    # Output sorted list
    sort "$temp_dir/selected" 2>/dev/null || true

    rm -rf "$temp_dir"
    trap - EXIT
}

# ── Build download list based on mode ────────────────────────────────────────
DOWNLOAD_LIST=""
if [[ "$MODE" == "small" ]]; then
    echo "Small mode: deterministic selection with filesystem coverage, max ${MAX_SIZE_GB}GB"
    DOWNLOAD_LIST=$(build_small_list)
elif [[ "$MODE" == "ci" ]]; then
    echo "CI mode: deterministic sampling with filesystem coverage, max ${MAX_SIZE_GB}GB"
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
SELECTED_BYTES=0

while IFS= read -r image_path; do
    [[ -z "$image_path" ]] && continue

    local_path="$CORPUS_DIR/$image_path"
    if [[ -f "$local_path" ]]; then
        SKIPPED=$((SKIPPED + 1))
    else
        MISSING_LIST="${MISSING_LIST:+$MISSING_LIST
}$image_path"
        size=$(get_image_size "$image_path")
        SELECTED_BYTES=$((SELECTED_BYTES + size))
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

echo "Need to download $MISSING_COUNT images ($(numfmt --to=iec $SELECTED_BYTES))."

if $DRY_RUN; then
    echo ""
    echo "=== DRY RUN — would download: ==="
    echo "$MISSING_LIST"
    echo ""
    echo "Total: $MISSING_COUNT images, $(numfmt --to=iec $SELECTED_BYTES) (mode=$MODE, max=${MAX_SIZE_GB}GB)"
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
echo "  Max size:   ${MAX_SIZE_GB}GB"
echo "  Downloaded: $DOWNLOADED"
echo "  Skipped:    $SKIPPED (already on disk)"
echo "  Failed:     $FAILED"
echo "  Total size: $SIZE_STR (downloaded this run)"
echo ""

# Always exit 0 — tests gracefully handle missing images
exit 0
