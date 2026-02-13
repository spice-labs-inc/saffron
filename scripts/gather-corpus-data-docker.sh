#!/bin/bash
# Script to gather file information from VM images for test generation
# Uses libguestfs inside a Docker container

set -e

OUTPUT_DIR="/output"
CORPUS_DIR="/corpus"

process_image() {
    local image_path="$1"
    local image_name=$(basename "$image_path" | sed 's/[^a-zA-Z0-9]/_/g')
    local mount_point="/mnt/vm_$$_${RANDOM}"
    local output_file="$OUTPUT_DIR/${image_name}.json"

    echo "Processing: $image_path" >&2

    mkdir -p "$mount_point"

    # Try to mount with guestmount (auto-detect filesystem)
    local mounted=false
    local fs_type="unknown"

    # First try with -i (inspect and mount all)
    if guestmount -a "$image_path" -i --ro "$mount_point" 2>/dev/null; then
        mounted=true
        fs_type=$(guestfish --ro -a "$image_path" -i <<< "list-filesystems" 2>/dev/null | head -1 | cut -d: -f2 | tr -d ' ' || echo "unknown")
    fi

    # If that fails, try mounting specific partitions
    if [ "$mounted" = false ]; then
        # Get list of filesystems
        local filesystems=$(guestfish --ro -a "$image_path" run : list-filesystems 2>/dev/null || echo "")

        if [ -n "$filesystems" ]; then
            # Try each filesystem
            while IFS=: read -r device fstype; do
                device=$(echo "$device" | tr -d ' ')
                fstype=$(echo "$fstype" | tr -d ' ')

                # Skip swap and small partitions
                if [ "$fstype" = "swap" ] || [ "$fstype" = "unknown" ]; then
                    continue
                fi

                echo "  Trying $device ($fstype)" >&2

                if guestmount -a "$image_path" -m "$device" --ro "$mount_point" 2>/dev/null; then
                    mounted=true
                    fs_type="$fstype"
                    break
                fi
            done <<< "$filesystems"
        fi
    fi

    if [ "$mounted" = false ]; then
        echo "  Failed to mount $image_path" >&2
        rmdir "$mount_point" 2>/dev/null || true
        return 1
    fi

    echo "  Mounted successfully (fs: $fs_type)" >&2

    # Count total files
    local total_files=$(find "$mount_point" -type f 2>/dev/null | wc -l)
    echo "  Total files: $total_files" >&2

    # Count total directories
    local total_dirs=$(find "$mount_point" -type d 2>/dev/null | wc -l)
    echo "  Total directories: $total_dirs" >&2

    # Select up to 20 random files (avoiding very large files >50MB)
    local sample_files=$(find "$mount_point" -type f -size -50M 2>/dev/null | shuf -n 20)

    # Start JSON output
    cat > "$output_file" << HEADER
{
  "imagePath": "$image_path",
  "imageBasename": "$(basename "$image_path")",
  "filesystemType": "$fs_type",
  "totalFiles": $total_files,
  "totalDirectories": $total_dirs,
  "sampleFiles": [
HEADER

    local first=true
    local count=0
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
                # Escape the path for JSON
                local escaped_path=$(echo "$rel_path" | sed 's/\\/\\\\/g; s/"/\\"/g')
                printf '    {"path": "%s", "sha256": "%s", "size": %s}' "$escaped_path" "$sha256" "$size" >> "$output_file"
                count=$((count + 1))
            fi
        fi
    done <<< "$sample_files"

    echo "" >> "$output_file"
    echo "  ]" >> "$output_file"
    echo "}" >> "$output_file"

    echo "  Sampled $count files" >&2

    # Unmount
    guestunmount "$mount_point" 2>/dev/null || fusermount -u "$mount_point" 2>/dev/null || umount "$mount_point" 2>/dev/null || true
    sleep 1
    rmdir "$mount_point" 2>/dev/null || true

    echo "  Output: $output_file" >&2
    return 0
}

# Main
mkdir -p "$OUTPUT_DIR"

# Find all VM images
images=$(find "$CORPUS_DIR" -type f \( -name "*.qcow2" -o -name "*.vmdk" -o -name "*.vdi" -o -name "*.vhd" -o -name "*.vhdx" \) 2>/dev/null | sort)

if [ -z "$images" ]; then
    echo "No VM images found in $CORPUS_DIR" >&2
    exit 1
fi

total_images=$(echo "$images" | wc -l)
echo "Found $total_images VM images to process" >&2
echo "" >&2

# Process each image
success=0
failed=0
current=0

while IFS= read -r image; do
    current=$((current + 1))
    echo "[$current/$total_images] Processing..." >&2

    if process_image "$image"; then
        success=$((success + 1))
    else
        failed=$((failed + 1))
    fi

    echo "" >&2
done <<< "$images"

echo "========================================" >&2
echo "Processing complete: $success succeeded, $failed failed" >&2
echo "========================================" >&2

# Output summary JSON
cat > "$OUTPUT_DIR/_summary.json" << EOF
{
  "totalImages": $((success + failed)),
  "successfulImages": $success,
  "failedImages": $failed
}
EOF
