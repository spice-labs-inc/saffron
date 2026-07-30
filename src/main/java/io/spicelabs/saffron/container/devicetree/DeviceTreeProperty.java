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
package io.spicelabs.saffron.container.devicetree;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A property in a parsed device tree node.
 *
 * <p>The value is stored as a slice of the original source buffer. The
 * {@link #asBytes()} method returns a defensive copy.</p>
 */
public final class DeviceTreeProperty {

    private final String name;
    private final ByteBuffer value;
    private final int length;

    DeviceTreeProperty(@NotNull String name, @NotNull ByteBuffer value, int length) {
        this.name = name;
        this.value = value;
        this.length = length;
    }

    /**
     * Returns the property name.
     *
     * @return the property name
     */
    public @NotNull String name() {
        return name;
    }

    /**
     * Returns the property value as a defensive copy.
     *
     * @return the value bytes
     */
    public byte @NotNull [] asBytes() {
        byte[] copy = new byte[length];
        value.duplicate().position(0).get(copy);
        return copy;
    }

    /**
     * Returns the property value as a read-only buffer.
     *
     * @return the value buffer
     */
    public @NotNull ByteBuffer asBuffer() {
        return value.asReadOnlyBuffer();
    }

    /**
     * Returns the property value as a UTF-8 string, without a trailing null.
     *
     * @return the string value
     */
    public @NotNull String asString() {
        byte[] bytes = asBytes();
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Returns the property length in bytes.
     *
     * @return the length
     */
    public int size() {
        return length;
    }
}
