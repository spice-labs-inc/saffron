/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents the test corpus manifest (manifest.json).
 *
 * <p>The manifest contains metadata about all images in the test corpus,
 * including format, filesystem, checksums, and provenance information.
 */
public final class CorpusManifest {

    private final String version;
    private final String generated;
    private final String description;
    private final List<CorpusImage> images;

    private CorpusManifest(String version, String generated, String description, List<CorpusImage> images) {
        this.version = version;
        this.generated = generated;
        this.description = description;
        this.images = List.copyOf(images);
    }

    /**
     * Loads the corpus manifest from a path.
     *
     * @param corpusPath path to the corpus directory (containing manifest.json)
     * @return the loaded manifest
     * @throws IOException if the manifest cannot be read
     */
    public static @NotNull CorpusManifest load(@NotNull Path corpusPath) throws IOException {
        Path manifestPath = corpusPath.resolve("manifest.json");
        if (!Files.exists(manifestPath)) {
            // Return empty manifest if not found
            return new CorpusManifest("1.0", "", "Empty corpus", List.of());
        }

        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        return parseJson(content);
    }

    /**
     * Parses the manifest from JSON content.
     *
     * <p>Note: This is a simple JSON parser for the manifest format.
     * In production, consider using Jackson or Gson.
     */
    private static CorpusManifest parseJson(String json) {
        // Simple JSON parsing - in production use Jackson/Gson
        // For now, return empty manifest if parsing is complex
        // The actual parsing will be implemented when we add a JSON library

        // Extract basic fields with simple string matching
        String version = extractJsonString(json, "version", "1.0");
        String generated = extractJsonString(json, "generated", "");
        String description = extractJsonString(json, "description", "");

        // Parse images array - simplified for now
        List<CorpusImage> images = parseImagesArray(json);

        return new CorpusManifest(version, generated, description, images);
    }

    private static String extractJsonString(String json, String key, String defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return defaultValue;
    }

    private static List<CorpusImage> parseImagesArray(String json) {
        // Find the images array and parse each entry
        // This is a simplified parser - use Jackson in production
        List<CorpusImage> images = new ArrayList<>();

        int imagesStart = json.indexOf("\"images\"");
        if (imagesStart == -1) {
            return images;
        }

        int arrayStart = json.indexOf('[', imagesStart);
        if (arrayStart == -1) {
            return images;
        }

        // Find matching closing bracket
        int depth = 1;
        int pos = arrayStart + 1;
        int objectStart = -1;

        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '[' || c == '{') {
                if (c == '{' && objectStart == -1) {
                    objectStart = pos;
                }
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (c == '}' && depth == 1 && objectStart != -1) {
                    // Found end of an image object
                    String imageJson = json.substring(objectStart, pos + 1);
                    CorpusImage image = parseImageObject(imageJson);
                    if (image != null) {
                        images.add(image);
                    }
                    objectStart = -1;
                }
            }
            pos++;
        }

        return images;
    }

    private static CorpusImage parseImageObject(String json) {
        try {
            String id = extractJsonString(json, "id", "");
            String path = extractJsonString(json, "path", "");
            String format = extractJsonString(json, "format", "");
            String era = extractJsonString(json, "era", "modern");
            String sourceUrl = extractJsonString(json, "source_url", "");
            String license = extractJsonString(json, "license", "unknown");
            String sha256 = extractJsonString(json, "sha256", "");
            String filesystem = extractJsonString(json, "filesystem", null);
            String os = extractJsonString(json, "os", null);
            String ciTier = extractJsonString(json, "ci_tier", "full");

            int year = extractJsonInt(json, "year", 2020);
            long virtualSize = extractJsonLong(json, "virtual_size_bytes", 0);
            long actualSize = extractJsonLong(json, "actual_size_bytes", 0);
            int priority = extractJsonInt(json, "priority", 3);

            if (id.isEmpty() || path.isEmpty() || format.isEmpty()) {
                return null;
            }

            return CorpusImage.builder()
                    .id(id)
                    .path(path)
                    .format(format)
                    .era(era)
                    .year(year)
                    .sourceUrl(sourceUrl)
                    .license(license)
                    .os(os)
                    .filesystem(filesystem)
                    .virtualSizeBytes(virtualSize)
                    .actualSizeBytes(actualSize)
                    .sha256(sha256)
                    .ciTier(ciTier)
                    .priority(priority)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private static int extractJsonInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return defaultValue;
    }

    private static long extractJsonLong(String json, String key, long defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return defaultValue;
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public @NotNull String version() {
        return version;
    }

    public @NotNull String generated() {
        return generated;
    }

    public @NotNull String description() {
        return description;
    }

    public @NotNull List<CorpusImage> images() {
        return images;
    }

    public int totalImages() {
        return images.size();
    }

    // ========================================================================
    // Query methods
    // ========================================================================

    /**
     * Returns images of a specific format.
     */
    public @NotNull Stream<CorpusImage> imagesByFormat(@NotNull DiskFormat format) {
        return images.stream()
                .filter(img -> img.diskFormat() == format);
    }

    /**
     * Returns images with a specific filesystem.
     */
    public @NotNull Stream<CorpusImage> imagesByFilesystem(@NotNull String filesystem) {
        return images.stream()
                .filter(img -> filesystem.equalsIgnoreCase(img.filesystem()));
    }

    /**
     * Returns legacy images (2005-2010).
     */
    public @NotNull Stream<CorpusImage> legacyImages() {
        return images.stream().filter(CorpusImage::isLegacy);
    }

    /**
     * Returns modern images (2011+).
     */
    public @NotNull Stream<CorpusImage> modernImages() {
        return images.stream().filter(img -> !img.isLegacy());
    }

    /**
     * Returns images for the CI quick tier.
     */
    public @NotNull Stream<CorpusImage> quickTierImages() {
        return images.stream()
                .filter(CorpusImage::isQuickTier)
                .sorted(Comparator.comparingInt(CorpusImage::priority));
    }

    /**
     * Returns images for the CI standard tier.
     */
    public @NotNull Stream<CorpusImage> standardTierImages() {
        return images.stream()
                .filter(CorpusImage::isStandardTier)
                .sorted(Comparator.comparingInt(CorpusImage::priority));
    }

    /**
     * Returns count of images by format.
     */
    public @NotNull Map<DiskFormat, Long> countByFormat() {
        return images.stream()
                .collect(Collectors.groupingBy(CorpusImage::diskFormat, Collectors.counting()));
    }

    /**
     * Returns count of images by filesystem.
     */
    public @NotNull Map<String, Long> countByFilesystem() {
        return images.stream()
                .filter(img -> img.filesystem() != null)
                .collect(Collectors.groupingBy(img -> img.filesystem().toLowerCase(), Collectors.counting()));
    }

    /**
     * Returns count of legacy images.
     */
    public long legacyCount() {
        return images.stream().filter(CorpusImage::isLegacy).count();
    }
}
