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

import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.filesystem.squashfs.SquashfsSuperblock;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Stream-friendly probe that answers "is this a Saffron-supported artifact?"
 * from caller-read byte ranges, mirroring {@link DiskReader#isSupported(java.nio.file.Path)}
 * without any file access.
 *
 * <p>Callers (e.g. Goat Rodeo) read a bounded prefix (and, optionally, a
 * bounded suffix) from their stream and pass those bytes. This class performs
 * <em>no</em> file I/O, opening, seeking, or stat-ing: all signature knowledge
 * (byte offsets, footers, superblocks, extension fallbacks) lives here in
 * Saffron.
 *
 * <p>This class is deterministic and pure: the same inputs always yield the
 * same result, caller-supplied byte arrays are never mutated, and it is
 * thread-safe.
 */
public final class SaffronProbe {

    private SaffronProbe() {
        // Static utility class
    }

    /** Minimum prefix length callers should read (first bytes of the artifact).
     *  Covers all head magics, the partition-table probe at offset 512, and the
     *  btrfs superblock (at offset 65536 + 4096 bytes). Chosen to be well under
     *  1 MiB so the probe stays cheap. */
    public static final int MIN_PREFIX = 131072;

    /** Minimum suffix length callers should read (last bytes of the artifact).
     *  Covers the VHD footer ("conectix" at footer offset 0) and the DMG
     *  footer ("koly"). */
    public static final int MIN_SUFFIX = 512;

    /** Disk format head magics. */
    private static final byte[] MAGIC_QCOW2 = {0x51, 0x46, 0x49, (byte) 0xfb};
    private static final byte[] MAGIC_VMDK = {'K', 'D', 'M', 'V'};
    private static final byte[] MAGIC_VHDX = "vhdxfile".getBytes();
    private static final byte[] MAGIC_VDI_TEXT = "<<< Oracle VM VirtualBox Disk Image >>>".getBytes();
    private static final byte[] MAGIC_GZIP = {0x1f, (byte) 0x8b};
    private static final byte[] MAGIC_VHD_FOOTER = "conectix".getBytes();

    /** Raw disk partition-table signatures. */
    private static final byte[] MAGIC_GPT = "EFI PART".getBytes();
    private static final byte[] MAGIC_MBR_SIGNATURE = {0x55, (byte) 0xaa};

    /** Container signatures. */
    private static final byte[] MAGIC_DTB = {(byte) 0xd0, 0x0d, (byte) 0xfe, (byte) 0xed};

    /**
     * The detected kind of artifact.
     */
    public enum Kind {
        DISK_QCOW2, DISK_VMDK, DISK_VHD, DISK_VHDX, DISK_VDI,
        DISK_RAW, DISK_AMI, DISK_GZIP_WRAPPED_RAW,
        CONTAINER_ELF, CONTAINER_FIT_IMAGE, CONTAINER_DTB,
        CONTAINER_LINUX_KERNEL, CONTAINER_RPI_FIRMWARE,
        CONTAINER_ANDROID_BOOT, CONTAINER_COMPRESSED_SINGLE,
        CONTAINER_WIM, CONTAINER_DMG,
        FILESYSTEM_EXT, FILESYSTEM_FAT, FILESYSTEM_EXFAT, FILESYSTEM_NTFS,
        FILESYSTEM_XFS, FILESYSTEM_BTRFS, FILESYSTEM_SQUASHFS,
        FILESYSTEM_HFSPLUS, FILESYSTEM_APFS, FILESYSTEM_SWAP,
        NONE
    }

    /**
     * A detected kind.
     *
     * <p>The record is intentionally anemic so format-specific metadata (e.g.
     * a gzip inner format) can be added later without a breaking change.
     *
     * @param kind the detected kind
     */
    public record Result(@NotNull Kind kind) {
        /** Convenience sentinel for a non-match. */
        public static final Result NONE = new Result(Kind.NONE);
    }

    /**
     * Probes an artifact from byte ranges.
     *
     * <p>No file access is performed. The prefix and suffix are treated as
     * opaque caller-read bytes and are never mutated.
     *
     * <p>This method never throws (R3): any malformed input, null range, or
     * unexpected byte sequence returns {@link Optional#empty()}.
     *
     * @param prefix first bytes of the artifact (may be shorter than
     *        {@link #MIN_PREFIX}; {@code null} yields an empty result)
     * @param suffix last bytes of the artifact (may be {@code null} or empty;
     *        needed only for VHD/DMG footer detection)
     * @param fileName the artifact's file name, for extension fallbacks
     *        ({@code null} disables extension fallbacks)
     * @return the detected kind, or {@link Optional#empty()} when the artifact
     *         is not Saffron-supported
     */
    public static @NotNull Optional<Result> detect(
            byte[] prefix, byte[] suffix, String fileName) {
        if (prefix == null) {
            return Optional.empty();
        }
        byte[] tail = suffix == null ? new byte[0] : suffix;
        String name = fileName == null ? "" : fileName;

        // "Never throws" contract (R3): malformed input is reported as a
        // non-match. Exception scope is deliberate -- we must NOT swallow
        // Errors (OOM, ThreadDeath, StackOverflow), which can leave the
        // process corrupt; only recoverable Exception types are folded.
        try {
            return probe(prefix, tail, name);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static @NotNull Optional<Result> probe(byte[] prefix, byte[] suffix, String name) {
        // 1. Disk format head magics.
        if (magicAt(prefix, 0, MAGIC_QCOW2)) {
            return of(Kind.DISK_QCOW2);
        }
        if (magicAt(prefix, 0, MAGIC_VMDK)) {
            return of(Kind.DISK_VMDK);
        }
        if (magicAt(prefix, 0, MAGIC_VHDX)) {
            return of(Kind.DISK_VHDX);
        }
        if (magicAt(prefix, 0, MAGIC_VDI_TEXT)) {
            return of(Kind.DISK_VDI);
        }
        // Gzip-wrapped raw disk images carry gzip magic and a raw-disk-style name.
        if (magicAt(prefix, 0, MAGIC_GZIP) && isGzipWrappedRawName(name)) {
            return of(Kind.DISK_GZIP_WRAPPED_RAW);
        }

        // 2. VHD footer ("conectix" at the start of the trailing footer).
        if (magicAt(suffix, 0, MAGIC_VHD_FOOTER)) {
            return of(Kind.DISK_VHD);
        }

        // 3. Binary containers. Reuse the prefix-based detector for all
        //    head-magic containers (ELF, Linux kernel, DTB/FIT, RPi firmware,
        //    Android boot, WIM, compressed single).
        Optional<ContainerFormat> container = ContainerDetector.detect(ByteBuffer.wrap(prefix));
        Kind containerKind = toContainerKind(container);
        if (containerKind != null) {
            return of(containerKind);
        }
        // Large DTB/FIT blobs whose structure block exceeds the prefix are still
        // identifiable by their device-tree magic; report the coarse DTB kind.
        if (magicAt(prefix, 0, MAGIC_DTB)) {
            return of(Kind.CONTAINER_DTB);
        }
        // DMG is a footer format; detect it from the trailing suffix.
        if (ContainerDetector.isDmgFooterMagic(ByteBuffer.wrap(suffix))) {
            return of(Kind.CONTAINER_DMG);
        }

        // 4. Bare filesystem images (superblock magic in the prefix). Run before
        //    the raw-GPT/MBR heuristic so bare FAT/NTFS/exFAT images are not
        //    shadowed by their 55 AA boot signature.
        // Squashfs is detected by magic only: its superblock validates the
        // bytes_used field against the full artifact size, which a prefix probe
        // cannot know.
        if (SquashfsSuperblock.isSquashfsMagic(ByteBuffer.wrap(prefix))) {
            return of(Kind.FILESYSTEM_SQUASHFS);
        }
        Kind fsKind;
        try {
            fsKind = toFilesystemKind(FilesystemDetector.detect(new PrefixRegion(prefix)));
        } catch (IOException e) {
            fsKind = null;
        }
        if (fsKind != null) {
            return of(fsKind);
        }

        // 5. Raw disk partition-table signatures (whole-disk images).
        if (magicAt(prefix, 512, MAGIC_GPT) || magicAt(prefix, 510, MAGIC_MBR_SIGNATURE)) {
            return of(Kind.DISK_RAW);
        }

        // 6. Extension fallbacks, mirroring DiskFormat.detectByExtension.
        Kind extKind = byExtension(name);
        if (extKind != null) {
            return of(extKind);
        }

        return Optional.empty();
    }

    private static boolean isGzipWrappedRawName(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".img.gz") || lower.endsWith(".raw.gz")
                || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    private static @Nullable Kind byExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".qcow2") || lower.endsWith(".qcow")) {
            return Kind.DISK_QCOW2;
        }
        if (lower.endsWith(".vmdk")) {
            return Kind.DISK_VMDK;
        }
        if (lower.endsWith(".vhd")) {
            return Kind.DISK_VHD;
        }
        if (lower.endsWith(".vhdx")) {
            return Kind.DISK_VHDX;
        }
        if (lower.endsWith(".vdi")) {
            return Kind.DISK_VDI;
        }
        if (lower.endsWith(".raw") || lower.endsWith(".img") || lower.endsWith(".dmg")
                || lower.endsWith(".squashfs")) {
            return Kind.DISK_RAW;
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".img.gz") || lower.endsWith(".raw.gz")) {
            // Note: the Kind taxonomy has no GCP; tar.gz/tgz map to the
            // gzip-wrapped-raw kind purely to preserve parity presence.
            return Kind.DISK_GZIP_WRAPPED_RAW;
        }
        if (lower.endsWith(".manifest.xml")) {
            return Kind.DISK_AMI;
        }
        return null;
    }

    private static @Nullable Kind toContainerKind(Optional<ContainerFormat> container) {
        if (container.isEmpty()) {
            return null;
        }
        return switch (container.get()) {
            case ELF -> Kind.CONTAINER_ELF;
            case FIT_IMAGE -> Kind.CONTAINER_FIT_IMAGE;
            case DTB -> Kind.CONTAINER_DTB;
            case LINUX_KERNEL -> Kind.CONTAINER_LINUX_KERNEL;
            case RPI_FIRMWARE -> Kind.CONTAINER_RPI_FIRMWARE;
            case ANDROID_BOOT -> Kind.CONTAINER_ANDROID_BOOT;
            case COMPRESSED_SINGLE -> Kind.CONTAINER_COMPRESSED_SINGLE;
            case WIM -> Kind.CONTAINER_WIM;
            case DMG -> Kind.CONTAINER_DMG;
            case UNKNOWN -> null;
        };
    }

    private static @Nullable Kind toFilesystemKind(Optional<FilesystemInfo> fs) {
        if (fs.isEmpty()) {
            return null;
        }
        return switch (fs.get().type()) {
            case EXT4 -> Kind.FILESYSTEM_EXT;
            case FAT32 -> Kind.FILESYSTEM_FAT;
            case EXFAT -> Kind.FILESYSTEM_EXFAT;
            case NTFS -> Kind.FILESYSTEM_NTFS;
            case XFS -> Kind.FILESYSTEM_XFS;
            case BTRFS -> Kind.FILESYSTEM_BTRFS;
            case SQUASHFS -> Kind.FILESYSTEM_SQUASHFS;
            case HFS_PLUS -> Kind.FILESYSTEM_HFSPLUS;
            case APFS -> Kind.FILESYSTEM_APFS;
            case SWAP -> Kind.FILESYSTEM_SWAP;
            default -> null;
        };
    }

    /**
     * Bounds-safe magic comparison. Returns false for any out-of-bounds or
     * too-short input rather than throwing.
     */
    private static boolean magicAt(byte[] data, int offset, byte[] magic) {
        if (data == null || offset < 0 || magic == null || offset + magic.length > data.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull Optional<Result> of(Kind kind) {
        return Optional.of(new Result(kind));
    }

    /**
     * An in-memory {@link DiskRegion} over the caller-supplied prefix, used to
     * reuse {@link FilesystemDetector}'s superblock probes. Size and read
     * bounds are hard-tied to the prefix length (never to attacker-controlled
     * header fields), so allocation is bounded.
     */
    private static final class PrefixRegion implements DiskRegion {
        private final byte[] data;

        PrefixRegion(byte[] data) {
            this.data = data;
        }

        @Override
        public @NotNull ByteBuffer read(long offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IOException("Read out of bounds: offset=" + offset + " length=" + length
                        + " size=" + data.length);
            }
            byte[] copy = new byte[length];
            System.arraycopy(data, (int) offset, copy, 0, length);
            return ByteBuffer.wrap(copy);
        }

        @Override
        public long size() {
            return data.length;
        }
    }
}