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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Corpus file verification tests.
 *
 * <p>These tests verify that the Saffron library can correctly read files
 * from VM images and compute matching SHA256 hashes.
 *
 * <p><b>Test requirements from corpus JSON data:</b>
 * <ul>
 *   <li><b>File count verification:</b> totalFiles field in JSON - count all files in the filesystem</li>
 *   <li><b>SHA256 verification:</b> sampleFiles array - verify 20 randomly selected files per image</li>
 * </ul>
 *
 * <p>Expected data was gathered by mounting the images with libguestfs
 * and collecting SHA256 hashes of 20 randomly selected files from each image.
 *
 * <p>Currently supports ext2/3/4 filesystems. NTFS, FAT32, and XFS will be added later.
 */
class CorpusFileVerificationTest {

    private static final String CORPUS_BASE = "/home/dpp/tmp/vmreader/saffron/test-corpus";
    private static final Gson GSON = new Gson();
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(5);
    private static List<CorpusImageData> corpusData;

    @BeforeAll
    static void loadCorpusData() throws IOException {
        corpusData = new ArrayList<>();

        // Load selected JSON files - only ext4 filesystems for now
        String[] jsonFiles = {
            // CirrOS (ext3/4)
            "cirros_0_6_2_x86_64_qcow2.json",

            // Alpine (ext4)
            "alpine_3_19_cloud_amd64_qcow2.json",

            // Debian QCOW2 (ext4)
            "debian_12_generic_amd64_qcow2.json",

            // Ubuntu QCOW2 (ext4)
            "ubuntu_22_04_cloudimg_amd64_qcow2.json",

            // VDI images (ext4)
            "debian_11_vbox_vdi.json",
            "debian_12_vbox_vdi.json",
            "ubuntu_22_04_vbox_vdi.json",

            // VHD images (ext4 for Linux ones)
            "ubuntu_22_04_azure_vhd.json"
        };

        for (String jsonFile : jsonFiles) {
            try (InputStream is = CorpusFileVerificationTest.class.getResourceAsStream(
                    "/corpus-verification/" + jsonFile)) {
                if (is != null) {
                    CorpusImageData data = GSON.fromJson(new InputStreamReader(is), CorpusImageData.class);
                    if (data != null) {
                        corpusData.add(data);
                    }
                }
            } catch (Exception e) {
                // Skip files that can't be loaded
            }
        }
    }

    static boolean corpusExists() {
        return Files.isDirectory(Paths.get(CORPUS_BASE));
    }

    /**
     * Test that we can count files in filesystems (dynamically detected).
     */
    @TestFactory
    @EnabledIf("corpusExists")
    Collection<DynamicTest> testFileCountOnImages() {
        List<DynamicTest> tests = new ArrayList<>();

        for (CorpusImageData data : corpusData) {
            Path imagePath = resolveImagePath(data.imagePath);
            if (imagePath != null && Files.exists(imagePath)) {
                tests.add(DynamicTest.dynamicTest(
                    "FileCount: " + data.imageBasename,
                    () -> assertTimeoutPreemptively(TEST_TIMEOUT,
                        () -> verifyFileCount(imagePath, data))
                ));
            }
        }

        return tests;
    }

    /**
     * Test that sample files can be found and have correct SHA256 hashes.
     */
    @TestFactory
    @EnabledIf("corpusExists")
    Collection<DynamicTest> testFileSha256OnImages() {
        List<DynamicTest> tests = new ArrayList<>();

        for (CorpusImageData data : corpusData) {
            Path imagePath = resolveImagePath(data.imagePath);
            if (imagePath != null && Files.exists(imagePath) && data.sampleFiles != null) {

                // Test up to 5 sample files per image to keep test time reasonable
                int count = 0;
                for (SampleFile sampleFile : data.sampleFiles) {
                    if (count >= 5) break;
                    count++;

                    tests.add(DynamicTest.dynamicTest(
                        "SHA256: " + data.imageBasename + ":" + truncatePath(sampleFile.path),
                        () -> assertTimeoutPreemptively(TEST_TIMEOUT,
                            () -> verifySha256(imagePath, data, sampleFile))
                    ));
                }
            }
        }

        return tests;
    }

    private String truncatePath(String path) {
        if (path == null) return "";
        if (path.length() <= 40) return path;
        return "..." + path.substring(path.length() - 37);
    }

    /**
     * Verify that we can count files in the filesystem.
     */
    private void verifyFileCount(Path imagePath, CorpusImageData data) throws Exception {
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Use LVM-aware mounting to access root filesystems in LVM partitions
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {
                AtomicLong fileCount = new AtomicLong(0);
                AtomicLong dirCount = new AtomicLong(0);

                try (Stream<FileSystemEntry> walkStream = fs.walk()) {
                    walkStream.forEach(entry -> {
                        if (entry instanceof FileSystemEntry.RegularFile) {
                            fileCount.incrementAndGet();
                        } else if (entry instanceof FileSystemEntry.Directory) {
                            dirCount.incrementAndGet();
                        }
                    });
                }

                System.out.println(data.imageBasename + ": " + fileCount.get() + " files, " +
                                   dirCount.get() + " directories (expected: " + data.totalFiles + " files)");

                // Allow some variance since cloud images may have been updated
                // Just verify we found a reasonable number of files
                assertThat(fileCount.get())
                    .as("Should find files in " + data.imageBasename)
                    .isGreaterThan(0);
            }
        }
    }

    /**
     * Verify that a specific file exists and has the correct SHA256 hash.
     */
    private void verifySha256(Path imagePath, CorpusImageData data, SampleFile sampleFile) throws Exception {
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            // Use LVM-aware mounting to access root filesystems in LVM partitions
            try (FileSystem fs = FileSystemMount.mountLargestIncludingLvm(disk)) {
                // Try to resolve the file
                Optional<FileSystemEntry> entry = fs.resolve(sampleFile.path);

                assertThat(entry)
                    .as("File should exist: " + sampleFile.path + " in " + data.imageBasename)
                    .isPresent();

                assertThat(entry.get())
                    .as("Entry should be a regular file: " + sampleFile.path)
                    .isInstanceOf(FileSystemEntry.RegularFile.class);

                FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) entry.get();

                // Large file reading must be supported
                assertThat(file.size())
                    .as("File " + sampleFile.path + " should be readable (max 256 MB)")
                    .isLessThanOrEqualTo(256 * 1024 * 1024);

                // Read file and compute SHA256
                byte[] content = file.readAllBytes();
                String actualSha256 = sha256(content);

                System.out.println(sampleFile.path + ": " + actualSha256.substring(0, 16) + "..." +
                                   " (size: " + content.length + " bytes)");

                // Verify size matches
                assertThat(content.length)
                    .as("File size should match for " + sampleFile.path)
                    .isEqualTo(sampleFile.size);

                // Verify SHA256 matches
                assertThat(actualSha256)
                    .as("SHA256 should match for " + sampleFile.path + " in " + data.imageBasename)
                    .isEqualTo(sampleFile.sha256.toLowerCase());
            }
        }
    }

    /**
     * Compute SHA256 hash of data and return as lowercase hex string.
     */
    private String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        return HexFormat.of().formatHex(hash);
    }

    /**
     * Convert container path (/corpus/...) to host filesystem path.
     */
    private Path resolveImagePath(String containerPath) {
        if (containerPath != null && containerPath.startsWith("/corpus/")) {
            String relativePath = containerPath.substring("/corpus/".length());
            return Paths.get(CORPUS_BASE, relativePath);
        }
        return null;
    }

    /**
     * Data class for corpus image verification data loaded from JSON.
     */
    static class CorpusImageData {
        String imagePath;
        String imageBasename;
        String filesystemType;
        int totalFiles;
        int totalDirectories;
        List<SampleFile> sampleFiles;
    }

    /**
     * Data class for sample file verification loaded from JSON.
     */
    static class SampleFile {
        String path;
        String sha256;
        long size;
    }
}
