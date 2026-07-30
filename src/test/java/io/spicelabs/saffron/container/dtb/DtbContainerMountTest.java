/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dtb;

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
 * End-to-end mount tests for DTB containers.
 */
class DtbContainerMountTest {

    private static final String DTB = "src/test/resources/dtb/bcm2710-rpi-3-b.dtb";

    @Test
    void mountAllReturnsBinaryContainerForDtb() throws IOException {
        try (VirtualDisk disk = DiskReader.open(Path.of(DTB))) {
            List<FileSystem> filesystems = FileSystemMount.mountAll(disk);
            assertThat(filesystems).hasSize(1);
            try (FileSystem fs = filesystems.get(0)) {
                assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
                assertThat(fs.resolve("/dtb")).isPresent();
                assertThat(fs.resolve("/model")).isPresent();
            }
        }
    }
}
