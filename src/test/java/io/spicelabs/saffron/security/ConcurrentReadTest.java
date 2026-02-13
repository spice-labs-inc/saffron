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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that filesystem operations are safe under concurrent access.
 *
 * <p>Each thread opens its own VirtualDisk and FileSystem instance to avoid
 * sharing the underlying SeekableByteChannel (which is not thread-safe).
 * This verifies that there are no shared global mutable state issues.
 */
class ConcurrentReadTest {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    static boolean corpusExists() {
        return Files.isDirectory(Path.of(CORPUS_BASE));
    }

    /**
     * Verify that multiple threads can walk the same image concurrently
     * (each with its own disk/FS instance) and all get the same file count.
     */
    @Test
    @EnabledIf("corpusExists")
    void concurrentWalkProducesSameCount() throws Exception {
        Path imagePath = findFirstCorpusImage();
        if (imagePath == null) return;

        int numThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Long>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try (VirtualDisk disk = DiskReader.open(imagePath)) {
                    List<FileSystem> allFs = FileSystemMount.mountAll(disk);
                    if (allFs.isEmpty()) return 0L;
                    FileSystem fs = allFs.get(0);
                    try {
                        try (Stream<FileSystemEntry> walk = fs.walk()) {
                            return walk.count();
                        }
                    } finally {
                        for (FileSystem f : allFs) {
                            try { f.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            }));
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        // All threads should get the same count
        List<Long> counts = new ArrayList<>();
        for (Future<Long> future : futures) {
            counts.add(future.get());
        }

        long expectedCount = counts.get(0);
        assertThat(expectedCount).isGreaterThan(0);

        for (int i = 1; i < counts.size(); i++) {
            assertThat(counts.get(i))
                .as("Thread %d should get same count as thread 0", i)
                .isEqualTo(expectedCount);
        }

        System.out.println("All " + numThreads + " threads counted " +
                           expectedCount + " entries");
    }

    /**
     * Verify that concurrent file reads produce correct results.
     */
    @Test
    @EnabledIf("corpusExists")
    void concurrentFileReadsAreCorrect() throws Exception {
        Path imagePath = findFirstCorpusImage();
        if (imagePath == null) return;

        // First, collect file paths from a single-threaded walk
        List<String> filePaths;
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return;
            FileSystem fs = allFs.get(0);
            try {
                filePaths = new ArrayList<>();
                try (Stream<FileSystemEntry> walk = fs.walk()) {
                    walk.filter(e -> e instanceof FileSystemEntry.RegularFile)
                        .filter(e -> e.size() > 0 && e.size() < 100_000)
                        .limit(20)
                        .forEach(e -> filePaths.add(e.path()));
                }
            } finally {
                for (FileSystem f : allFs) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }

        if (filePaths.isEmpty()) return;

        int numThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < numThreads; t++) {
            futures.add(executor.submit(() -> {
                try (VirtualDisk disk = DiskReader.open(imagePath)) {
                    List<FileSystem> allFs = FileSystemMount.mountAll(disk);
                    if (allFs.isEmpty()) return;
                    FileSystem fs = allFs.get(0);
                    try {
                        for (String path : filePaths) {
                            try {
                                var entry = fs.resolve(path);
                                if (entry.isPresent() && entry.get() instanceof FileSystemEntry.RegularFile file) {
                                    byte[] data = file.readAllBytes();
                                    assertThat(data.length).isEqualTo((int) file.size());
                                    successCount.incrementAndGet();
                                }
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                            }
                        }
                    } finally {
                        for (FileSystem f : allFs) {
                            try { f.close(); } catch (Exception ignored) {}
                        }
                    }
                } catch (IOException e) {
                    errorCount.incrementAndGet();
                }
            }));
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        for (Future<?> future : futures) {
            future.get(); // Rethrow any assertion errors
        }

        System.out.println("Concurrent reads: " + successCount.get() +
                           " successful, " + errorCount.get() + " errors");
        assertThat(successCount.get()).isGreaterThan(0);
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
