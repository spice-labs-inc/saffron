#!/bin/bash
# Helper script to rename and catalog extracted VMDK/VDI files
# that weren't properly handled by the main acquisition script

set -e

# Resolve project root from script location
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CORPUS_DIR="$PROJECT_ROOT/test-corpus"
MANIFEST_FILE="$CORPUS_DIR/manifest.json"

# Mapping of extracted filenames to target names and metadata
declare -A VMDK_MAP=(
    ["Debian_12.2.0_VM_LinuxVMImages.COM.vmdk"]="debian-12.2-vmware:2023:DFSG:Debian 12.2:ext4"
    ["Debian_11.6_VM_LinuxVMImages.COM.vmdk"]="debian-11.6-vmware:2023:DFSG:Debian 11.6:ext4"
    ["Fedora_39_VM_LinuxVMImages.COM.vmdk"]="fedora-39-vmware:2023:MIT:Fedora 39:ext4"
    ["Fedora_38_VM_LinuxVMImages.COM.vmdk"]="fedora-38-vmware:2023:MIT:Fedora 38:ext4"
    ["CentOS_Stream_9_VM_LinuxVMImages.COM.vmdk"]="centos-stream-9-vmware:2023:GPL:CentOS Stream 9:xfs"
    ["AlmaLinux_9.2_VM_LinuxVMImages.COM.vmdk"]="almalinux-9.2-vmware:2023:GPL:AlmaLinux 9.2:xfs"
    ["Rocky_Linux_9.2_VM_LinuxVMImages.COM.vmdk"]="rocky-9.2-vmware:2023:BSD:Rocky Linux 9.2:xfs"
    ["Kali_Linux_2023.3_VM_LinuxVMImages.COM.vmdk"]="kali-2023.3-vmware:2023:GPL:Kali Linux 2023.3:ext4"
    ["LinuxMint_21.2_VM_LinuxVMImages.COM.vmdk"]="linuxmint-21.2-vmware:2023:GPL:Linux Mint 21.2:ext4"
    ["Manjaro_23.0_VM_LinuxVMImages.COM.vmdk"]="manjaro-23.0-vmware:2023:GPL:Manjaro 23.0:ext4"
    ["openSUSE_Leap_15.5_VM_LinuxVMImages.COM.vmdk"]="opensuse-15.5-vmware:2023:GPL:openSUSE 15.5:ext4"
    ["Arch_Linux_2023.09_VM_LinuxVMImages.COM.vmdk"]="arch-2023.09-vmware:2023:GPL:Arch Linux:ext4"
)

declare -A VDI_MAP=(
    ["Ubuntu_23.10_VB_LinuxVMImages.COM.vdi"]="ubuntu-23.10-vbox:2023:GPL:Ubuntu 23.10:ext4"
    ["Ubuntu_23.04_VB_LinuxVMImages.COM.vdi"]="ubuntu-23.04-vbox:2023:GPL:Ubuntu 23.04:ext4"
    ["Debian_12.2.0_VB_LinuxVMImages.COM.vdi"]="debian-12.2-vbox:2023:DFSG:Debian 12.2:ext4"
    ["Debian_11.6_VB_LinuxVMImages.COM.vdi"]="debian-11.6-vbox:2023:DFSG:Debian 11.6:ext4"
    ["Fedora_39_VB_LinuxVMImages.COM.vdi"]="fedora-39-vbox:2023:MIT:Fedora 39:ext4"
    ["Fedora_38_VB_LinuxVMImages.COM.vdi"]="fedora-38-vbox:2023:MIT:Fedora 38:ext4"
    ["CentOS_Stream_9_VB_LinuxVMImages.COM.vdi"]="centos-stream-9-vbox:2023:GPL:CentOS Stream 9:xfs"
    ["AlmaLinux_9.2_VB_LinuxVMImages.COM.vdi"]="almalinux-9.2-vbox:2023:GPL:AlmaLinux 9.2:xfs"
    ["Rocky_Linux_9.2_VB_LinuxVMImages.COM.vdi"]="rocky-9.2-vbox:2023:BSD:Rocky Linux 9.2:xfs"
    ["Kali_Linux_2023.3_VB_LinuxVMImages.COM.vdi"]="kali-2023.3-vbox:2023:GPL:Kali Linux 2023.3:ext4"
    ["LinuxMint_21.2_VB_LinuxVMImages.COM.vdi"]="linuxmint-21.2-vbox:2023:GPL:Linux Mint 21.2:ext4"
    ["Manjaro_23.0_VB_LinuxVMImages.COM.vdi"]="manjaro-23.0-vbox:2023:GPL:Manjaro 23.0:ext4"
    ["openSUSE_Leap_15.5_VB_LinuxVMImages.COM.vdi"]="opensuse-15.5-vbox:2023:GPL:openSUSE 15.5:ext4"
    ["Arch_Linux_2023.09_VB_LinuxVMImages.COM.vdi"]="arch-2023.09-vbox:2023:GPL:Arch Linux:ext4"
)

catalog() {
    local id="$1"
    local path="$2"
    local format="$3"
    local era="$4"
    local year="$5"
    local source="$6"
    local license="$7"
    local os="$8"
    local fs="$9"
    local variant="${10}"

    # Check if already in manifest
    if jq -e ".images[] | select(.id == \"$id\")" "$MANIFEST_FILE" >/dev/null 2>&1; then
        echo "Already cataloged: $id"
        return 0
    fi

    jq --arg id "$id" \
       --arg path "$path" \
       --arg format "$format" \
       --arg era "$era" \
       --argjson year "$year" \
       --arg source "$source" \
       --arg license "$license" \
       --arg os "$os" \
       --arg fs "$fs" \
       --arg variant "$variant" \
       '.images += [{
         "id": $id,
         "path": $path,
         "format": $format,
         "era": $era,
         "year": $year,
         "source": $source,
         "license": $license,
         "os": $os,
         "filesystem": $fs,
         "variant": $variant
       }] | .total_images = (.images | length)' "$MANIFEST_FILE" > "$MANIFEST_FILE.tmp" && \
    mv "$MANIFEST_FILE.tmp" "$MANIFEST_FILE"
    echo "Cataloged: $id"
}

# Process VMDK files
echo "Processing VMDK files..."
for extracted_name in "${!VMDK_MAP[@]}"; do
    extracted_path="$CORPUS_DIR/vmdk/modern/$extracted_name"
    if [ -f "$extracted_path" ]; then
        IFS=':' read -r target_id year license os fs <<< "${VMDK_MAP[$extracted_name]}"
        target_path="$CORPUS_DIR/vmdk/modern/$target_id.vmdk"

        if [ ! -f "$target_path" ]; then
            echo "Renaming: $extracted_name -> $target_id.vmdk"
            mv "$extracted_path" "$target_path"
        fi

        if [ -f "$target_path" ]; then
            source="https://sourceforge.net/projects/linuxvmimages/files/VMware/"
            catalog "${target_id}-vmdk" "vmdk/modern/$target_id.vmdk" "vmdk" "modern" "$year" "$source" "$license" "$os" "$fs" "standard"
        fi
    fi
done

# Process VDI files
echo "Processing VDI files..."
for extracted_name in "${!VDI_MAP[@]}"; do
    extracted_path="$CORPUS_DIR/vdi/modern/$extracted_name"
    if [ -f "$extracted_path" ]; then
        IFS=':' read -r target_id year license os fs <<< "${VDI_MAP[$extracted_name]}"
        target_path="$CORPUS_DIR/vdi/modern/$target_id.vdi"

        if [ ! -f "$target_path" ]; then
            echo "Renaming: $extracted_name -> $target_id.vdi"
            mv "$extracted_path" "$target_path"
        fi

        if [ -f "$target_path" ]; then
            source="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/"
            catalog "${target_id}-vdi" "vdi/modern/$target_id.vdi" "vdi" "modern" "$year" "$source" "$license" "$os" "$fs" "standard"
        fi
    fi
done

echo ""
echo "Current status:"
jq -r '.images | group_by(.format) | .[] | "  \(.[0].format): \(length)"' "$MANIFEST_FILE"
echo "  Total: $(jq '.total_images' "$MANIFEST_FILE")"
