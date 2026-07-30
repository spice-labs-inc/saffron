#!/usr/bin/env python3
"""Generate a synthetic Android boot image (v2) for Saffron tests.

The fixture is intentionally tiny (under 10 KB) and contains synthetic kernel,
ramdisk, second stage, and DTB components. It follows the public Android boot
image header format used by AOSP mkbootimg.
"""
import hashlib
import os
import struct

PAGE_SIZE = 2048
HEADER_VERSION = 2
HEADER_SIZE = 1660
KERNEL_ADDR = 0x80008000
RAMDISK_ADDR = 0x81000000
SECOND_ADDR = 0x80f00000
DTB_ADDR = 0x82000000
TAGS_ADDR = 0x80000100

# Synthetic components. These are not real binaries; they are only used to
# exercise the parser's offset/size extraction.
KERNEL = b"\x00" * 1024 + b"KERNEL-PAYLOAD" + b"\x00" * (1024 - 14)
RAMDISK = b"RAMDISK-PAYLOAD" + b"\x00" * (1024 - 15)
SECOND = b"SECOND-STAGE-LOADER"
DTB = b"DTB-PAYLOAD" + b"\x00" * (256 - 11)


def pad(data, alignment):
    padding = (alignment - (len(data) % alignment)) % alignment
    return data + b"\x00" * padding


def write_boot_image(output_path):
    kernel_size = len(KERNEL)
    ramdisk_size = len(RAMDISK)
    second_size = len(SECOND)
    dtb_size = len(DTB)

    # SHA-1 id over components, matching AOSP mkbootimg.
    sha = hashlib.sha1()
    for component in (KERNEL, RAMDISK, SECOND, b"", DTB):
        sha.update(component)
        sha.update(struct.pack("<I", len(component)))
    img_id = sha.digest().ljust(32, b"\x00")
    assert len(img_id) == 32

    header = struct.pack("<8s", b"ANDROID!")
    header += struct.pack("<I", kernel_size)
    header += struct.pack("<I", KERNEL_ADDR)
    header += struct.pack("<I", ramdisk_size)
    header += struct.pack("<I", RAMDISK_ADDR)
    header += struct.pack("<I", second_size)
    header += struct.pack("<I", SECOND_ADDR)
    header += struct.pack("<I", TAGS_ADDR)
    header += struct.pack("<I", PAGE_SIZE)
    header += struct.pack("<I", HEADER_VERSION)
    header += struct.pack("<I", 0)  # os_version
    header += struct.pack("<16s", b"saffron\x00")
    header += struct.pack("<512s", b"console=ttyS0\x00")
    header += struct.pack("<32s", img_id)
    header += struct.pack("<1024s", b"\x00")
    header += struct.pack("<I", 0)  # recovery_dtbo_size
    header += struct.pack("<Q", 0)  # recovery_dtbo_offset
    header += struct.pack("<I", HEADER_SIZE)
    header += struct.pack("<I", dtb_size)
    header += struct.pack("<Q", DTB_ADDR)

    assert len(header) == HEADER_SIZE, f"header size {len(header)} != {HEADER_SIZE}"
    header = pad(header, PAGE_SIZE)

    image = header + pad(KERNEL, PAGE_SIZE) + pad(RAMDISK, PAGE_SIZE) + pad(SECOND, PAGE_SIZE) + pad(DTB, PAGE_SIZE)

    with open(output_path, "wb") as f:
        f.write(image)

    print(f"Generated {output_path}: {len(image)} bytes")


if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    output = os.path.join(script_dir, "boot.img")
    write_boot_image(output)
