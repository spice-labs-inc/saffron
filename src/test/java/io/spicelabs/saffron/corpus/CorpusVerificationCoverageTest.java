/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that all corpus images have corresponding verification data.
 *
 * <p>This test ensures that every VM image in the test-corpus directory
 * has a corresponding verification JSON file in corpus-verification.
 * This prevents regression testing gaps when new images are added.
 */
class CorpusVerificationCoverageTest {

    private static final Path CORPUS_PATH = Path.of("test-corpus");
    private static final Path VERIFICATION_PATH = Path.of("src/test/resources/corpus-verification");

    /**
     * Only run if corpus is populated.
     */
    static boolean corpusIsPopulated() {
        try {
            if (!Files.isDirectory(CORPUS_PATH)) {
                return false;
            }
            try (Stream<Path> walk = Files.walk(CORPUS_PATH)) {
                return walk.filter(Files::isRegularFile)
                           .anyMatch(CorpusVerificationCoverageTest::isVirtualDisk);
            }
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("corpusIsPopulated")
    void allCorpusImages_shouldHaveVerificationData() throws IOException {
        List<String> missingVerifications = new ArrayList<>();
        List<String> existingImages = new ArrayList<>();

        // Find all images in corpus
        try (Stream<Path> walk = Files.walk(CORPUS_PATH)) {
            walk.filter(Files::isRegularFile)
                .filter(CorpusVerificationCoverageTest::isVirtualDisk)
                .forEach(imagePath -> {
                    String verificationName = toVerificationName(imagePath);
                    Path verificationPath = VERIFICATION_PATH.resolve(verificationName);
                    existingImages.add(CORPUS_PATH.relativize(imagePath).toString());

                    if (!Files.exists(verificationPath)) {
                        missingVerifications.add(CORPUS_PATH.relativize(imagePath).toString() +
                                                 " -> " + verificationName);
                    }
                });
        }

        if (!missingVerifications.isEmpty()) {
            // Provide helpful error message with instructions
            StringBuilder message = new StringBuilder();
            message.append("Found ").append(missingVerifications.size())
                   .append(" corpus images without verification data:\n\n");

            for (String missing : missingVerifications) {
                message.append("  - ").append(missing).append("\n");
            }

            message.append("\nTo generate missing verification data, run:\n");
            message.append("  mvn exec:java -Dexec.mainClass=\"io.spicelabs.saffron.corpus.CorpusVerificationGenerator\" -Dexec.classpathScope=test\n");
            message.append("\nOr run the generator directly in your IDE.\n");

            fail(message.toString());
        }

        // Log success
        System.out.println("All " + existingImages.size() + " corpus images have verification data.");
    }

    @Test
    @EnabledIf("corpusIsPopulated")
    void verificationFiles_shouldHaveCorrespondingImages() throws IOException {
        if (!Files.isDirectory(VERIFICATION_PATH)) {
            return; // No verification directory yet
        }

        Set<String> imageVerificationNames = new HashSet<>();

        // Collect verification names for all existing images
        try (Stream<Path> walk = Files.walk(CORPUS_PATH)) {
            walk.filter(Files::isRegularFile)
                .filter(CorpusVerificationCoverageTest::isVirtualDisk)
                .forEach(imagePath -> {
                    imageVerificationNames.add(toVerificationName(imagePath));
                });
        }

        // Check for orphaned verification files
        List<String> orphanedVerifications = new ArrayList<>();
        try (Stream<Path> verificationFiles = Files.list(VERIFICATION_PATH)) {
            verificationFiles.filter(p -> p.toString().endsWith(".json"))
                            .filter(p -> !p.getFileName().toString().equals("_summary.json"))
                            .forEach(verificationPath -> {
                                String name = verificationPath.getFileName().toString();
                                if (!imageVerificationNames.contains(name)) {
                                    orphanedVerifications.add(name);
                                }
                            });
        }

        if (!orphanedVerifications.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append("Found ").append(orphanedVerifications.size())
                   .append(" orphaned verification files (no corresponding image):\n\n");

            for (String orphan : orphanedVerifications) {
                message.append("  - ").append(orphan).append("\n");
            }

            message.append("\nThese verification files may be stale and should be reviewed.\n");

            // This is a warning, not a failure - images may have been removed intentionally
            System.err.println("WARNING: " + message);
        }
    }

    /**
     * Converts image path to verification JSON filename.
     * Must match the logic in CorpusVerificationGenerator.
     */
    private static String toVerificationName(Path imagePath) {
        String name = imagePath.getFileName().toString();
        // Remove extension and convert to safe filename
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            String extension = name.substring(dotIndex + 1);
            name = name.substring(0, dotIndex);
            // Convert to verification name format (replace special chars with underscore)
            name = name.replace("-", "_").replace(".", "_").replace("!", "_").replace(" ", "_");
            name = name + "_" + extension + ".json";
        }
        return name;
    }

    /**
     * Checks if a path is a virtual disk image.
     */
    private static boolean isVirtualDisk(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".qcow2") || name.endsWith(".vmdk") ||
               name.endsWith(".vdi") || name.endsWith(".vhd") || name.endsWith(".vhdx");
    }
}
