#!/usr/bin/env bash
# generate-fixtures.sh — one-time NTFS fixture generation via Docker.
#
# The tests never invoke external processes (project invariant): this script
# generates the committed fixtures under src/test/resources/ntfs/fixtures/
# once, using the reference mkntfs + ntfs-3g (FUSE) from Debian bookworm in
# a throwaway Docker container. Each image is formatted with mkntfs, mounted
# with ntfs-3g, populated from a deterministic source tree built by
# fixture-tree.py, unmounted, and paired with an expectations JSON computed
# from that source tree (never by reading the NTFS image back).
#
# Usage:
#   src/test/resources/ntfs/generate-fixtures.sh
#
# Prerequisites: docker. FUSE inside the container needs
#   --privileged --device /dev/fuse
# (works on Docker Desktop for Mac 4.x / Engine 29). If that is refused, try
#   --cap-add SYS_ADMIN --device /dev/fuse --security-opt apparmor:unconfined
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixtures"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$FIXTURE_DIR"
cp "$SCRIPT_DIR/fixture-tree.py" "$WORK/fixture-tree.py"

# ── Build a throwaway image with ntfs-3g installed ───────────────────────────
IMAGE="saffron-ntfs-fixture:latest"
docker build -q -t "$IMAGE" -f - "$WORK" <<'EOF'
FROM debian:bookworm
RUN apt-get update -qq && apt-get install -y -qq ntfs-3g attr coreutils python3 \
    && rm -rf /var/lib/apt/lists/*
EOF

# ── Variant table: name | image size | extra mkntfs options | mount options ──
# Every image is formatted with `mkntfs -F -Q -T -L <name> <extra>` (-T zeroes
# the system-file timestamps; the volume serial number is still random).
# Sizes are the smallest mkntfs accepts with room for the content: mkntfs
# needs 2 MiB with 4 KiB clusters, 4 MiB with 256 KiB clusters, and its
# $LogFile alone is 2 MiB on an 8 MiB volume.
cat > "$WORK/variants.txt" <<'EOF'
basic-4k|3M||
compressed|2M||compression
sparse-and-ads|2M||streams_interface=xattr
links|2M||
fragmented|8M||streams_interface=xattr
cluster-64k|2M|-c 65536|
cluster-256k|6M|-c 262144|
sector-4k|2M|-s 4096|
EOF

# ── Generate inside the container (FUSE needs root + /dev/fuse) ──────────────
docker run --rm -i --privileged --device /dev/fuse \
    -e "HOST_UID=$(id -u)" -e "HOST_GID=$(id -g)" \
    -v "$WORK:/work" -v "$FIXTURE_DIR:/out" "$IMAGE" bash -s <<'INNER'
set -euo pipefail
GEN=/work/fixture-tree.py
mkdir -p /mnt/ntfs

count_attr() { # file-or-inode-arg image attr  -> number of "Dumping attribute $X" lines
    ntfsinfo "$1" "$2" "$3" 2>/dev/null | grep -c "Dumping attribute $4" || true
}

while IFS='|' read -r name size mkopts mntopts; do
    [ -n "$name" ] || continue
    echo "=== $name (size=$size mkntfs='$mkopts' mount='$mntopts')"
    img="/out/$name.img"
    work="/work/$name"
    rm -f "$img"; mkdir -p "$work"

    python3 "$GEN" build "$name" "$work"

    truncate -s "$size" "$img"
    # shellcheck disable=SC2086
    mkntfs -F -Q -T -L "$name" $mkopts "$img" >/dev/null 2>&1
    if [ -n "$mntopts" ]; then ntfs-3g -o "$mntopts" "$img" /mnt/ntfs; else ntfs-3g "$img" /mnt/ntfs; fi
    python3 "$GEN" populate "$name" "$work" /mnt/ntfs
    df -k /mnt/ntfs | tail -1
    umount /mnt/ntfs

    python3 "$GEN" expect "$name" "$work" "$name.img" "-F -Q -T -L $name $mkopts" "$mntopts" "$name" "/out/$name.json"

    # ── structural checks that the fixtures exercise what they claim ──
    case "$name" in
        fragmented)
            al=$(count_attr -i 0 "$img" '$ATTRIBUTE_LIST'); mftdata=$(count_attr -i 0 "$img" '$DATA')
            bigdata=$(count_attr -F big.bin "$img" '$DATA')
            echo "  \$MFT: attribute lists=$al, \$DATA pieces=$mftdata; big.bin \$DATA pieces=$bigdata (incl. ADS)"
            [ "$al" -ge 1 ] && [ "$mftdata" -ge 2 ] && [ "$bigdata" -ge 3 ] \
                || { echo "fragmented: \$MFT/big.bin not fragmented enough"; exit 1; }
            ;;
        compressed)
            ntfsinfo -F comp/text-300k.txt "$img" | grep -Eq "Compression unit:[[:space:]]+4 " \
                || { echo "compressed: text-300k.txt is not compressed"; exit 1; }
            ;;
        cluster-256k)
            ntfsinfo -m "$img" | grep -E "Cluster Size|Sectors per Cluster" || true
            ;;
    esac
done < /work/variants.txt

chown "$HOST_UID:$HOST_GID" /out/*
ls -la /out
INNER

docker rmi -f "$IMAGE" >/dev/null 2>&1 || true

echo "Fixtures written to $FIXTURE_DIR:"
ls -la "$FIXTURE_DIR"
