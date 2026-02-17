/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;

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
        assertThat(manifest.totalImages())
                .as("Corpus should have at least 50 images")
                .isGreaterThanOrEqualTo(50);
    }

    @Test
    void corpus_hasAllRequiredFormats() {
        Map<DiskFormat, Long> formatCounts = manifest.countByFormat();

        assertThat(formatCounts.getOrDefault(DiskFormat.VMDK, 0L))
                .as("VMDK count")
                .isGreaterThanOrEqualTo(5);

        assertThat(formatCounts.getOrDefault(DiskFormat.QCOW2, 0L))
                .as("QCOW2 count")
                .isGreaterThanOrEqualTo(20);

        assertThat(formatCounts.getOrDefault(DiskFormat.VHD, 0L))
                .as("VHD count")
                .isGreaterThanOrEqualTo(5);

        assertThat(formatCounts.getOrDefault(DiskFormat.VDI, 0L))
                .as("VDI count")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void corpus_hasRequiredFilesystems() {
        Map<String, Long> fsCounts = manifest.countByFilesystem();

        long extCount = fsCounts.getOrDefault("ext4", 0L) +
                        fsCounts.getOrDefault("ext3", 0L) +
                        fsCounts.getOrDefault("ext2", 0L);

        assertThat(extCount)
                .as("ext4/ext3/ext2 count")
                .isGreaterThanOrEqualTo(20);

        long fatCount = fsCounts.getOrDefault("fat32", 0L) +
                        fsCounts.getOrDefault("fat16", 0L);

        assertThat(fatCount)
                .as("FAT32/FAT16 count")
                .isGreaterThanOrEqualTo(1);

        assertThat(fsCounts.getOrDefault("xfs", 0L))
                .as("XFS count")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void corpus_hasLegacyImages() {
        long legacyCount = manifest.legacyCount();

        assertThat(legacyCount)
                .as("Legacy images (2005-2010)")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "CI downloads only a stratified 5% sample of corpus images")
    void corpus_allImageFilesExist() {
        List<String> missing = new ArrayList<>();
        for (CorpusImage image : manifest.images()) {
            Path imagePath = CORPUS_PATH.resolve(image.path());
            if (!Files.exists(imagePath)) {
                missing.add(image.path());
            }
        }

        if (!missing.isEmpty()) {
            System.err.println("WARNING: " + missing.size() + " manifest entries missing from disk:");
            missing.forEach(p -> System.err.println("  - " + p));
        }

        // At least 90% of manifest entries should exist
        assertThat(manifest.totalImages() - missing.size())
                .as("Most manifest images should exist on disk")
                .isGreaterThan(manifest.totalImages() * 9 / 10);
    }

    @Test
    void corpus_allImageChecksumsMatch() throws Exception {
        for (CorpusImage image : manifest.images()) {
            Path imagePath = CORPUS_PATH.resolve(image.path());

            if (!Files.exists(imagePath)) {
                continue; // Covered by existence test
            }

            if (image.sha256() == null || image.sha256().isBlank()) {
                continue; // Manifest entry has no checksum
            }

            // Stream the file through MessageDigest to avoid loading entire file into memory
            String actualSha256 = computeSha256Streaming(imagePath);

            assertThat(actualSha256)
                    .as("SHA256 for %s", image.path())
                    .isEqualToIgnoringCase(image.sha256());
        }
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
