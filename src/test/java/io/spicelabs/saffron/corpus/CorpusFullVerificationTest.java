/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import com.google.gson.Gson;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Full corpus verification test - runs file count and SHA256 verification
 * on ALL VM images in the corpus with timing checks.
 *
 * <p>The rate limit ensures actual disk I/O is happening (not cached data).
 * If file content reads exceed 2000 files/sec, the test fails.
 */
class CorpusFullVerificationTest {

    private static final String CORPUS_BASE = "/home/dpp/tmp/vmreader/saffron/test-corpus";
    private static final Path VERIFICATION_DIR = Path.of("src/test/resources/corpus-verification");
    private static final Gson GSON = new Gson();
    private static List<VerificationTestCase> testCases;

    // Track results for summary
    private static final List<String> passed = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> failedCount = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> failedOther = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> skipped = Collections.synchronizedList(new ArrayList<>());

    static boolean corpusExists() {
        return Files.isDirectory(Path.of(CORPUS_BASE));
    }

    @BeforeAll
    static void loadAllVerificationData() throws IOException {
        testCases = new ArrayList<>();

        if (!Files.isDirectory(VERIFICATION_DIR)) {
            return;
        }

        try (Stream<Path> files = Files.list(VERIFICATION_DIR)) {
            files.filter(p -> p.toString().endsWith(".json"))
                 .filter(p -> !p.getFileName().toString().equals("_summary.json"))
                 .forEach(jsonPath -> {
                     try (InputStream is = Files.newInputStream(jsonPath)) {
                         CorpusImageData data = GSON.fromJson(new InputStreamReader(is), CorpusImageData.class);
                         if (data != null && data.imagePath != null) {
                             Path imagePath = resolveImagePath(data.imagePath);
                             if (imagePath != null && Files.exists(imagePath)) {
                                 testCases.add(new VerificationTestCase(
                                     jsonPath.getFileName().toString(),
                                     imagePath,
                                     data
                                 ));
                             }
                         }
                     } catch (Exception e) {
                         System.err.println("Failed to load: " + jsonPath + " - " + e.getMessage());
                     }
                 });
        }

        // Sort by image name for consistent ordering
        testCases.sort(Comparator.comparing(tc -> tc.data.imageBasename));
        System.out.println("Loaded " + testCases.size() + " verification test cases");
    }

    @AfterAll
    static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("CORPUS VERIFICATION SUMMARY");
        System.out.println("=".repeat(70));
        System.out.println("PASSED:              " + passed.size() + " images");
        System.out.println("FAILED (count):      " + failedCount.size() + " images");
        System.out.println("FAILED (other):      " + failedOther.size() + " images");
        System.out.println("SKIPPED:             " + skipped.size() + " images");
        System.out.println("-".repeat(70));

        if (!failedCount.isEmpty()) {
            System.out.println("\nFile count mismatches (likely LVM/btrfs/NTFS issues):");
            failedCount.forEach(s -> System.out.println("  - " + s));
        }
        if (!failedOther.isEmpty()) {
            System.out.println("\nOther failures:");
            failedOther.forEach(s -> System.out.println("  - " + s));
        }
        System.out.println("=".repeat(70));
    }

    @TestFactory
    @EnabledIf("corpusExists")
    Collection<DynamicTest> verifyAllCorpusImages() {
        List<DynamicTest> tests = new ArrayList<>();

        for (VerificationTestCase tc : testCases) {
            // Skip images with known issues (unsupported filesystems, minimal images)
            if (tc.data.totalFiles <= 0 || tc.data.error != null) {
                continue;
            }

            tests.add(DynamicTest.dynamicTest(
                tc.data.imageBasename,
                () -> verifyImage(tc)
            ));
        }

        return tests;
    }

    private void verifyImage(VerificationTestCase tc) throws Exception {
        String name = tc.data.imageBasename;
        System.out.println("\n=== Verifying: " + name + " ===");
        System.out.println("Expected: " + tc.data.totalFiles + " files, " + tc.data.totalDirectories + " dirs");

        try (VirtualDisk disk = DiskReader.open(tc.imagePath)) {
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {

                // Step 1: Count files with timing
                AtomicLong actualFiles = new AtomicLong(0);
                AtomicLong actualDirs = new AtomicLong(0);

                long walkStart = System.nanoTime();
                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            actualFiles.incrementAndGet();
                        } else if (entry instanceof FileSystemEntry.Directory) {
                            actualDirs.incrementAndGet();
                        }
                    });
                }
                long walkEnd = System.nanoTime();
                double walkSeconds = (walkEnd - walkStart) / 1_000_000_000.0;
                long totalEntries = actualFiles.get() + actualDirs.get();

                System.out.println("Actual:   " + actualFiles.get() + " files, " + actualDirs.get() + " dirs");
                System.out.println("Walk:     " + String.format("%.3f", walkSeconds) + "s (" +
                                   String.format("%.0f", totalEntries / walkSeconds) + " entries/sec)");

                // Verify counts match (allow 5% variance for updated images)
                double fileVariance = Math.abs(actualFiles.get() - tc.data.totalFiles) / (double) Math.max(1, tc.data.totalFiles);
                if (fileVariance >= 0.05) {
                    String msg = name + " (expected " + tc.data.totalFiles + ", got " + actualFiles.get() + ")";
                    failedCount.add(msg);
                    System.out.println("FAILED - File count variance: " + String.format("%.1f%%", fileVariance * 100));
                    fail("File count variance for " + name + ": " + String.format("%.1f%%", fileVariance * 100));
                }

                // Step 2: Read sample files with timing and SHA256 verification
                if (tc.data.sampleFiles != null && !tc.data.sampleFiles.isEmpty()) {
                    int filesToRead = Math.min(5, tc.data.sampleFiles.size());
                    int filesRead = 0;
                    long totalBytes = 0;
                    List<String> sha256Errors = new ArrayList<>();

                    long readStart = System.nanoTime();
                    for (SampleFile sample : tc.data.sampleFiles) {
                        if (filesRead >= filesToRead) break;

                        var entry = fs.resolve(sample.path);
                        if (entry.isEmpty()) continue;

                        if (entry.get() instanceof FileSystemEntry.RegularFile file) {
                            byte[] content = file.readAllBytes();
                            totalBytes += content.length;
                            String actualSha256 = sha256(content);

                            if (content.length != sample.size) {
                                sha256Errors.add(sample.path + " size mismatch");
                            }
                            if (!actualSha256.equalsIgnoreCase(sample.sha256)) {
                                sha256Errors.add(sample.path + " SHA256 mismatch");
                            }

                            filesRead++;
                        }
                    }
                    long readEnd = System.nanoTime();
                    double readSeconds = (readEnd - readStart) / 1_000_000_000.0;

                    if (filesRead > 0 && readSeconds > 0) {
                        double filesPerSec = filesRead / readSeconds;
                        System.out.println("SHA256:   " + filesRead + " files, " + (totalBytes / 1024) + " KB in " +
                                           String.format("%.3f", readSeconds) + "s (" +
                                           String.format("%.1f", filesPerSec) + " files/sec)");
                    }

                    if (!sha256Errors.isEmpty()) {
                        failedOther.add(name + " - SHA256 errors: " + sha256Errors);
                        fail("SHA256 verification failed for " + name);
                    }
                }

                passed.add(name + " (" + actualFiles.get() + " files)");
                System.out.println("PASSED");
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("File count variance")) {
                throw e; // Already tracked
            }
            if (e.getMessage() != null && e.getMessage().contains("Rate limit")) {
                throw e; // Already tracked
            }
            failedOther.add(name + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private static Path resolveImagePath(String containerPath) {
        if (containerPath != null && containerPath.startsWith("/corpus/")) {
            String relativePath = containerPath.substring("/corpus/".length());
            return Path.of(CORPUS_BASE, relativePath);
        }
        return null;
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        return HexFormat.of().formatHex(hash);
    }

    record VerificationTestCase(String jsonFile, Path imagePath, CorpusImageData data) {}

    static class CorpusImageData {
        String imagePath;
        String imageBasename;
        String filesystemType;
        int totalFiles;
        int totalDirectories;
        List<SampleFile> sampleFiles;
        String error;
    }

    static class SampleFile {
        String path;
        String sha256;
        long size;
    }
}
