#!/bin/bash
set -euo pipefail

# Corpus scanner runner
# Builds the Docker image and runs the scanner against the test corpus.
#
# Usage:
#   ./run.sh                    # Scan all images
#   ./run.sh <image-filename>   # Scan a specific image
#
# The corpus directory is expected at ../../test-corpus relative to this script.
# Output JSON files go to ../../src/test/resources/corpus-verification/

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CORPUS_DIR="$PROJECT_DIR/test-corpus"
OUTPUT_DIR="$PROJECT_DIR/src/test/resources/corpus-verification"

IMAGE_NAME="saffron-corpus-scanner"

echo "=== Saffron Corpus Scanner ==="
echo "Corpus:  $CORPUS_DIR"
echo "Output:  $OUTPUT_DIR"
echo ""

# Ensure output directory exists
mkdir -p "$OUTPUT_DIR"

# Build Docker image
echo "Building Docker image..."
docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"
echo ""

# Run scanner
DOCKER_ARGS=(
    --rm
    -v "$CORPUS_DIR:/corpus:ro"
    -v "$OUTPUT_DIR:/output"
)

# Add KVM device if available (accelerates libguestfs)
if [ -e /dev/kvm ]; then
    DOCKER_ARGS+=(--device /dev/kvm:/dev/kvm)
fi

if [ $# -gt 0 ]; then
    echo "Scanning specific image: $1"
    docker run "${DOCKER_ARGS[@]}" "$IMAGE_NAME" /corpus /output --image "$1"
else
    echo "Scanning all images..."
    docker run "${DOCKER_ARGS[@]}" "$IMAGE_NAME" /corpus /output
fi

echo ""
echo "=== Done ==="
echo "JSON files written to: $OUTPUT_DIR"
echo "Number of JSON files: $(ls -1 "$OUTPUT_DIR"/*.json 2>/dev/null | wc -l)"
