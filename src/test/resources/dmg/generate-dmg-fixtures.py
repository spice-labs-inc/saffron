#!/usr/bin/env python3
"""Generate synthetic DMG fixtures for Saffron unit tests."""
import os
import struct
import uuid

OUT_DIR = os.path.dirname(os.path.abspath(__file__))

KOLY_SIGNATURE = b"koly"
FOOTER_SIZE = 512
VERSION = 4


def make_footer(data_fork_offset=0, data_fork_length=0, header_size=FOOTER_SIZE,
                version=VERSION, signature=KOLY_SIGNATURE):
    footer = bytearray(FOOTER_SIZE)
    # All fields big-endian.
    footer[0:4] = signature
    struct.pack_into(">I", footer, 4, version)
    struct.pack_into(">I", footer, 8, header_size)
    struct.pack_into(">I", footer, 12, 0)  # flags
    struct.pack_into(">Q", footer, 16, 0)  # RunningDataForkOffset
    struct.pack_into(">Q", footer, 24, data_fork_offset)
    struct.pack_into(">Q", footer, 32, data_fork_length)
    struct.pack_into(">Q", footer, 40, 0)  # RsrcForkOffset
    struct.pack_into(">Q", footer, 48, 0)  # RsrcForkLength
    struct.pack_into(">I", footer, 56, 1)  # SegmentNumber
    struct.pack_into(">I", footer, 60, 1)  # SegmentCount
    struct.pack_into(">16s", footer, 64, uuid.uuid4().bytes)
    # Data checksum type/size at 80/84, checksum array 88-215: leave 0
    struct.pack_into(">Q", footer, 216, 0)  # XMLOffset
    struct.pack_into(">Q", footer, 224, 0)  # XMLLength
    # Reserved1 232-351: leave 0
    # Checksum type/size at 352/356, checksum 360-487: leave 0
    struct.pack_into(">I", footer, 488, 1)  # ImageVariant
    struct.pack_into(">Q", footer, 492, data_fork_length // 512)  # SectorCount
    struct.pack_into(">I", footer, 500, 0)  # reserved2
    struct.pack_into(">I", footer, 504, 0)  # reserved3
    struct.pack_into(">I", footer, 508, 0)  # reserved4
    return footer


def write(name, data):
    path = os.path.join(OUT_DIR, name)
    with open(path, "wb") as f:
        f.write(data)
    print(f"wrote {path} ({len(data)} bytes)")


def main():
    data_fork = bytes(4096)
    write("valid.dmg", data_fork + make_footer(data_fork_length=len(data_fork)))
    write("empty.dmg", make_footer(data_fork_length=0))
    write("truncated-footer.dmg", data_fork[:300])
    write("missing-koly.dmg", data_fork + make_footer(data_fork_length=len(data_fork), signature=b"nope"))

    footer_with_trailing = make_footer(data_fork_length=len(data_fork))
    write("footer-not-at-end.dmg", data_fork + footer_with_trailing + b"EXTRA")

    write("invalid-header-size.dmg", data_fork + make_footer(data_fork_length=len(data_fork), header_size=256))

    # data fork length larger than the space before the footer
    write("data-fork-beyond-source.dmg", data_fork + make_footer(data_fork_length=len(data_fork) + 1000))


if __name__ == "__main__":
    main()
