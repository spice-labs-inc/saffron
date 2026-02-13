#!/bin/bash
# Script to gather file information from VM images for test generation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/test-corpus-data"
mkdir -p "$OUTPUT_DIR"

gather_image_data() {
    local image_path="$1"
    local image_name=$(basename "$image_path" | sed 's/[^a-zA-Z0-9]/_/g')
    local mount_point="/tmp/vm_mount_$$"
    local output_file="$OUTPUT_DIR/${image_name}.json"

    echo "Processing: $image_path"

    mkdir -p "$mount_point"

    # Try to mount with guestmount
    if ! guestmount -a "$image_path" -i --ro "$mount_point" 2>/dev/null; then
        # Try mounting first partition
        if ! guestmount -a "$image_path" -m /dev/sda1 --ro "$mount_point" 2>/dev/null; then
            echo "  Failed to mount $image_path"
            rmdir "$mount_point"
            return 1
        fi
    fi

    # Count total files
    local total_files=$(find "$mount_point" -type f 2>/dev/null | wc -l)
    echo "  Total files: $total_files"

    # Select up to 20 random files (avoiding very large files)
    local sample_files=$(find "$mount_point" -type f -size -10M 2>/dev/null | shuf -n 20)

    # Start JSON output
    echo "{" > "$output_file"
    echo "  \"imagePath\": \"$image_path\"," >> "$output_file"
    echo "  \"totalFiles\": $total_files," >> "$output_file"
    echo "  \"sampleFiles\": [" >> "$output_file"

    local first=true
    while IFS= read -r file; do
        if [ -n "$file" ] && [ -f "$file" ]; then
            local rel_path="${file#$mount_point}"
            local sha256=$(sha256sum "$file" 2>/dev/null | cut -d' ' -f1)
            local size=$(stat -c%s "$file" 2>/dev/null)

            if [ -n "$sha256" ]; then
                if [ "$first" = true ]; then
                    first=false
                else
                    echo "," >> "$output_file"
                fi
                printf '    {"path": "%s", "sha256": "%s", "size": %s}' "$rel_path" "$sha256" "$size" >> "$output_file"
            fi
        fi
    done <<< "$sample_files"

    echo "" >> "$output_file"
    echo "  ]" >> "$output_file"
    echo "}" >> "$output_file"

    # Unmount
    guestunmount "$mount_point" 2>/dev/null || fusermount -u "$mount_point" 2>/dev/null || true
    rmdir "$mount_point" 2>/dev/null || true

    echo "  Output: $output_file"
}

# Process specific images passed as arguments, or default set
if [ $# -gt 0 ]; then
    for img in "$@"; do
        gather_image_data "$img"
    done
else
    echo "Usage: $0 <image1> [image2] ..."
fi
