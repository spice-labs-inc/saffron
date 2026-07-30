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

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Parses a Device Tree Blob (DTB) or Flattened Image Tree (FIT) into a node tree.
 *
 * <p>The parser validates every header field against the source size and uses
 * safe long arithmetic for offset+size checks. Malformed input yields an empty
 * {@link Optional} rather than an unchecked exception.</p>
 */
public final class DeviceTreeBlob {

    private static final int DTB_HEADER_SIZE = 40;
    private static final int DTB_MAGIC = 0xd00d_feed;

    private static final int FDT_BEGIN_NODE = 0x0000_0001;
    private static final int FDT_END_NODE = 0x0000_0002;
    private static final int FDT_PROP = 0x0000_0003;
    private static final int FDT_NOP = 0x0000_0004;
    private static final int FDT_END = 0x0000_0009;

    private final ByteBuffer source;
    private final int totalsize;
    private final DeviceTreeNode root;

    private DeviceTreeBlob(@NotNull ByteBuffer source, int totalsize, @NotNull DeviceTreeNode root) {
        this.source = source;
        this.totalsize = totalsize;
        this.root = root;
    }

    /**
     * Parses a DTB from a byte array.
     *
     * @param source the full DTB bytes
     * @return the parsed blob, or empty if the input is not a valid DTB
     */
    public static @NotNull Optional<DeviceTreeBlob> parse(byte @NotNull [] source) {
        return parse(ByteBuffer.wrap(source));
    }

    /**
     * Parses a DTB from a byte buffer.
     *
     * @param source the full DTB bytes; the buffer is not modified
     * @return the parsed blob, or empty if the input is not a valid DTB
     */
    public static @NotNull Optional<DeviceTreeBlob> parse(@NotNull ByteBuffer source) {
        try {
            ByteBuffer big = source.duplicate();
            big.position(0);
            big.order(ByteOrder.BIG_ENDIAN);
            long available = big.remaining();
            if (available < DTB_HEADER_SIZE) {
                return Optional.empty();
            }
            if (big.getInt(0) != DTB_MAGIC) {
                return Optional.empty();
            }
            long totalsize = big.getInt(4) & 0xFFFFFFFFL;
            long offDtStruct = big.getInt(8) & 0xFFFFFFFFL;
            long offDtStrings = big.getInt(12) & 0xFFFFFFFFL;
            long sizeDtStrings = big.getInt(32) & 0xFFFFFFFFL;
            long sizeDtStruct = big.getInt(36) & 0xFFFFFFFFL;

            if (totalsize < DTB_HEADER_SIZE || totalsize > available) {
                return Optional.empty();
            }
            if (!withinBounds(offDtStruct, sizeDtStruct, available)
                    || !withinBounds(offDtStrings, sizeDtStrings, available)) {
                return Optional.empty();
            }
            if (offDtStruct + sizeDtStruct > offDtStrings && offDtStrings + sizeDtStrings > offDtStruct) {
                // Structure and strings blocks overlap (other than the trivial empty case)
                return Optional.empty();
            }

            DeviceTreeNode root = parseStructure(big, (int) offDtStruct, (int) sizeDtStruct,
                    (int) offDtStrings, (int) sizeDtStrings);
            if (root == null) {
                return Optional.empty();
            }
            return Optional.of(new DeviceTreeBlob(big, (int) totalsize, root));
        } catch (IllegalArgumentException | BufferUnderflowException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    /**
     * Parses a DTB from a virtual disk.
     *
     * @param disk the virtual disk to read
     * @return the parsed blob, or empty if the disk is not a valid DTB
     * @throws IOException if an I/O error occurs while reading
     */
    public static @NotNull Optional<DeviceTreeBlob> parse(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size < DTB_HEADER_SIZE || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        try {
            ByteBuffer data = disk.read(0, (int) size);
            return parse(data);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Parses a DTB from a file path.
     *
     * @param path the path to read
     * @return the parsed blob, or empty if the file is not a valid DTB
     * @throws IOException if an I/O error occurs while reading
     */
    public static @NotNull Optional<DeviceTreeBlob> parse(@NotNull Path path) throws IOException {
        long size = Files.size(path);
        if (size < DTB_HEADER_SIZE || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        byte[] data = Files.readAllBytes(path);
        return parse(data);
    }

    /**
     * Returns the root node of the device tree.
     *
     * @return the root node
     */
    public @NotNull DeviceTreeNode root() {
        return root;
    }

    /**
     * Returns the total size declared in the DTB header.
     *
     * @return the declared total size in bytes
     */
    public long totalSize() {
        return totalsize & 0xFFFFFFFFL;
    }

    private static boolean withinBounds(long offset, long length, long available) {
        if (offset < 0 || length < 0) {
            return false;
        }
        long end;
        try {
            end = Math.addExact(offset, length);
        } catch (ArithmeticException e) {
            return false;
        }
        return end <= available;
    }

    private static DeviceTreeNode parseStructure(ByteBuffer source, int offDtStruct, int sizeDtStruct,
                                                  int offDtStrings, int sizeDtStrings) {
        int structStart = offDtStruct;
        int structEnd = structStart + sizeDtStruct;
        if (structEnd < structStart) {
            return null;
        }
        ByteBuffer struct = source.duplicate();
        struct.position(structStart).limit(structEnd);
        struct = struct.slice();
        struct.order(ByteOrder.BIG_ENDIAN);

        int stringsStart = offDtStrings;
        int stringsEnd = stringsStart + sizeDtStrings;
        if (stringsEnd < stringsStart) {
            return null;
        }

        Deque<DeviceTreeNode> stack = new ArrayDeque<>();
        DeviceTreeNode root = null;

        while (struct.hasRemaining()) {
            if (struct.remaining() < 4) {
                return null;
            }
            int token = struct.getInt();
            switch (token) {
                case FDT_BEGIN_NODE -> {
                    String name = readNullTerminatedString(struct);
                    if (name == null) {
                        return null;
                    }
                    DeviceTreeNode node = new DeviceTreeNode(name);
                    if (root == null) {
                        root = node;
                    } else {
                        if (stack.isEmpty()) {
                            return null;
                        }
                        stack.peek().addChild(node);
                    }
                    stack.push(node);
                }
                case FDT_END_NODE -> {
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.pop();
                }
                case FDT_PROP -> {
                    if (struct.remaining() < 8) {
                        return null;
                    }
                    int len = struct.getInt();
                    int nameoff = struct.getInt();
                    if (len < 0 || struct.remaining() < len) {
                        return null;
                    }
                    String name = readStringFromStrings(source, stringsStart, stringsEnd, nameoff);
                    if (name == null) {
                        return null;
                    }
                    int valueStart = struct.position();
                    ByteBuffer value = struct.slice(valueStart, len);
                    DeviceTreeProperty property = new DeviceTreeProperty(name, value, len);
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.peek().addProperty(property);
                    struct.position(valueStart + len);
                    if (!alignToWord(struct)) {
                        return null;
                    }
                }
                case FDT_NOP -> {
                    // Skip
                }
                case FDT_END -> {
                    if (stack.isEmpty()) {
                        return root;
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private static String readNullTerminatedString(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (!buffer.hasRemaining()) {
                return null;
            }
            byte b = buffer.get();
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        if (!alignToWord(buffer)) {
            return null;
        }
        return sb.toString();
    }

    private static String readStringFromStrings(ByteBuffer source, int stringsStart, int stringsEnd, int nameoff) {
        long absLong = (long) stringsStart + (long) nameoff;
        if (absLong < stringsStart || absLong >= stringsEnd || absLong > Integer.MAX_VALUE) {
            return null;
        }
        int abs = (int) absLong;
        ByteBuffer strings = source.duplicate();
        strings.position(abs).limit(stringsEnd);
        strings = strings.slice();
        StringBuilder sb = new StringBuilder();
        boolean terminated = false;
        while (strings.hasRemaining()) {
            byte b = strings.get();
            if (b == 0) {
                terminated = true;
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        if (!terminated) {
            return null;
        }
        return sb.toString();
    }

    private static boolean alignToWord(ByteBuffer buffer) {
        int pos = buffer.position();
        int padding = (4 - (pos % 4)) % 4;
        if (buffer.remaining() < padding) {
            return false;
        }
        buffer.position(pos + padding);
        return true;
    }
}
