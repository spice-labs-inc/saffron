#!/usr/bin/env bash
# generate-fixture.sh — one-time ext2 sparse-file fixture generation via Docker.
#
# The tests never invoke external processes (project invariant): this script
# generates the committed sparse.img once, using mke2fs from Alpine's
# e2fsprogs in a throwaway container. The image holds one file, /sparse.bin,
# whose holes exercise every way an indirect-block (ext2/ext3) inode can
# record one; Ext2SparseFileTest rebuilds the expected bytes from the same
# layout.
#
# Layout of /sparse.bin, in 1 KiB blocks (data blocks are filled with
# 1 + (block + offset) % 255, so no data block is all zeros):
#   0, 11            data; block 1 is a hole in the direct pointers
#   12-267           all holes: the single-indirect pointer is 0
#   268-523          all holes: the double-indirect block's first entry is 0
#   600, 700         data, with holes between them
#   1000             a partial data block of 300 bytes
#
# Usage:
#   src/test/resources/ext2/generate-fixture.sh
#
# Prerequisites: docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

docker run --rm -v "$SCRIPT_DIR:/out" alpine:3.22 sh -euc '
apk add -q e2fsprogs e2fsprogs-extra python3 >/dev/null
mkdir -p /work/root
python3 - <<EOF
BS = 1024
blocks = [0, 11, 600, 700]
tail_block, tail_len = 1000, 300
with open("/work/root/sparse.bin", "wb") as f:
    f.truncate(tail_block * BS + tail_len)
    for b in blocks + [tail_block]:
        n = tail_len if b == tail_block else BS
        f.seek(b * BS)
        f.write(bytes(1 + (b + i) % 255 for i in range(n)))
EOF
rm -f /out/sparse.img
E2FSPROGS_FAKE_TIME=1767225600 mke2fs -q -t ext2 -b 1024 -U clear \
    -E hash_seed=00000000-0000-0000-0000-000000000000 -d /work/root /out/sparse.img 2048
# The fixture is only useful if the file really is sparse.
debugfs -R "stat /sparse.bin" /out/sparse.img 2>/dev/null | grep -A2 "^BLOCKS:"
blocks=$(debugfs -R "stat /sparse.bin" /out/sparse.img 2>/dev/null | sed -n "s/^TOTAL: //p")
[ "$blocks" -lt 20 ] || { echo "sparse.bin is not sparse ($blocks blocks)" >&2; exit 1; }
'
