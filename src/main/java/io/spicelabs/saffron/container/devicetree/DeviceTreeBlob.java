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
import io.spicelabs.saffron.io.ChunkedDisk;
import io.spicelabs.saffron.raw.RawDiskImpl;
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
     * <p>Reads are bounded: the header and structure/strings blocks are
     * traversed through a chunked reader (see {@link ChunkedDisk}), so no
     * single disk read exceeds 256 KiB regardless of the artifact size.
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
        return parse(new ChunkedDisk(disk));
    }

    /**
     * Parses a DTB through a chunked disk reader.
     *
     * @param disk the chunked disk to read (bounded reads)
     * @return the parsed blob, or empty if the input is not a valid DTB
     * @throws IOException if an I/O error occurs while reading
     */
    public static @NotNull Optional<DeviceTreeBlob> parse(@NotNull ChunkedDisk disk) throws IOException {
        try {
            if (disk.size() < DTB_HEADER_SIZE) {
                return Optional.empty();
            }
            if (disk.getUnsignedInt(0) != (DTB_MAGIC & 0xffffffffL)) {
                return Optional.empty();
            }
            long totalsize = disk.getUnsignedInt(4);
            long offDtStruct = disk.getUnsignedInt(8);
            long offDtStrings = disk.getUnsignedInt(12);
            long sizeDtStrings = disk.getUnsignedInt(32);
            long sizeDtStruct = disk.getUnsignedInt(36);

            if (totalsize < DTB_HEADER_SIZE || totalsize > disk.size()) {
                return Optional.empty();
            }
            if (!withinBounds(offDtStruct, sizeDtStruct, disk.size())
                    || !withinBounds(offDtStrings, sizeDtStrings, disk.size())) {
                return Optional.empty();
            }
            if (offDtStruct + sizeDtStruct > offDtStrings && offDtStrings + sizeDtStrings > offDtStruct) {
                return Optional.empty();
            }

            DeviceTreeNode root = parseStructure(disk, offDtStruct, sizeDtStruct,
                    offDtStrings, sizeDtStrings);
            if (root == null) {
                return Optional.empty();
            }
            return Optional.of(new DeviceTreeBlob(ByteBuffer.allocate(0), (int) totalsize, root));
        } catch (IndexOutOfBoundsException | IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Parses a DTB from a file path.
     *
     * <p>Reads are bounded: the file is traversed through a chunked reader
     * (see {@link ChunkedDisk}), so no single read exceeds 256 KiB and the
     * file is never loaded as a whole.
     *
     * @param path the path to read
     * @return the parsed blob, or empty if the file is not a valid DTB
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<DeviceTreeBlob> parse(@NotNull Path path) throws IOException {
        long size = Files.size(path);
        if (size < DTB_HEADER_SIZE || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        try (RawDiskImpl disk = RawDiskImpl.open(path)) {
            return parse(new ChunkedDisk(disk));
        }
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

    /**
     * Structure walk over a {@link ChunkedDisk}: sequential token reads with
     * bounded underlying disk reads.
     */
    private static DeviceTreeNode parseStructure(ChunkedDisk disk, long offDtStruct,
                                                  long sizeDtStruct, long offDtStrings,
                                                  long sizeDtStrings) throws IOException {
        long structStart = offDtStruct;
        long structEnd = structStart + sizeDtStruct;
        long stringsStart = offDtStrings;
        long stringsEnd = stringsStart + sizeDtStrings;
        long pos = structStart;

        Deque<DeviceTreeNode> stack = new ArrayDeque<>();
        DeviceTreeNode root = null;

        while (pos < structEnd) {
            if (structEnd - pos < 4) {
                return null;
            }
            int token = (int) disk.getUnsignedInt(pos);
            pos += 4;
            switch (token) {
                case FDT_BEGIN_NODE -> {
                    String name = readNullTerminatedString(disk, pos, structEnd);
                    if (name == null) {
                        return null;
                    }
                    pos += name.length() + 1;
                    pos += (4 - (pos % 4)) % 4;
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
                    if (structEnd - pos < 8) {
                        return null;
                    }
                    long len = disk.getUnsignedInt(pos);
                    long nameoff = disk.getUnsignedInt(pos + 4);
                    pos += 8;
                    if (len < 0 || len > Integer.MAX_VALUE || len > structEnd - pos) {
                        return null;
                    }
                    String name = readStringFromStrings(disk, stringsStart, stringsEnd, nameoff);
                    if (name == null) {
                        return null;
                    }
                    byte[] value = disk.copyRange(pos, (int) len);
                    DeviceTreeProperty property =
                            new DeviceTreeProperty(name, ByteBuffer.wrap(value), (int) len);
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.peek().addProperty(property);
                    pos += len;
                    pos += (4 - (pos % 4)) % 4;
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

    private static String readNullTerminatedString(ChunkedDisk disk, long pos, long end)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        while (pos < end) {
            int b = disk.get(pos++);
            if (b == 0) {
                return sb.toString();
            }
            sb.append((char) b);
        }
        return null;
    }

    private static String readStringFromStrings(ChunkedDisk disk, long stringsStart,
                                                long stringsEnd, long nameoff)
            throws IOException {
        long abs = stringsStart + nameoff;
        if (abs < stringsStart || abs >= stringsEnd) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        long pos = abs;
        boolean terminated = false;
        while (pos < stringsEnd) {
            int b = disk.get(pos++);
            if (b == 0) {
                terminated = true;
                break;
            }
            sb.append((char) b);
        }
        if (!terminated) {
            return null;
        }
        return sb.toString();
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
