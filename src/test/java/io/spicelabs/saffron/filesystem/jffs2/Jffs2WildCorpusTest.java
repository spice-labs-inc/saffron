/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.jffs2;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.filesystem.FilesystemDetector;
import io.spicelabs.saffron.filesystem.FilesystemInfo;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystemMount;
import io.spicelabs.saffron.fs.FileSystemMount.FilesystemLocation;
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
 * Wild-image test: mounts real-world JFFS2 images committed under
 * {@code src/test/resources/jffs2/wild/} (each with an adjacent
 * {@code .license.txt} stating provenance and license terms), the committed
 * mkfs.jffs2 fixtures under {@code src/test/resources/jffs2/fixtures/}, plus
 * any additional images dropped into {@code test-corpus/jffs2/}.
 *
 * <p>The committed wild set contains a real device root filesystem: the
 * LeapFrog Didj handheld's {@code erootfs.jffs2} (part of the Didj NAND),
 * as published on archive.org. Standalone JFFS2 images are otherwise rare in
 * the wild: common distributions publish squashfs or combined firmware
 * images, and JFFS2 filesystems normally exist inside router firmware as
 * flash partitions.
 */
class Jffs2WildCorpusTest {

    private static final Path WILD_DIR = Path.of("src", "test", "resources", "jffs2", "wild");
    private static final Path FIXTURES_DIR = Path.of("src", "test", "resources", "jffs2", "fixtures");
    private static final Path EXTRA_DIR = Path.of("test-corpus", "jffs2");

    static boolean wildCorpusExists() {
        return Files.isDirectory(WILD_DIR);
    }

    /**
     * Every wild image and committed fixture must be detected as JFFS2,
     * mount without throwing, and produce a walkable tree.
     */
    @Test
    @EnabledIf("wildCorpusExists")
    void wildImagesMountAndWalk() throws IOException {
        List<Path> images = new ArrayList<>();
        for (Path dir : new Path[] {WILD_DIR, FIXTURES_DIR, EXTRA_DIR}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Jffs2WildCorpusTest::isImageFile).forEach(images::add);
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
                        .isEqualTo(FileSystem.FileSystemType.JFFS2);

                Optional<FilesystemLocation> location = FileSystemMount.findFilesystems(disk)
                        .stream().findFirst();
                assertThat(location).as("mount location of %s", image.getFileName()).isPresent();

                try (FileSystem fs = FileSystemMount.mount(disk, location.get())) {
                    try (Stream<FileSystemEntry> walk = fs.walk()) {
                        long count = walk.count();
                        assertThat(count).as("walk count of %s", image.getFileName()).isGreaterThan(0);
                    }
                }
            }
        }
    }

    private static boolean isImageFile(Path p) {
        String name = p.getFileName().toString();
        if (Files.isDirectory(p) || name.endsWith(".license.txt") || name.endsWith(".md")
                || name.endsWith(".sh")) {
            return false;
        }
        // empty.jffs2 is a detection-negative fixture (zero nodes, zero bytes):
        // it must NOT be detected, which Jffs2DetectionTest.rejectsEmptyImage
        // asserts. It cannot mount or walk.
        return !name.equals("empty.jffs2");
    }
}
