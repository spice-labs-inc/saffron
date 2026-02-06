/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class CorpusValidationTest {

    private static final Path CORPUS_PATH = Path.of("test-corpus");
    private static CorpusManifest manifest;

    /**
     * Only run these tests if the corpus is fully populated (200+ images).
     * This is a validation suite for production corpus, not the minimal test corpus.
     */
    static boolean corpusIsPopulated() {
        try {
            Path manifestPath = CORPUS_PATH.resolve("manifest.json");
            if (!Files.exists(manifestPath)) {
                return false;
            }
            CorpusManifest m = CorpusManifest.load(CORPUS_PATH);
            // Only run validation tests for full corpus (200+ images)
            return m.totalImages() >= 200;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void loadManifest() throws IOException {
        manifest = CorpusManifest.load(CORPUS_PATH);
    }

    @Test
    void corpus_hasMinimumImageCount() {
        assertThat(manifest.totalImages())
                .as("Corpus should have at least 200 images")
                .isGreaterThanOrEqualTo(200);
    }

    @Test
    void corpus_hasAllRequiredFormats() {
        Map<DiskFormat, Long> formatCounts = manifest.countByFormat();

        assertThat(formatCounts.getOrDefault(DiskFormat.VMDK, 0L))
                .as("VMDK count")
                .isGreaterThanOrEqualTo(50);

        assertThat(formatCounts.getOrDefault(DiskFormat.QCOW2, 0L))
                .as("QCOW2 count")
                .isGreaterThanOrEqualTo(50);

        assertThat(formatCounts.getOrDefault(DiskFormat.VHD, 0L))
                .as("VHD count")
                .isGreaterThanOrEqualTo(30);

        assertThat(formatCounts.getOrDefault(DiskFormat.VHDX, 0L))
                .as("VHDX count")
                .isGreaterThanOrEqualTo(20);

        assertThat(formatCounts.getOrDefault(DiskFormat.VDI, 0L))
                .as("VDI count")
                .isGreaterThanOrEqualTo(50);
    }

    @Test
    void corpus_hasRequiredFilesystems() {
        Map<String, Long> fsCounts = manifest.countByFilesystem();

        long extCount = fsCounts.getOrDefault("ext4", 0L) +
                        fsCounts.getOrDefault("ext3", 0L) +
                        fsCounts.getOrDefault("ext2", 0L);

        assertThat(extCount)
                .as("ext4/ext3/ext2 count")
                .isGreaterThanOrEqualTo(80);

        assertThat(fsCounts.getOrDefault("ntfs", 0L))
                .as("NTFS count")
                .isGreaterThanOrEqualTo(50);

        long fatCount = fsCounts.getOrDefault("fat32", 0L) +
                        fsCounts.getOrDefault("fat16", 0L);

        assertThat(fatCount)
                .as("FAT32/FAT16 count")
                .isGreaterThanOrEqualTo(30);

        assertThat(fsCounts.getOrDefault("xfs", 0L))
                .as("XFS count")
                .isGreaterThanOrEqualTo(20);
    }

    @Test
    void corpus_hasLegacyImages() {
        long legacyCount = manifest.legacyCount();

        assertThat(legacyCount)
                .as("Legacy images (2005-2010)")
                .isGreaterThanOrEqualTo(100);
    }

    @Test
    void corpus_allImageFilesExist() {
        for (CorpusImage image : manifest.images()) {
            Path imagePath = CORPUS_PATH.resolve(image.path());

            assertThat(imagePath)
                    .as("Image file should exist: %s", image.path())
                    .exists();
        }
    }

    @Test
    void corpus_allImageChecksumsMatch() throws Exception {
        for (CorpusImage image : manifest.images()) {
            Path imagePath = CORPUS_PATH.resolve(image.path());

            if (!Files.exists(imagePath)) {
                continue; // Covered by existence test
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

            long actualSize = Files.size(imagePath);

            assertThat(actualSize)
                    .as("File size for %s", image.path())
                    .isEqualTo(image.actualSizeBytes());
        }
    }

    @Test
    void corpus_hasNoOrphanedFiles() throws IOException {
        // Find all image files in the corpus directory
        try (var stream = Files.walk(CORPUS_PATH)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String name = p.getFileName().toString().toLowerCase();
                      return name.endsWith(".qcow2") || name.endsWith(".vmdk") ||
                             name.endsWith(".vhd") || name.endsWith(".vhdx") ||
                             name.endsWith(".vdi");
                  })
                  .forEach(path -> {
                      String relPath = CORPUS_PATH.relativize(path).toString();
                      boolean inManifest = manifest.images().stream()
                              .anyMatch(img -> img.path().equals(relPath));

                      assertThat(inManifest)
                              .as("File should be in manifest: %s", relPath)
                              .isTrue();
                  });
        }
    }
}
