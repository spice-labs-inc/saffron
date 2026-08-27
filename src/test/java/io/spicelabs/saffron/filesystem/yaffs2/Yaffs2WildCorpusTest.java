/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.yaffs2;

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
 * Wild-image test: mounts real-world YAFFS2 images committed under
 * {@code src/test/resources/yaffs2/wild/} (licenses in the adjacent
 * {@code LICENSE-*.txt} files), plus any additional images dropped into
 * {@code test-corpus/yaffs2/}.
 *
 * <p>The committed set covers the geometry matrix (page 1024-16384, spare
 * 16-512), both endians, empty dirs/files, hardlinks, symlinks, and real
 * device rootfs images (NUC972-era lab image).
 */
class Yaffs2WildCorpusTest {

    private static final Path WILD_DIR = Path.of("src", "test", "resources", "yaffs2", "wild");
    private static final Path SYNTHETIC_DIR =
            Path.of("src", "test", "resources", "yaffs2", "synthetic");
    private static final Path EXTRA_DIR = Path.of("test-corpus", "yaffs2");

    static boolean wildCorpusExists() {
        return Files.isDirectory(WILD_DIR);
    }

    @Test
    @EnabledIf("wildCorpusExists")
    void wildImagesMountAndWalk() throws IOException {
        List<Path> images = new ArrayList<>();
        for (Path dir : new Path[] {WILD_DIR, SYNTHETIC_DIR, EXTRA_DIR}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Yaffs2WildCorpusTest::isImageFile).forEach(images::add);
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
                        .isEqualTo(FileSystem.FileSystemType.YAFFS2);

                Optional<FileSystemMount.FilesystemLocation> location =
                        FileSystemMount.findFilesystems(disk).stream().findFirst();
                assertThat(location).as("mount location of %s", image.getFileName()).isPresent();

                try (FileSystem fs = FileSystemMount.mount(disk, location.get())) {
                    try (Stream<FileSystemEntry> walk = fs.walk()) {
                        assertThat(walk.count()).as("walk of %s", image.getFileName())
                                .isGreaterThan(0);
                    }
                }
            }
        }
    }

    private static boolean isImageFile(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p) && !name.startsWith("LICENSE") && !name.endsWith(".md");
    }
}
