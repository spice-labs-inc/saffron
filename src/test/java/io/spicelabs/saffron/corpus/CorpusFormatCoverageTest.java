/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import io.spicelabs.saffron.DiskFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that all corpus images can be parsed correctly by format.
 *
 * <p>These tests verify that the DiskReader can open and read basic
 * metadata from each image in the corpus.
 */
@EnabledIf("corpusIsPopulated")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class CorpusFormatCoverageTest {

    private static final Path CORPUS_PATH = Path.of("test-corpus");
    private static CorpusManifest manifest;

    /**
     * Only run these tests if the corpus has actual images (not just empty manifest).
     */
    static boolean corpusIsPopulated() {
        try {
            Path manifestPath = CORPUS_PATH.resolve("manifest.json");
            if (!Files.exists(manifestPath)) {
                return false;
            }
            CorpusManifest m = CorpusManifest.load(CORPUS_PATH);
            return m.totalImages() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void loadManifest() throws IOException {
        manifest = CorpusManifest.load(CORPUS_PATH);
    }

    // ========================================================================
    // Format-specific tests
    // ========================================================================

    @ParameterizedTest(name = "VMDK: {0}")
    @MethodSource("vmdkImages")
    void vmdk_images_canDetectFormat(CorpusImage image) throws IOException {
        Path imagePath = CORPUS_PATH.resolve(image.path());

        if (!Files.exists(imagePath)) {
            return; // Skip missing files - covered by validation test
        }

        var detected = DiskFormat.detect(imagePath);

        assertThat(detected)
                .as("Should detect VMDK format for %s", image.path())
                .isPresent()
                .contains(DiskFormat.VMDK);
    }

    @ParameterizedTest(name = "QCOW2: {0}")
    @MethodSource("qcow2Images")
    void qcow2_images_canDetectFormat(CorpusImage image) throws IOException {
        Path imagePath = CORPUS_PATH.resolve(image.path());

        if (!Files.exists(imagePath)) {
            return;
        }

        var detected = DiskFormat.detect(imagePath);

        assertThat(detected)
                .as("Should detect QCOW2 format for %s", image.path())
                .isPresent()
                .contains(DiskFormat.QCOW2);
    }

    @ParameterizedTest(name = "VHD: {0}")
    @MethodSource("vhdImages")
    void vhd_images_canDetectFormat(CorpusImage image) throws IOException {
        Path imagePath = CORPUS_PATH.resolve(image.path());

        if (!Files.exists(imagePath)) {
            return;
        }

        var detected = DiskFormat.detect(imagePath);

        assertThat(detected)
                .as("Should detect VHD format for %s", image.path())
                .isPresent()
                .contains(DiskFormat.VHD);
    }

    @ParameterizedTest(name = "VHDX: {0}")
    @MethodSource("vhdxImages")
    void vhdx_images_canDetectFormat(CorpusImage image) throws IOException {
        Path imagePath = CORPUS_PATH.resolve(image.path());

        if (!Files.exists(imagePath)) {
            return;
        }

        var detected = DiskFormat.detect(imagePath);

        assertThat(detected)
                .as("Should detect VHDX format for %s", image.path())
                .isPresent()
                .contains(DiskFormat.VHDX);
    }

    @ParameterizedTest(name = "VDI: {0}")
    @MethodSource("vdiImages")
    void vdi_images_canDetectFormat(CorpusImage image) throws IOException {
        Path imagePath = CORPUS_PATH.resolve(image.path());

        if (!Files.exists(imagePath)) {
            return;
        }

        var detected = DiskFormat.detect(imagePath);

        assertThat(detected)
                .as("Should detect VDI format for %s", image.path())
                .isPresent()
                .contains(DiskFormat.VDI);
    }

    // ========================================================================
    // Legacy format tests (2005-2010)
    // ========================================================================

    @ParameterizedTest(name = "Legacy VMDK: {0}")
    @MethodSource("legacyVmdkImages")
    void vmdk_legacyImages_canDetectFormat(CorpusImage image) throws IOException {
        vmdk_images_canDetectFormat(image);
    }

    @ParameterizedTest(name = "Legacy QCOW2: {0}")
    @MethodSource("legacyQcow2Images")
    void qcow2_legacyImages_canDetectFormat(CorpusImage image) throws IOException {
        qcow2_images_canDetectFormat(image);
    }

    @ParameterizedTest(name = "Legacy VHD: {0}")
    @MethodSource("legacyVhdImages")
    void vhd_legacyImages_canDetectFormat(CorpusImage image) throws IOException {
        vhd_images_canDetectFormat(image);
    }

    @ParameterizedTest(name = "Legacy VDI: {0}")
    @MethodSource("legacyVdiImages")
    void vdi_legacyImages_canDetectFormat(CorpusImage image) throws IOException {
        vdi_images_canDetectFormat(image);
    }

    // ========================================================================
    // Method sources
    // ========================================================================

    static Stream<CorpusImage> vmdkImages() {
        return getImagesByFormat(DiskFormat.VMDK);
    }

    static Stream<CorpusImage> qcow2Images() {
        return getImagesByFormat(DiskFormat.QCOW2);
    }

    static Stream<CorpusImage> vhdImages() {
        return getImagesByFormat(DiskFormat.VHD);
    }

    static Stream<CorpusImage> vhdxImages() {
        return getImagesByFormat(DiskFormat.VHDX);
    }

    static Stream<CorpusImage> vdiImages() {
        return getImagesByFormat(DiskFormat.VDI);
    }

    static Stream<CorpusImage> legacyVmdkImages() {
        return getLegacyImagesByFormat(DiskFormat.VMDK);
    }

    static Stream<CorpusImage> legacyQcow2Images() {
        return getLegacyImagesByFormat(DiskFormat.QCOW2);
    }

    static Stream<CorpusImage> legacyVhdImages() {
        return getLegacyImagesByFormat(DiskFormat.VHD);
    }

    static Stream<CorpusImage> legacyVdiImages() {
        return getLegacyImagesByFormat(DiskFormat.VDI);
    }

    private static Stream<CorpusImage> getImagesByFormat(DiskFormat format) {
        if (manifest == null) {
            // Return a placeholder that will be skipped (file doesn't exist)
            return Stream.of(createPlaceholder(format, "modern"));
        }
        Stream<CorpusImage> images = manifest.imagesByFormat(format);
        java.util.List<CorpusImage> imageList = images.toList();
        if (imageList.isEmpty()) {
            // Return a placeholder that will be skipped (file doesn't exist)
            return Stream.of(createPlaceholder(format, "modern"));
        }
        return imageList.stream();
    }

    private static Stream<CorpusImage> getLegacyImagesByFormat(DiskFormat format) {
        if (manifest == null) {
            // Return a placeholder that will be skipped (file doesn't exist)
            return Stream.of(createPlaceholder(format, "legacy"));
        }
        java.util.List<CorpusImage> imageList = manifest.imagesByFormat(format)
                .filter(CorpusImage::isLegacy)
                .toList();
        if (imageList.isEmpty()) {
            // Return a placeholder that will be skipped (file doesn't exist)
            return Stream.of(createPlaceholder(format, "legacy"));
        }
        return imageList.stream();
    }

    private static CorpusImage createPlaceholder(DiskFormat format, String era) {
        return CorpusImage.builder()
                .id("__placeholder__")
                .path("__nonexistent__")
                .format(format.name().toLowerCase())
                .era(era)
                .build();
    }
}
