/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.cramfs;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wild-image test: mounts real-world cramfs images committed under
 * {@code src/test/resources/cramfs/wild/} (each with an adjacent
 * {@code .license.txt} stating provenance and license terms), plus any
 * additional images dropped into {@code test-corpus/cramfs/}.
 *
 * <p>The committed wild set contains cramfs images recovered from public
 * projects and device firmware: util-linux (big- and little-endian),
 * e2fsprogs libblkid, binwalk, dissect.cramfs (standard, hole-support, and
 * a real device web filesystem), fact_extractor, a Xiaomi Xiaofang camera
 * rootfs, and a Hisilicon IP-camera module filesystem.
 */
class CramfsWildCorpusTest {

    private static final Path WILD_DIR = Path.of("src", "test", "resources", "cramfs", "wild");
    private static final Path SYNTHETIC_DIR =
            Path.of("src", "test", "resources", "cramfs", "synthetic");
    private static final Path EXTRA_DIR = Path.of("test-corpus", "cramfs");

    static boolean wildCorpusExists() {
        return Files.isDirectory(WILD_DIR);
    }

    /**
     * Every wild and synthetic image must be detected as cramfs, mount, and
     * walk.
     */
    @Test
    @EnabledIf("wildCorpusExists")
    void wildImagesMountAndWalk() throws IOException {
        List<Path> images = new ArrayList<>();
        for (Path dir : new Path[] {WILD_DIR, SYNTHETIC_DIR, EXTRA_DIR}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(CramfsWildCorpusTest::isImageFile).forEach(images::add);
            }
        }
        images.sort(Comparator.comparing(Path::toString));
        assertThat(images).as("wild corpus images present").isNotEmpty();

        for (Path image : images) {
            try (VirtualDisk disk = DiskReader.open(image, DiskFormat.RAW)) {
                Optional<FilesystemInfo> info = FilesystemDetector.detect(disk, 0);
                assertThat(info).as("detection of %s", image.getFileName()).isPresent();
                assertThat(info.get().type())
                        .as("type of %s", image.getFileName())
                        .isEqualTo(FileSystem.FileSystemType.CRAMFS);

                Optional<FileSystemMount.FilesystemLocation> location =
                        FileSystemMount.findFilesystems(disk).stream().findFirst();
                assertThat(location).as("mount location of %s", image.getFileName()).isPresent();

                try (FileSystem fs = FileSystemMount.mount(disk, location.get())) {
                    try (Stream<FileSystemEntry> walk = fs.walk()) {
                        assertThat(walk.count()).as("walk of %s", image.getFileName()).isGreaterThan(0);
                    }
                }
            }
        }
    }

    private static boolean isImageFile(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p) && !name.endsWith(".license.txt") && !name.endsWith(".md");
    }
}
