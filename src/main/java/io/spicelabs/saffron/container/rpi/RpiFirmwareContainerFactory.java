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
package io.spicelabs.saffron.container.rpi;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Detection helpers for Raspberry Pi firmware blobs.
 */
public final class RpiFirmwareContainerFactory {

    private static final int BOOTCODE_PADDING_SIZE = 512;
    private static final int BOOTCODE_PROBE_END = 0x201; // inclusive probe at 0x200
    public static final int FIXUP_PATTERN_WINDOW = 64;
    private static final double FIXUP_PATTERN_THRESHOLD = 0.80;

    private RpiFirmwareContainerFactory() {
    }

    /**
     * Checks whether a file path looks like Raspberry Pi firmware.
     *
     * @param path       the file path (used for filename checks)
     * @param header     the first bytes of the file (position must be 0)
     * @param sourceSize the total file size
     * @return true if the file should be classified as RPi firmware
     * @throws IOException if reading the header fails
     */
    public static boolean looksLikeRpiFirmware(@NotNull Path path, @NotNull ByteBuffer header, long sourceSize)
            throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "bootcode.bin" -> isBootcode(header, sourceSize);
            case "fixup.dat" -> isFixup(header, sourceSize);
            default -> false;
        };
    }

    /**
     * Checks whether a byte buffer (with no filename context) looks like a
     * Raspberry Pi firmware blob. Only {@code bootcode.bin} is detected by
     * content alone; {@code fixup.dat} requires a filename because its content
     * pattern is too weak to avoid false positives.
     *
     * @param buffer     the bytes to examine; position must be 0
     * @param sourceSize the total size of the source
     * @return true if the buffer should be classified as RPi firmware
     */
    public static boolean looksLikeRpiFirmware(@NotNull ByteBuffer buffer, long sourceSize) {
        return isBootcode(buffer, sourceSize);
    }

    private static boolean isBootcode(@NotNull ByteBuffer buffer, long sourceSize) {
        if (sourceSize <= BOOTCODE_PADDING_SIZE) {
            return false;
        }
        if (buffer.remaining() < BOOTCODE_PROBE_END) {
            return false;
        }
        // First 512 bytes must be zero.
        for (int i = 0; i < BOOTCODE_PADDING_SIZE; i++) {
            if (buffer.get(i) != 0) {
                return false;
            }
        }
        // Byte at offset 0x200 must be non-zero (the actual bootcode starts there).
        return buffer.get(BOOTCODE_PADDING_SIZE) != 0;
    }

    private static boolean isFixup(@NotNull ByteBuffer buffer, long sourceSize) throws IOException {
        if (sourceSize <= 0) {
            return false;
        }
        if (buffer.remaining() < FIXUP_PATTERN_WINDOW) {
            return false;
        }
        int matched = 0;
        for (int i = 0; i < FIXUP_PATTERN_WINDOW; i++) {
            byte b = buffer.get(i);
            if (b == 0x03 || b == 0x0f) {
                matched++;
            }
        }
        return (double) matched / FIXUP_PATTERN_WINDOW >= FIXUP_PATTERN_THRESHOLD;
    }

    /**
     * Reads a small probe window from the start of a file without loading the
     * whole file. The returned buffer has position 0 and contains at most
     * {@code maxProbe} bytes (or the whole file if smaller).
     *
     * @param path     the file to probe
     * @param maxProbe the maximum number of bytes to read
     * @return a buffer containing the probe bytes
     * @throws IOException if an I/O error occurs
     */
    static @NotNull ByteBuffer probeFile(@NotNull Path path, int maxProbe) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] probe = new byte[maxProbe];
            int read = 0;
            int n;
            while (read < maxProbe && (n = is.read(probe, read, maxProbe - read)) != -1) {
                read += n;
            }
            if (read < maxProbe) {
                byte[] exact = new byte[read];
                System.arraycopy(probe, 0, exact, 0, read);
                probe = exact;
            }
            return ByteBuffer.wrap(probe);
        }
    }
}
