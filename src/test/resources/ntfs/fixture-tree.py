#!/usr/bin/env python3
"""Helper for generate-fixtures.sh: builds the deterministic source trees,
populates a mounted ntfs-3g volume from them, and writes the expectations
JSON from the *source tree* (never by reading the NTFS image back).

Runs inside the Docker container. Subcommands:

  build    <variant> <workdir>              write <workdir>/src + manifest.json
  populate <variant> <workdir> <mountpoint> copy the tree onto the mounted volume
  expect   <variant> <workdir> <image> <mkntfs-opts> <mount-opts> <label> <out.json>

All content is derived from fixed strings (SHA-256 counter streams for
incompressible data, numbered text lines for compressible data), so the
trees, and therefore the expectations, are identical on every run.
"""
import errno
import hashlib
import json
import os
import struct
import subprocess
import sys

KIB = 1024
MIB = 1024 * 1024


# ── deterministic content ─────────────────────────────────────────────────────

def prand(label, size):
    """Incompressible bytes: SHA-256 counter stream keyed by label."""
    out = bytearray()
    i = 0
    while len(out) < size:
        out += hashlib.sha256(f"{label}:{i}".encode()).digest()
        i += 1
    return bytes(out[:size])


def text(label, size):
    """Compressible bytes: numbered lines of prose."""
    out = bytearray()
    i = 0
    while len(out) < size:
        out += (f"{label} line {i:07d}: the quick brown fox jumps over the lazy dog; "
                f"pack my box with five dozen liquor jugs.\n").encode()
        i += 1
    return bytes(out[:size])


def symlink_reparse(print_name, subst_name, relative):
    """IO_REPARSE_TAG_SYMLINK buffer (MS-FSCC 2.1.2.4), as accepted by the
    ntfs-3g system.ntfs_reparse_data xattr."""
    p = print_name.encode("utf-16-le")
    s = subst_name.encode("utf-16-le")
    body = struct.pack("<HHHHI", 0, len(s), len(s), len(p), 1 if relative else 0) + s + p
    return struct.pack("<IHH", 0xA000000C, len(body), 0) + body


# ── variant specifications ───────────────────────────────────────────────────
# Each spec is a list of items:
#   ("dir", path)
#   ("file", path, bytes)
#   ("hardlink", path, existing_path)
#   ("symlink", path, {print, subst, relative, isdir, target, resolvesTo})
#   ("ads", path, stream_name, bytes)              # path must already exist
#   ("sparse", path, logical_size, [(offset, bytes), ...])
#   ("compressed_dir", path)                       # marks the dir; files inherit
# Fragmented has an extra pre-population phase (see populate_fragmented).

def spec_basic():
    items = [("dir", "docs"), ("dir", "docs/nested"), ("dir", "docs/nested/l1"),
             ("dir", "docs/nested/l1/l2"), ("dir", "docs/nested/l1/l2/l3"),
             ("dir", "docs/nested/l1/l2/l3/l4"), ("dir", "docs/nested/l1/l2/l3/l4/l5"),
             ("dir", "big"), ("dir", "bigdir"), ("dir", "unicode"), ("dir", "unicode/Ünïcödé dir"),
             ("dir", "longname"), ("dir", "sizes"), ("dir", "small")]
    items.append(("file", "readme.txt", b"Saffron NTFS fixture: basic-4k\n"))
    items.append(("file", "empty.txt", b""))
    items.append(("file", "docs/nested/l1/l2/l3/l4/l5/deep.txt", b"five levels down\n"))
    items.append(("file", "big/large-1mib.bin", prand("basic-large", MIB + 4321)))
    items.append(("file", "big/text-200k.txt", text("basic-text", 200 * KIB)))
    for i in range(150):
        items.append(("file", f"bigdir/entry-{i:03d}.txt", f"bigdir entry {i}\n".encode()))
    items.append(("file", "unicode/café.txt", "café au lait\n".encode()))
    items.append(("file", "unicode/日本語.txt", "日本語のテキスト\n".encode()))
    items.append(("file", "unicode/naïve résumé.txt", b"accents\n"))
    items.append(("file", "unicode/emoji-\U0001F600.txt", b"astral plane name\n"))
    items.append(("file", "unicode/Ünïcödé dir/файл.txt", "кириллица\n".encode()))
    name255 = ("abcdefghij" * 25) + "12345"          # exactly 255 characters
    assert len(name255) == 255
    items.append(("file", "longname/" + name255, b"file whose name is 255 characters long\n"))
    for n in (1, 100, 600, 700, 800, 1000, 4095, 4096, 4097, 12345, 65536, 65537):
        items.append(("file", f"sizes/s{n}.bin", prand(f"basic-size-{n}", n)))
    items.append(("file", "sizes/s0.bin", b""))
    for d in range(5):
        items.append(("dir", f"small/d{d}"))
        for f in range(8):
            items.append(("file", f"small/d{d}/f{f}.txt", f"small file {d}/{f}\n".encode()))
    return items


def spec_compressed():
    items = [("dir", "comp"), ("compressed_dir", "comp"), ("dir", "plain")]
    items.append(("file", "comp/text-300k.txt", text("comp-300k", 300 * KIB)))
    items.append(("file", "comp/random-200k.bin", prand("comp-random", 200 * KIB)))
    items.append(("file", "comp/mixed.bin",
                  text("comp-mixed-a", 128 * KIB) + prand("comp-mixed-b", 64 * KIB)
                  + text("comp-mixed-c", 96 * KIB)))
    items.append(("file", "comp/small-10k.txt", text("comp-small", 10 * KIB)))
    items.append(("file", "comp/tiny.txt", b"tiny resident file inside a compressed directory\n"))
    items.append(("file", "comp/zeros-128k.bin", b"\0" * (128 * KIB)))
    items.append(("file", "comp/text-64k.txt", text("comp-64k", 64 * KIB)))
    items.append(("file", "comp/text-65537.txt", text("comp-65537", 64 * KIB + 1)))
    items.append(("file", "comp/zeros-then-text.bin", b"\0" * (64 * KIB) + text("comp-zt", 20 * KIB)))
    items.append(("file", "plain/notcompressed.txt", text("plain", 70 * KIB)))
    return items


def spec_sparse_and_ads():
    items = [("dir", "sparse"), ("dir", "ads"), ("dir", "ads/dir-with-stream")]
    items.append(("sparse", "sparse/holes-8m.bin", 8 * MIB, [
        (64 * KIB, prand("sparse-a", 4096)),
        (MIB + 512, prand("sparse-b", 12345)),
        (8 * MIB - 100, prand("sparse-c", 100)),
    ]))
    items.append(("sparse", "sparse/holes-small.bin", 64 * KIB, [(20000, prand("sparse-d", 100))]))
    items.append(("sparse", "sparse/leading-data.bin", MIB, [(0, prand("sparse-e", 16 * KIB))]))
    items.append(("file", "ads/two-streams.txt", b"main stream of two-streams.txt\n"))
    items.append(("ads", "ads/two-streams.txt", "first", b"stream one content\n"))
    items.append(("ads", "ads/two-streams.txt", "second", text("ads-second", 5000)))
    items.append(("file", "ads/only-ads.txt", b""))
    items.append(("ads", "ads/only-ads.txt", "hidden", prand("ads-hidden", 60000)))
    items.append(("file", "ads/dir-with-stream/inside.txt", b"file inside directory with an ADS\n"))
    items.append(("ads", "ads/dir-with-stream", "dirstream", b"directory stream content\n"))
    items.append(("file", "ads/plain.txt", b"no streams here\n"))
    return items


def spec_links():
    items = [("dir", "links"), ("dir", "other"), ("dir", "hard"), ("dir", "hard/sub")]
    items.append(("file", "links/target.txt", b"target file in links/\n"))
    items.append(("file", "other/o.txt", b"target file in other/\n"))
    items.append(("file", "other/inside.txt", b"another file in other/\n"))
    items.append(("symlink", "links/rel-file.txt",
                  dict(print="target.txt", subst="target.txt", relative=True, isdir=False,
                       target="target.txt", resolvesTo="/links/target.txt")))
    items.append(("symlink", "links/rel-updir.txt",
                  dict(print="..\\other\\o.txt", subst="..\\other\\o.txt", relative=True, isdir=False,
                       target="../other/o.txt", resolvesTo="/other/o.txt")))
    items.append(("symlink", "links/rel-dir",
                  dict(print="..\\other", subst="..\\other", relative=True, isdir=True,
                       target="../other", resolvesTo="/other")))
    items.append(("symlink", "links/abs-file.txt",
                  dict(print="C:\\other\\o.txt", subst="\\??\\C:\\other\\o.txt", relative=False,
                       isdir=False, target="C:/other/o.txt", resolvesTo="/other/o.txt")))
    items.append(("file", "hard/a.txt", b"hard-linked pair\n"))
    items.append(("hardlink", "hard/b.txt", "hard/a.txt"))
    items.append(("file", "hard/one.txt", text("hard-three", 9000)))
    items.append(("hardlink", "hard/sub/two.txt", "hard/one.txt"))
    items.append(("hardlink", "other/three.txt", "hard/one.txt"))
    items.append(("file", "hard/single.txt", b"only one name\n"))
    return items


FRAG_FILLERS = 1024      # 4 KiB files written first (one cluster each)
FRAG_KEEP_EVERY = 8      # fillers kept in the final image: 0, 8, 16, ...
FRAG_TINY = 2200         # resident files so $MFT grows into the fragmented free space


def filler_bytes(i):
    return prand(f"frag-fill-{i}", 4096)


def spec_fragmented():
    items = [("dir", "fill"), ("dir", "tiny")]
    for i in range(0, FRAG_FILLERS, FRAG_KEEP_EVERY):
        items.append(("file", f"fill/f{i:04d}.bin", filler_bytes(i)))
    items.append(("file", "big.bin", prand("frag-big", 2 * MIB)))
    items.append(("ads", "big.bin", "note", b"alternate stream on a heavily fragmented file\n"))
    for t in range(FRAG_TINY):
        items.append(("file", f"tiny/t{t:04d}.txt", f"tiny {t}\n".encode()))
    return items


def spec_cluster(cluster, label):
    items = [("dir", "many"), ("dir", "sub")]
    items.append(("file", "small.txt", f"resident file on a {label} volume\n".encode()))
    items.append(("file", "one-cluster-plus.bin", prand(f"{label}-c1", cluster + 1)))
    items.append(("file", "text-100k.txt", text(f"{label}-text", 100 * KIB)))
    items.append(("file", "sub/nested.txt", b"nested\n"))
    for i in range(120):
        items.append(("file", f"many/m{i:03d}.txt", f"many {i}\n".encode()))
    return items


def spec_sector_4k():
    items = [("dir", "dir"), ("dir", "dir/sub"), ("dir", "many"), ("dir", "unicode")]
    items.append(("file", "readme.txt", b"4096-byte sectors\n"))
    items.append(("file", "dir/sub/deep.txt", b"deep\n"))
    items.append(("file", "data-100k.bin", prand("s4k-100k", 100 * KIB)))
    items.append(("file", "text-30k.txt", text("s4k-text", 30 * KIB)))
    items.append(("file", "unicode/café.txt", "café\n".encode()))
    items.append(("file", "empty.txt", b""))
    for i in range(120):
        items.append(("file", f"many/m{i:03d}.txt", f"many {i}\n".encode()))
    return items


VARIANTS = {
    "basic-4k": spec_basic,
    "compressed": spec_compressed,
    "sparse-and-ads": spec_sparse_and_ads,
    "links": spec_links,
    "fragmented": spec_fragmented,
    "cluster-64k": lambda: spec_cluster(64 * KIB, "cluster-64k"),
    "cluster-256k": lambda: spec_cluster(256 * KIB, "cluster-256k"),
    "sector-4k": spec_sector_4k,
}


# ── build: source tree + manifest ────────────────────────────────────────────

def build(variant, workdir):
    src = os.path.join(workdir, "src")
    os.makedirs(src, exist_ok=True)
    manifest = {"symlinks": [], "ads": [], "sparse": [], "compressedDirs": []}
    for item in VARIANTS[variant]():
        kind = item[0]
        if kind == "dir":
            os.makedirs(os.path.join(src, item[1]), exist_ok=True)
        elif kind == "file":
            with open(os.path.join(src, item[1]), "wb") as f:
                f.write(item[2])
        elif kind == "hardlink":
            os.link(os.path.join(src, item[2]), os.path.join(src, item[1]))
        elif kind == "symlink":
            manifest["symlinks"].append(dict(path=item[1], **item[2]))
        elif kind == "ads":
            side = os.path.join(workdir, "ads", item[1].replace("/", "__") + "__" + item[2])
            os.makedirs(os.path.dirname(side), exist_ok=True)
            with open(side, "wb") as f:
                f.write(item[3])
            manifest["ads"].append(dict(path=item[1], stream=item[2], side=side))
        elif kind == "sparse":
            # The source copy is a genuinely sparse file (seek + write) so that
            # hashing the tree yields the expected zero-filled content.
            with open(os.path.join(src, item[1]), "wb") as f:
                f.truncate(item[2])
                for off, data in item[3]:
                    f.seek(off)
                    f.write(data)
            manifest["sparse"].append(dict(path=item[1], size=item[2],
                                           islands=[[off, len(data)] for off, data in item[3]]))
        elif kind == "compressed_dir":
            manifest["compressedDirs"].append(item[1])
        else:
            raise ValueError(kind)
    with open(os.path.join(workdir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)


# ── populate: source tree -> mounted ntfs-3g volume ──────────────────────────

def run(*cmd):
    subprocess.run(cmd, check=True)


def setxattr(path, name, value):
    """Equivalent of `setfattr -n NAME -v 0x<hex> PATH`, but without the
    128 KiB per-argument limit of the command line (ADS can be larger)."""
    os.setxattr(path, name, value)


def populate_fragmented(mnt):
    """Fragment the free space before the generic copy.

    ntfs-3g's allocator is largest-free-extent-first and it alternates
    between its two data zones for consecutive new files, so a naive "write
    N files, delete every other one" leaves long free runs. Instead:
      1. write one-cluster fillers until ENOSPC (no large free extent left);
      2. delete every other *pair* (f2,f3, f6,f7, ...): in each zone that is
         every other cluster, i.e. single-cluster holes;
      3. write the 2 MiB file: it can only be built from those holes
         (hundreds of runs -> several $DATA pieces + $ATTRIBUTE_LIST);
      4. delete every remaining filler except f0000, f0008, ...: the free
         space is again single-cluster holes, into which $MFT then has to
         grow when the tiny files are created by the generic copy.
    Only fillers below FRAG_FILLERS can be kept, so the source tree (built
    before population) already lists the final set.
    """
    fill = os.path.join(mnt, "fill")
    os.makedirs(fill, exist_ok=True)
    n = 0
    while True:
        path = os.path.join(fill, f"f{n:04d}.bin")
        try:
            with open(path, "wb") as f:
                f.write(filler_bytes(n))
        except OSError as e:
            if e.errno != errno.ENOSPC:
                raise
            os.unlink(path)
            break
        n += 1
    print(f"  fragmented: {n} fillers written before ENOSPC")
    for i in range(n):
        if (i >> 1) & 1:
            os.unlink(os.path.join(fill, f"f{i:04d}.bin"))
    with open(os.path.join(mnt, "big.bin"), "wb") as f:
        f.write(prand("frag-big", 2 * MIB))
    for i in range(n):
        path = os.path.join(fill, f"f{i:04d}.bin")
        if os.path.exists(path) and not (i < FRAG_FILLERS and i % FRAG_KEEP_EVERY == 0):
            os.unlink(path)


def populate(variant, workdir, mnt):
    src = os.path.join(workdir, "src")
    with open(os.path.join(workdir, "manifest.json")) as f:
        manifest = json.load(f)
    sparse_paths = {s["path"] for s in manifest["sparse"]}

    if variant == "fragmented":
        populate_fragmented(mnt)

    # 1. directories (compressed ones flagged before anything is put inside)
    for root, dirs, _ in os.walk(src):
        for d in sorted(dirs):
            rel = os.path.relpath(os.path.join(root, d), src)
            os.makedirs(os.path.join(mnt, rel), exist_ok=True)
    for d in manifest["compressedDirs"]:
        # FILE_ATTRIBUTE_COMPRESSED, big-endian as the xattr name says
        setxattr(os.path.join(mnt, d), "system.ntfs_attrib_be", struct.pack(">I", 0x00000800))

    # 2. regular files (hard links recreated by inode)
    seen = {}
    for root, _, files in os.walk(src):
        for name in sorted(files):
            full = os.path.join(root, name)
            rel = os.path.relpath(full, src)
            dst = os.path.join(mnt, rel)
            if rel in sparse_paths or os.path.exists(dst):
                continue          # sparse handled below; fragmented pre-phase wrote it
            ino = os.stat(full).st_ino
            if ino in seen:
                os.link(seen[ino], dst)
                continue
            seen[ino] = dst
            with open(full, "rb") as i, open(dst, "wb") as o:
                o.write(i.read())

    # 3. sparse files: truncate to the logical size, dd the data islands in
    for s in manifest["sparse"]:
        dst = os.path.join(mnt, s["path"])
        run("truncate", "-s", str(s["size"]), dst)
        for off, length in s["islands"]:
            run("dd", f"if={os.path.join(src, s['path'])}", f"of={dst}", "bs=1",
                f"skip={off}", f"seek={off}", f"count={length}", "conv=notrunc", "status=none")

    # 4. alternate data streams (mount option streams_interface=xattr)
    for a in manifest["ads"]:
        with open(a["side"], "rb") as f:
            data = f.read()
        setxattr(os.path.join(mnt, a["path"]), "user." + a["stream"], data)

    # 5. Windows symlink reparse points (ln -s would give Interix symlinks)
    for l in manifest["symlinks"]:
        dst = os.path.join(mnt, l["path"])
        if l["isdir"]:
            os.makedirs(dst)
        else:
            open(dst, "wb").close()
        setxattr(dst, "system.ntfs_reparse_data",
                 symlink_reparse(l["print"], l["subst"], l["relative"]))


# ── expect: expectations JSON from the source tree ───────────────────────────

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def expect(variant, workdir, image, mkntfs_opts, mount_opts, label, out):
    src = os.path.join(workdir, "src")
    with open(os.path.join(workdir, "manifest.json")) as f:
        manifest = json.load(f)
    sparse_paths = {s["path"] for s in manifest["sparse"]}
    compressed_dirs = manifest["compressedDirs"]
    ads = {}
    for a in manifest["ads"]:
        with open(a["side"], "rb") as f:
            data = f.read()
        ads.setdefault(a["path"], {})[a["stream"]] = {
            "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    def under_compressed(rel):
        return any(rel == d or rel.startswith(d + "/") for d in compressed_dirs)

    entries = []
    inode_paths = {}
    for root, dirs, files in os.walk(src):
        for d in dirs:
            rel = os.path.relpath(os.path.join(root, d), src)
            e = {"path": "/" + rel, "type": "dir"}
            if rel in ads:
                e["streams"] = ads[rel]
            if under_compressed(rel):
                e["compressed"] = True
            entries.append(e)
        for name in files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, src)
            st = os.stat(full)
            e = {"path": "/" + rel, "type": "file", "size": st.st_size, "sha256": sha256_file(full)}
            if rel in ads:
                e["streams"] = ads[rel]
            if rel in sparse_paths:
                e["sparse"] = True
            if under_compressed(rel):
                e["compressed"] = True
            if st.st_nlink > 1:
                inode_paths.setdefault(st.st_ino, []).append(e)
            entries.append(e)
    for l in manifest["symlinks"]:
        entries.append({"path": "/" + l["path"], "type": "symlink", "target": l["target"],
                        "resolvesTo": l["resolvesTo"], "isDirectoryLink": l["isdir"]})
    groups = sorted(inode_paths.values(), key=lambda es: min(e["path"] for e in es))
    for n, es in enumerate(groups, 1):
        for e in es:
            e["hardlinkGroup"] = f"hl{n}"
    entries.sort(key=lambda e: e["path"])

    doc = {
        "id": f"ntfs/fixtures/{variant}",
        "image": image,
        "mkntfsOptions": mkntfs_opts,
        "mountOptions": mount_opts,
        "label": label,
        "fileCount": sum(1 for e in entries if e["type"] == "file"),
        "directoryCount": sum(1 for e in entries if e["type"] == "dir"),
        "symlinkCount": sum(1 for e in entries if e["type"] == "symlink"),
        "$comment": "directoryCount excludes the root directory; NTFS system files ($MFT etc.) "
                    "are excluded from every count. fileCount counts names, so a hard-linked "
                    "file counts once per name. sizes/sha256 come from the source tree.",
    }
    with open(out, "w", encoding="utf-8") as f:
        f.write("{\n")
        for k, v in doc.items():
            f.write(f"  {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)},\n")
        f.write('  "entries": [\n')
        for i, e in enumerate(entries):
            sep = "," if i < len(entries) - 1 else ""
            f.write("    " + json.dumps(e, ensure_ascii=False) + sep + "\n")
        f.write("  ]\n}\n")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    {"build": build, "populate": populate, "expect": expect}[cmd](*args)
