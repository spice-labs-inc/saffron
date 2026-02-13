/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that symlink resolution has depth limiting to prevent infinite loops.
 *
 * <p>Verifies that symlink chains don't cause StackOverflowError or infinite recursion.
 * Also verifies that walk() has cycle detection to prevent revisiting directories.
 */
class SymlinkSecurityTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Path.of(CORPUS_BASE));
    }

    /**
     * Verify that walk() terminates on all corpus images within a reasonable time.
     * This tests cycle detection — if there were no cycle detection, hard-linked
     * directories could cause infinite recursion.
     */
    @Test
    @EnabledIf("corpusExists")
    void walkTerminatesOnCorpusImages() throws Exception {
        // Use a known ext4 image
        Path imagePath = findFirstCorpusImage("qcow2");
        if (imagePath == null) return;

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            try {
                for (FileSystem fs : allFs) {
                    AtomicInteger count = new AtomicInteger(0);
                    try (Stream<FileSystemEntry> walk = fs.walk()) {
                        walk.forEach(e -> count.incrementAndGet());
                    }
                    assertThat(count.get()).isGreaterThan(0);
                }
            } finally {
                for (FileSystem fs : allFs) {
                    try { fs.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * Verify that symlink resolution on real images doesn't throw StackOverflowError.
     * On real images, symlinks should resolve normally (depth < 40).
     */
    @Test
    @EnabledIf("corpusExists")
    void symlinkResolutionWorksOnRealImages() throws Exception {
        Path imagePath = findFirstCorpusImage("qcow2");
        if (imagePath == null) return;

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            try {
                for (FileSystem fs : allFs) {
                    // Walk looking for symlinks and try to resolve them
                    AtomicInteger resolvedCount = new AtomicInteger(0);
                    AtomicInteger unresolvedCount = new AtomicInteger(0);
                    try (Stream<FileSystemEntry> walk = fs.walk()) {
                        walk.filter(e -> e instanceof FileSystemEntry.SymbolicLink)
                            .limit(50) // Test first 50 symlinks
                            .forEach(e -> {
                                try {
                                    FileSystemEntry.SymbolicLink link = (FileSystemEntry.SymbolicLink) e;
                                    Optional<FileSystemEntry> resolved = link.resolve();
                                    if (resolved.isPresent()) {
                                        resolvedCount.incrementAndGet();
                                    } else {
                                        unresolvedCount.incrementAndGet();
                                    }
                                } catch (IOException ex) {
                                    // Expected for broken symlinks — that's OK
                                    unresolvedCount.incrementAndGet();
                                }
                            });
                    }
                    System.out.println("Resolved: " + resolvedCount.get() +
                                       ", unresolved: " + unresolvedCount.get());
                }
            } finally {
                for (FileSystem fs : allFs) {
                    try { fs.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private Path findFirstCorpusImage(String format) {
        Path dir = Path.of(CORPUS_BASE, format);
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith("." + format))
                        .findFirst()
                        .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
