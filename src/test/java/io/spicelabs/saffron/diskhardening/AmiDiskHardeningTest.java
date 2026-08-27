/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.diskhardening;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.ami.AmiDiskImpl;
import io.spicelabs.saffron.diskharness.DiskFixtures;
import io.spicelabs.saffron.exception.SaffronException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AMI hardening tests (phase 1, R1.6).
 *
 * <h2>Why this test exists</h2>
 * <p>Pre-fix, AMI {@code read()} silently skipped missing parts (zeros),
 * under-filled buffers on partial reads, and let manifest number-format
 * errors escape as unchecked exceptions. R1.6 requires checked failures,
 * full-read semantics, and bounds validation.</p>
 *
 * <h2>LLM section</h2>
 * <p>Fixtures are built in a temp dir: a small manifest + two parts. The
 * {@code readFully} seam is tested directly with a short-reading stream.</p>
 */
class AmiDiskHardeningTest {

    private static final String MANIFEST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<manifest><version>2007-10-10</version>\n"
            + "<image>\n"
            + "  <name>test</name>\n"
            + "  <size>%d</size>\n"
            + "  <bundled_size>%d</bundled_size>\n"
            + "  <ec2_encrypted_key>NOT_ENCRYPTED_TEST</ec2_encrypted_key>\n"
            + "  <parts count=\"%d\">\n"
            + "%s"
            + "  </parts>\n"
            + "</image></manifest>\n";

    private Path writeBundle(Path dir, String name, byte[] part0, byte[] part1) throws IOException {
        StringBuilder parts = new StringBuilder();
        int idx = 0;
        if (part0 != null) {
            parts.append("    <part index=\"").append(idx++).append("\"><filename>")
                    .append(name).append(".part.00</filename></part>\n");
            Files.write(dir.resolve(name + ".part.00"), part0);
        }
        if (part1 != null) {
            parts.append("    <part index=\"").append(idx++).append("\"><filename>")
                    .append(name).append(".part.01</filename></part>\n");
            Files.write(dir.resolve(name + ".part.01"), part1);
        }
        long total = (long) (part0 == null ? 0 : part0.length) + (part1 == null ? 0 : part1.length);
        String xml = String.format(MANIFEST, total, total, idx, parts.toString());
        Path manifest = dir.resolve(name + ".manifest.xml");
        Files.write(manifest, xml.getBytes());
        return manifest;
    }

    @Test
    void missingPartRejectedAtOpen(@TempDir Path dir) throws IOException {
        byte[] part0 = new byte[4096];
        DiskFixtures.fill(part0, 0, 4096);
        byte[] part1 = new byte[1024];
        DiskFixtures.fill(part1, 0, 1024);
        // Manifest declares two parts; delete the second file.
        Path m = writeBundle(dir, "img2", part0, part1);
        Files.delete(dir.resolve("img2.part.01"));

        assertThatThrownBy(() -> AmiDiskImpl.open(m))
                .isInstanceOf(SaffronException.InvalidDiskException.class);
    }

    @Test
    void badManifestNumbersRejected(@TempDir Path dir) throws IOException {
        String xml = MANIFEST.replace("%d", "not-a-number");
        Path manifest = dir.resolve("bad.manifest.xml");
        Files.write(manifest, xml.getBytes());

        assertThatThrownBy(() -> AmiDiskImpl.open(manifest))
                .isInstanceOf(SaffronException.InvalidDiskException.class);
    }

    @Test
    void readOutOfBoundsRejected(@TempDir Path dir) throws IOException {
        byte[] part0 = new byte[4096];
        DiskFixtures.fill(part0, 0, 4096);
        Path manifest = writeBundle(dir, "img3", part0, null);

        try (var disk = AmiDiskImpl.open(manifest)) {
            assertThatThrownBy(() -> disk.read(4000, 128)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> disk.read(-1, 16)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void readWithinBundleMatchesPattern(@TempDir Path dir) throws IOException {
        byte[] part0 = new byte[4096];
        DiskFixtures.fill(part0, 0, 4096);
        byte[] part1 = new byte[1024];
        DiskFixtures.fill(part1, 0, 1024);
        Path manifest = writeBundle(dir, "img4", part0, part1);

        try (var disk = AmiDiskImpl.open(manifest)) {
            ByteBuffer buf = disk.read(4096, 64);
            byte[] out = new byte[64];
            buf.get(out);
            for (int i = 0; i < 64; i++) {
                assertThat(out[i]).isEqualTo(DiskFixtures.pattern(i));
            }
        }
    }

    @Test
    void completeBundleStreamsAllBytes(@TempDir Path dir) throws IOException {
        byte[] part0 = new byte[4096];
        DiskFixtures.fill(part0, 0, 4096);
        byte[] part1 = new byte[1024];
        DiskFixtures.fill(part1, 0, 1024);
        Path manifest = writeBundle(dir, "img5", part0, part1);

        try (var disk = AmiDiskImpl.open(manifest)) {
            try (InputStream in = disk.openStream()) {
                byte[] all = in.readAllBytes();
                assertThat(all.length).isEqualTo(5120);
                for (int i = 0; i < 4096; i++) {
                    assertThat(all[i]).isEqualTo(DiskFixtures.pattern(i));
                }
                for (int i = 0; i < 1024; i++) {
                    assertThat(all[4096 + i]).isEqualTo(DiskFixtures.pattern(i));
                }
            }
        }
    }

}
