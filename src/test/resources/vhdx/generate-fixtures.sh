#!/usr/bin/env bash
# generate-fixtures.sh — one-time VHDX fixture generation via Docker.
#
# The tests never invoke external processes (project invariant): this script
# generates the committed fixtures under src/test/resources/vhdx/fixtures/
# once, using the reference qemu-img / qemu-io from qemu-utils in a throwaway
# Docker container. The generated files preserve the invoking user's uid/gid.
#
# Fixtures:
#   chunk-ratio-512.vhdx   dynamic, 512-byte logical sectors, 1 MiB blocks,
#                          5 GiB virtual. One 1 MiB block of 0xAB written at
#                          4608 MiB (past the first 4 GiB BAT chunk) and one
#                          block of 0xCD at 1 MiB. Exercises the interleaved
#                          sector-bitmap BAT entries (VHDX spec 2.5).
#
# Usage:
#   src/test/resources/vhdx/generate-fixtures.sh
#
# Prerequisites: docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixtures"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$FIXTURE_DIR"

# ── Build a throwaway image with qemu-utils installed ────────────────────────
IMAGE="saffron-vhdx-fixture:latest"
docker build -q -t "$IMAGE" -f - "$WORK" <<'EOF2'
FROM debian:bookworm
RUN apt-get update -qq && apt-get install -y -qq qemu-utils && rm -rf /var/lib/apt/lists/*
EOF2

# ── Generate ─────────────────────────────────────────────────────────────────
docker run --rm --user "$(id -u):$(id -g)" -v "$FIXTURE_DIR:/out" "$IMAGE" sh -ec '
qemu-img --version | head -1
rm -f /out/chunk-ratio-512.vhdx
qemu-img create -q -f vhdx -o subformat=dynamic,block_size=1M /out/chunk-ratio-512.vhdx 5G
qemu-io -f vhdx -c "write -q -P 0xcd 1M 1M" -c "write -q -P 0xab 4608M 1M" /out/chunk-ratio-512.vhdx
qemu-img info /out/chunk-ratio-512.vhdx
'

ls -l "$FIXTURE_DIR"
