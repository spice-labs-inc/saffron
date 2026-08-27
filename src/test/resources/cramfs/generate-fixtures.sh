#!/usr/bin/env bash
# generate-fixtures.sh — one-time cramfs fixture generation via Docker.
#
# Tests never invoke external processes (project invariant): this script
# generates the committed fixtures under src/test/resources/cramfs/fixtures/
# once, using util-linux mkfs.cramfs and cramfsswap in a throwaway Docker
# container. Generated files preserve the invoking user's uid/gid.
#
# Usage:
#   src/test/resources/cramfs/generate-fixtures.sh
#
# Prerequisites: docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixtures"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$FIXTURE_DIR"

IMAGE="saffron-cramfs-fixture:latest"
docker build -q -t "$IMAGE" -f - "$WORK" <<'EOF'
FROM debian:bookworm
RUN apt-get update -qq && apt-get install -y -qq util-linux cramfsswap && rm -rf /var/lib/apt/lists/*
EOF

SRC="$WORK/src"
mkdir -p "$SRC/root/dir1/sub" "$SRC/root/dir2" "$SRC/empty"

printf 'root' > "$SRC/root/root.txt"
printf 'aaa' > "$SRC/root/dir1/a.bin"
printf 'deep' > "$SRC/root/dir1/sub/deep.txt"
printf 'bb' > "$SRC/root/dir2/b.txt"
: > "$SRC/root/empty.txt"
python3 - <<'PYEOF'
with open("$SRC/root/compress.txt", "w") as f:
    for _ in range(3000):
        f.write("cramfs compression test data!\n")
with open("$SRC/root/sparse.bin", "wb") as f:
    f.write(b"A" * 8192)
    f.seek(100000)
    f.write(b"Z" * 4096)
PYEOF
ln -s dir1/a.bin "$SRC/root/lnk.txt"

docker run --rm --user "$(id -u):$(id -g)" \
    -v "$SRC:/src:ro" -v "$FIXTURE_DIR:/out" "$IMAGE" sh -c '
mkfs.cramfs /src/root /out/tree.cramfs >/dev/null 2>&1
mkfs.cramfs /src/empty /out/empty.cramfs >/dev/null 2>&1
cramfsswap /out/tree.cramfs /out/tree-be.cramfs
'

docker rmi -f "$IMAGE" >/dev/null 2>&1 || true

echo "Fixtures written to $FIXTURE_DIR:"
ls -la "$FIXTURE_DIR"
