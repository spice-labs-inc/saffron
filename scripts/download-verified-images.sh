#!/bin/bash
# Download verified VM images with correct URLs
set -euo pipefail

CORPUS_DIR="${CORPUS_DIR:-./test-corpus}"
MANIFEST_FILE="$CORPUS_DIR/manifest.json"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $*"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] ERROR:${NC} $*" >&2; }

# Download helper with retry and redirect following
download() {
    local url="$1"
    local dest="$2"
    local name="$3"

    if [ -f "$dest" ]; then
        log "Already exists: $name"
        return 0
    fi

    log "Downloading: $name"
    mkdir -p "$(dirname "$dest")"

    # Use curl with redirect following for SourceForge
    if curl -L -f --retry 3 --retry-delay 5 --connect-timeout 30 -o "$dest.tmp" "$url" 2>/dev/null; then
        # Check file size > 1KB
        local size=$(stat -c%s "$dest.tmp" 2>/dev/null || echo "0")
        if [ "$size" -gt 1024 ]; then
            mv "$dest.tmp" "$dest"
            log "Downloaded: $name ($(du -h "$dest" | cut -f1))"
            return 0
        else
            rm -f "$dest.tmp"
            error "Download too small: $name"
            return 1
        fi
    else
        rm -f "$dest.tmp"
        error "Failed to download: $name"
        return 1
    fi
}

catalog() {
    local id="$1"
    local path="$2"
    local format="$3"
    local era="$4"
    local year="$5"
    local url="$6"
    local license="$7"
    local os="${8:-}"
    local filesystem="${9:-}"

    local full_path="$CORPUS_DIR/$path"
    [ -f "$full_path" ] || return 1

    local sha256=$(sha256sum "$full_path" | cut -d' ' -f1)
    local size=$(stat -c%s "$full_path")

    if jq -e ".images[] | select(.id == \"$id\")" "$MANIFEST_FILE" >/dev/null 2>&1; then
        log "Already cataloged: $id"
        return 0
    fi

    local tmp=$(mktemp)
    jq --arg id "$id" \
       --arg path "$path" \
       --arg format "$format" \
       --arg era "$era" \
       --argjson year "$year" \
       --arg url "$url" \
       --arg license "$license" \
       --arg os "$os" \
       --arg fs "$filesystem" \
       --arg sha "$sha256" \
       --argjson size "$size" \
       --arg date "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
       '.images += [{
         "id": $id,
         "path": $path,
         "format": $format,
         "era": $era,
         "year": $year,
         "source_url": $url,
         "license": $license,
         "os": $os,
         "filesystem": $fs,
         "sha256": $sha,
         "actual_size_bytes": $size,
         "native_format": true
       }] | .total_images = (.images | length) | .generated = $date' \
       "$MANIFEST_FILE" > "$tmp" && mv "$tmp" "$MANIFEST_FILE"

    log "Cataloged: $id"
}

extract_and_catalog() {
    local archive="$1"
    local dest_dir="$2"
    local id="$3"
    local format="$4"
    local year="$5"
    local url="$6"
    local license="$7"
    local os="$8"
    local fs="$9"

    local ext="${archive##*.}"
    local before_extract=$(find "$dest_dir" -name "*.$format" 2>/dev/null | sort)

    log "Extracting: $(basename $archive)"

    case "$ext" in
        7z|7Z)
            if command -v 7z &>/dev/null; then
                7z x -o"$dest_dir" "$archive" -y >/dev/null 2>&1 || true
            fi
            ;;
        zip|ZIP)
            unzip -o -d "$dest_dir" "$archive" >/dev/null 2>&1 || true
            ;;
    esac

    rm -f "$archive"

    local after_extract=$(find "$dest_dir" -name "*.$format" 2>/dev/null | sort)
    local extracted_file=$(comm -13 <(echo "$before_extract") <(echo "$after_extract") | head -1)

    if [ -n "$extracted_file" ] && [ -f "$extracted_file" ]; then
        local dest="$dest_dir/$id.$format"
        mv "$extracted_file" "$dest" 2>/dev/null || true
        local relpath="${dest#$CORPUS_DIR/}"
        catalog "$id-$format" "$relpath" "$format" "modern" "$year" "$url" "$license" "$os" "$fs"
    fi
}

mkdir -p "$CORPUS_DIR"/{qcow2,vmdk,vhd,vhdx,vdi}/{legacy,modern}

# ============================================================================
# VERIFIED VDI DOWNLOADS (VirtualBox native)
# ============================================================================

log "=== Downloading VDI Images ==="

# Ubuntu - direct files
for file in "Ubuntu_23.10_VB.7z:ubuntu-23.10-vbox:2023:Ubuntu 23.10" "Ubuntu_21.10_VB.7z:ubuntu-21.10-vbox:2021:Ubuntu 21.10" "Ubuntu_21.04_VB.7z:ubuntu-21.04-vbox:2021:Ubuntu 21.04"; do
    IFS=':' read -r filename id year os <<< "$file"
    url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/U/$filename/download"
    archive="$CORPUS_DIR/vdi/modern/$id.7z"
    dest="$CORPUS_DIR/vdi/modern/$id.vdi"
    [ -f "$dest" ] && { log "Already exists: $os VDI"; continue; }
    download "$url" "$archive" "$os VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "$id" "vdi" "$year" "$url" "GPL" "$os" "ext4"
done

# Debian 12
url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/D/12/Debian_12.0.0_VBM.7z/download"
dest="$CORPUS_DIR/vdi/modern/debian-12-vbox.vdi"
archive="$CORPUS_DIR/vdi/modern/debian-12.7z"
[ -f "$dest" ] || { download "$url" "$archive" "Debian 12 VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "debian-12-vbox" "vdi" "2023" "$url" "DFSG" "Debian 12" "ext4"; }

# Debian 11
url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/D/11/Debian_11.1.0_VBM.7z/download"
dest="$CORPUS_DIR/vdi/modern/debian-11-vbox.vdi"
archive="$CORPUS_DIR/vdi/modern/debian-11.7z"
[ -f "$dest" ] || { download "$url" "$archive" "Debian 11 VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "debian-11-vbox" "vdi" "2021" "$url" "DFSG" "Debian 11" "ext4"; }

# Arch Linux
url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/A/ArchLinux_2021.01.01_VB.zip/download"
dest="$CORPUS_DIR/vdi/modern/arch-2021-vbox.vdi"
archive="$CORPUS_DIR/vdi/modern/arch-2021.zip"
[ -f "$dest" ] || { download "$url" "$archive" "Arch Linux VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "arch-2021-vbox" "vdi" "2021" "$url" "GPL" "Arch Linux" "ext4"; }

# AlmaLinux
url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/A/AlmaLinux_8.3_Beta_Minimal_VB.zip/download"
dest="$CORPUS_DIR/vdi/modern/almalinux-8.3-vbox.vdi"
archive="$CORPUS_DIR/vdi/modern/almalinux-8.3.zip"
[ -f "$dest" ] || { download "$url" "$archive" "AlmaLinux 8.3 VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "almalinux-8.3-vbox" "vdi" "2021" "$url" "GPL" "AlmaLinux 8.3" "xfs"; }

# Lubuntu
url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/L/lubuntu_23.04_VB.7z/download"
dest="$CORPUS_DIR/vdi/modern/lubuntu-23.04-vbox.vdi"
archive="$CORPUS_DIR/vdi/modern/lubuntu-23.04.7z"
[ -f "$dest" ] || { download "$url" "$archive" "Lubuntu 23.04 VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "lubuntu-23.04-vbox" "vdi" "2023" "$url" "GPL" "Lubuntu 23.04" "ext4"; }

# Kali Linux - in kalilinux subfolder
url="https://sourceforge.net/projects/linuxvmimages/files/VirtualBox/K/kalilinux/KaliLinux_2024.3_VB.7z/download"
dest="$CORPUS_DIR/vdi/modern/kali-2024.3-vbox.vdi"
archive="$CORPUS_DIR/vdi/modern/kali-2024.3.7z"
[ -f "$dest" ] || { download "$url" "$archive" "Kali 2024.3 VDI" && extract_and_catalog "$archive" "$CORPUS_DIR/vdi/modern" "kali-2024.3-vbox" "vdi" "2024" "$url" "GPL" "Kali Linux 2024.3" "ext4"; }

# ============================================================================
# VERIFIED VMDK DOWNLOADS (VMware native)
# ============================================================================

log "=== Downloading VMDK Images ==="

# Ubuntu - direct files
for file in "Ubuntu_23.10_VM.7z:ubuntu-23.10-vmware:2023:Ubuntu 23.10" "Ubuntu_21.10_VM.7z:ubuntu-21.10-vmware:2021:Ubuntu 21.10" "Ubuntu_21.04_VM.7z:ubuntu-21.04-vmware:2021:Ubuntu 21.04"; do
    IFS=':' read -r filename id year os <<< "$file"
    url="https://sourceforge.net/projects/linuxvmimages/files/VMware/U/$filename/download"
    archive="$CORPUS_DIR/vmdk/modern/$id.7z"
    dest="$CORPUS_DIR/vmdk/modern/$id.vmdk"
    [ -f "$dest" ] && { log "Already exists: $os VMDK"; continue; }
    download "$url" "$archive" "$os VMDK" && extract_and_catalog "$archive" "$CORPUS_DIR/vmdk/modern" "$id" "vmdk" "$year" "$url" "GPL" "$os" "ext4"
done

# Devuan
url="https://sourceforge.net/projects/linuxvmimages/files/VMware/D/Devuan_Beowulf_3.1.0_VM.zip/download"
dest="$CORPUS_DIR/vmdk/modern/devuan-3.1-vmware.vmdk"
archive="$CORPUS_DIR/vmdk/modern/devuan-3.1.zip"
[ -f "$dest" ] || { download "$url" "$archive" "Devuan 3.1 VMDK" && extract_and_catalog "$archive" "$CORPUS_DIR/vmdk/modern" "devuan-3.1-vmware" "vmdk" "2021" "$url" "DFSG" "Devuan 3.1" "ext4"; }

# ============================================================================
# VHD DOWNLOADS (Archive.org)
# ============================================================================

log "=== Downloading VHD Images ==="

# Windows XP Mode Base
url="https://archive.org/download/WindowsXPModeBase_64-bit/Windows%20XP%20Mode%20base.vhd"
dest="$CORPUS_DIR/vhd/legacy/windows-xp-mode-base.vhd"
download "$url" "$dest" "Windows XP Mode Base VHD" && \
catalog "xpmode-base-vhd" "vhd/legacy/windows-xp-mode-base.vhd" "vhd" "legacy" "2009" "$url" "MS-EULA" "Windows XP Mode" "ntfs"

# Windows NT 4.0
url="https://archive.org/download/WindowsNTWorkstation4VHD/Windows%20NT%20Workstation%204.0.vhd"
dest="$CORPUS_DIR/vhd/legacy/windows-nt-4.0.vhd"
download "$url" "$dest" "Windows NT 4.0 VHD" && \
catalog "winnt40-vhd" "vhd/legacy/windows-nt-4.0.vhd" "vhd" "legacy" "1996" "$url" "MS-EULA" "Windows NT 4.0" "ntfs"

# ============================================================================
# VHDX DOWNLOADS
# ============================================================================

log "=== Downloading VHDX Images ==="

url="https://archive.org/download/windows-10-x-build-20279/Windows%2010X%20Build%2020279.vhdx"
dest="$CORPUS_DIR/vhdx/modern/windows-10x-build-20279.vhdx"
download "$url" "$dest" "Windows 10X VHDX" && \
catalog "win10x-20279-vhdx" "vhdx/modern/windows-10x-build-20279.vhdx" "vhdx" "modern" "2020" "$url" "MS-EULA" "Windows 10X" "ntfs"

# ============================================================================
# Summary
# ============================================================================

log "=== Download Summary ==="
echo ""
echo "Total images: $(jq '.total_images' "$MANIFEST_FILE")"
echo ""
echo "By format:"
jq -r '.images | group_by(.format) | map("  " + .[0].format + ": " + (length | tostring)) | .[]' "$MANIFEST_FILE"

log "Done"
