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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JMH benchmark for filesystem walk operations.
 *
 * <p>Run with:
 * <pre>
 * mvn test-compile exec:java \
 *   -Dexec.mainClass="org.openjdk.jmh.Main" \
 *   -Dexec.classpathScope="test" \
 *   -Dexec.args="io.spicelabs.saffron.benchmark.WalkBenchmark"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class WalkBenchmark {

    private static final String CORPUS_BASE = Path.of("test-corpus").toAbsolutePath().toString();

    private Path imagePath;

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
    }

    @Benchmark
    public long walkAndCount() throws IOException {
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            if (allFs.isEmpty()) return 0;
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
    }

    @Benchmark
    public long mountOnly() throws IOException {
        try (VirtualDisk disk = DiskReader.open(imagePath)) {
            List<FileSystem> allFs = FileSystemMount.mountAll(disk);
            long count = allFs.size();
            for (FileSystem f : allFs) {
                try { f.close(); } catch (Exception ignored) {}
            }
            return count;
        }
    }
}
