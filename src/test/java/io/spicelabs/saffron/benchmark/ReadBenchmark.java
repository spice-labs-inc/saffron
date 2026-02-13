/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.benchmark;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JMH benchmark for file read operations.
 *
 * <p>Measures throughput of readAllBytes() and openStream() for files
 * of various sizes within a disk image.
 *
 * <p>Run with:
 * <pre>
 * mvn test-compile exec:java \
 *   -Dexec.mainClass="org.openjdk.jmh.Main" \
 *   -Dexec.classpathScope="test" \
 *   -Dexec.args="io.spicelabs.saffron.benchmark.ReadBenchmark"
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class ReadBenchmark {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    private Path imagePath;
    private List<String> smallFilePaths;
    private List<String> mediumFilePaths;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        Path dir = Path.of(CORPUS_BASE, "qcow2");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Corpus not found at " + dir);
        }
        try (Stream<Path> files = Files.walk(dir)) {
            imagePath = files.filter(Files::isRegularFile)
                             .filter(p -> p.getFileName().toString().endsWith(".qcow2"))
                             .findFirst()
                             .orElseThrow(() -> new IllegalStateException("No qcow2 images found"));
        }

        // Collect file paths by size category
        smallFilePaths = new ArrayList<>();
        mediumFilePaths = new ArrayList<>();

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return;
            FileSystem fs = allFs.get(0);
            try {
                try (Stream<FileSystemEntry> walk = fs.walk()) {
                    walk.filter(e -> e instanceof FileSystemEntry.RegularFile)
                        .filter(e -> e.size() > 0)
                        .forEach(e -> {
                            if (e.size() < 1024 && smallFilePaths.size() < 10) {
                                smallFilePaths.add(e.path());
                            } else if (e.size() >= 1024 && e.size() < 100_000 && mediumFilePaths.size() < 10) {
                                mediumFilePaths.add(e.path());
                            }
                        });
                }
            } finally {
                for (FileSystem f : allFs) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    @Benchmark
    public long readSmallFiles() throws IOException {
        long totalBytes = 0;
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return 0;
            FileSystem fs = allFs.get(0);
            try {
                for (String path : smallFilePaths) {
                    var entry = fs.resolve(path);
                    if (entry.isPresent() && entry.get() instanceof FileSystemEntry.RegularFile file) {
                        totalBytes += file.readAllBytes().length;
                    }
                }
            } finally {
                for (FileSystem f : allFs) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }
        return totalBytes;
    }

    @Benchmark
    public long readMediumFiles() throws IOException {
        long totalBytes = 0;
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return 0;
            FileSystem fs = allFs.get(0);
            try {
                for (String path : mediumFilePaths) {
                    var entry = fs.resolve(path);
                    if (entry.isPresent() && entry.get() instanceof FileSystemEntry.RegularFile file) {
                        totalBytes += file.readAllBytes().length;
                    }
                }
            } finally {
                for (FileSystem f : allFs) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }
        return totalBytes;
    }

    @Benchmark
    public long streamMediumFiles() throws IOException {
        long totalBytes = 0;
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return 0;
            FileSystem fs = allFs.get(0);
            try {
                byte[] buf = new byte[4096];
                for (String path : mediumFilePaths) {
                    var entry = fs.resolve(path);
                    if (entry.isPresent() && entry.get() instanceof FileSystemEntry.RegularFile file) {
                        try (InputStream is = file.openStream()) {
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                totalBytes += n;
                            }
                        }
                    }
                }
            } finally {
                for (FileSystem f : allFs) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }
        return totalBytes;
    }
}
