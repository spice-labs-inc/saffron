#!/bin/bash
# Export Google Cloud Platform public images for Saffron test corpus
# Requires: gcloud CLI authenticated with a GCP project
#
# This script exports public GCP images to Cloud Storage and downloads them.
# You need:
#   1. A GCP project with billing enabled
#   2. A Cloud Storage bucket for exports
#   3. gcloud CLI authenticated: gcloud auth login
#
# Usage:
#   export GCP_PROJECT=my-project
#   export GCP_BUCKET=my-bucket
#   ./export-gcp-images.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Check required environment variables
if [[ -z "${GCP_PROJECT:-}" ]]; then
    echo "ERROR: GCP_PROJECT environment variable not set"
    echo "Usage: GCP_PROJECT=my-project GCP_BUCKET=my-bucket $0"
    exit 1
fi

if [[ -z "${GCP_BUCKET:-}" ]]; then
    echo "ERROR: GCP_BUCKET environment variable not set"
    exit 1
fi

# Check for gcloud
if ! command -v gcloud &> /dev/null; then
    echo "ERROR: gcloud CLI is required but not installed."
    echo "Install from: https://cloud.google.com/sdk/docs/install"
    exit 1
fi

# Create target directories
mkdir -p "$CORPUS_BASE/vmdk/cloud/gcp"
mkdir -p "$CORPUS_BASE/vhd/cloud/gcp"
mkdir -p "$CORPUS_BASE/qcow2/cloud/gcp"

# Define images to export
# Format: "project|image-family|export-format|output-name"
declare -a IMAGES=(
    "ubuntu-os-cloud|ubuntu-2404-lts|vmdk|ubuntu-2404-gcp"
    "ubuntu-os-cloud|ubuntu-2204-lts|vmdk|ubuntu-2204-gcp"
    "ubuntu-os-cloud|ubuntu-2004-lts|vmdk|ubuntu-2004-gcp"
    "debian-cloud|debian-12|vmdk|debian-12-gcp"
    "debian-cloud|debian-11|vmdk|debian-11-gcp"
    "centos-cloud|centos-stream-9|vmdk|centos-stream-9-gcp"
    "rocky-linux-cloud|rocky-linux-9|vmdk|rocky-9-gcp"
    "rhel-cloud|rhel-9|vmdk|rhel-9-gcp"
)

export_and_download() {
    local project="$1"
    local family="$2"
    local format="$3"
    local output_name="$4"

    local target_dir=""
    local ext=""

    case "$format" in
        vmdk)
            target_dir="$CORPUS_BASE/vmdk/cloud/gcp"
            ext="vmdk"
            ;;
        vhdx)
            target_dir="$CORPUS_BASE/vhdx/cloud/gcp"
            ext="vhdx"
            ;;
        vpc)
            target_dir="$CORPUS_BASE/vhd/cloud/gcp"
            ext="vhd"
            ;;
        qcow2)
            target_dir="$CORPUS_BASE/qcow2/cloud/gcp"
            ext="qcow2"
            ;;
        *)
            echo "Unknown format: $format"
            return 1
            ;;
    esac

    local target_file="$target_dir/${output_name}.${ext}"
    local gcs_path="gs://${GCP_BUCKET}/saffron-exports/${output_name}.${ext}"

    if [[ -f "$target_file" ]]; then
        echo "SKIP: $output_name.$ext (already exists locally)"
        return 0
    fi

    echo "Exporting: $project/$family -> $output_name.$ext"

    # Get the latest image from the family
    local image_name
    image_name=$(gcloud compute images describe-from-family "$family" \
        --project="$project" \
        --format="value(name)" 2>/dev/null) || {
        echo "  FAILED: Could not get image name from family $family"
        return 1
    }

    echo "  Image: $image_name"
    echo "  Exporting to: $gcs_path"

    # Start export (this is async)
    if gcloud compute images export \
        --project="$GCP_PROJECT" \
        --image="$image_name" \
        --image-project="$project" \
        --destination-uri="$gcs_path" \
        --export-format="$format" \
        --async 2>/dev/null; then

        echo "  Export started. This may take several minutes."
        echo "  Check status: gcloud compute operations list --project=$GCP_PROJECT"

        # Note: For a production script, we'd wait for completion and download
        # For now, we'll just note that download needs to happen separately
        echo "  After completion, download with:"
        echo "    gsutil cp $gcs_path $target_file"
        return 0
    else
        echo "  FAILED: Export failed"
        return 1
    fi
}

echo "=== GCP Image Export ==="
echo "Project: $GCP_PROJECT"
echo "Bucket: $GCP_BUCKET"
echo "Target: $CORPUS_BASE"
echo ""
echo "NOTE: Exports are asynchronous and may take 10-30 minutes each."
echo "      Download files after export completes."
echo ""

for image_spec in "${IMAGES[@]}"; do
    IFS='|' read -r project family format output_name <<< "$image_spec"
    export_and_download "$project" "$family" "$format" "$output_name" || true
    echo ""
done

echo ""
echo "=== Export Jobs Started ==="
echo "Monitor with: gcloud compute operations list --project=$GCP_PROJECT --filter='operationType=compute.images.export'"
echo ""
echo "After exports complete, download with:"
echo "  gsutil -m cp 'gs://${GCP_BUCKET}/saffron-exports/*' $CORPUS_BASE/"
