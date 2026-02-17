/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.security;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that openStream() works correctly for streaming reads,
 * and that readAllBytes() produces identical results.
 */
class StreamingReadTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Path.of(CORPUS_BASE));
    }

    /**
     * Verify that openStream() and readAllBytes() produce identical content
     * for files read from a real filesystem image.
     */
    @Test
    @EnabledIf("corpusExists")
    void streamingReadMatchesFullRead() throws Exception {
        Path imagePath = findFirstCorpusImage();
        if (imagePath == null) return;

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            try {
                for (FileSystem fs : allFs) {
                    int filesChecked = 0;
                    try (Stream<FileSystemEntry> walk = fs.walk()) {
                        var it = walk.filter(e -> e instanceof FileSystemEntry.RegularFile)
                                     .filter(e -> e.size() > 0 && e.size() < 1024 * 1024)
                                     .limit(10)
                                     .iterator();

                        while (it.hasNext()) {
                            FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) it.next();

                            // Read via readAllBytes
                            byte[] fullRead = file.readAllBytes();

                            // Read via openStream in 4KB chunks
                            byte[] streamRead;
                            try (InputStream is = file.openStream()) {
                                streamRead = is.readAllBytes();
                            }

                            assertThat(streamRead.length)
                                .as("Stream read length for " + file.path())
                                .isEqualTo(fullRead.length);

                            assertThat(sha256(streamRead))
                                .as("Stream SHA256 for " + file.path())
                                .isEqualTo(sha256(fullRead));

                            filesChecked++;
                        }
                    }
                    System.out.println("Verified streaming read for " + filesChecked +
                                       " files on " + fs.type().getName());
                }
            } finally {
                for (FileSystem fs : allFs) {
                    try { fs.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * Verify that openStream() can read in small chunks correctly.
     */
    @Test
    @EnabledIf("corpusExists")
    void streamingReadWithSmallChunks() throws Exception {
        Path imagePath = findFirstCorpusImage();
        if (imagePath == null) return;

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            try {
                for (FileSystem fs : allFs) {
                    try (Stream<FileSystemEntry> walk = fs.walk()) {
                        var fileOpt = walk.filter(e -> e instanceof FileSystemEntry.RegularFile)
                                          .filter(e -> e.size() > 100 && e.size() < 50_000)
                                          .findFirst();

                        if (fileOpt.isEmpty()) continue;
                        FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) fileOpt.get();

                        byte[] fullRead = file.readAllBytes();

                        // Read in tiny 7-byte chunks (odd size to stress boundary handling)
                        byte[] streamRead = new byte[fullRead.length];
                        int totalRead = 0;
                        try (InputStream is = file.openStream()) {
                            byte[] chunk = new byte[7];
                            int n;
                            while ((n = is.read(chunk)) != -1) {
                                System.arraycopy(chunk, 0, streamRead, totalRead, n);
                                totalRead += n;
                            }
                        }

                        assertThat(totalRead)
                            .as("Total bytes read in chunks for " + file.path())
                            .isEqualTo(fullRead.length);

                        assertThat(sha256(streamRead))
                            .as("Chunked stream SHA256 for " + file.path())
                            .isEqualTo(sha256(fullRead));
                    }
                }
            } finally {
                for (FileSystem fs : allFs) {
                    try { fs.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        return HexFormat.of().formatHex(hash);
    }

    private Path findFirstCorpusImage() {
        Path dir = Path.of(CORPUS_BASE, "qcow2");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".qcow2"))
                        .findFirst()
                        .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
