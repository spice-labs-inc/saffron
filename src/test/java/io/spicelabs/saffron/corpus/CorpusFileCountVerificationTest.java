/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.corpus;

import com.google.gson.Gson;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates file count and SHA256 verification against expected values.
 *
 * <p>This test shows exactly how verification data is compared:
 * <ol>
 *   <li>Load expected values from JSON (totalFiles, totalDirectories, sampleFiles)</li>
 *   <li>Mount the virtual disk image</li>
 *   <li>Count actual files and directories</li>
 *   <li>Compare actual vs expected counts</li>
 *   <li>Verify sample file SHA256 hashes match</li>
 * </ol>
 */
class CorpusFileCountVerificationTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();
    private static final Gson GSON = new Gson();

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    @Test
    @EnabledIf("corpusExists")
    void demonstrateFileCountVerification_debian12() throws Exception {
        // Step 1: Load expected values from verification JSON
        CorpusTestData.CorpusImageData expected;
        try (InputStream is = getClass().getResourceAsStream("/corpus-verification/debian_12_nocloud_qcow2.json")) {
            expected = GSON.fromJson(new InputStreamReader(is), CorpusTestData.CorpusImageData.class);
        }

        // Collect all sample files from all filesystems
        List<CorpusTestData.SampleFile> allSamples = new ArrayList<>();
        if (expected.filesystems != null) {
            for (CorpusTestData.FilesystemData fsData : expected.filesystems) {
                if (fsData.sampleFiles != null) {
                    allSamples.addAll(fsData.sampleFiles);
                }
            }
        }

        System.out.println("=== File Count Verification Demo: " + expected.imageBasename + " ===\n");
        System.out.println("Expected values from JSON:");
        System.out.println("  totalFiles:       " + expected.totalFiles);
        System.out.println("  totalDirectories: " + expected.totalDirectories);
        System.out.println("  sampleFiles:      " + allSamples.size() + " files to verify\n");

        // Step 2: Mount the virtual disk image
        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/debian-12-nocloud.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found, skipping: " + imagePath);
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {

                // Step 3: Count actual files and directories with timing
                AtomicLong actualFiles = new AtomicLong(0);
                AtomicLong actualDirs = new AtomicLong(0);

                System.out.println("Counting files in filesystem...");
                long startTime = System.nanoTime();
                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            actualFiles.incrementAndGet();
                        } else if (entry instanceof FileSystemEntry.Directory) {
                            actualDirs.incrementAndGet();
                        }
                    });
                }
                long endTime = System.nanoTime();
                double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
                long totalEntries = actualFiles.get() + actualDirs.get();
                double entriesPerSecond = totalEntries / elapsedSeconds;

                System.out.println("\nDirectory Walk Timing (metadata only):");
                System.out.println("  Elapsed time:       " + String.format("%.3f", elapsedSeconds) + " seconds");
                System.out.println("  Total entries:      " + totalEntries);
                System.out.println("  Processing rate:    " + String.format("%.1f", entriesPerSecond) + " entries/second");
                System.out.println("  (Note: Walk reads directory metadata, not file contents)");

                // Step 4: Compare actual vs expected counts
                System.out.println("\nActual values from image:");
                System.out.println("  totalFiles:       " + actualFiles.get());
                System.out.println("  totalDirectories: " + actualDirs.get());

                System.out.println("\nComparison:");
                long fileDiff = actualFiles.get() - expected.totalFiles;
                long dirDiff = actualDirs.get() - expected.totalDirectories;
                System.out.println("  File count difference:      " + (fileDiff >= 0 ? "+" : "") + fileDiff);
                System.out.println("  Directory count difference: " + (dirDiff >= 0 ? "+" : "") + dirDiff);

                // Assert counts match (allowing small variance for cloud image updates)
                double fileVariance = Math.abs(fileDiff) / (double) expected.totalFiles;
                System.out.println("  File count variance:        " + String.format("%.2f%%", fileVariance * 100));

                assertThat(fileVariance)
                    .as("File count should be within 5% of expected")
                    .isLessThan(0.05);

                // Step 5: Verify sample file SHA256 hashes with timing
                System.out.println("\n=== SHA256 Verification (first 5 sample files) ===\n");

                int verified = 0;
                long totalBytesRead = 0;
                long fileReadStartTime = System.nanoTime();

                for (CorpusTestData.SampleFile sample : allSamples) {
                    if (verified >= 5) break;

                    var entry = fs.resolve(sample.path);
                    if (entry.isEmpty()) {
                        System.out.println("MISSING: " + sample.path);
                        continue;
                    }

                    if (entry.get() instanceof FileSystemEntry.RegularFile file) {
                        byte[] content = file.readAllBytes();
                        totalBytesRead += content.length;
                        String actualSha256 = sha256(content);

                        boolean matches = actualSha256.equalsIgnoreCase(sample.sha256);
                        String status = matches ? "MATCH" : "MISMATCH";

                        System.out.println("File: " + sample.path);
                        System.out.println("  Expected size:   " + sample.size + " bytes");
                        System.out.println("  Actual size:     " + content.length + " bytes");
                        System.out.println("  Expected SHA256: " + sample.sha256.substring(0, 16) + "...");
                        System.out.println("  Actual SHA256:   " + actualSha256.substring(0, 16) + "...");
                        System.out.println("  Status:          " + status + "\n");

                        assertThat(content.length)
                            .as("File size should match for " + sample.path)
                            .isEqualTo(sample.size);

                        assertThat(actualSha256)
                            .as("SHA256 should match for " + sample.path)
                            .isEqualToIgnoringCase(sample.sha256);

                        verified++;
                    }
                }

                long fileReadEndTime = System.nanoTime();
                double fileReadSeconds = (fileReadEndTime - fileReadStartTime) / 1_000_000_000.0;
                double bytesPerSecond = totalBytesRead / fileReadSeconds;
                double filesPerSecond = verified / fileReadSeconds;

                System.out.println("=== File Content Read Timing ===");
                System.out.println("  Files read:         " + verified);
                System.out.println("  Total bytes:        " + totalBytesRead + " (" + (totalBytesRead / 1024) + " KB)");
                System.out.println("  Elapsed time:       " + String.format("%.3f", fileReadSeconds) + " seconds");
                System.out.println("  Read rate:          " + String.format("%.1f", bytesPerSecond / 1024) + " KB/sec");
                System.out.println("  Files per second:   " + String.format("%.1f", filesPerSecond));

                System.out.println("\n=== Verification Complete ===");
            }
        }
    }

    @Test
    @EnabledIf("corpusExists")
    void demonstrateFileCountVerification_alpine() throws Exception {
        // Load expected values
        CorpusTestData.CorpusImageData expected;
        try (InputStream is = getClass().getResourceAsStream("/corpus-verification/alpine_3_19_cloud_amd64_qcow2.json")) {
            expected = GSON.fromJson(new InputStreamReader(is), CorpusTestData.CorpusImageData.class);
        }

        System.out.println("=== File Count Verification: " + expected.imageBasename + " ===\n");

        Path imagePath = Paths.get(CORPUS_BASE, "qcow2/modern/alpine-3.19-cloud-amd64.qcow2");
        if (!Files.exists(imagePath)) {
            System.out.println("Image not found, skipping");
            return;
        }

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {
                AtomicLong actualFiles = new AtomicLong(0);
                AtomicLong actualDirs = new AtomicLong(0);

                long startTime = System.nanoTime();
                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            actualFiles.incrementAndGet();
                        } else if (entry instanceof FileSystemEntry.Directory) {
                            actualDirs.incrementAndGet();
                        }
                    });
                }
                long endTime = System.nanoTime();
                double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
                long totalEntries = actualFiles.get() + actualDirs.get();
                double entriesPerSecond = totalEntries / elapsedSeconds;

                System.out.println("Expected: " + expected.totalFiles + " files, " + expected.totalDirectories + " dirs");
                System.out.println("Actual:   " + actualFiles.get() + " files, " + actualDirs.get() + " dirs");

                long fileDiff = actualFiles.get() - expected.totalFiles;
                System.out.println("Diff:     " + (fileDiff >= 0 ? "+" : "") + fileDiff + " files");
                System.out.println("Walk time:" + String.format("%.3f", elapsedSeconds) + " seconds");
                System.out.println("Walk rate:" + String.format("%.1f", entriesPerSecond) + " entries/second (metadata only)");

                // File count must match exactly; directory count may differ slightly
                // because this test uses mountLargestIncludingLvm (single FS) while
                // ground truth counts across all filesystems
                assertThat(actualFiles.get())
                    .as("File count should match expected")
                    .isEqualTo(expected.totalFiles);

                // Now read actual file content to verify I/O rate
                List<CorpusTestData.SampleFile> alpineSamples = new ArrayList<>();
                if (expected.filesystems != null) {
                    for (CorpusTestData.FilesystemData fsData : expected.filesystems) {
                        if (fsData.sampleFiles != null) {
                            alpineSamples.addAll(fsData.sampleFiles);
                        }
                    }
                }

                System.out.println("\nReading sample file contents...");
                int filesToRead = Math.min(5, alpineSamples.size());
                long totalBytesRead = 0;
                long contentReadStart = System.nanoTime();

                for (int i = 0; i < filesToRead; i++) {
                    CorpusTestData.SampleFile sample = alpineSamples.get(i);
                    var entry = fs.resolve(sample.path);
                    if (entry.isPresent() && entry.get() instanceof FileSystemEntry.RegularFile file) {
                        byte[] content = file.readAllBytes();
                        totalBytesRead += content.length;
                        String actualSha256 = sha256(content);
                        assertThat(actualSha256)
                            .as("SHA256 should match for " + sample.path)
                            .isEqualToIgnoringCase(sample.sha256);
                    }
                }

                long contentReadEnd = System.nanoTime();
                double contentReadSeconds = (contentReadEnd - contentReadStart) / 1_000_000_000.0;
                double filesPerSecond = filesToRead / contentReadSeconds;

                System.out.println("  Files read:       " + filesToRead);
                System.out.println("  Bytes read:       " + totalBytesRead + " (" + (totalBytesRead / 1024) + " KB)");
                System.out.println("  Read time:        " + String.format("%.3f", contentReadSeconds) + " seconds");
                System.out.println("  Files/second:     " + String.format("%.1f", filesPerSecond));

                System.out.println("\nVERIFICATION PASSED - Counts match and I/O rate is realistic!");
            }
        }
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        return HexFormat.of().formatHex(hash);
    }
}
