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
package io.spicelabs.saffron.container.linuxkernel;

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.io.ChunkedDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Factory for opening Linux kernel images as binary containers.
 */
public final class LinuxKernelContainerFactory {

    private static final int UIMAGE_MAGIC = 0x2705_1956;
    private static final int ZIMAGE_MAGIC = 0x016f_2818;
    private static final int ARM64_IMAGE_MAGIC = 0x644d_5241; // "ARM\x64"
    private static final short X86_JUMP_SIGNATURE = (short) 0xAA55;
    private static final byte[] GZIP_MAGIC = {(byte) 0x1f, (byte) 0x8b};

    /**
     * Linux kernel image subtypes.
     */
    enum KernelType {
        BZIMAGE, ZIMAGE, IMAGE, GZIP_IMAGE, UIMAGE, UNKNOWN
    }

    private LinuxKernelContainerFactory() {
        // Static utility class
    }

    /**
     * Detects the Linux kernel image subtype from a header.
     *
     * @param data header bytes (usually the first 512 bytes)
     * @param sourceSize the full size of the source file or disk
     * @return the detected subtype, or {@link KernelType#UNKNOWN}
     */
    static @NotNull KernelType detectType(byte[] data, long sourceSize) {
        if (data.length < 4 || sourceSize < 512) {
            return KernelType.UNKNOWN;
        }

        // U-Boot uImage magic is stored big-endian.
        if ((data[0] & 0xFF) == 0x27
                && (data[1] & 0xFF) == 0x05
                && (data[2] & 0xFF) == 0x19
                && (data[3] & 0xFF) == 0x56) {
            return KernelType.UIMAGE;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // x86 bzImage: starts with "MZ" and has the 0xAA55 boot signature
        if (data.length >= 512
                && buf.getShort(0) == (short) 0x5A4D
                && buf.getShort(510) == X86_JUMP_SIGNATURE) {
            return KernelType.BZIMAGE;
        }

        // ARM32 zImage magic at offset 0x24
        if (data.length >= 0x28 && buf.getInt(0x24) == ZIMAGE_MAGIC) {
            return KernelType.ZIMAGE;
        }

        // ARM32 kernels for Raspberry Pi do not always have the zImage magic at
        // 0x24; they start with a branch instruction (bits 27-25 == 101b).
        // To avoid random false positives, require the branch target to stay
        // within the source bounds.
        int firstInstruction = buf.getInt(0);
        int instructionType = (firstInstruction >>> 25) & 0x7;
        if (instructionType == 0x5 && buf.getShort(0) != (short) 0x5A4D) {
            int offset = firstInstruction & 0x00FF_FFFF;
            long signedOffset = offset << 6 >> 6; // sign-extend 24 bits
            long branchTarget = 8 + (signedOffset << 2);
            if (branchTarget >= 0 && branchTarget < sourceSize) {
                return KernelType.ZIMAGE;
            }
        }

        // ARM64 Image magic at offset 0x38
        if (data.length >= 0x3C && buf.getInt(0x38) == ARM64_IMAGE_MAGIC) {
            return KernelType.IMAGE;
        }

        // gzip-compressed ARM64 Image (e.g., Raspberry Pi kernel8.img)
        if (data.length >= 2 && data[0] == GZIP_MAGIC[0] && data[1] == GZIP_MAGIC[1]) {
            return KernelType.GZIP_IMAGE;
        }

        return KernelType.UNKNOWN;
    }

    /**
     * Returns {@code true} if the buffer starts with a recognized Linux kernel
     * image header.
     *
     * @param buffer little-endian header buffer
     * @return true if the header looks like a Linux kernel image
     */
    public static boolean looksLikeLinuxKernel(@NotNull ByteBuffer buffer) {
        return looksLikeLinuxKernel(buffer, buffer.remaining());
    }

    /**
     * Returns {@code true} if the buffer starts with a recognized Linux kernel
     * image header.
     *
     * @param buffer little-endian header buffer
     * @param sourceSize the full size of the source file or disk
     * @return true if the header looks like a Linux kernel image
     */
    public static boolean looksLikeLinuxKernel(@NotNull ByteBuffer buffer, long sourceSize) {
        if (buffer.remaining() < 512 || sourceSize < 512) {
            return false;
        }
        byte[] header = new byte[Math.min(buffer.remaining(), 512)];
        ByteBuffer view = buffer.duplicate();
        view.get(header);
        return detectType(header, sourceSize) != KernelType.UNKNOWN;
    }

    /**
     * Attempts to open a virtual disk as a Linux kernel container.
     *
     * @param disk the virtual disk to examine
     * @return the container, or empty if the disk is not a supported kernel image
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size < 512 || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        ByteBuffer header = disk.read(0, (int) Math.min(size, 512));
        header.order(ByteOrder.LITTLE_ENDIAN);
        if (!looksLikeLinuxKernel(header, size)) {
            return Optional.empty();
        }

        // Bounded backing: the kernel is never read into memory as a whole.
        return Optional.of(new LinuxKernelContainer(new ChunkedDisk(disk)));
    }
}
