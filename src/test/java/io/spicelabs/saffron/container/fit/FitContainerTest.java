/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.fit;

import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for exposing FIT sub-images as container entries.
 *
 * <p>Each test validates that the OpenWrt FIT fixture exposes the expected
 * kernel, ramdisk, and device-tree entries with correct sizes, content
 * prefixes, and metadata. The measurements below are derived from the actual
 * fixture because the plan's original values did not match the downloaded file.</p>
 */
class FitContainerTest {

    private static final String FIT_FIXTURE =
            "src/test/resources/fit/openwrt-23.05.3-mediatek-filogic-mediatek_mt7981-rfb-initramfs.itb";

    // Measured from the actual OpenWrt FIT fixture. fdtget appends a trailing
    // newline, so the values below exclude that byte.
    private static final long KERNEL_SIZE = 3_774_256L;
    private static final String KERNEL_SHA256 =
            "151582d8f9e5ca229b8e32d8adc84261436eac00d5b59936a31259d8112de4fd";
    private static final byte[] KERNEL_PREFIX = {
            0x6d, 0x00, 0x00, (byte) 0x80, 0x00, 0x08, 0x28, (byte) 0xb1
    };

    private static final long RAMDISK_SIZE = 3_482_412L;
    private static final String RAMDISK_SHA256 =
            "d11ec6d0318d104bcd3259a21cdfa3d3dadef56e1d8341bcbf8f376add63c7e1";
    private static final byte[] XZ_MAGIC = {
            (byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00
    };

    private static final long DTB_SIZE = 24_803L;
    private static final String DTB_SHA256 =
            "63f638708b8304a1bca9dafe561c9b3bb2e2c23d8616943aa76c5fb58e1ffc26";
    private static final byte[] DTB_MAGIC = {
            (byte) 0xd0, 0x0d, (byte) 0xfe, (byte) 0xed
    };

    private static final List<String> OVERLAYS = List.of(
            "/fdt-mt7981-rfb-spim-nand",
            "/fdt-mt7981-rfb-mxl-2p5g-phy-eth1",
            "/fdt-mt7981-rfb-mxl-2p5g-phy-swp5"
    );

    private BinaryContainer open() throws IOException {
        return FitContainer.open(Path.of(FIT_FIXTURE))
                .orElseThrow(() -> new NoSuchElementException("Failed to open FIT fixture"));
    }

    @Test
    void exposesKernel() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/kernel-1").orElseThrow();

        assertThat(entry.size()).isEqualTo(KERNEL_SIZE);
        assertThat(sha256(entry)).isEqualTo(KERNEL_SHA256);
        assertThat(firstBytes(entry, KERNEL_PREFIX.length)).containsExactly(KERNEL_PREFIX);
        assertThat(container.findEntry("/kernel"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.size()).isEqualTo(KERNEL_SIZE));
    }

    @Test
    void exposesRamdisk() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/initrd-1").orElseThrow();

        assertThat(entry.size()).isEqualTo(RAMDISK_SIZE);
        assertThat(sha256(entry)).isEqualTo(RAMDISK_SHA256);
        assertThat(firstBytes(entry, XZ_MAGIC.length)).containsExactly(XZ_MAGIC);
        assertThat(container.findEntry("/ramdisk"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.size()).isEqualTo(RAMDISK_SIZE));
    }

    @Test
    void exposesDtb() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/fdt-1").orElseThrow();

        assertThat(entry.size()).isEqualTo(DTB_SIZE);
        assertThat(sha256(entry)).isEqualTo(DTB_SHA256);
        assertThat(firstBytes(entry, DTB_MAGIC.length)).containsExactly(DTB_MAGIC);
        assertThat(container.findEntry("/dtb"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.size()).isEqualTo(DTB_SIZE));
    }

    @Test
    void exposesOverlayDtbs() throws IOException {
        BinaryContainer container = open();

        for (String overlay : OVERLAYS) {
            ContainerEntry entry = container.findEntry(overlay).orElseThrow();
            assertThat(firstBytes(entry, DTB_MAGIC.length)).containsExactly(DTB_MAGIC);
        }
    }

    @Test
    void handlesNoSignature() throws IOException {
        BinaryContainer container = open();

        assertThat(container.findEntry("/signature")).isEmpty();
    }

    @Test
    void entryMetadataMatches() throws IOException {
        BinaryContainer container = open();

        ContainerEntry kernel = container.findEntry("/kernel-1").orElseThrow();
        assertThat(kernel.metadata())
                .containsEntry("type", "kernel")
                .containsEntry("compression", "lzma")
                .containsEntry("arch", "arm64");

        ContainerEntry fdt = container.findEntry("/fdt-1").orElseThrow();
        assertThat(fdt.metadata())
                .containsEntry("type", "flat_dt")
                .containsEntry("compression", "none");
    }

    @Test
    void resolveNonExistent() throws IOException {
        BinaryContainer container = open();

        assertThat(container.findEntry("/no-such-entry")).isEmpty();
    }

    @Test
    void walksAllEntries() throws IOException {
        BinaryContainer container = open();

        List<String> names = container.entries().stream().map(ContainerEntry::name).toList();
        assertThat(names)
                .contains(
                        "/kernel-1", "/initrd-1", "/fdt-1",
                        "/kernel", "/ramdisk", "/dtb"
                )
                .containsAll(OVERLAYS);
    }

    @Test
    void concurrentStreamsAreIndependent() throws IOException {
        BinaryContainer container = open();
        ContainerEntry entry = container.findEntry("/fdt-1").orElseThrow();

        byte[] a;
        byte[] b;
        try (InputStream left = entry.openStream(); InputStream right = entry.openStream()) {
            left.skip(10);
            a = left.readAllBytes();
            b = right.readAllBytes();
        }

        assertThat(a.length).isEqualTo(entry.size() - 10);
        assertThat(b.length).isEqualTo(entry.size());
    }

    private static byte[] firstBytes(ContainerEntry entry, int count) throws IOException {
        try (InputStream is = entry.openStream()) {
            return is.readNBytes(count);
        }
    }

    private static String sha256(ContainerEntry entry) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = entry.openStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
