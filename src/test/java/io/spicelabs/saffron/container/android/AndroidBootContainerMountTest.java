/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.android;

import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemEntry;
import io.spicelabs.saffron.fs.FileSystem.BinaryContainerFileSystem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mount adapter tests for Android boot images.
 */
class AndroidBootContainerMountTest {

    private static final String FIXTURE = "src/test/resources/android-boot/boot.img";

    @Test
    void mountsContainerFromVirtualDisk() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(FIXTURE));
        Optional<FileSystem> fs = BinaryContainerMount.mount(new AndroidBootContainerDetectionTest.ByteArrayVirtualDisk(data));
        assertThat(fs).isPresent();
        try (BinaryContainerFileSystem f = (BinaryContainerFileSystem) fs.get()) {
            assertThat(f.containerFormat()).isEqualTo("android_boot");
            FileSystemEntry.Directory root = f.root();
            assertThat(root.list().map(FileSystemEntry::name).collect(Collectors.toList()))
                    .contains("raw", "kernel", "ramdisk", "second", "dtb");
            Optional<FileSystemEntry> kernel = f.resolve("/kernel");
            assertThat(kernel).isPresent();
            assertThat(kernel.get()).isInstanceOf(FileSystemEntry.RegularFile.class);
            assertThat(kernel.get().size()).isEqualTo(2048);
        }
    }
}
