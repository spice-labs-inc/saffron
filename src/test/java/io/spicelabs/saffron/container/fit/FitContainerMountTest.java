/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.fit;

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
 * End-to-end test that a FIT file is opened through {@link DiskReader} and
 * mounted as a binary container filesystem.
 */
class FitContainerMountTest {

    private static final String FIT_FIXTURE =
            "src/test/resources/fit/openwrt-23.05.3-mediatek-filogic-mediatek_mt7981-rfb-initramfs.itb";

    @Test
    void mountAllReturnsBinaryContainer() throws IOException {
        Path fixture = Path.of(FIT_FIXTURE);

        try (VirtualDisk disk = DiskReader.open(fixture)) {
            List<FileSystem> filesystems = FileSystemMount.mountAll(disk);
            assertThat(filesystems).hasSize(1);
            try (FileSystem fs = filesystems.get(0)) {
                assertThat(fs.type()).isEqualTo(FileSystem.FileSystemType.BINARY_CONTAINER);
                assertThat(fs.root().list()).anyMatch(e -> e.name().equals("kernel-1"));
            }
        }
    }
}
