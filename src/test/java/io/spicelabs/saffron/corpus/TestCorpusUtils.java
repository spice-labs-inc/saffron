/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Utilities for discovering test corpus images by filesystem type.
 *
 * <p>This class enables tests to dynamically discover available images
 * rather than relying on hardcoded paths. This ensures tests can run
 * in CI with a subset of images while still exercising all filesystem
 * code paths.
 *
 * <p>Usage example:
 * <pre>
 * static boolean hasXfsImage() {
 *     return TestCorpusUtils.hasFilesystem("xfs");
 * }
 *
 * @Test
 * @EnabledIf("hasXfsImage")
 * void testXfsReading() throws Exception {
 *     Path image = TestCorpusUtils.findImageWithFilesystem("xfs")
 *         .orElseThrow();
 *     // Test with the discovered image...
 * }
 * </pre>
 */
public final class TestCorpusUtils {

    private static final Path CORPUS_DIR = Paths.get("test-corpus");
    private static final Map<String, Boolean> FILESYSTEM_CACHE = new ConcurrentHashMap<>();
    private static volatile CorpusManifest MANIFEST;
    private static volatile boolean MANIFEST_LOADED = false;

    private TestCorpusUtils() {
        // Utility class
    }

    /**
     * Returns the corpus directory path.
     */
    public static @NotNull Path corpusDirectory() {
        return CORPUS_DIR;
    }

    /**
     * Loads and returns the corpus manifest, or empty manifest if not available.
     */
    public static @NotNull CorpusManifest manifest() {
        if (!MANIFEST_LOADED) {
            synchronized (TestCorpusUtils.class) {
                if (!MANIFEST_LOADED) {
                    try {
                        MANIFEST = CorpusManifest.load(CORPUS_DIR);
                    } catch (IOException e) {
                        MANIFEST = CorpusManifest.empty();
                    }
                    MANIFEST_LOADED = true;
                }
            }
        }
        return MANIFEST;
    }

    /**
     * Checks if any image with the specified filesystem type exists in the corpus.
     *
     * @param filesystem the filesystem type (e.g., "xfs", "btrfs", "ntfs", "ext4", "fat32")
     * @return true if at least one image with this filesystem exists on disk
     */
    public static boolean hasFilesystem(@NotNull String filesystem) {
        return FILESYSTEM_CACHE.computeIfAbsent(filesystem.toLowerCase(), fs ->
            findImageWithFilesystem(fs).isPresent()
        );
    }

    /**
     * Checks if images with all specified filesystem types exist.
     *
     * @param filesystems varargs of filesystem types
     * @return true if all filesystem types are available
     */
    public static boolean hasAllFilesystems(@NotNull String... filesystems) {
        for (String fs : filesystems) {
            if (!hasFilesystem(fs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if an image with any of the specified filesystem types exists.
     *
     * @param filesystems varargs of filesystem types
     * @return true if at least one filesystem type is available
     */
    public static boolean hasAnyFilesystem(@NotNull String... filesystems) {
        for (String fs : filesystems) {
            if (hasFilesystem(fs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the full corpus is available (most images from manifest exist on disk).
     * Used to skip tests that require the full corpus in CI mode.
     *
     * @return true if at least 90% of manifest images exist on disk
     */
    public static boolean isFullCorpusAvailable() {
        CorpusManifest manifest = manifest();
        if (manifest.totalImages() == 0) {
            return false;
        }

        long existingCount = manifest.images().stream()
                .filter(img -> Files.exists(CORPUS_DIR.resolve(img.path())))
                .count();

        // Consider "full corpus" if at least 90% of images exist
        return existingCount >= manifest.totalImages() * 9L / 10L;
    }

    /**
     * Finds the first available image with the specified filesystem type.
     *
     * @param filesystem the filesystem type (case-insensitive)
     * @return Optional containing the path to the image, or empty if not found
     */
    public static @NotNull Optional<Path> findImageWithFilesystem(@NotNull String filesystem) {
        String fsLower = filesystem.toLowerCase();

        // First check if a specific image is designated via environment variable
        String envVar = "SAFFRON_TEST_IMAGE_" + fsLower.toUpperCase();
        String envPath = System.getenv(envVar);
        if (envPath != null && !envPath.isEmpty()) {
            Path path = Paths.get(envPath);
            if (Files.exists(path)) {
                return Optional.of(path);
            }
        }

        // Search manifest for images with this filesystem
        return manifest().imagesByFilesystem(fsLower)
                .map(img -> CORPUS_DIR.resolve(img.path()))
                .filter(Files::exists)
                .findFirst();
    }

    /**
     * Finds all available images with the specified filesystem type.
     *
     * @param filesystem the filesystem type (case-insensitive)
     * @return list of paths to images with this filesystem
     */
    public static @NotNull List<Path> findAllImagesWithFilesystem(@NotNull String filesystem) {
        return manifest().imagesByFilesystem(filesystem.toLowerCase())
                .map(img -> CORPUS_DIR.resolve(img.path()))
                .filter(Files::exists)
                .collect(Collectors.toList());
    }

    /**
     * Finds an image with the specified filesystem and format.
     *
     * @param filesystem the filesystem type
     * @param format the disk format (e.g., "qcow2", "vmdk", "vhd", "vdi")
     * @return Optional containing the path to the image, or empty if not found
     */
    public static @NotNull Optional<Path> findImageWithFilesystemAndFormat(
            @NotNull String filesystem,
            @NotNull String format) {
        String fsLower = filesystem.toLowerCase();
        String fmtLower = format.toLowerCase();

        return manifest().imagesByFilesystem(fsLower)
                .filter(img -> img.format().equalsIgnoreCase(fmtLower))
                .map(img -> CORPUS_DIR.resolve(img.path()))
                .filter(Files::exists)
                .findFirst();
    }

    /**
     * Finds the "best" image for testing a filesystem - preferring quick tier images
     * and smaller file sizes for faster tests.
     *
     * @param filesystem the filesystem type
     * @return Optional containing the path to the best test image
     */
    public static @NotNull Optional<Path> findBestTestImage(@NotNull String filesystem) {
        String fsLower = filesystem.toLowerCase();

        return manifest().imagesByFilesystem(fsLower)
                .filter(img -> Files.exists(CORPUS_DIR.resolve(img.path())))
                .min((a, b) -> {
                    // Prefer quick tier images
                    int tierCompare = Boolean.compare(b.isQuickTier(), a.isQuickTier());
                    if (tierCompare != 0) return tierCompare;

                    // Then prefer smaller images (faster to process)
                    return Long.compare(a.actualSizeBytes(), b.actualSizeBytes());
                })
                .map(img -> CORPUS_DIR.resolve(img.path()));
    }

    /**
     * Returns a set of all filesystem types available in the corpus.
     */
    public static @NotNull Set<String> availableFilesystems() {
        return manifest().images().stream()
                .map(CorpusImage::filesystem)
                .filter(fs -> fs != null && !fs.isEmpty())
                .map(String::toLowerCase)
                .filter(fs -> {
                    Path path = findImageWithFilesystem(fs).orElse(null);
                    return path != null && Files.exists(path);
                })
                .collect(Collectors.toSet());
    }

    /**
     * Returns a summary of available filesystem coverage.
     */
    public static @NotNull FilesystemCoverageSummary getCoverageSummary() {
        Map<String, Long> byFs = manifest().countByFilesystem();
        Map<String, Long> available = byFs.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> findAllImagesWithFilesystem(e.getKey()).stream()
                                .filter(Files::exists)
                                .count()
                ));

        return new FilesystemCoverageSummary(available);
    }

    /**
     * Clears the filesystem cache, forcing rediscovery on next access.
     * Useful for tests that modify the corpus.
     */
    public static void clearCache() {
        FILESYSTEM_CACHE.clear();
    }

    /**
     * Summary of filesystem coverage in the test corpus.
     */
    public record FilesystemCoverageSummary(
            @NotNull Map<String, Long> availableByFilesystem
    ) {
        /**
         * Returns true if all standard filesystems are available.
         */
        public boolean hasFullCoverage() {
            Set<String> standard = Set.of("ext4", "xfs", "ntfs", "fat32");
            return availableByFilesystem.keySet().containsAll(standard);
        }

        /**
         * Returns true if the specified filesystem is available.
         */
        public boolean hasFilesystem(@NotNull String filesystem) {
            return availableByFilesystem.getOrDefault(filesystem.toLowerCase(), 0L) > 0;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Filesystem Coverage:\n");
            availableByFilesystem.forEach((fs, count) ->
                sb.append("  ").append(fs).append(": ").append(count).append(" images\n")
            );
            return sb.toString();
        }
    }
}
