/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystemMount;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests that ELF files are mounted as binary containers.
 */
class ElfContainerMountTest {

    @Test
    void mountAllReturnsBinaryContainerForStartElf() throws IOException {
        Path fixture = Path.of("src/test/resources/elf/start.elf");

        try (VirtualDisk disk = DiskReader.open(fixture)) {
            List<FileSystem> filesystems = FileSystemMount.mountAll(disk);
            assertThat(filesystems).hasSize(1);
            try (FileSystem fs = filesystems.get(0)) {
                assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
                assertThat(fs.root().list()).anyMatch(e -> e.name().equals(".text"));
            }
        }
    }

    @Test
    void mountAllReturnsBinaryContainerForLibElf() throws IOException {
        Path fixture = Path.of("src/test/resources/elf/libmbedx509.so");

        try (VirtualDisk disk = DiskReader.open(fixture)) {
            List<FileSystem> filesystems = FileSystemMount.mountAll(disk);
            assertThat(filesystems).hasSize(1);
            try (FileSystem fs = filesystems.get(0)) {
                assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
                assertThat(fs.root().list()).anyMatch(e -> e.name().equals("0"));
            }
        }
    }
}
