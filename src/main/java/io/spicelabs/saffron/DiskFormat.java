/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * Enumeration of supported virtual machine disk image formats.
 *
 * <p>Each format has associated magic bytes for detection, file extensions,
 * and MIME types. The {@link #detect(Path)} method provides automatic format
 * detection based on file content.
 *
 * <p>Aligned with Baharat's {@code PackageFormat} pattern.
 */
public enum DiskFormat {

    /**
     * QEMU Copy-On-Write version 2/3 format.
     * Magic: "QFI\xfb" (0x514649fb big-endian)
     */
    QCOW2("application/x-qcow2", ".qcow2", new byte[]{0x51, 0x46, 0x49, (byte) 0xfb}),

    /**
     * VMware Virtual Machine Disk format.
     * Magic: "KDMV" (0x564d444b little-endian) for sparse, or text descriptor
     */
    VMDK("application/x-vmdk", ".vmdk", new byte[]{0x4b, 0x44, 0x4d, 0x56}),

    /**
     * Microsoft Virtual Hard Disk (legacy) format.
     * Magic: "conectix" at end of file (footer)
     */
    VHD("application/x-vhd", ".vhd", "conectix".getBytes()),

    /**
     * Microsoft Virtual Hard Disk Extended format.
     * Magic: "vhdxfile" at start of file
     */
    VHDX("application/x-vhdx", ".vhdx", "vhdxfile".getBytes()),

    /**
     * VirtualBox Virtual Disk Image format.
     * Magic: signature at offset 0x40
     */
    VDI("application/x-vdi", ".vdi", new byte[]{0x7f, 0x10, (byte) 0xda, (byte) 0xbe}),

    /**
     * RAW disk image format.
     * No magic bytes - detected by extension or exclusion.
     */
    RAW("application/octet-stream", ".raw", new byte[]{}),

    /**
     * Google Cloud Platform disk image format.
     * A tar.gz archive containing disk.raw.
     * Magic: gzip magic 0x1f 0x8b
     */
    GCP("application/gzip", ".tar.gz", new byte[]{0x1f, (byte) 0x8b}),

    /**
     * Amazon Machine Image bundle format.
     * Directory containing manifest.xml and image.part.* files.
     * Detected by manifest file presence.
     */
    AMI("application/x-ami", ".manifest.xml", new byte[]{});

    private final String mimeType;
    private final String extension;
    private final byte[] magic;

    DiskFormat(@NotNull String mimeType, @NotNull String extension, byte[] magic) {
        this.mimeType = mimeType;
        this.extension = extension;
        this.magic = magic;
    }

    /**
     * Returns the MIME type for this disk format.
     *
     * @return the MIME type string
     */
    public @NotNull String mimeType() {
        return mimeType;
    }

    /**
     * Returns the primary file extension for this disk format.
     *
     * @return the file extension including the leading dot
     */
    public @NotNull String extension() {
        return extension;
    }

    /**
     * Returns the magic bytes used to identify this format.
     *
     * @return a defensive copy of the magic bytes
     */
    public byte[] magic() {
        return Arrays.copyOf(magic, magic.length);
    }

    /**
     * Returns the family/vendor of this disk format.
     *
     * @return the format family
     */
    public @NotNull Family family() {
        return switch (this) {
            case QCOW2 -> Family.QEMU;
            case VMDK -> Family.VMWARE;
            case VHD, VHDX -> Family.MICROSOFT;
            case VDI -> Family.ORACLE;
            case RAW -> Family.GENERIC;
            case GCP -> Family.GOOGLE;
            case AMI -> Family.AMAZON;
        };
    }

    /**
     * Attempts to detect the disk format from a file path.
     *
     * <p>Detection uses a multi-strategy approach:
     * <ol>
     *   <li>Magic byte signature detection</li>
     *   <li>File extension fallback</li>
     * </ol>
     *
     * @param path the path to the disk image file
     * @return an Optional containing the detected format, or empty if unknown
     * @throws IOException if the file cannot be read
     */
    public static @NotNull Optional<DiskFormat> detect(@NotNull Path path) throws IOException {
        // Read header bytes for magic detection
        byte[] header = new byte[16];
        try (var is = Files.newInputStream(path)) {
            int read = is.read(header);
            if (read < 4) {
                return Optional.empty();
            }
        }

        // Try magic byte detection. Note: gzip magic no longer maps to GCP here.
        Optional<DiskFormat> byMagic = detect(header);
        if (byMagic.isPresent()) {
            return byMagic;
        }

        // GCP is detected by extension rather than magic because a .gz file could
        // be a compressed single payload, a raw disk image, or a GCP tar archive.
        String lower = path.getFileName().toString().toLowerCase();
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return Optional.of(GCP);
        }
        // Compressed raw disk images are still raw disks, handled by openRaw().
        if (lower.endsWith(".img.gz") || lower.endsWith(".raw.gz")) {
            return Optional.of(RAW);
        }

        // VHD has magic at end of file - special case
        long size = Files.size(path);
        if (size >= 512) {
            byte[] footer = new byte[8];
            try (var raf = Files.newByteChannel(path)) {
                raf.position(size - 512);
                var buf = java.nio.ByteBuffer.wrap(footer);
                raf.read(buf);
            }
            if (Arrays.equals(footer, VHD.magic)) {
                return Optional.of(VHD);
            }
        }

        // Fallback to extension
        return detectByExtension(path.getFileName().toString());
    }

    /**
     * Attempts to detect the disk format from magic bytes.
     *
     * @param magic the first bytes of the file
     * @return an Optional containing the detected format, or empty if unknown
     */
    public static @NotNull Optional<DiskFormat> detect(byte[] magic) {
        if (magic == null || magic.length < 4) {
            return Optional.empty();
        }

        // QCOW2: "QFI\xfb"
        if (magic[0] == 0x51 && magic[1] == 0x46 && magic[2] == 0x49 && magic[3] == (byte) 0xfb) {
            return Optional.of(QCOW2);
        }

        // VMDK sparse: "KDMV"
        if (magic[0] == 0x4b && magic[1] == 0x44 && magic[2] == 0x4d && magic[3] == 0x56) {
            return Optional.of(VMDK);
        }

        // VHDX: "vhdxfile"
        if (magic.length >= 8 && new String(magic, 0, 8).equals("vhdxfile")) {
            return Optional.of(VHDX);
        }

        // GCP is intentionally NOT detected by gzip magic alone. A .gz file is a
        // compressed single payload (ContainerFormat.COMPRESSED_SINGLE), not a GCP
        // disk image. GCP is detected from the .tar.gz / .tgz extension in
        // detect(Path) and detectByExtension().

        // VDI: check signature at offset - need more bytes
        // This is a simplified check; full implementation needs offset 0x40

        return Optional.empty();
    }

    /**
     * Attempts to detect the disk format from a file extension.
     *
     * @param filename the filename or extension
     * @return an Optional containing the detected format, or empty if unknown
     */
    public static @NotNull Optional<DiskFormat> detectByExtension(@NotNull String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".qcow2") || lower.endsWith(".qcow")) {
            return Optional.of(QCOW2);
        }
        if (lower.endsWith(".vmdk")) {
            return Optional.of(VMDK);
        }
        if (lower.endsWith(".vhd")) {
            return Optional.of(VHD);
        }
        if (lower.endsWith(".vhdx")) {
            return Optional.of(VHDX);
        }
        if (lower.endsWith(".vdi")) {
            return Optional.of(VDI);
        }
        if (lower.endsWith(".raw") || lower.endsWith(".img") || lower.endsWith(".dmg")
                || lower.endsWith(".squashfs")) {
            return Optional.of(RAW);
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return Optional.of(GCP);
        }
        if (lower.endsWith(".img.gz") || lower.endsWith(".raw.gz")) {
            return Optional.of(RAW);
        }
        if (lower.endsWith(".manifest.xml")) {
            return Optional.of(AMI);
        }
        return Optional.empty();
    }

    /**
     * Format family classification (like Baharat's PackageFormat.Family).
     */
    public enum Family {
        /** QEMU/KVM formats */
        QEMU,
        /** VMware formats */
        VMWARE,
        /** Microsoft Hyper-V formats */
        MICROSOFT,
        /** Oracle VirtualBox formats */
        ORACLE,
        /** Generic/unformatted */
        GENERIC,
        /** Google Cloud Platform */
        GOOGLE,
        /** Amazon Web Services */
        AMAZON
    }
}
