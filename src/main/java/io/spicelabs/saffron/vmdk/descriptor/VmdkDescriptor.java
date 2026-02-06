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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.vmdk.descriptor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a parsed VMDK descriptor file.
 *
 * <p>VMDK descriptor files are text-based configuration files that describe
 * the virtual disk layout. They can be standalone files or embedded in a
 * sparse VMDK extent.
 *
 * <p>Example descriptor:
 * <pre>
 * # Disk DescriptorFile
 * version=1
 * CID=fffffffe
 * parentCID=ffffffff
 * createType="monolithicSparse"
 *
 * # Extent description
 * RW 20971520 SPARSE "disk.vmdk"
 *
 * # The Disk Data Base
 * #DDB
 * ddb.virtualHWVersion = "4"
 * ddb.geometry.cylinders = "1305"
 * ddb.geometry.heads = "16"
 * ddb.geometry.sectors = "63"
 * ddb.adapterType = "ide"
 * </pre>
 */
public record VmdkDescriptor(
        int version,
        @NotNull String cid,
        @NotNull String parentCid,
        @NotNull String createType,
        @NotNull List<Extent> extents,
        @NotNull Map<String, String> ddb
) {

    /** Pattern for key=value pairs */
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^\\s*(\\w+(?:\\.\\w+)*)\\s*=\\s*(.+)\\s*$");

    /** Pattern for extent lines */
    private static final Pattern EXTENT_PATTERN = Pattern.compile(
            "^\\s*(RW|RDONLY|NOACCESS)\\s+(\\d+)\\s+(FLAT|SPARSE|ZERO|VMFS|VMFSSPARSE|VMFSRDM|VMFSRAW|SESPARSE)\\s+\"?([^\"]+)\"?(?:\\s+(\\d+))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Reads a descriptor from the specified location in a channel.
     *
     * @param channel the channel to read from
     * @param offset the byte offset where the descriptor starts
     * @param size the size in bytes of the descriptor region
     * @return the parsed descriptor
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VmdkDescriptor read(@NotNull SeekableByteChannel channel,
                                                long offset, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        channel.position(offset);
        int read = channel.read(buffer);
        if (read < size) {
            throw new IOException("Failed to read VMDK descriptor: got " + read + " bytes");
        }
        buffer.flip();

        // Convert to string, trimming at first null byte
        byte[] bytes = new byte[(int) size];
        buffer.get(bytes);
        int length = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                break;
            }
            length++;
        }
        String content = new String(bytes, 0, length, StandardCharsets.US_ASCII);

        return parse(content);
    }

    /**
     * Parses a descriptor from a string.
     *
     * @param content the descriptor content
     * @return the parsed descriptor
     * @throws IOException if parsing fails
     */
    public static @NotNull VmdkDescriptor parse(@NotNull String content) throws IOException {
        int version = 1;
        String cid = "ffffffff";
        String parentCid = "ffffffff";
        String createType = "monolithicSparse";
        List<Extent> extents = new ArrayList<>();
        Map<String, String> ddb = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments (except #DDB which marks the database section)
                if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("#DDB"))) {
                    continue;
                }

                // Check for extent line
                Matcher extentMatcher = EXTENT_PATTERN.matcher(line);
                if (extentMatcher.matches()) {
                    String access = extentMatcher.group(1).toUpperCase();
                    long sectors = Long.parseLong(extentMatcher.group(2));
                    String type = extentMatcher.group(3).toUpperCase();
                    String filename = extentMatcher.group(4);
                    long offset = extentMatcher.group(5) != null ?
                            Long.parseLong(extentMatcher.group(5)) : 0;

                    extents.add(new Extent(
                            AccessMode.valueOf(access),
                            sectors,
                            ExtentType.valueOf(type),
                            filename,
                            offset
                    ));
                    continue;
                }

                // Check for key=value
                Matcher kvMatcher = KEY_VALUE_PATTERN.matcher(line);
                if (kvMatcher.matches()) {
                    String key = kvMatcher.group(1);
                    String value = kvMatcher.group(2).trim();

                    // Remove quotes if present
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }

                    if (key.equalsIgnoreCase("version")) {
                        version = Integer.parseInt(value);
                    } else if (key.equalsIgnoreCase("CID")) {
                        cid = value;
                    } else if (key.equalsIgnoreCase("parentCID")) {
                        parentCid = value;
                    } else if (key.equalsIgnoreCase("createType")) {
                        createType = value;
                    } else if (key.startsWith("ddb.")) {
                        ddb.put(key.substring(4), value);
                    }
                }
            }
        }

        return new VmdkDescriptor(
                version,
                cid,
                parentCid,
                createType,
                List.copyOf(extents),
                Map.copyOf(ddb)
        );
    }

    /**
     * Returns the total capacity in sectors.
     */
    public long totalSectors() {
        return extents.stream()
                .mapToLong(Extent::sizeInSectors)
                .sum();
    }

    /**
     * Returns the total capacity in bytes.
     */
    public long totalBytes() {
        return totalSectors() * 512;
    }

    /**
     * Returns the virtual hardware version.
     */
    public @Nullable String virtualHWVersion() {
        return ddb.get("virtualHWVersion");
    }

    /**
     * Returns the adapter type (ide, lsilogic, buslogic, etc.).
     */
    public @Nullable String adapterType() {
        return ddb.get("adapterType");
    }

    /**
     * Returns the geometry information if present.
     */
    public @NotNull Optional<Geometry> geometry() {
        String cylinders = ddb.get("geometry.cylinders");
        String heads = ddb.get("geometry.heads");
        String sectors = ddb.get("geometry.sectors");

        if (cylinders != null && heads != null && sectors != null) {
            try {
                return Optional.of(new Geometry(
                        Integer.parseInt(cylinders),
                        Integer.parseInt(heads),
                        Integer.parseInt(sectors)
                ));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether this is a linked clone (has a parent).
     */
    public boolean hasParent() {
        return !parentCid.equalsIgnoreCase("ffffffff");
    }

    /**
     * Extent access mode.
     */
    public enum AccessMode {
        RW,
        RDONLY,
        NOACCESS
    }

    /**
     * Extent type.
     */
    public enum ExtentType {
        FLAT,
        SPARSE,
        ZERO,
        VMFS,
        VMFSSPARSE,
        VMFSRDM,
        VMFSRAW,
        SESPARSE
    }

    /**
     * Represents a disk extent in the descriptor.
     *
     * @param accessMode the access mode
     * @param sizeInSectors the size in sectors
     * @param type the extent type
     * @param filename the extent filename
     * @param offset the offset within the extent file (for flat extents)
     */
    public record Extent(
            @NotNull AccessMode accessMode,
            long sizeInSectors,
            @NotNull ExtentType type,
            @NotNull String filename,
            long offset
    ) {
        /**
         * Returns the size in bytes.
         */
        public long sizeInBytes() {
            return sizeInSectors * 512;
        }
    }

    /**
     * Disk geometry information.
     *
     * @param cylinders number of cylinders
     * @param heads number of heads
     * @param sectorsPerTrack sectors per track
     */
    public record Geometry(
            int cylinders,
            int heads,
            int sectorsPerTrack
    ) {}
}
