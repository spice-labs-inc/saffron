#!/usr/bin/env bash
# generate-synthetic.sh — one-time synthetic cramfs sample generation via Docker.
#
# Produces 20 synthetic cramfs samples under src/test/resources/cramfs/synthetic/,
# covering the format's variant axes: endianness, toolchain generations
# (util-linux mkfs.cramfs, npitre/cramfs-tools, and the OpenRG mkcramfs-lzma
# from the Actiontec MI424WR GPL sources), hole support, uncompressed blocks,
# device nodes, deep/long names, wrong-signature, unsorted dirs, fsid v1, and
# a 512-byte-shifted (boot-sector) layout.
#
# Tests never invoke external processes (project invariant): this script runs
# once and the samples are committed. Generated files preserve the invoking
# user's uid/gid.
#
# Usage:
#   src/test/resources/cramfs/generate-synthetic.sh
#
# Prerequisites: docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/synthetic"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT_DIR"

# ── Toolchain image ──────────────────────────────────────────────────────────
IMAGE="saffron-cramfs-synthetic:latest"
docker build -q -t "$IMAGE" -f - "$WORK" <<'EOF'
FROM debian:bookworm
RUN apt-get update -qq && apt-get install -y -qq git build-essential zlib1g-dev util-linux cramfsswap && rm -rf /var/lib/apt/lists/*
RUN git clone -q https://github.com/npitre/cramfs-tools /opt/cramfs-tools && cd /opt/cramfs-tools && make >/dev/null 2>&1
RUN git clone -q --recursive https://github.com/batterystaples/mkcramfs-lzma /opt/mkcramfs-lzma && cd /opt/mkcramfs-lzma && make >/dev/null 2>&1
EOF

# ── Source trees ─────────────────────────────────────────────────────────────
SRC="$WORK/src"
mkdir -p "$SRC/tree/dir1/sub" "$SRC/tree/dir2" "$SRC/empty" "$SRC/lzma-tree"

printf 'root' > "$SRC/tree/root.txt"
printf 'aaa' > "$SRC/tree/dir1/a.bin"
printf 'deep' > "$SRC/tree/dir1/sub/deep.txt"
printf 'bb' > "$SRC/tree/dir2/b.txt"
: > "$SRC/tree/empty.txt"
export SRC
python3 - <<'PYEOF'
import os
src = os.environ['SRC']
with open(os.path.join(src, "tree", "compress.txt"), "w") as f:
    for _ in range(3000):
        f.write("cramfs compression test data!\n")
with open(os.path.join(src, "tree", "sparse.bin"), "wb") as f:
    f.write(b"A" * 8192)
    f.seek(100000)
    f.write(b"Z" * 4096)
PYEOF
ln -s dir1/a.bin "$SRC/tree/lnk.txt"

# incompressible tree (forces uncompressed blocks)
mkdir -p "$SRC/incompressible"
dd if=/dev/urandom of="$SRC/incompressible/random.bin" bs=1k count=2048 status=none

# deep tree with very long names and many files
mkdir -p "$SRC/deep"
python3 - <<'PYEOF'
import os
src = os.environ['SRC']
p = os.path.join(src, "deep")
cur = p
for i in range(8):
    cur = os.path.join(cur, "d%d" % i)
    os.mkdir(cur)
longname = "n" * 240
open(os.path.join(cur, longname), "w").write("long")
for i in range(300):
    open(os.path.join(p, "f%03d" % i), "w").write("x" * (i % 50))
PYEOF

# lzma trees (no symlinks: symlink targets require decompression, which the
# base reader does not perform for lzma blocks)
printf 'hello' > "$SRC/lzma-tree/hello.txt"
mkdir -p "$SRC/lzma-tree/bin"
printf '#!/bin/sh\necho hi\n' > "$SRC/lzma-tree/bin/hi.sh"
python3 - <<'PYEOF'
import os
src = os.environ['SRC']
with open(os.path.join(src, "lzma-tree", "data.txt"), "w") as f:
    for _ in range(1000):
        f.write("OpenRG cramfs-lzma data line\n")
PYEOF

# single-file tree
mkdir -p "$SRC/single"
printf 'one file' > "$SRC/single/only.txt"

# device table (util-linux devtable format: name type mode uid gid major minor start inc count)
mkdir -p "$SRC/tree/dev"
cat > "$WORK/devtable.txt" <<'DEVEOF'
/dev/console c 600 0 0 5 1 0 0 -
/dev/mem     c 640 0 0 1 1 0 0 -
/dev/null    c 666 0 0 1 3 0 0 -
/dev/sda     b 640 0 0 8 0 0 0 -
/dev/fifo    p 644 0 0 0 0 0 0 -
DEVEOF

# ── Generate ────────────────────────────────────────────────────────────────
docker run --rm -i --user "$(id -u):$(id -g)" \
    -v "$SRC:/src:ro" -v "$WORK:/work" -v "$OUT_DIR:/out" "$IMAGE" bash -s <<'INNER'
set -euo pipefail
UL=/usr/sbin/mkfs.cramfs
NP=/opt/cramfs-tools/mkcramfs
LZ=/opt/mkcramfs-lzma/mkcramfs-lzma

# Group A: util-linux mkfs.cramfs 2.39
$UL /src/tree            /out/synth-ul-le-tree.cramfs
cramfsswap /out/synth-ul-le-tree.cramfs /out/synth-ul-be-tree.cramfs
$UL -z /src/tree         /out/synth-ul-holes.cramfs
$UL /src/incompressible  /out/synth-ul-incompressible.cramfs
$UL /src/deep            /out/synth-ul-deep.cramfs
$UL /src/empty           /out/synth-ul-empty.cramfs

# Group B: npitre cramfs-tools mkcramfs (2018)
$NP /src/tree            /out/synth-np-tree.cramfs
$NP -B /src/tree         /out/synth-np-be.cramfs
$NP -z /src/tree         /out/synth-np-holes.cramfs
$NP /src/incompressible  /out/synth-np-incompressible.cramfs
$NP -D /work/devtable.txt /src/tree /out/synth-np-devices.cramfs
$NP -p /src/tree         /out/synth-np-padded-512.cramfs
$NP -x /src/tree         /out/synth-np-extptr.cramfs

# Group C: OpenRG mkcramfs-lzma (Actiontec MI424WR GPL toolchain).
# The OpenRG tool encodes block size and compression method in the
# superblock flags; -c lzma is the classic router configuration.
$LZ -c lzma -b 4096  /src/lzma-tree /out/synth-lzma-4k.cramfs
$LZ -c lzma -b 16384 /src/lzma-tree /out/synth-lzma-16k.cramfs
$LZ -c lzma -b 32768 /src/lzma-tree /out/synth-lzma-32k.cramfs
$LZ -c lzma -b 65536 /src/lzma-tree /out/synth-lzma-64k.cramfs
$LZ -c gzip -b 65536 /src/lzma-tree /out/synth-openrg-gzip-64k.cramfs
$LZ -c none -b 65536 /src/lzma-tree /out/synth-openrg-none-64k.cramfs
INNER

# ── Group D: flag/layout variants (python patches of the util-linux tree) ───
python3 - "$OUT_DIR" <<'PYEOF'
import struct, sys
out = sys.argv[1]

def rd(path):
    return open(path, 'rb').read()

def wr(path, data):
    open(path, 'wb').write(data)

# 15. wrong-signature: set WRONG_SIGNATURE and mangle the signature string
d = bytearray(rd(f"{out}/synth-ul-le-tree.cramfs"))
d[8:12] = struct.pack('<I', 0x3 | 0x200)
d[16:32] = b'Nope, not ROMFS!'
wr(f"{out}/synth-wrong-signature.cramfs", bytes(d))

# 16. unsorted dirs: clear SORTED_DIRS
d = bytearray(rd(f"{out}/synth-ul-le-tree.cramfs"))
flags = struct.unpack('<I', d[8:12])[0]
d[8:12] = struct.pack('<I', flags & ~0x2)
wr(f"{out}/synth-unsorted.cramfs", bytes(d))

# 17. fsid v1: clear FSID_V2 and zero the size field (classic kernels used 1<<28)
d = bytearray(rd(f"{out}/synth-ul-le-tree.cramfs"))
flags = struct.unpack('<I', d[8:12])[0]
d[8:12] = struct.pack('<I', flags & ~0x1)
d[4:8] = struct.pack('<I', 0)
wr(f"{out}/synth-fsid-v1.cramfs", bytes(d))

# 18. shifted-512: 512-byte boot prefix, all offsets +512, SHIFTED_ROOT_OFFSET flag
src = bytearray(rd(f"{out}/synth-ul-le-tree.cramfs"))
flags = struct.unpack('<I', src[8:12])[0]
src[8:12] = struct.pack('<I', flags | 0x400)

def inode(off):
    w0, w1, w2 = struct.unpack('<III', src[off:off+12])
    mode = w0 & 0xffff
    size = w1 & 0xffffff
    nl = w2 & 0x3f
    of = (w2 >> 6) & 0x3ffffff
    return mode, size, nl, of

def patch_inode(off, new_of):
    w0, w1, w2 = struct.unpack('<III', src[off:off+12])
    nl = w2 & 0x3f
    src[off+8:off+12] = struct.pack('<I', nl | (new_of << 6))

def walk_dir(off, size):
    end = off + size
    while off + 12 <= end:
        mode, sz, nl, of = inode(off)
        nb = nl * 4
        if nb == 0:
            break
        ftype = mode & 0xf000
        new_of = of + 128  # +512 bytes in /4 units
        patch_inode(off, new_of)
        if ftype == 0x4000:  # dir: patch children
            walk_dir(of * 4, sz)
        elif ftype in (0x8000, 0xa000) and sz > 0:
            # patch block pointer table entries (+512 bytes each)
            maxblock = (sz + 4095) // 4096
            table = of * 4
            for i in range(maxblock):
                v = struct.unpack('<I', src[table+4*i:table+4+4*i])[0]
                flags_bits = v & 0xc0000000
                v = (v & ~0xc0000000) + 512
                src[table+4*i:table+4+4*i] = struct.pack('<I', v | flags_bits)
        off += 12 + nb

# root inode at 64
rootmode, rootsize, rootnl, rootof = inode(64)
walk_dir(rootof * 4, rootsize)
patch_inode(64, rootof + 128)

d = bytearray(512 + len(src))
d[512:] = src
# fix the size field to cover the prefix
struct.pack_into('<I', d, 512 + 4, len(d))
wr(f"{out}/synth-shifted-512.cramfs", d)
print('shifted-512 written, len', len(d))
PYEOF

docker rmi -f "$IMAGE" >/dev/null 2>&1 || true

echo "Synthetic samples written to $OUT_DIR:"
ls -la "$OUT_DIR"
