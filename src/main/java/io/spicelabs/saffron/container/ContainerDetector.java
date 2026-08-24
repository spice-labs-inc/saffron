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
package io.spicelabs.saffron.container;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.android.AndroidBootContainerFactory;
import io.spicelabs.saffron.container.compressed.CompressedSingleContainerFactory;
import io.spicelabs.saffron.container.devicetree.DeviceTreeBlob;
import io.spicelabs.saffron.container.elf.ElfContainer;
import io.spicelabs.saffron.container.linuxkernel.LinuxKernelContainerFactory;
import io.spicelabs.saffron.container.rpi.RpiFirmwareContainerFactory;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Detects binary container formats from files, disks, or buffers.
 */
public final class ContainerDetector {

    private static final int LINUX_KERNEL_HEADER_BYTES = 512;
    private static final int RPI_FIRMWARE_PROBE_BYTES = 0x201; // 513 bytes: 512 padding + probe at 0x200
    private static final int ANDROID_BOOT_PROBE_BYTES = 2048;
    private static final int DTB_HEADER_SIZE = 40;
    private static final int DTB_MAGIC = 0xd00d_feed;
    private static final int WIM_HEADER_SIZE = 208;
    private static final byte @NotNull [] WIM_MAGIC = {0x4d, 0x53, 0x57, 0x49, 0x4d, 0x00, 0x00, 0x00};
    private static final int DMG_FOOTER_SIZE = 512;
    private static final byte @NotNull [] DMG_MAGIC = {0x6b, 0x6f, 0x6c, 0x79}; // "koly"

    private ContainerDetector() {
        // Static utility class
    }

    /**
     * Detects a container format from a file path.
     *
     * @param path the path to examine
     * @return the detected format, or empty if none
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<ContainerFormat> detect(@NotNull Path path) throws IOException {
        long size = Files.size(path);
        if (size < 2) {
            return Optional.empty();
        }
        // Read enough bytes for ELF, Linux-kernel, RPi firmware, and Android boot detection.
        // DTB/FIT need the whole source, so they are tried after reading the full file.
        int firstRead = (int) Math.min(Math.max(Math.max(LINUX_KERNEL_HEADER_BYTES, RPI_FIRMWARE_PROBE_BYTES), ANDROID_BOOT_PROBE_BYTES), size);
        byte[] header = new byte[firstRead];
        try (var is = Files.newInputStream(path)) {
            if (is.read(header) != firstRead) {
                return Optional.empty();
            }
        }
        ByteBuffer headerBuffer = ByteBuffer.wrap(header);

        // Compressed single payloads: check first so that a plain .gz/.xz/.bz2 file is
        // not misclassified as a gzip-compressed Linux kernel image. Path-based detection
        // excludes tar-in-compression archives and compressed disk images.
        if (CompressedSingleContainerFactory.looksLikeCompressedSingle(path, headerBuffer, size)) {
            return Optional.of(ContainerFormat.COMPRESSED_SINGLE);
        }

        Optional<ContainerFormat> format = detectInternal(headerBuffer, size);
        if (format.isPresent()) {
            return format;
        }

        // WIM has a header magic in the first 208 bytes.
        Optional<ContainerFormat> wim = tryDetectWim(headerBuffer, size);
        if (wim.isPresent()) {
            return wim;
        }
        // DMG has a footer magic; read it from the end of the file.
        Optional<ContainerFormat> dmg = tryDetectDmg(path, size);
        if (dmg.isPresent()) {
            return dmg;
        }

        // Filename-based detection for RPi firmware (fixup.dat requires a filename).
        if (RpiFirmwareContainerFactory.looksLikeRpiFirmware(path, headerBuffer, size)) {
            return Optional.of(ContainerFormat.RPI_FIRMWARE);
        }
        if (size <= Integer.MAX_VALUE) {
            byte[] all = Files.readAllBytes(path);
            Optional<ContainerFormat> dtb = tryDetectDtb(ByteBuffer.wrap(all), size);
            if (dtb.isPresent()) {
                return dtb;
            }
        }
        return Optional.empty();
    }

    /**
     * Detects a container format from a virtual disk.
     *
     * <p>Linux kernel detection needs only the first 512 bytes. DTB/FIT detection
     * reads the whole disk because the structure block is required to tell a FIT
     * from a plain DTB.</p>
     *
     * @param disk the virtual disk to examine
     * @return the detected format, or empty if none
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<ContainerFormat> detect(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size < 4) {
            return Optional.empty();
        }
        int firstRead = (int) Math.min(Math.max(Math.max(LINUX_KERNEL_HEADER_BYTES, RPI_FIRMWARE_PROBE_BYTES), ANDROID_BOOT_PROBE_BYTES), size);
        ByteBuffer header = disk.read(0, firstRead);

        // Compressed single payloads: check first so that a plain .gz/.xz/.bz2 disk
        // is not misclassified as a gzip-compressed Linux kernel image.
        if (CompressedSingleContainerFactory.looksLikeCompressedSingle(disk)) {
            return Optional.of(ContainerFormat.COMPRESSED_SINGLE);
        }

        Optional<ContainerFormat> elf = tryDetectElf(header, size);
        if (elf.isPresent()) {
            return elf;
        }

        Optional<ContainerFormat> kernel = detectLinuxKernel(header, size);
        if (kernel.isPresent()) {
            return kernel;
        }

        if (size <= Integer.MAX_VALUE && isDtbHeader(header)) {
            Optional<DeviceTreeBlob> blob = DeviceTreeBlob.parse(disk);
            return classifyDtb(blob);
        }

        Optional<ContainerFormat> rpi = tryDetectRpiFirmware(header, size);
        if (rpi.isPresent()) {
            return rpi;
        }

        Optional<ContainerFormat> android = tryDetectAndroidBoot(header, size);
        if (android.isPresent()) {
            return android;
        }

        Optional<ContainerFormat> wim = tryDetectWim(header, size);
        if (wim.isPresent()) {
            return wim;
        }

        return tryDetectDmg(disk, size);
    }

    /**
     * Detects a container format from a byte buffer.
     *
     * @param buffer the buffer to examine; position must be 0
     * @return the detected format, or empty if none
     */
    public static @NotNull Optional<ContainerFormat> detect(@NotNull ByteBuffer buffer) {
        long sourceSize = buffer.remaining();
        if (CompressedSingleContainerFactory.looksLikeCompressedSingle(buffer, sourceSize)) {
            return Optional.of(ContainerFormat.COMPRESSED_SINGLE);
        }
        Optional<ContainerFormat> format = detectInternal(buffer, sourceSize);
        if (format.isPresent()) {
            return format;
        }
        Optional<ContainerFormat> wim = tryDetectWim(buffer, sourceSize);
        if (wim.isPresent()) {
            return wim;
        }
        return tryDetectDmg(buffer, sourceSize);
    }

    private static @NotNull Optional<ContainerFormat> detectInternal(@NotNull ByteBuffer buffer, long sourceSize) {
        Optional<ContainerFormat> elf = tryDetectElf(buffer, sourceSize);
        if (elf.isPresent()) {
            return elf;
        }

        Optional<ContainerFormat> kernel = detectLinuxKernel(buffer, sourceSize);
        if (kernel.isPresent()) {
            return kernel;
        }

        Optional<ContainerFormat> dtb = tryDetectDtb(buffer, sourceSize);
        if (dtb.isPresent()) {
            return dtb;
        }

        Optional<ContainerFormat> rpi = tryDetectRpiFirmware(buffer, sourceSize);
        if (rpi.isPresent()) {
            return rpi;
        }

        Optional<ContainerFormat> android = tryDetectAndroidBoot(buffer, sourceSize);
        if (android.isPresent()) {
            return android;
        }

        return Optional.empty();
    }

    private static @NotNull Optional<ContainerFormat> detectLinuxKernel(@NotNull ByteBuffer buffer, long sourceSize) {
        if (buffer.remaining() < LINUX_KERNEL_HEADER_BYTES) {
            return Optional.empty();
        }
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (LinuxKernelContainerFactory.looksLikeLinuxKernel(buffer, sourceSize)) {
                return Optional.of(ContainerFormat.LINUX_KERNEL);
            }
            return Optional.empty();
        } finally {
            buffer.order(originalOrder);
        }
    }

    private static @NotNull Optional<ContainerFormat> tryDetectDtb(@NotNull ByteBuffer buffer, long sourceSize) {
        if (sourceSize < DTB_HEADER_SIZE || buffer.remaining() < DTB_HEADER_SIZE) {
            return Optional.empty();
        }
        if (!isDtbHeader(buffer)) {
            return Optional.empty();
        }
        Optional<DeviceTreeBlob> blob = DeviceTreeBlob.parse(buffer);
        return classifyDtb(blob);
    }

    private static boolean isDtbHeader(@NotNull ByteBuffer buffer) {
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.BIG_ENDIAN);
            return buffer.remaining() >= 4 && buffer.getInt(0) == DTB_MAGIC;
        } finally {
            buffer.order(originalOrder);
        }
    }

    private static @NotNull Optional<ContainerFormat> tryDetectElf(@NotNull ByteBuffer buffer, long sourceSize) {
        if (ElfContainer.isElf(buffer, sourceSize)) {
            return Optional.of(ContainerFormat.ELF);
        }
        return Optional.empty();
    }

    private static @NotNull Optional<ContainerFormat> tryDetectRpiFirmware(@NotNull ByteBuffer buffer, long sourceSize) {
        if (RpiFirmwareContainerFactory.looksLikeRpiFirmware(buffer, sourceSize)) {
            return Optional.of(ContainerFormat.RPI_FIRMWARE);
        }
        return Optional.empty();
    }

    private static @NotNull Optional<ContainerFormat> tryDetectAndroidBoot(@NotNull ByteBuffer buffer, long sourceSize) {
        if (AndroidBootContainerFactory.looksLikeAndroidBoot(buffer, sourceSize)) {
            return Optional.of(ContainerFormat.ANDROID_BOOT);
        }
        return Optional.empty();
    }

    private static @NotNull Optional<ContainerFormat> classifyDtb(@NotNull Optional<DeviceTreeBlob> blob) {
        if (blob.isEmpty()) {
            return Optional.empty();
        }
        if (blob.get().root().child("images").isPresent()) {
            return Optional.of(ContainerFormat.FIT_IMAGE);
        }
        return Optional.of(ContainerFormat.DTB);
    }

    private static @NotNull Optional<ContainerFormat> tryDetectWim(@NotNull ByteBuffer buffer, long sourceSize) {
        if (sourceSize < WIM_HEADER_SIZE || buffer.remaining() < WIM_HEADER_SIZE) {
            return Optional.empty();
        }
        if (!isWimMagic(buffer)) {
            return Optional.empty();
        }
        ByteOrder original = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int headerSize = buffer.getInt(8);
            if (headerSize < WIM_HEADER_SIZE || headerSize > sourceSize) {
                return Optional.empty();
            }
            int version = buffer.getInt(12);
            if (version == 0) {
                return Optional.empty();
            }
            int imageCount = buffer.getInt(44);
            if (imageCount < 0) {
                return Optional.empty();
            }
            return Optional.of(ContainerFormat.WIM);
        } finally {
            buffer.order(original);
        }
    }

    private static boolean isWimMagic(@NotNull ByteBuffer buffer) {
        if (buffer.remaining() < WIM_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < WIM_MAGIC.length; i++) {
            if (buffer.get(i) != WIM_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull Optional<ContainerFormat> tryDetectDmg(@NotNull ByteBuffer buffer, long sourceSize) {
        if (sourceSize < DMG_FOOTER_SIZE) {
            return Optional.empty();
        }
        ByteBuffer footer = buffer.duplicate();
        footer.position((int) (sourceSize - DMG_FOOTER_SIZE));
        footer.limit((int) sourceSize);
        return detectDmgFooter(footer.slice(), sourceSize);
    }

    private static @NotNull Optional<ContainerFormat> tryDetectDmg(@NotNull Path path, long sourceSize) {
        if (sourceSize < DMG_FOOTER_SIZE) {
            return Optional.empty();
        }
        byte[] footer = new byte[DMG_FOOTER_SIZE];
        try (var channel = Files.newByteChannel(path, java.nio.file.StandardOpenOption.READ)) {
            channel.position(sourceSize - DMG_FOOTER_SIZE);
            if (channel.read(ByteBuffer.wrap(footer)) != DMG_FOOTER_SIZE) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return detectDmgFooter(ByteBuffer.wrap(footer).order(ByteOrder.BIG_ENDIAN), sourceSize);
    }

    private static @NotNull Optional<ContainerFormat> tryDetectDmg(@NotNull VirtualDisk disk, long sourceSize) {
        if (sourceSize < DMG_FOOTER_SIZE) {
            return Optional.empty();
        }
        try {
            ByteBuffer footer = disk.read(sourceSize - DMG_FOOTER_SIZE, DMG_FOOTER_SIZE);
            return detectDmgFooter(footer, sourceSize);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static @NotNull Optional<ContainerFormat> detectDmgFooter(@NotNull ByteBuffer footer, long sourceSize) {
        ByteBuffer buffer = footer.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < DMG_FOOTER_SIZE) {
            return Optional.empty();
        }
        if (!isDmgMagic(buffer)) {
            return Optional.empty();
        }
        int version = buffer.getInt(4);
        if (version <= 0) {
            return Optional.empty();
        }
        int headerSize = buffer.getInt(8);
        if (headerSize != DMG_FOOTER_SIZE) {
            return Optional.empty();
        }
        long dataForkOffset = buffer.getLong(24);
        long dataForkLength = buffer.getLong(32);
        long rsrcForkOffset = buffer.getLong(40);
        long rsrcForkLength = buffer.getLong(48);
        long xmlOffset = buffer.getLong(216);
        long xmlLength = buffer.getLong(224);
        if (dataForkOffset < 0 || dataForkLength < 0 || rsrcForkOffset < 0 || rsrcForkLength < 0
                || xmlOffset < 0 || xmlLength < 0) {
            return Optional.empty();
        }
        long maxPayload = sourceSize - DMG_FOOTER_SIZE;
        if (!isDmgRegionValid(dataForkOffset, dataForkLength, maxPayload)
                || !isDmgRegionValid(rsrcForkOffset, rsrcForkLength, maxPayload)
                || !isDmgRegionValid(xmlOffset, xmlLength, maxPayload)) {
            return Optional.empty();
        }
        return Optional.of(ContainerFormat.DMG);
    }

    private static boolean isDmgRegionValid(long offset, long length, long maxPayload) {
        if (offset == 0 && length == 0) {
            return true;
        }
        long end;
        try {
            end = Math.addExact(offset, length);
        } catch (ArithmeticException e) {
            return false;
        }
        return end <= maxPayload;
    }

    private static boolean isDmgMagic(@NotNull ByteBuffer buffer) {
        if (buffer.remaining() < DMG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < DMG_MAGIC.length; i++) {
            if (buffer.get(i) != DMG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether the given buffer begins with the DMG footer magic
     * {@code "koly"}. This is a lightweight, presence-only check intended for
     * stream-based probing where only the trailing {@link #DMG_FOOTER_SIZE}
     * bytes of an artifact are available; it does not validate the footer's
     * region table (no total artifact size is available).
     *
     * @param footer the trailing bytes of the artifact
     * @return true if the buffer starts with the DMG footer magic
     */
    public static boolean isDmgFooterMagic(@NotNull ByteBuffer footer) {
        if (footer == null) {
            return false;
        }
        return isDmgMagic(footer.duplicate());
    }
}
