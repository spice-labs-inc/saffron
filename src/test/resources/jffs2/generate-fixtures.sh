#!/usr/bin/env bash
# generate-fixtures.sh — one-time JFFS2 fixture generation via Docker.
#
# The tests never invoke external processes (project invariant): this script
# generates the committed fixtures under src/test/resources/jffs2/fixtures/
# once, using the reference mkfs.jffs2 from mtd-utils in a throwaway Docker
# container. The generated files preserve the invoking user's uid/gid.
#
# Usage:
#   src/test/resources/jffs2/generate-fixtures.sh
#
# Prerequisites: docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixtures"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$FIXTURE_DIR"

# ── Build a throwaway image with mtd-utils installed ─────────────────────────
IMAGE="saffron-jffs2-fixture:latest"
docker build -q -t "$IMAGE" -f - "$WORK" <<'EOF'
FROM debian:bookworm
RUN apt-get update -qq && apt-get install -y -qq mtd-utils && rm -rf /var/lib/apt/lists/*
EOF

# ── Source tree (directory layout + file contents the tests assert on) ──────
SRC="$WORK/src"
mkdir -p "$SRC/root/dir1/sub" "$SRC/root/dir2" "$SRC/emptydir"

printf 'root' > "$SRC/root/root.txt"
printf 'aaa' > "$SRC/root/dir1/a.bin"
printf 'deep' > "$SRC/root/dir1/sub/deep.txt"
printf 'bb' > "$SRC/root/dir2/b.txt"
: > "$SRC/root/empty.txt"
dd if=/dev/urandom of="$SRC/root/big.bin" bs=1k count=40 status=none
python3 - <<'PYEOF'
with open("$SRC/root/compress.txt", "w") as f:
    for _ in range(2000):
        f.write("hello world, jffs2 compression test data!\n")
with open("$SRC/root/zeros.bin", "wb") as f:
    f.write(b"\x00" * 16384)
PYEOF
ln "$SRC/root/root.txt" "$SRC/root/hlink.txt"
ln -s dir1/a.bin "$SRC/root/lnk.txt"

# ── Generate fixtures with the reference mkfs.jffs2 ─────────────────────────
# Compression-mode names in mtd-utils: none | priority | size. Specific
# compressors are forced by disabling the others (-x) / enabling (-X).
docker run --rm --user "$(id -u):$(id -g)" \
    -v "$SRC:/src:ro" -v "$FIXTURE_DIR:/out" "$IMAGE" sh -c '
mkfs.jffs2 -m none --root=/src/root --output=/out/tree-none.jffs2 --eraseblock=64KiB -q
mkfs.jffs2 -m priority -x lzo -x rtime --root=/src/root --output=/out/tree-zlib.jffs2 --eraseblock=64KiB -q
mkfs.jffs2 -m priority -X lzo -x zlib -x rtime --root=/src/root --output=/out/tree-lzo.jffs2 --eraseblock=64KiB -q
mkfs.jffs2 -m priority -X rtime -x zlib -x lzo --root=/src/root --output=/out/tree-rtime.jffs2 --eraseblock=64KiB -q
mkfs.jffs2 -m priority --root=/src/emptydir --output=/out/empty.jffs2 --eraseblock=64KiB -q
mkfs.jffs2 -m none --root=/src/root --output=/out/tree-none-noclean.jffs2 --eraseblock=64KiB -n -q
'

docker rmi -f "$IMAGE" >/dev/null 2>&1 || true

echo "Fixtures written to $FIXTURE_DIR:"
ls -la "$FIXTURE_DIR"
