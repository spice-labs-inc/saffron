/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Generates verification JSON data for corpus images.
 *
 * <p>This utility scans a VM image, counts files and directories,
 * and samples up to 20 files with their SHA256 hashes for verification.
 *
 * <p>Run with: mvn exec:java -Dexec.mainClass="io.spicelabs.saffron.corpus.CorpusVerificationGenerator"
 */
public class CorpusVerificationGenerator {

    private static final Path CORPUS_PATH = Path.of("test-corpus");
    private static final Path VERIFICATION_PATH = Path.of("src/test/resources/corpus-verification");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_SAMPLE_FILES = 20;
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB max for sampling

    public static void main(String[] args) throws IOException {
        // Ensure verification directory exists
        Files.createDirectories(VERIFICATION_PATH);

        // Find all images in corpus
        Set<Path> existingVerifications = findExistingVerifications();
        List<Path> missingVerifications = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(CORPUS_PATH)) {
            walk.filter(Files::isRegularFile)
                .filter(CorpusVerificationGenerator::isVirtualDisk)
                .forEach(imagePath -> {
                    String verificationName = toVerificationName(imagePath);
                    Path verificationPath = VERIFICATION_PATH.resolve(verificationName);
                    if (!Files.exists(verificationPath)) {
                        missingVerifications.add(imagePath);
                    }
                });
        }

        System.out.println("Found " + missingVerifications.size() + " images without verification data:");
        for (Path image : missingVerifications) {
            System.out.println("  - " + CORPUS_PATH.relativize(image));
        }

        // Generate verification data for missing images
        for (Path imagePath : missingVerifications) {
            generateVerification(imagePath);
        }

        System.out.println("\nDone! Generated " + missingVerifications.size() + " verification files.");
    }

    /**
     * Generates verification data for a single image and writes to JSON file.
     */
    public static void generateVerification(Path imagePath) {
        String verificationName = toVerificationName(imagePath);
        Path verificationPath = VERIFICATION_PATH.resolve(verificationName);

        System.out.println("\nGenerating: " + verificationName);

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {
                VerificationData data = new VerificationData();
                data.imagePath = "/corpus/" + CORPUS_PATH.relativize(imagePath).toString();
                data.imageBasename = imagePath.getFileName().toString();
                data.filesystemType = fs.type().name().toLowerCase();

                // Count files and directories
                AtomicLong fileCount = new AtomicLong(0);
                AtomicLong dirCount = new AtomicLong(0);
                List<FileSystemEntry.RegularFile> candidateFiles = new ArrayList<>();

                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile file) {
                            fileCount.incrementAndGet();
                            // Collect candidate files for sampling
                            if (file.size() > 0 && file.size() < MAX_FILE_SIZE) {
                                candidateFiles.add(file);
                            }
                        } else if (entry instanceof FileSystemEntry.Directory) {
                            dirCount.incrementAndGet();
                        }
                    });
                }

                data.totalFiles = (int) fileCount.get();
                data.totalDirectories = (int) dirCount.get();

                System.out.println("  Files: " + data.totalFiles + ", Directories: " + data.totalDirectories);

                // Sample up to MAX_SAMPLE_FILES files
                data.sampleFiles = sampleFiles(candidateFiles);
                System.out.println("  Sampled " + data.sampleFiles.size() + " files for verification");

                // Write JSON
                String json = GSON.toJson(data);
                Files.writeString(verificationPath, json);
                System.out.println("  Written to: " + verificationPath);

            } catch (Exception e) {
                System.err.println("  ERROR mounting filesystem: " + e.getMessage());
                // Create minimal verification data for unsupported filesystems
                createMinimalVerification(imagePath, verificationPath, e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("  ERROR opening image: " + e.getMessage());
        }
    }

    /**
     * Creates minimal verification data for images that can't be fully mounted.
     */
    private static void createMinimalVerification(Path imagePath, Path verificationPath, String error) {
        try {
            VerificationData data = new VerificationData();
            data.imagePath = "/corpus/" + CORPUS_PATH.relativize(imagePath).toString();
            data.imageBasename = imagePath.getFileName().toString();
            data.filesystemType = "unsupported";
            data.totalFiles = -1;
            data.totalDirectories = -1;
            data.sampleFiles = List.of();
            data.error = error;

            String json = GSON.toJson(data);
            Files.writeString(verificationPath, json);
            System.out.println("  Created minimal verification (unsupported): " + verificationPath);
        } catch (IOException e) {
            System.err.println("  Failed to create minimal verification: " + e.getMessage());
        }
    }

    /**
     * Samples files from the candidate list for SHA256 verification.
     */
    private static List<SampleFile> sampleFiles(List<FileSystemEntry.RegularFile> candidates) {
        List<SampleFile> samples = new ArrayList<>();

        // Sort by path for deterministic selection, then sample evenly
        candidates.sort(Comparator.comparing(FileSystemEntry::path));

        // Select files evenly distributed across the list
        int step = Math.max(1, candidates.size() / MAX_SAMPLE_FILES);
        for (int i = 0; i < candidates.size() && samples.size() < MAX_SAMPLE_FILES; i += step) {
            FileSystemEntry.RegularFile file = candidates.get(i);
            try {
                byte[] content = file.readAllBytes();
                String sha256 = sha256(content);

                SampleFile sample = new SampleFile();
                sample.path = file.path();
                sample.sha256 = sha256;
                sample.size = content.length;
                samples.add(sample);
            } catch (Exception e) {
                // Skip files that can't be read
                System.err.println("    Skipping unreadable file: " + file.path() + " - " + e.getMessage());
            }
        }

        return samples;
    }

    /**
     * Computes SHA256 hash of data.
     */
    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts image path to verification JSON filename.
     */
    public static String toVerificationName(Path imagePath) {
        String name = CORPUS_PATH.relativize(imagePath).toString();
        // Remove directory path components
        name = imagePath.getFileName().toString();
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

    /**
     * Finds existing verification files.
     */
    private static Set<Path> findExistingVerifications() throws IOException {
        Set<Path> existing = new HashSet<>();
        if (Files.isDirectory(VERIFICATION_PATH)) {
            try (Stream<Path> files = Files.list(VERIFICATION_PATH)) {
                files.filter(p -> p.toString().endsWith(".json"))
                     .forEach(existing::add);
            }
        }
        return existing;
    }

    /**
     * Verification data structure for JSON.
     */
    static class VerificationData {
        String imagePath;
        String imageBasename;
        String filesystemType;
        int totalFiles;
        int totalDirectories;
        List<SampleFile> sampleFiles;
        String error; // Present only if there was an error
    }

    /**
     * Sample file data structure for JSON.
     */
    static class SampleFile {
        String path;
        String sha256;
        long size;
    }
}
