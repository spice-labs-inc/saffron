#!/bin/bash
set -euo pipefail

# Regenerate all ground truth JSON files with per-filesystem classification
# This script must be run on a system with Docker available
#
# Usage:
#   ./regenerate-all.sh              # Regenerate all images
#   ./regenerate-all.sh --verify     # Verify existing JSON files have new fields
#   ./regenerate-all.sh --sample N   # Regenerate N random images (for testing)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CORPUS_DIR="$PROJECT_DIR/test-corpus"
OUTPUT_DIR="$PROJECT_DIR/src/test/resources/corpus-verification"

IMAGE_NAME="saffron-corpus-scanner"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=== Saffron Ground Truth Regeneration ==="
echo "Corpus:  $CORPUS_DIR"
echo "Output:  $OUTPUT_DIR"
echo ""

# Check Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}ERROR: Docker is not available${NC}"
    echo "This script requires Docker to run the corpus scanner."
    exit 1
fi

# Verify corpus directory exists
if [[ ! -d "$CORPUS_DIR" ]]; then
    echo -e "${RED}ERROR: Corpus directory not found: $CORPUS_DIR${NC}"
    exit 1
fi

# Count available images
IMAGE_COUNT=$(find "$CORPUS_DIR" -type f \( -name "*.qcow2" -o -name "*.vdi" -o -name "*.vhd" -o -name "*.vhdx" -o -name "*.vmdk" -o -name "*.raw" -o -name "*.img" -o -name "*.dmg" \) | wc -l)
echo "Found $IMAGE_COUNT disk images in corpus"
echo ""

# Parse arguments
MODE="regenerate"
SAMPLE_SIZE=0

if [[ $# -gt 0 ]]; then
    case "$1" in
        --verify)
            MODE="verify"
            ;;
        --sample)
            MODE="sample"
            SAMPLE_SIZE="${2:-5}"
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --verify       Verify existing JSON files have required fields"
            echo "  --sample N     Regenerate N random images (for testing)"
            echo "  --help         Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
fi

# Verify mode - check existing JSON files
if [[ "$MODE" == "verify" ]]; then
    echo "Verifying existing JSON files..."
    echo ""

    MISSING_FIELDS=0
    TOTAL_FILES=0

    for json_file in "$OUTPUT_DIR"/*.json; do
        if [[ ! -f "$json_file" ]]; then
            continue
        fi

        TOTAL_FILES=$((TOTAL_FILES + 1))
        basename=$(basename "$json_file")

        # Check for new fields
        if ! python3 -c "
import json
import sys

with open('$json_file') as f:
    data = json.load(f)

if 'filesystems' not in data:
    sys.exit(0)  # Error files don't have filesystems

missing = []
for i, fs in enumerate(data.get('filesystems', [])):
    if 'purpose' not in fs:
        missing.append(f'filesystems[{i}].purpose')
    if 'isMountable' not in fs:
        missing.append(f'filesystems[{i}].isMountable')

if missing:
    print('MISSING: ' + ', '.join(missing))
    sys.exit(1)
sys.exit(0)
" 2>/dev/null; then
            echo -e "${RED}✗ $basename - missing new fields${NC}"
            MISSING_FIELDS=$((MISSING_FIELDS + 1))
        else
            echo -e "${GREEN}✓ $basename${NC}"
        fi
    done

    echo ""
    echo "Verification complete:"
    echo "  Total files: $TOTAL_FILES"
    echo "  Missing fields: $MISSING_FIELDS"

    if [[ $MISSING_FIELDS -gt 0 ]]; then
        exit 1
    fi
    exit 0
fi

# Sample mode - select random images
if [[ "$MODE" == "sample" ]]; then
    echo "Sample mode: selecting $SAMPLE_SIZE random images"

    # Get list of all images and shuffle
    mapfile -t ALL_IMAGES < <(find "$CORPUS_DIR" -type f \( -name "*.qcow2" -o -name "*.vdi" -o -name "*.vhd" -o -name "*.vhdx" -o -name "*.vmdk" -o -name "*.raw" -o -name "*.img" -o -name "*.dmg" \) | sort -R | head -n "$SAMPLE_SIZE")

    echo "Selected images:"
    for img in "${ALL_IMAGES[@]}"; do
        echo "  - $(basename "$img")"
    done
    echo ""
fi

# Build Docker image
echo "Building Docker image..."
docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"
echo -e "${GREEN}✓ Docker image built${NC}"
echo ""

# Prepare output directory
mkdir -p "$OUTPUT_DIR"

# Function to scan a single image
scan_image() {
    local image_path="$1"
    local basename
    basename=$(basename "$image_path")
    local rel_path="${image_path#$CORPUS_DIR/}"

    echo "Scanning: $basename"

    DOCKER_ARGS=(
        --rm
        -v "$CORPUS_DIR:/corpus:ro"
        -v "$OUTPUT_DIR:/output"
    )

    # Add KVM device if available (accelerates libguestfs)
    if [[ -e /dev/kvm ]]; then
        DOCKER_ARGS+=(--device /dev/kvm:/dev/kvm)
    fi

    if docker run "${DOCKER_ARGS[@]}" "$IMAGE_NAME" /corpus /output --image "$rel_path" 2>&1; then
        echo -e "${GREEN}✓ Completed: $basename${NC}"
        return 0
    else
        echo -e "${RED}✗ Failed: $basename${NC}"
        return 1
    fi
}

# Track results
SUCCESS=0
FAILED=0

# Scan images
if [[ "$MODE" == "sample" ]]; then
    # Sample mode - scan selected images
    for img in "${ALL_IMAGES[@]}"; do
        if scan_image "$img"; then
            SUCCESS=$((SUCCESS + 1))
        else
            FAILED=$((FAILED + 1))
        fi
        echo ""
    done
else
    # Full regeneration mode - scan all images
    # This uses the scanner's built-in batch mode
    echo "Starting full corpus scan (this will take a while)..."
    echo ""

    DOCKER_ARGS=(
        --rm
        -v "$CORPUS_DIR:/corpus:ro"
        -v "$OUTPUT_DIR:/output"
    )

    # Add KVM device if available
    if [[ -e /dev/kvm ]]; then
        DOCKER_ARGS+=(--device /dev/kvm:/dev/kvm)
    fi

    if docker run "${DOCKER_ARGS[@]}" "$IMAGE_NAME" /corpus /output 2>&1; then
        echo -e "${GREEN}✓ Full scan completed${NC}"
        SUCCESS=$IMAGE_COUNT
    else
        echo -e "${RED}✗ Full scan completed with errors${NC}"
        # Count actual results
        SUCCESS=$(ls -1 "$OUTPUT_DIR"/*.json 2>/dev/null | wc -l)
        FAILED=$((IMAGE_COUNT - SUCCESS))
    fi
fi

echo ""
echo "=== Summary ==="
echo "Successfully scanned: $SUCCESS images"
if [[ $FAILED -gt 0 ]]; then
    echo "Failed: $FAILED images"
fi
echo ""
echo "JSON files in output directory:"
ls -1 "$OUTPUT_DIR"/*.json 2>/dev/null | wc -l
echo ""

# Final verification
echo "Verifying new JSON structure..."
python3 << 'EOF'
import json
import os
import sys

output_dir = "/data/src/test/resources/corpus-verification"
corpus_dir = "/data/test-corpus"

json_files = [f for f in os.listdir(output_dir) if f.endswith('.json')]
print(f"Checking {len(json_files)} JSON files...")

with_purpose = 0
with_mountable = 0
with_expected_paths = 0
swap_filesystems = 0

for json_file in json_files:
    try:
        with open(os.path.join(output_dir, json_file)) as f:
            data = json.load(f)

        if 'filesystems' not in data:
            continue

        for fs in data.get('filesystems', []):
            if 'purpose' in fs:
                with_purpose += 1
            if 'isMountable' in fs:
                with_mountable += 1
            if 'expectedPaths' in fs:
                with_expected_paths += 1
            if fs.get('purpose') == 'swap':
                swap_filesystems += 1
    except Exception as e:
        print(f"Error reading {json_file}: {e}")

print(f"\nResults:")
print(f"  Filesystems with 'purpose' field: {with_purpose}")
print(f"  Filesystems with 'isMountable' field: {with_mountable}")
print(f"  Filesystems with 'expectedPaths' field: {with_expected_paths}")
print(f"  Swap filesystems detected: {swap_filesystems}")
EOF

echo ""
echo -e "${GREEN}Ground truth regeneration complete!${NC}"
