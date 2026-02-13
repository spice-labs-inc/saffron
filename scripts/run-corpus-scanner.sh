#!/bin/bash
# Build and run the corpus scanner Docker container

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CORPUS_DIR="${1:-/home/dpp/tmp/vmreader/saffron/test-corpus}"
OUTPUT_DIR="${2:-/home/dpp/tmp/vmreader/saffron/test-corpus-data}"

mkdir -p "$OUTPUT_DIR"

echo "Building Docker image (this may take a few minutes the first time)..."
docker build -t corpus-scanner -f "$SCRIPT_DIR/Dockerfile.corpus-scanner" "$SCRIPT_DIR"

echo ""
echo "Running corpus scanner..."
echo "  Corpus: $CORPUS_DIR"
echo "  Output: $OUTPUT_DIR"
echo ""

# Run with privileged mode and FUSE device access
docker run --rm \
    --privileged \
    --cap-add SYS_ADMIN \
    --device /dev/fuse \
    -v "$CORPUS_DIR:/corpus:ro" \
    -v "$OUTPUT_DIR:/output" \
    corpus-scanner

echo ""
echo "Done! Output files in: $OUTPUT_DIR"
echo ""
echo "Summary:"
cat "$OUTPUT_DIR/_summary.json" 2>/dev/null || echo "No summary available"
echo ""
ls -la "$OUTPUT_DIR"/*.json 2>/dev/null | head -30
