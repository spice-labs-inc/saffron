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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JMH benchmark for path resolution operations.
 *
 * <p>Measures how quickly paths of varying depth can be resolved.
 *
 * <p>Run with:
 * <pre>
 * mvn test-compile exec:java \
 *   -Dexec.mainClass="org.openjdk.jmh.Main" \
 *   -Dexec.classpathScope="test" \
 *   -Dexec.args="io.spicelabs.saffron.benchmark.ResolveBenchmark"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class ResolveBenchmark {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    private Path imagePath;
    private List<String> shallowPaths;
    private List<String> deepPaths;

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

        shallowPaths = new ArrayList<>();
        deepPaths = new ArrayList<>();

        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return;
            FileSystem fs = allFs.get(0);
            try {
                try (Stream<FileSystemEntry> walk = fs.walk()) {
                    walk.forEach(e -> {
                        String path = e.path();
                        long depth = path.chars().filter(c -> c == '/').count();
                        if (depth <= 2 && shallowPaths.size() < 10) {
                            shallowPaths.add(path);
                        } else if (depth >= 4 && deepPaths.size() < 10) {
                            deepPaths.add(path);
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
    public int resolveShallowPaths() throws IOException {
        int found = 0;
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return 0;
            FileSystem fs = allFs.get(0);
            try {
                for (String path : shallowPaths) {
                    if (fs.resolve(path).isPresent()) found++;
                }
            } finally {
                for (FileSystem f : allFs) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }
        return found;
    }

    @Benchmark
    public int resolveDeepPaths() throws IOException {
        int found = 0;
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return 0;
            FileSystem fs = allFs.get(0);
            try {
                for (String path : deepPaths) {
                    if (fs.resolve(path).isPresent()) found++;
                }
            } finally {
                for (FileSystem f : allFs) {
                    try { f.close(); } catch (Exception ignored) {}
                }
            }
        }
        return found;
    }
}
