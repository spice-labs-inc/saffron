#!/usr/bin/env bash
# generate-synthetic.sh — one-time synthetic UBIFS/UBI sample generation.
#
# Produces 20 synthetic samples under src/test/resources/ubifs/synthetic/,
# built with the reference mkfs.ubifs/ubinize (mtd-utils) in a Docker
# container: four compression algorithms, several LEB geometries, empty and
# single-file trees, and multi-volume UBI containers (including a truncated
# one). Tests never invoke external processes (project invariant): this
# script runs once and the samples are committed. Generated files preserve
# the invoking user's uid/gid.
#
# Usage:
#   src/test/resources/ubifs/generate-synthetic.sh
#
# Prerequisites: docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/synthetic"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT_DIR"

IMAGE="saffron-ubifs-synthetic:latest"
docker build -q -t "$IMAGE" -f - "$WORK" <<'EOF'
FROM debian:bookworm
RUN apt-get update -qq && apt-get install -y -qq mtd-utils && rm -rf /var/lib/apt/lists/*
EOF

SRC="$WORK/src"
mkdir -p "$SRC/tree/dir1/sub" "$SRC/tree/dir2" "$SRC/empty" "$SRC/single"
printf 'root' > "$SRC/tree/root.txt"
printf 'aaa' > "$SRC/tree/dir1/a.bin"
printf 'deep' > "$SRC/tree/dir1/sub/deep.txt"
printf 'bb' > "$SRC/tree/dir2/b.txt"
: > "$SRC/tree/empty.txt"
ln -s dir1/a.bin "$SRC/tree/lnk.txt"
ln "$SRC/tree/root.txt" "$SRC/tree/hlink.txt"
export SRC
python3 - <<'PYEOF'
import os
src = os.environ['SRC']
with open(os.path.join(src, "tree", "compress.txt"), "w") as f:
    for _ in range(5000):
        f.write("ubifs compression test data line\n")
with open(os.path.join(src, "tree", "sparse.bin"), "wb") as f:
    f.write(b"A" * 8192)
    f.seek(50000)
    f.write(b"Z" * 4096)
open(os.path.join(src, "single", "only.txt"), "w").write("one file")
PYEOF

cat > "$WORK/ubinize.cfg" <<'CFGEOF'
[data]
mode=ubi
vol_id=0
vol_type=dynamic
vol_name=data
vol_size=2MiB
image=/work/tree-zlib.ubifs

[apple]
mode=ubi
vol_id=1
vol_type=static
vol_name=apple
vol_size=2MiB
image=/work/tree-none.ubifs

[pear]
mode=ubi
vol_id=2
vol_type=dynamic
vol_name=pear
vol_size=2MiB
image=/work/tree-zstd.ubifs
CFGEOF

docker run --rm -i --user "$(id -u):$(id -g)" \
    -v "$SRC:/src:ro" -v "$WORK:/work" -v "$OUT_DIR:/out" "$IMAGE" bash -s <<'INNER'
set -euo pipefail
MK=mkfs.ubifs

# compression variants, LEB 124KiB (min-io 2048)
$MK -m 2048 -e 126976 -c 32 -r /src/tree -o /work/tree-zlib.ubifs
$MK -m 2048 -e 126976 -c 32 -x none -r /src/tree -o /work/tree-none.ubifs
$MK -m 2048 -e 126976 -c 32 -x lzo -r /src/tree -o /work/tree-lzo.ubifs
$MK -m 2048 -e 126976 -c 32 -x zstd -r /src/tree -o /work/tree-zstd.ubifs
cp /work/tree-zlib.ubifs /out/synth-zlib-124k.ubifs
cp /work/tree-none.ubifs  /out/synth-none-124k.ubifs
cp /work/tree-lzo.ubifs   /out/synth-lzo-124k.ubifs
cp /work/tree-zstd.ubifs  /out/synth-zstd-124k.ubifs

# geometry variants (zlib): LEB 248KiB (min-io 4096), 62KiB, 31KiB, 15.5KiB (min LEB)
$MK -m 4096  -e 253952 -c 32 -r /src/tree -o /out/synth-zlib-248k.ubifs
$MK -m 2048  -e 63488  -c 32 -r /src/tree -o /out/synth-zlib-62k.ubifs
$MK -m 2048  -e 30720  -c 32 -r /src/tree -o /out/synth-zlib-31k.ubifs
$MK -m 512   -e 15872  -c 32 -r /src/tree -o /out/synth-zlib-15k.ubifs

# tree variants
$MK -m 2048 -e 126976 -c 32 -r /src/empty  -o /out/synth-empty.ubifs
$MK -m 2048 -e 126976 -c 32 -r /src/single -o /out/synth-single.ubifs

# UBI containers
ubinize -o /out/synth-two-vol.ubi -p 128KiB -m 2048 /work/ubinize.cfg
ubinize -o /out/synth-two-vol-248k.ubi -p 256KiB -m 4096 /work/ubinize.cfg
# truncated container (cut the last 64KiB)
head -c $(( 3801088 - 65536 )) /out/synth-two-vol.ubi > /out/synth-two-vol-truncated.ubi

# small-PEB container (2048-byte PEBs, test-style like unblob's fruits.ubi)
cat > /work/tiny.cfg <<'TCFG'
[tiny]
mode=ubi
vol_id=0
vol_type=dynamic
vol_name=tiny
vol_size=64KiB
image=/work/tree-lzo.ubifs
TCFG
ubinize -o /out/synth-tiny-peb.ubi -p 2048 -m 512 /work/tiny.cfg 2>/dev/null || true
ls -la /out/ | wc -l
INNER

docker rmi -f "$IMAGE" >/dev/null 2>&1 || true

echo "Synthetic samples written to $OUT_DIR:"
ls -la "$OUT_DIR"
