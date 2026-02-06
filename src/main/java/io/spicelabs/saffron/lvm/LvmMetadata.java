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
package io.spicelabs.saffron.lvm;

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LVM2 metadata from the metadata area.
 *
 * <p>LVM metadata is stored in a text format that describes:
 * <ul>
 *   <li>Volume Group (VG) properties</li>
 *   <li>Physical Volume (PV) definitions</li>
 *   <li>Logical Volume (LV) definitions with segments</li>
 * </ul>
 *
 * <p>Metadata area header:
 * <pre>
 * Offset  Size  Description
 * 0       4     Checksum
 * 4       16    Magic " LVM2 x[5A%r0N*>"
 * 20      4     Version
 * 24      8     Start of metadata
 * 32      8     Size of metadata
 * </pre>
 */
public record LvmMetadata(
        @NotNull String vgName,
        @NotNull String vgUuid,
        long extentSize,
        @NotNull List<PhysicalVolume> physicalVolumes,
        @NotNull List<LogicalVolume> logicalVolumes
) {

    /** Metadata area magic */
    public static final String MDA_MAGIC = " LVM2 x[5A%r0N*>";

    /**
     * Represents a Physical Volume in the VG.
     */
    public record PhysicalVolume(
            @NotNull String name,
            @NotNull String uuid,
            long deviceSize,
            long peStart,
            long peCount
    ) {}

    /**
     * Represents a Logical Volume in the VG.
     */
    public record LogicalVolume(
            @NotNull String name,
            @NotNull String uuid,
            @NotNull List<Segment> segments
    ) {
        /**
         * Gets the total size in extents.
         */
        public long sizeInExtents() {
            return segments.stream().mapToLong(Segment::extentCount).sum();
        }
    }

    /**
     * Represents a segment of a Logical Volume.
     */
    public record Segment(
            long startExtent,
            long extentCount,
            @NotNull String type,
            @NotNull List<Stripe> stripes
    ) {}

    /**
     * Represents a stripe within a segment.
     */
    public record Stripe(
            @NotNull String pvName,
            long startExtent
    ) {}

    /**
     * Parses LVM metadata from the metadata area.
     *
     * @param disk the virtual disk
     * @param partitionOffset the offset where the PV starts
     * @param label the PV label
     * @return the parsed metadata, or empty if parsing fails
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<LvmMetadata> parse(@NotNull VirtualDisk disk,
                                                        long partitionOffset,
                                                        @NotNull LvmLabel label) throws IOException {
        if (label.metadataOffset() == 0 || label.metadataSize() == 0) {
            return Optional.empty();
        }

        // Read metadata area header
        long mdaOffset = partitionOffset + label.metadataOffset();
        ByteBuffer headerBuf = disk.read(mdaOffset, 40);
        headerBuf.order(ByteOrder.LITTLE_ENDIAN);

        // Skip checksum
        headerBuf.getInt();

        // Check magic
        byte[] magicBytes = new byte[16];
        headerBuf.get(magicBytes);
        String magic = new String(magicBytes, StandardCharsets.US_ASCII);

        if (!magic.equals(MDA_MAGIC)) {
            return Optional.empty();
        }

        // Version
        int version = headerBuf.getInt();
        if (version != 1) {
            return Optional.empty();
        }

        // Metadata location within the metadata area
        long metaStart = headerBuf.getLong();
        long metaSize = headerBuf.getLong();

        if (metaSize == 0 || metaSize > 2 * 1024 * 1024) { // Sanity check: max 2MB
            return Optional.empty();
        }

        // The metadata text area starts right after the 512-byte header
        long textAreaStart = mdaOffset + 512;
        int readSize = (int) Math.min(metaSize, 256 * 1024);

        // Try reading from the indicated metaStart offset first (this is the latest metadata)
        String metadataText = null;
        if (metaStart > 0 && metaStart < metaSize) {
            long textOffset = textAreaStart + metaStart;
            ByteBuffer textBuf = disk.read(textOffset, readSize);
            byte[] textBytes = new byte[readSize];
            textBuf.get(textBytes);
            String text = new String(textBytes, StandardCharsets.US_ASCII);

            // Check if this looks like valid LVM metadata (starts with VG name { )
            if (text.trim().matches("^[\\w-]+\\s*\\{[\\s\\S]*")) {
                metadataText = text;
            }
        }

        // If metaStart didn't have valid data, fall back to offset 0
        // This handles the case where the circular buffer hasn't wrapped yet
        if (metadataText == null) {
            ByteBuffer textBuf = disk.read(textAreaStart, readSize);
            byte[] textBytes = new byte[readSize];
            textBuf.get(textBytes);
            metadataText = new String(textBytes, StandardCharsets.US_ASCII);
        }

        return parseMetadataText(metadataText);
    }

    /**
     * Parses the LVM metadata text format.
     */
    private static Optional<LvmMetadata> parseMetadataText(String text) {
        try {
            // Remove comments and normalize whitespace
            text = text.replaceAll("#[^\n]*", "");

            // Find VG name (first identifier at the ABSOLUTE start of text, not per-line)
            // Use \\A to match only at the beginning of the string
            Pattern vgPattern = Pattern.compile("\\A\\s*([\\w-]+)\\s*\\{");
            Matcher vgMatcher = vgPattern.matcher(text);
            if (!vgMatcher.find()) {
                return Optional.empty();
            }
            String vgName = vgMatcher.group(1);

            // Extract VG UUID
            String vgUuid = extractValue(text, "id");

            // Extract extent size
            long extentSize = extractLongValue(text, "extent_size");
            if (extentSize == 0) {
                extentSize = 8192; // Default 4MB (8192 sectors of 512 bytes)
            }

            // Parse physical volumes
            List<PhysicalVolume> pvs = parsePhysicalVolumes(text);

            // Parse logical volumes
            List<LogicalVolume> lvs = parseLogicalVolumes(text);

            return Optional.of(new LvmMetadata(vgName, vgUuid, extentSize, pvs, lvs));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parses physical_volumes section.
     */
    private static List<PhysicalVolume> parsePhysicalVolumes(String text) {
        List<PhysicalVolume> pvs = new ArrayList<>();

        // Find physical_volumes section
        int pvStart = text.indexOf("physical_volumes");
        if (pvStart < 0) {
            return pvs;
        }

        int pvBlockStart = text.indexOf("{", pvStart);
        if (pvBlockStart < 0) {
            return pvs;
        }

        int pvBlockEnd = findMatchingBrace(text, pvBlockStart);
        if (pvBlockEnd < 0) {
            return pvs;
        }

        String pvSection = text.substring(pvBlockStart + 1, pvBlockEnd);

        // Find each PV definition
        Pattern pvPattern = Pattern.compile("(pv\\d+|\\w+)\\s*\\{([^}]+)\\}", Pattern.DOTALL);
        Matcher pvMatcher = pvPattern.matcher(pvSection);

        while (pvMatcher.find()) {
            String pvName = pvMatcher.group(1);
            String pvContent = pvMatcher.group(2);

            String uuid = extractValue(pvContent, "id");
            long deviceSize = extractLongValue(pvContent, "dev_size");
            long peStart = extractLongValue(pvContent, "pe_start");
            long peCount = extractLongValue(pvContent, "pe_count");

            pvs.add(new PhysicalVolume(pvName, uuid, deviceSize, peStart, peCount));
        }

        return pvs;
    }

    /**
     * Parses logical_volumes section.
     */
    private static List<LogicalVolume> parseLogicalVolumes(String text) {
        List<LogicalVolume> lvs = new ArrayList<>();

        // Find logical_volumes section
        int lvStart = text.indexOf("logical_volumes");
        if (lvStart < 0) {
            return lvs;
        }

        int lvBlockStart = text.indexOf("{", lvStart);
        if (lvBlockStart < 0) {
            return lvs;
        }

        int lvBlockEnd = findMatchingBrace(text, lvBlockStart);
        if (lvBlockEnd < 0) {
            return lvs;
        }

        String lvSection = text.substring(lvBlockStart + 1, lvBlockEnd);

        // Find each LV definition
        Pattern lvPattern = Pattern.compile("(\\w+)\\s*\\{", Pattern.MULTILINE);
        Matcher lvMatcher = lvPattern.matcher(lvSection);

        int searchStart = 0;
        while (lvMatcher.find(searchStart)) {
            String lvName = lvMatcher.group(1);
            int lvContentStart = lvMatcher.end();
            int lvContentEnd = findMatchingBrace(lvSection, lvMatcher.end() - 1);

            if (lvContentEnd < 0) {
                break;
            }

            String lvContent = lvSection.substring(lvContentStart, lvContentEnd);

            String uuid = extractValue(lvContent, "id");
            List<Segment> segments = parseSegments(lvContent);

            lvs.add(new LogicalVolume(lvName, uuid, segments));
            searchStart = lvContentEnd + 1;
        }

        return lvs;
    }

    /**
     * Parses segments within an LV.
     */
    private static List<Segment> parseSegments(String lvContent) {
        List<Segment> segments = new ArrayList<>();

        // Find each segment
        Pattern segPattern = Pattern.compile("segment(\\d+)\\s*\\{([^}]+)\\}", Pattern.DOTALL);
        Matcher segMatcher = segPattern.matcher(lvContent);

        while (segMatcher.find()) {
            String segContent = segMatcher.group(2);

            long startExtent = extractLongValue(segContent, "start_extent");
            long extentCount = extractLongValue(segContent, "extent_count");
            String type = extractValue(segContent, "type");
            if (type.isEmpty()) {
                type = "striped";
            }

            List<Stripe> stripes = parseStripes(segContent);

            segments.add(new Segment(startExtent, extentCount, type, stripes));
        }

        return segments;
    }

    /**
     * Parses stripes within a segment.
     */
    private static List<Stripe> parseStripes(String segContent) {
        List<Stripe> stripes = new ArrayList<>();

        // Find stripes array
        Pattern stripesPattern = Pattern.compile("stripes\\s*=\\s*\\[([^\\]]+)\\]", Pattern.DOTALL);
        Matcher stripesMatcher = stripesPattern.matcher(segContent);

        if (stripesMatcher.find()) {
            String stripesContent = stripesMatcher.group(1);

            // Parse stripe entries: "pvname", start_extent
            Pattern stripePattern = Pattern.compile("\"([^\"]+)\"\\s*,\\s*(\\d+)");
            Matcher stripeMatcher = stripePattern.matcher(stripesContent);

            while (stripeMatcher.find()) {
                String pvName = stripeMatcher.group(1);
                long startExtent = Long.parseLong(stripeMatcher.group(2));
                stripes.add(new Stripe(pvName, startExtent));
            }
        }

        return stripes;
    }

    /**
     * Extracts a string value from metadata text.
     */
    private static String extractValue(String text, String key) {
        Pattern pattern = Pattern.compile(key + "\\s*=\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * Extracts a long value from metadata text.
     */
    private static long extractLongValue(String text, String key) {
        Pattern pattern = Pattern.compile(key + "\\s*=\\s*(\\d+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 0;
    }

    /**
     * Finds the matching closing brace.
     */
    private static int findMatchingBrace(String text, int openBracePos) {
        int depth = 1;
        for (int i = openBracePos + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Finds a logical volume by name.
     */
    public @NotNull Optional<LogicalVolume> findLogicalVolume(@NotNull String name) {
        return logicalVolumes.stream()
                .filter(lv -> lv.name().equals(name))
                .findFirst();
    }

    /**
     * Finds a physical volume by name.
     */
    public @NotNull Optional<PhysicalVolume> findPhysicalVolume(@NotNull String name) {
        return physicalVolumes.stream()
                .filter(pv -> pv.name().equals(name))
                .findFirst();
    }

    /**
     * Returns the extent size in bytes.
     */
    public long extentSizeBytes() {
        return extentSize * 512; // extentSize is in sectors
    }
}
