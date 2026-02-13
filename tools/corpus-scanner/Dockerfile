FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    libguestfs-tools \
    python3-guestfs \
    python3 \
    linux-image-generic \
    # Filesystem tools for full support
    ntfs-3g \
    exfatprogs \
    xfsprogs \
    btrfs-progs \
    hfsprogs \
    apfsprogs \
    e2fsprogs \
    dosfstools \
    # QEMU for disk image formats
    qemu-utils \
    && rm -rf /var/lib/apt/lists/*

# libguestfs needs a kernel to boot its appliance
ENV LIBGUESTFS_BACKEND=direct

COPY scan_corpus.py /opt/scan_corpus.py
RUN chmod +x /opt/scan_corpus.py

WORKDIR /corpus

ENTRYPOINT ["python3", "/opt/scan_corpus.py"]
