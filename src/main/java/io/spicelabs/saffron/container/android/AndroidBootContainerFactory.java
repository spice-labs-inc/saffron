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
package io.spicelabs.saffron.container.android;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Helpers for detecting Android boot images.
 */
public final class AndroidBootContainerFactory {

    private static final int MAGIC_LENGTH = 8;
    private static final byte[] MAGIC = "ANDROID!".getBytes(StandardCharsets.US_ASCII);
    private static final int MIN_PROBE_BYTES = 44;
    private static final int PAGE_SIZE_OFFSET = 36;
    private static final int V0V2_HEADER_VERSION_OFFSET = 40;
    private static final int V3V4_HEADER_VERSION_OFFSET = 40;
    private static final int V3V4_HEADER_SIZE_OFFSET = 20;

    private static final int V3_HEADER_SIZE = 1580;
    private static final int V4_HEADER_SIZE = 1584;

    private AndroidBootContainerFactory() {
        // Static utility class
    }

    /**
     * Returns true if the buffer begins with a recognized Android boot image header.
     *
     * <p>This only confirms the magic and header version; it does not validate
     * offsets, sizes, or component layout.</p>
     *
     * @param buffer    the buffer to examine; position must be 0
     * @param sourceSize the total size of the source
     * @return true if the buffer looks like an Android boot image header
     */
    public static boolean looksLikeAndroidBoot(@NotNull ByteBuffer buffer, long sourceSize) {
        if (sourceSize < MIN_PROBE_BYTES || buffer.remaining() < MIN_PROBE_BYTES) {
            return false;
        }
        ByteOrder originalOrder = buffer.order();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            if (!isMagic(buffer)) {
                return false;
            }

            int pageSize = buffer.getInt(PAGE_SIZE_OFFSET);
            // v0-2 layout: page size is a valid power of two and header version is at offset 40.
            if (isSupportedPageSize(pageSize)) {
                int version = buffer.getInt(V0V2_HEADER_VERSION_OFFSET);
                return version >= 0 && version <= 2;
            }

            // v3/v4 layout: page size field is reserved (usually zero) and header version is at offset 44.
            int v3v4Version = buffer.getInt(V3V4_HEADER_VERSION_OFFSET);
            int v3v4HeaderSize = buffer.getInt(V3V4_HEADER_SIZE_OFFSET);
            if (v3v4Version == 3 || v3v4Version == 4) {
                return v3v4HeaderSize == V3_HEADER_SIZE || v3v4HeaderSize == V4_HEADER_SIZE;
            }
            return false;
        } finally {
            buffer.order(originalOrder);
        }
    }

    private static boolean isMagic(@NotNull ByteBuffer buffer) {
        for (int i = 0; i < MAGIC_LENGTH; i++) {
            if (buffer.get(i) != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedPageSize(int pageSize) {
        return pageSize == 2048 || pageSize == 4096 || pageSize == 8192 || pageSize == 16384;
    }
}
