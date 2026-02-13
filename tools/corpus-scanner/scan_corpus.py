#!/usr/bin/env python3
"""
Corpus scanner: discovers filesystems in VM disk images and generates
ground truth JSON for Saffron verification tests.

For each VM image, this script:
1. Opens the image with libguestfs
2. Discovers all filesystems
3. For each filesystem: counts all regular files
4. Selects 20 random files per filesystem and computes SHA256
5. Writes a JSON verification file

Usage:
    python3 scan_corpus.py /corpus /output [--image <specific-image>]
"""

import guestfs
import hashlib
import json
import os
import random
import sys
import traceback
from pathlib import Path


def scan_image(image_path: str) -> dict:
    """Scan a single VM disk image and return verification data."""
    basename = os.path.basename(image_path)
    print(f"\n{'='*70}")
    print(f"Scanning: {basename}")
    print(f"{'='*70}")

    g = guestfs.GuestFS(python_return_dict=True)

    # Determine format from extension
    ext = os.path.splitext(image_path)[1].lower()
    fmt_map = {
        '.qcow2': 'qcow2',
        '.vdi': 'vdi',
        '.vhd': 'vpc',
        '.vhdx': 'vhdx',
        '.vmdk': 'vmdk',
        '.raw': 'raw',
        '.img': 'raw',
        '.dmg': 'raw',  # libguestfs handles DMG as raw
    }
    fmt = fmt_map.get(ext, 'auto')

    g.add_drive_opts(image_path, readonly=True, format=fmt)
    g.launch()

    # Discover all filesystems
    filesystems = {}
    try:
        filesystems = g.list_filesystems()
    except Exception as e:
        print(f"  Error listing filesystems: {e}")
        g.close()
        return {"error": str(e), "imageBasename": basename,
                "imagePath": make_container_path(image_path)}

    print(f"  Found {len(filesystems)} filesystem(s): {filesystems}")

    # Filter to only mountable, real filesystems
    # Skip swap, unknown, LVM PVs that aren't actual filesystems
    mountable_fs = {}
    for dev, fstype in filesystems.items():
        if fstype in ('swap', 'unknown', '', 'LVM2_member', 'crypto_LUKS'):
            print(f"  Skipping {dev} ({fstype})")
            continue
        mountable_fs[dev] = fstype

    if not mountable_fs:
        print(f"  No mountable filesystems found")
        g.close()
        return {"error": "No mountable filesystems",
                "imageBasename": basename,
                "imagePath": make_container_path(image_path)}

    print(f"  Mountable filesystems: {len(mountable_fs)}")

    # Scan each filesystem
    fs_results = []
    for dev, fstype in sorted(mountable_fs.items()):
        print(f"\n  --- Filesystem: {dev} ({fstype}) ---")
        fs_data = scan_filesystem(g, dev, fstype)
        if fs_data is not None:
            fs_results.append(fs_data)

    g.close()

    # Compute totals
    total_files = sum(fs["fileCount"] for fs in fs_results)
    total_dirs = sum(fs["directoryCount"] for fs in fs_results)

    result = {
        "imagePath": make_container_path(image_path),
        "imageBasename": basename,
        "filesystemCount": len(fs_results),
        "totalFiles": total_files,
        "totalDirectories": total_dirs,
        "filesystems": fs_results,
    }

    print(f"\n  TOTAL: {len(fs_results)} filesystems, {total_files} files, {total_dirs} dirs")
    return result


def scan_filesystem(g, device: str, fstype: str) -> dict | None:
    """Scan a single filesystem and return its verification data."""
    try:
        g.mount_ro(device, "/")
    except Exception as e:
        print(f"    Failed to mount {device}: {e}")
        return None

    try:
        all_files = []
        all_dirs = []

        # Walk the entire filesystem
        try:
            entries = walk_filesystem(g, "/")
            all_files = entries["files"]
            all_dirs = entries["dirs"]
        except Exception as e:
            print(f"    Error walking filesystem: {e}")
            traceback.print_exc()
            g.umount_all()
            return None

        file_count = len(all_files)
        dir_count = len(all_dirs)
        print(f"    Files: {file_count}, Directories: {dir_count}")

        # Select up to 20 random files for SHA256 verification
        sample_files = select_sample_files(g, all_files, count=20)

        result = {
            "device": device,
            "fstype": fstype,
            "fileCount": file_count,
            "directoryCount": dir_count,
            "sampleFiles": sample_files,
        }

        return result

    finally:
        try:
            g.umount_all()
        except Exception:
            pass


def walk_filesystem(g, root: str) -> dict:
    """Walk the entire filesystem tree and collect files and directories."""
    files = []
    dirs = []
    stack = [root]

    while stack:
        current = stack.pop()

        try:
            entries = g.readdir(current)
        except Exception as e:
            # Permission denied or other errors - skip this directory
            continue

        for entry in entries:
            name = entry["name"]
            ftype = entry["ftyp"]

            if name in (".", ".."):
                continue

            if current == "/":
                full_path = "/" + name
            else:
                full_path = current + "/" + name

            if ftype == "d":  # directory
                dirs.append(full_path)
                stack.append(full_path)
            elif ftype == "r":  # regular file
                files.append(full_path)
            # Skip symlinks, block/char devices, pipes, sockets, etc.

    return {"files": files, "dirs": dirs}


def select_sample_files(g, all_files: list, count: int = 20) -> list:
    """Select random files and compute their SHA256 hashes."""
    if not all_files:
        return []

    # Use a deterministic seed based on the sorted file list for reproducibility
    seed = hashlib.md5("\n".join(sorted(all_files)).encode()).hexdigest()
    rng = random.Random(seed)

    # Filter to files we can actually read (skip very large files > 256MB)
    candidates = list(all_files)
    rng.shuffle(candidates)

    samples = []
    for path in candidates:
        if len(samples) >= count:
            break

        try:
            stat = g.statns(path)
            size = stat["st_size"]

            # Skip empty files and very large files
            if size == 0 or size > 256 * 1024 * 1024:
                continue

            # Read file and compute SHA256
            content = g.read_file(path)
            sha256 = hashlib.sha256(content).hexdigest()

            samples.append({
                "path": path,
                "size": size,
                "sha256": sha256,
            })

            print(f"    Sample: {path} ({size} bytes, sha256={sha256[:16]}...)")

        except Exception as e:
            # Skip files we can't read
            continue

    return samples


def make_container_path(host_path: str) -> str:
    """Convert host path to container-relative path for JSON."""
    # Inside Docker, corpus is mounted at /corpus
    # The JSON stores paths relative to /corpus
    if host_path.startswith("/corpus/"):
        return host_path
    # If running outside Docker, strip the corpus base
    return "/corpus/" + os.path.basename(host_path)


def json_filename(image_basename: str) -> str:
    """Generate JSON filename from image basename."""
    # Replace dots, dashes, spaces with underscores
    name = image_basename
    for ch in ".-() ":
        name = name.replace(ch, "_")
    # Remove double underscores
    while "__" in name:
        name = name.replace("__", "_")
    return name + ".json"


def find_all_images(corpus_dir: str) -> list:
    """Find all VM disk images in the corpus directory."""
    extensions = {'.qcow2', '.vdi', '.vhd', '.vhdx', '.vmdk', '.raw', '.img', '.dmg'}
    images = []

    for root, dirs, files in os.walk(corpus_dir):
        for f in sorted(files):
            ext = os.path.splitext(f)[1].lower()
            if ext in extensions:
                images.append(os.path.join(root, f))

    return sorted(images)


def main():
    if len(sys.argv) < 3:
        print("Usage: scan_corpus.py <corpus-dir> <output-dir> [--image <specific-image>]")
        sys.exit(1)

    corpus_dir = sys.argv[1]
    output_dir = sys.argv[2]
    specific_image = None

    if "--image" in sys.argv:
        idx = sys.argv.index("--image")
        if idx + 1 < len(sys.argv):
            specific_image = sys.argv[idx + 1]

    os.makedirs(output_dir, exist_ok=True)

    if specific_image:
        images = [os.path.join(corpus_dir, specific_image)]
    else:
        images = find_all_images(corpus_dir)

    print(f"Found {len(images)} images to scan")

    results = []
    errors = []

    for image_path in images:
        if not os.path.exists(image_path):
            print(f"Image not found: {image_path}")
            errors.append(image_path)
            continue

        try:
            result = scan_image(image_path)
            basename = os.path.basename(image_path)
            out_file = os.path.join(output_dir, json_filename(basename))

            with open(out_file, "w") as f:
                json.dump(result, f, indent=2)

            print(f"  Written: {out_file}")
            results.append(basename)

        except Exception as e:
            print(f"  FAILED: {image_path}: {e}")
            traceback.print_exc()
            errors.append(image_path)

    print(f"\n{'='*70}")
    print(f"SCAN COMPLETE")
    print(f"{'='*70}")
    print(f"Scanned: {len(results)} images")
    print(f"Errors:  {len(errors)} images")
    if errors:
        print("Failed images:")
        for e in errors:
            print(f"  - {e}")


if __name__ == "__main__":
    main()
