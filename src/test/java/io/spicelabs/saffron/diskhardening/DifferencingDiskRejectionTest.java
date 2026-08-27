/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.diskharness.DiskFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Differencing-image rejection tests (phase 1, R1.7).
 *
 * <h2>Why this test exists</h2>
 * <p>Pre-fix, differencing VHD/VDI/VHDX/VMDK images opened successfully but
 * silently returned ZEROS for all unallocated blocks (the parent data was
 * never resolved) — silent wrong data. R1.7 requires rejection at
 * {@code open()} with a checked {@code IOException}, while
 * {@code detect()} keeps identifying the format.</p>
 *
 * <h2>LLM section</h2>
 * <p>Fixtures: VHD with footer diskType DIFFERENCING; VDI with a parent
 * UUID; VHDX with the file-parameters hasParent flag; VMDK with a
 * descriptor declaring a parent. Each asserts open throws IOException and
 * format detection still succeeds.</p>
 */
class DifferencingDiskRejectionTest {

    @Test
    void differencingVhdOpenRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.differencingVhd(8 * 1024 * 1024, 2 * 1024 * 1024);
        Path file = dir.resolve("diff.vhd");
        Files.write(file, image);

        assertThat(DiskFormat.detect(file)).contains(DiskFormat.VHD);
        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHD))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Differencing");
    }

    @Test
    void differencingVdiOpenRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vdi(8 * 1024 * 1024, 1024 * 1024, false,
                UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        Path file = dir.resolve("diff.vdi");
        Files.write(file, image);

        assertThat(DiskFormat.detect(file)).contains(DiskFormat.VDI);
        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VDI))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Differencing");
    }

    @Test
    void differencingVhdxOpenRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vhdx(8 * 1024 * 1024, 1024 * 1024, false, true);
        Path file = dir.resolve("diff.vhdx");
        Files.write(file, image);

        assertThat(DiskFormat.detect(file)).contains(DiskFormat.VHDX);
        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VHDX))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Differencing");
    }

    @Test
    void differencingVmdkOpenRejected(@TempDir Path dir) throws IOException {
        byte[] image = DiskFixtures.vmdk(8 * 65536, 65536, false, false,
                DiskValidationTest.PARENT_DESCRIPTOR);
        Path file = dir.resolve("diff.vmdk");
        Files.write(file, image);

        assertThat(DiskFormat.detect(file)).contains(DiskFormat.VMDK);
        assertThatThrownBy(() -> DiskReader.open(file, DiskFormat.VMDK))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Differencing");
    }

    @Test
    void parentNameButDynamicTypeStillOpens(@TempDir Path dir) throws IOException {
        // A dynamic VHD with a parent NAME string but diskType DYNAMIC must
        // NOT be rejected (rejection keys on diskType, not parent name).
        byte[] image = DiskFixtures.dynamicVhd(8 * 1024 * 1024, 2 * 1024 * 1024, false);
        // Set parent unicode name in the dynamic header (offset 512+576).
        byte[] name = "parent.vhd".getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        System.arraycopy(name, 0, image, 512 + 576, name.length);
        Path file = dir.resolve("dyn-with-name.vhd");
        Files.write(file, image);

        try (var disk = DiskReader.open(file, DiskFormat.VHD)) {
            assertThat(disk.virtualSize()).isEqualTo(8 * 1024 * 1024);
        }
    }
}
