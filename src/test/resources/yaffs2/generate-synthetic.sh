#!/usr/bin/env bash
# generate-synthetic.sh — one-time synthetic YAFFS2 sample generation via Docker.
#
# Produces 20 synthetic YAFFS2 samples under
# src/test/resources/yaffs2/synthetic/, built with the reference
# mkyaffs2image from the YAFFS2 source (Aleph One, GPL-2.0) compiled for
# four page/spare geometries (2048/64, 4096/128, 1024/32, 8192/256), both
# endians, and covering directories, files, symlinks, hardlinks, device
# nodes, deep nesting, long names, empty trees, and multi-chunk files.
#
# Tests never invoke external processes (project invariant): this script
# runs once and the samples are committed. Generated files preserve the
# invoking user's uid/gid.
#
# Usage:
#   src/test/resources/yaffs2/generate-synthetic.sh
#
# Prerequisites: docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/synthetic"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT_DIR"
cp -r /dev/null "$WORK" 2>/dev/null || true
mkdir -p "$WORK/src"
tar -C /tmp -cf - yaffs2 2>/dev/null | tar -C "$WORK/src" -xf - 2>/dev/null || true

IMAGE="saffron-yaffs2-synthetic:latest"
docker build -q -t "$IMAGE" -f - "$WORK" <<'EOF'
FROM debian:bookworm
RUN apt-get update -qq && apt-get install -y -qq build-essential python3 && rm -rf /var/lib/apt/lists/*
COPY src/yaffs2 /src/yaffs2
RUN cat > /src/shim.h <<'SHIM'
#include <stdint.h>
#include <stdlib.h>
typedef uint8_t u8; typedef uint16_t u16; typedef uint32_t u32; typedef uint64_t u64;
typedef int8_t s8; typedef int16_t s16; typedef int32_t s32; typedef int64_t s64;
#define CONFIG_YAFFS_DEFINES_TYPES
SHIM
RUN cd /src/yaffs2/utils && \
    make CFLAGS="-O2 -Wall -DCONFIG_YAFFS_UTIL -include /src/shim.h" >/dev/null 2>&1 && cp mkyaffs2image /src/mk-2048-64 && \
    sed -i 's/#define chunkSize 2048/#define chunkSize 4096/; s/#define spareSize 64/#define spareSize 128/' mkyaffs2image.c && make clean >/dev/null 2>&1 && \
    make CFLAGS="-O2 -Wall -DCONFIG_YAFFS_UTIL -include /src/shim.h" >/dev/null 2>&1 && cp mkyaffs2image /src/mk-4096-128 && \
    sed -i 's/#define chunkSize 4096/#define chunkSize 1024/; s/#define spareSize 128/#define spareSize 32/' mkyaffs2image.c && make clean >/dev/null 2>&1 && \
    make CFLAGS="-O2 -Wall -DCONFIG_YAFFS_UTIL -include /src/shim.h" >/dev/null 2>&1 && cp mkyaffs2image /src/mk-1024-32 && \
    sed -i 's/#define chunkSize 1024/#define chunkSize 8192/; s/#define spareSize 32/#define spareSize 256/' mkyaffs2image.c && make clean >/dev/null 2>&1 && \
    make CFLAGS="-O2 -Wall -DCONFIG_YAFFS_UTIL -include /src/shim.h" >/dev/null 2>&1 && cp mkyaffs2image /src/mk-8192-256
EOF

docker run --rm -i -e OUT_UID="$(id -u)" -e OUT_GID="$(id -g)" \
    -v "$OUT_DIR:/out" "$IMAGE" bash -s <<'INNER'
set -euo pipefail
mkdir -p /tmp/work && cd /tmp/work

# ── source trees ─────────────────────────────────────────────────────────────
mkdir -p tree/dir1/sub tree/dir2 empty-dir emptytree single deep dev
printf 'hello yaffs2\n' > tree/hello.txt
printf 'aaa' > tree/dir1/a.txt
printf 'deep' > tree/dir1/sub/deep.txt
printf 'bb' > tree/dir2/b.txt
mkdir -p emptytree
: > emptytree/only-empty.txt
: > tree/empty.txt
ln tree/hello.txt tree/hard.txt            # hard link
ln -s dir1/a.txt tree/link.txt             # symlink
mknod dev/console c 5 1 2>/dev/null || true
mknod dev/sda b 8 0 2>/dev/null || true
mknod dev/fifo p 2>/dev/null || true
cp dev/console dev/console 2>/dev/null || true
python3 - <<'PYEOF'
import os
with open('tree/big.txt', 'wb') as f:
    for i in range(60):
        f.write(b'big file chunk %04d\n' % i)
# deep nesting + long name
cur = 'deep'
for i in range(6):
    cur = os.path.join(cur, 'd%d' % i)
    os.mkdir(cur)
open(os.path.join(cur, 'n' * 240), 'w').write('long')
open('single/only.txt', 'w').write('one file')
PYEOF

# ── generate ─────────────────────────────────────────────────────────────────
MK2048=/src/mk-2048-64
MK4096=/src/mk-4096-128
MK1024=/src/mk-1024-32
MK8192=/src/mk-8192-256

$MK2048 tree      /out/synth-2048-64-tree-le.yaffs2
$MK2048 tree      /out/synth-2048-64-tree-be.yaffs2 convert
$MK2048 emptytree /out/synth-2048-64-empty-dir-le.yaffs2
$MK2048 dev       /out/synth-2048-64-devices-le.yaffs2
$MK2048 deep      /out/synth-2048-64-deep-le.yaffs2
$MK2048 single    /out/synth-2048-64-single-le.yaffs2
$MK2048 tree/big.txt /tmp/x.yaffs2 2>/dev/null || true
mkdir -p bigonly && cp tree/big.txt bigonly/
$MK2048 bigonly   /out/synth-2048-64-bigfile-le.yaffs2
$MK2048 single    /out/synth-2048-64-single-be.yaffs2 convert

$MK4096 tree      /out/synth-4096-128-tree-le.yaffs2
$MK4096 tree      /out/synth-4096-128-tree-be.yaffs2 convert
$MK4096 deep      /out/synth-4096-128-deep-le.yaffs2
$MK4096 bigonly   /out/synth-4096-128-bigfile-le.yaffs2
$MK4096 dev       /out/synth-4096-128-devices-le.yaffs2

$MK1024 tree      /out/synth-1024-32-tree-le.yaffs2
$MK1024 tree      /out/synth-1024-32-tree-be.yaffs2 convert
$MK1024 bigonly   /out/synth-1024-32-bigfile-le.yaffs2
$MK1024 deep      /out/synth-1024-32-deep-le.yaffs2
$MK1024 dev       /out/synth-1024-32-devices-le.yaffs2

$MK8192 tree      /out/synth-8192-256-tree-le.yaffs2
$MK8192 dev       /out/synth-8192-256-devices-le.yaffs2

chown -R "$OUT_UID:$OUT_GID" /out
INNER

docker rmi -f "$IMAGE" >/dev/null 2>&1 || true

echo "Synthetic samples written to $OUT_DIR:"
ls -la "$OUT_DIR"
