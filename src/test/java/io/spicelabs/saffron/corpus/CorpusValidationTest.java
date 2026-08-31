/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Validates the test corpus integrity and completeness.
 *
 * <p>These tests are only run when the full corpus is available.
 * They verify that the corpus meets minimum requirements for testing.
 */
@EnabledIf("corpusIsPopulated")
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class CorpusValidationTest {

    private static final Path CORPUS_PATH = Path.of("test-corpus");
    private static CorpusManifest manifest;

    /**
     * Only run these tests if the corpus has a manifest.
     */
    static boolean corpusIsPopulated() {
        return Files.exists(CORPUS_PATH.resolve("manifest.json"));
    }

    @BeforeAll
    static void loadManifest() throws IOException {
        manifest = CorpusManifest.load(CORPUS_PATH);
    }

    @Test
    void corpus_hasMinimumImageCount() {
        // In CI mode with small corpus, we only need a few images for coverage
        String ciMode = System.getenv("CI");
        int minImages = "true".equals(ciMode) ? 5 : 50;

        assertThat(manifest.totalImages())
                .as("Corpus should have at least " + minImages + " images")
                .isGreaterThanOrEqualTo(minImages);
    }

    @Test
    void corpus_hasAllRequiredFormats() {
        Map<DiskFormat, Long> formatCounts = manifest.countByFormat();

        // In CI mode with small corpus, just need at least 1 of each format
        String ciMode = System.getenv("CI");
        int minCount = "true".equals(ciMode) ? 1 : 5;
        int minQcow2 = "true".equals(ciMode) ? 1 : 20;

        assertThat(formatCounts.getOrDefault(DiskFormat.VMDK, 0L))
                .as("VMDK count")
                .isGreaterThanOrEqualTo(minCount);

        assertThat(formatCounts.getOrDefault(DiskFormat.QCOW2, 0L))
                .as("QCOW2 count")
                .isGreaterThanOrEqualTo(minQcow2);

        assertThat(formatCounts.getOrDefault(DiskFormat.VHD, 0L))
                .as("VHD count")
                .isGreaterThanOrEqualTo(minCount);

        assertThat(formatCounts.getOrDefault(DiskFormat.VDI, 0L))
                .as("VDI count")
                .isGreaterThanOrEqualTo(minCount);
    }

    @Test
    void corpus_hasRequiredFilesystems() {
        Map<String, Long> fsCounts = manifest.countByFilesystem();

        // In CI mode with small corpus, just need at least 1 of each filesystem
        String ciMode = System.getenv("CI");
        int minCount = "true".equals(ciMode) ? 1 : 5;
        int minExt = "true".equals(ciMode) ? 1 : 20;

        long extCount = fsCounts.getOrDefault("ext4", 0L) +
                        fsCounts.getOrDefault("ext3", 0L) +
                        fsCounts.getOrDefault("ext2", 0L);

        assertThat(extCount)
                .as("ext4/ext3/ext2 count")
                .isGreaterThanOrEqualTo(minExt);

        long fatCount = fsCounts.getOrDefault("fat32", 0L) +
                        fsCounts.getOrDefault("fat16", 0L);

        assertThat(fatCount)
                .as("FAT32/FAT16 count")
                .isGreaterThanOrEqualTo(1);

        assertThat(fsCounts.getOrDefault("xfs", 0L))
                .as("XFS count")
                .isGreaterThanOrEqualTo(minCount);
    }

    @Test
    void corpus_hasLegacyImages() {
        // Skip in CI mode - legacy images are large and may not fit in size limit
        String ciMode = System.getenv("CI");
        if ("true".equals(ciMode)) {
            return;
        }

        long legacyCount = manifest.legacyCount();

        assertThat(legacyCount)
                .as("Legacy images (2005-2010)")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void corpus_allImageFilesExist() {
        // Skip this test in CI mode - we intentionally download only a subset
        String ciMode = System.getenv("CI");
        if ("true".equals(ciMode)) {
            return;
        }

        List<String> missing = new ArrayList<>();
        for (CorpusImage image : manifest.images()) {
            Path imagePath = CORPUS_PATH.resolve(image.path());
            if (!Files.exists(imagePath)) {
                missing.add(image.path());
            }
        }

        // Local tests MUST have all corpus files - fail if any are missing
        assertThat(missing)
                .as("All corpus images must exist on disk for local testing")
                .isEmpty();
    }

    /**
     * Verifies the SHA-256 of every corpus image against the manifest.
     *
     * <p>Each image is its own DynamicTest so checksums are computed
     * concurrently (see {@code junit-platform.properties} and ADR-0002).
     * Files are streamed through {@link MessageDigest} to avoid loading them
     * into memory.
     */
    @Execution(ExecutionMode.CONCURRENT)
    @TestFactory
    Stream<DynamicTest> corpus_allImageChecksumsMatch() {
        return manifest.images().stream()
                .filter(image -> image.sha256() != null && !image.sha256().isBlank())
                .filter(image -> Files.exists(CORPUS_PATH.resolve(image.path())))
                .map(image -> DynamicTest.dynamicTest(image.path(), () -> {
                    String actualSha256 = computeSha256Streaming(CORPUS_PATH.resolve(image.path()));
                    assertThat(actualSha256)
                            .as("SHA256 for %s", image.path())
                            .isEqualToIgnoringCase(image.sha256());
                }));
    }

    /**
     * Computes SHA-256 hash of a file using streaming to avoid OOM on large files.
     */
    private String computeSha256Streaming(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024]; // 64KB buffer

        try (InputStream is = new BufferedInputStream(Files.newInputStream(path))) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void corpus_allImageSizesMatch() throws IOException {
        for (CorpusImage image : manifest.images()) {
            Path imagePath = CORPUS_PATH.resolve(image.path());

            if (!Files.exists(imagePath)) {
                continue;
            }

            if (image.actualSizeBytes() <= 0) {
                continue; // Manifest entry has no size data
            }

            long actualSize = Files.size(imagePath);

            assertThat(actualSize)
                    .as("File size for %s", image.path())
                    .isEqualTo(image.actualSizeBytes());
        }
    }

    @Test
    void corpus_hasNoOrphanedFiles() throws IOException {
        // Find all image files in the corpus directory
        List<String> orphaned = new ArrayList<>();
        try (var stream = Files.walk(CORPUS_PATH)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String name = p.getFileName().toString().toLowerCase();
                      return name.endsWith(".qcow2") || name.endsWith(".vmdk") ||
                             name.endsWith(".vhd") || name.endsWith(".vhdx") ||
                             name.endsWith(".vdi") || name.endsWith(".raw") ||
                             name.endsWith(".dmg");
                  })
                  .forEach(path -> {
                      String relPath = CORPUS_PATH.relativize(path).toString();
                      boolean inManifest = manifest.images().stream()
                              .anyMatch(img -> img.path().equals(relPath));

                      if (!inManifest) {
                          orphaned.add(relPath);
                      }
                  });
        }

        if (!orphaned.isEmpty()) {
            System.err.println("WARNING: " + orphaned.size() + " files not in manifest:");
            orphaned.forEach(p -> System.err.println("  - " + p));
        }

        // Most corpus files should be tracked in the manifest
        long totalOnDisk = orphaned.size() + manifest.totalImages();
        assertThat(orphaned.size())
                .as("Orphaned files should be less than 50% of corpus")
                .isLessThan((int) (totalOnDisk / 2));
    }
}
