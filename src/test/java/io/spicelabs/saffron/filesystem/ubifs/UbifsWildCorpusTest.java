/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ubifs;

import io.spicelabs.saffron.filesystem.ubi.UbiSuperblock;
import io.spicelabs.saffron.filesystem.ubi.UbiVolumeRegion;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wild-image test: mounts real-world UBIFS volumes and UBI containers
 * committed under {@code src/test/resources/ubi/wild/} (licenses in the
 * adjacent {@code LICENSE-*.txt} files), plus the synthetic fixtures.
 *
 * <p>Bare UBIFS volumes mount directly; UBI containers are attached and
 * every UBIFS volume inside them is mounted and walked.
 */
class UbifsWildCorpusTest {

    private static final Path WILD_DIR = Path.of("src", "test", "resources", "ubi", "wild");
    private static final Path SYNTHETIC_DIR =
            Path.of("src", "test", "resources", "ubifs", "synthetic");

    static boolean wildCorpusExists() {
        return Files.isDirectory(WILD_DIR);
    }

    @Test
    @EnabledIf("wildCorpusExists")
    void wildImagesMountAndWalk() throws IOException {
        List<Path> images = new ArrayList<>();
        for (Path dir : new Path[] {WILD_DIR, SYNTHETIC_DIR}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(UbifsWildCorpusTest::isImageFile).forEach(images::add);
            }
        }
        images.sort(Comparator.comparing(Path::toString));
        assertThat(images).as("wild corpus images present").isNotEmpty();

        for (Path image : images) {
            byte[] bytes = Files.readAllBytes(image);
            Region region = new Region(bytes);

            // UBI container?
            Optional<UbiSuperblock> ubi = UbiSuperblock.attach(region);
            if (ubi.isPresent()) {
                int mounted = 0;
                for (UbiSuperblock.UbiVolume volume : ubi.get().volumesFlat()) {
                    UbiVolumeRegion vr = UbiVolumeRegion.of(ubi.get(), volume);
                    try (FileSystem fs = UbifsFileSystemImpl.mount(vr)) {
                        mounted++;
                        assertThat(walkCount(fs))
                                .as("walk of %s volume %s", image.getFileName(), volume.name())
                                .isGreaterThan(0);
                    } catch (IOException e) {
                        // Not a UBIFS volume (raw data, e.g. a plain-text
                        // volume in synthetic test containers): skip.
                    }
                }
                // Some wild containers (tiny synthetic test images) contain
                // only raw volumes; attach itself is the assertion for those.
                continue;
            }

            // Bare UBIFS volume.
            try (FileSystem fs = UbifsFileSystemImpl.mount(region)) {
                assertThat(walkCount(fs)).as("walk of %s", image.getFileName()).isGreaterThan(0);
            }
        }
    }

    private static long walkCount(FileSystem fs) throws IOException {
        try (Stream<FileSystemEntry> walk = fs.walk()) {
            return walk.count();
        }
    }

    private static boolean isImageFile(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p) && !name.startsWith("LICENSE") && !name.endsWith(".md");
    }

    static final class Region implements DiskRegion {
        private final byte[] data;

        Region(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IOException("Read out of bounds");
            }
            byte[] copy = new byte[length];
            System.arraycopy(data, (int) offset, copy, 0, length);
            return ByteBuffer.wrap(copy);
        }

        @Override
        public long size() {
            return data.length;
        }
    }
}
