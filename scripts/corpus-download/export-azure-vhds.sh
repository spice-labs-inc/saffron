#!/bin/bash
# Export Azure Marketplace VM images for Saffron test corpus
# Requires: Azure CLI authenticated with a subscription
#
# This script exports Azure VM images to blob storage and downloads them.
# You need:
#   1. An Azure subscription with billing enabled
#   2. A Storage Account and container for exports
#   3. Azure CLI authenticated: az login
#
# Usage:
#   export AZURE_SUBSCRIPTION=my-subscription-id
#   export AZURE_STORAGE_ACCOUNT=mystorageaccount
#   export AZURE_CONTAINER=saffron-exports
#   export AZURE_RESOURCE_GROUP=my-resource-group
#   ./export-azure-vhds.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORPUS_BASE="${CORPUS_BASE:-/home/dpp/tmp/vmreader/saffron/test-corpus}"

# Check required environment variables
if [[ -z "${AZURE_SUBSCRIPTION:-}" ]]; then
    echo "ERROR: AZURE_SUBSCRIPTION environment variable not set"
    echo "Usage: AZURE_SUBSCRIPTION=xxx AZURE_STORAGE_ACCOUNT=xxx AZURE_CONTAINER=xxx AZURE_RESOURCE_GROUP=xxx $0"
    exit 1
fi

if [[ -z "${AZURE_STORAGE_ACCOUNT:-}" ]]; then
    echo "ERROR: AZURE_STORAGE_ACCOUNT environment variable not set"
    exit 1
fi

if [[ -z "${AZURE_CONTAINER:-}" ]]; then
    echo "ERROR: AZURE_CONTAINER environment variable not set"
    exit 1
fi

if [[ -z "${AZURE_RESOURCE_GROUP:-}" ]]; then
    echo "ERROR: AZURE_RESOURCE_GROUP environment variable not set"
    exit 1
fi

# Check for az CLI
if ! command -v az &> /dev/null; then
    echo "ERROR: Azure CLI (az) is required but not installed."
    echo "Install from: https://docs.microsoft.com/en-us/cli/azure/install-azure-cli"
    exit 1
fi

# Create target directories
mkdir -p "$CORPUS_BASE/vhd/cloud/azure"
mkdir -p "$CORPUS_BASE/vhdx/cloud/azure"

# Define images to export
# Format: "publisher|offer|sku|output-name"
# Azure exports to VHD format natively
declare -a IMAGES=(
    "Canonical|ubuntu-24_04-lts|server|ubuntu-2404-azure"
    "Canonical|ubuntu-22_04-lts|server|ubuntu-2204-azure"
    "Canonical|0001-com-ubuntu-server-focal|20_04-lts|ubuntu-2004-azure"
    "debian|debian-12|12|debian-12-azure"
    "debian|debian-11|11|debian-11-azure"
    "RedHat|RHEL|9-lvm|rhel-9-azure"
    "RedHat|RHEL|8-lvm|rhel-8-azure"
    "OpenLogic|CentOS|8_5|centos-8-azure"
    "almalinux|almalinux|9-gen2|almalinux-9-azure"
    "erockyenterprisesoftwarefoundationinc1653071250513|rockylinux|rockylinux-9|rocky-9-azure"
    "MicrosoftWindowsServer|WindowsServer|2022-datacenter-azure-edition|windows-2022-azure"
    "MicrosoftWindowsServer|WindowsServer|2019-datacenter|windows-2019-azure"
)

export_and_download() {
    local publisher="$1"
    local offer="$2"
    local sku="$3"
    local output_name="$4"

    local target_file="$CORPUS_BASE/vhd/cloud/azure/${output_name}.vhd"
    local disk_name="saffron-export-${output_name}"
    local vm_name="saffron-vm-${output_name}"

    if [[ -f "$target_file" ]]; then
        echo "SKIP: $output_name.vhd (already exists locally)"
        return 0
    fi

    echo "Exporting: $publisher/$offer/$sku -> $output_name.vhd"

    # Azure export process:
    # 1. Create a managed disk from the marketplace image
    # 2. Grant access to get SAS URL
    # 3. Copy to blob storage or download directly
    # 4. Cleanup

    echo "  Creating managed disk from image..."

    # Get the image URN
    local image_urn="${publisher}:${offer}:${sku}:latest"

    # Create a managed disk from the marketplace image
    if ! az disk create \
        --subscription "$AZURE_SUBSCRIPTION" \
        --resource-group "$AZURE_RESOURCE_GROUP" \
        --name "$disk_name" \
        --image-reference "$image_urn" \
        --size-gb 30 \
        --sku Standard_LRS \
        --output none 2>/dev/null; then
        echo "  FAILED: Could not create disk from image"
        return 1
    fi

    echo "  Granting SAS access..."

    # Grant access to the disk
    local sas_url
    sas_url=$(az disk grant-access \
        --subscription "$AZURE_SUBSCRIPTION" \
        --resource-group "$AZURE_RESOURCE_GROUP" \
        --name "$disk_name" \
        --duration-in-seconds 3600 \
        --access-level Read \
        --query "accessSas" -o tsv 2>/dev/null) || {
        echo "  FAILED: Could not grant SAS access"
        az disk delete --subscription "$AZURE_SUBSCRIPTION" --resource-group "$AZURE_RESOURCE_GROUP" --name "$disk_name" --yes --no-wait 2>/dev/null || true
        return 1
    }

    echo "  Downloading VHD..."

    # Download using azcopy or curl
    if command -v azcopy &> /dev/null; then
        if azcopy copy "$sas_url" "$target_file.tmp" 2>/dev/null; then
            mv "$target_file.tmp" "$target_file"
            echo "  OK: Downloaded $(du -h "$target_file" | cut -f1)"
        else
            rm -f "$target_file.tmp"
            echo "  FAILED: Download failed"
        fi
    else
        if curl -fSL --progress-bar -o "$target_file.tmp" "$sas_url"; then
            mv "$target_file.tmp" "$target_file"
            echo "  OK: Downloaded $(du -h "$target_file" | cut -f1)"
        else
            rm -f "$target_file.tmp"
            echo "  FAILED: Download failed"
        fi
    fi

    # Cleanup: revoke access and delete disk
    echo "  Cleaning up..."
    az disk revoke-access \
        --subscription "$AZURE_SUBSCRIPTION" \
        --resource-group "$AZURE_RESOURCE_GROUP" \
        --name "$disk_name" \
        --output none 2>/dev/null || true

    az disk delete \
        --subscription "$AZURE_SUBSCRIPTION" \
        --resource-group "$AZURE_RESOURCE_GROUP" \
        --name "$disk_name" \
        --yes \
        --output none 2>/dev/null || true

    [[ -f "$target_file" ]]
}

echo "=== Azure VHD Export ==="
echo "Subscription: $AZURE_SUBSCRIPTION"
echo "Storage Account: $AZURE_STORAGE_ACCOUNT"
echo "Resource Group: $AZURE_RESOURCE_GROUP"
echo "Target: $CORPUS_BASE"
echo ""
echo "NOTE: Each export creates a temporary managed disk and may incur charges."
echo ""

success=0
failed=0

for image_spec in "${IMAGES[@]}"; do
    IFS='|' read -r publisher offer sku output_name <<< "$image_spec"
    if export_and_download "$publisher" "$offer" "$sku" "$output_name"; then
        ((success++))
    else
        ((failed++))
    fi
    echo ""
done

echo ""
echo "=== Summary ==="
echo "Exported: $success"
echo "Failed: $failed"
echo ""
echo "Downloaded VHDs:"
find "$CORPUS_BASE/vhd/cloud/azure" -name "*.vhd" -type f 2>/dev/null | sort
