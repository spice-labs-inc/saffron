#!/usr/bin/env python3
"""Generate synthetic WIM fixtures for Saffron unit tests."""
import os
import struct

OUT_DIR = os.path.dirname(os.path.abspath(__file__))

WIM_MAGIC = b"MSWIM\x00\x00\x00"
HEADER_SIZE = 208
VERSION = 0x000D0100


def make_valid_header(image_count=1, flags=0):
    header = bytearray(HEADER_SIZE)
    header[0:8] = WIM_MAGIC
    struct.pack_into("<I", header, 8, HEADER_SIZE)
    struct.pack_into("<I", header, 12, VERSION)
    struct.pack_into("<I", header, 16, flags)
    # compressed file size at 20: leave 0
    # identifier GUID at 24: leave 0
    struct.pack_into("<H", header, 40, 1)  # segment number
    struct.pack_into("<H", header, 42, 1)  # number of segments
    struct.pack_into("<I", header, 44, image_count)
    # file resources at 48, 72, 96, 124: leave 0
    return header


def write(name, data):
    path = os.path.join(OUT_DIR, name)
    with open(path, "wb") as f:
        f.write(data)
    print(f"wrote {path} ({len(data)} bytes)")


def main():
    write("valid.wim", make_valid_header())
    write("two-images.wim", make_valid_header(image_count=2, flags=0x20000))
    write("truncated-magic.wim", WIM_MAGIC[:4])
    write("wrong-magic.wim", b"NOTWIM\x00\x00" + make_valid_header()[8:])
    write("truncated-header.wim", make_valid_header()[:100])

    mismatched = bytearray(make_valid_header())
    struct.pack_into("<I", mismatched, 8, 100)
    write("header-size-mismatch.wim", mismatched)

    write("source-smaller-than-header.wim", make_valid_header()[:200])


if __name__ == "__main__":
    main()
