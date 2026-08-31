/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.linuxkernel;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.fs.FileSystem;
import io.spicelabs.saffron.fs.FileSystem.FileSystemType;
import io.spicelabs.saffron.fs.FileSystemEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that Linux kernel images expose their internal components as container
 * entries.
 */
class LinuxKernelContainerTest {

    private static final String FIXTURE_DIR = "src/test/resources/linux-kernel";

    /**
     * Verifies that opening an x86 bzImage exposes a {@code /kernel-payload} entry
     * whose size matches the header-declared payload length, and that the
     * filesystem reports itself as a binary container.
     */
    @Test
    void exposesPayload() throws IOException {
        Path fixture = Path.of("src/test/resources/linux-kernel/iotgoat-x86-vmlinuz");
        long declaredPayloadSize = bzImagePayloadLength(fixture);

        try (VirtualDisk disk = DiskReader.open(fixture);
             FileSystem fs = BinaryContainerMount.mount(disk)
                     .orElseThrow(() -> new AssertionError("Expected kernel container to mount"))) {
            assertThat(fs.type()).isEqualTo(FileSystemType.BINARY_CONTAINER);
            Optional<FileSystemEntry> entry = fs.resolve("/kernel-payload");
            assertThat(entry)
                    .isPresent()
                    .hasValueSatisfying(e -> assertThat(e.type()).isEqualTo(FileSystemEntry.EntryType.REGULAR_FILE));
            assertThat(entry.get().size()).isEqualTo(declaredPayloadSize);
        }
    }

    private static long bzImagePayloadLength(Path fixture) throws IOException {
        byte[] header = Files.readAllBytes(fixture);
        if (header.length < 0x250) {
            return header.length;
        }
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        return buf.getInt(0x24c) & 0xffffffffL;
    }

    /**
     * Verifies that an ARM zImage with an embedded {@code config.gz} exposes the
     * configuration file and that it decompresses to text containing
     * {@code CONFIG_} entries.
     */
    @Test
    void extractsConfigGz() throws IOException {
        Path fixture = Path.of(FIXTURE_DIR, "iotgoat-rpi-kernel.img");

        try (VirtualDisk disk = DiskReader.open(fixture);
             FileSystem fs = BinaryContainerMount.mount(disk)
                     .orElseThrow(() -> new AssertionError("Expected kernel container to mount"))) {
            Optional<FileSystemEntry> config = fs.resolve("/config.gz");
            assertThat(config).isPresent();

            FileSystemEntry.RegularFile file = (FileSystemEntry.RegularFile) config.get();
            try (InputStream raw = file.openStream();
                 GZIPInputStream gz = new GZIPInputStream(raw)) {
                byte[] decompressed = gz.readAllBytes();
                String text = new String(decompressed);
                assertThat(text).contains("CONFIG_");
            }
        }
    }

    /**
     * Verifies that the IoTGoat x86 bzImage does not contain an initramfs entry.
     */
    @Test
    void noInitramfsForIotGoat() throws IOException {
        Path fixture = Path.of("src/test/resources/linux-kernel/iotgoat-x86-vmlinuz");

        try (VirtualDisk disk = DiskReader.open(fixture);
             FileSystem fs = BinaryContainerMount.mount(disk)
                     .orElseThrow(() -> new AssertionError("Expected kernel container to mount"))) {
            assertThat(fs.resolve("/initramfs")).isEmpty();
        }
    }

    /**
     * Verifies that the IoTGoat x86 bzImage does not contain an X.509 certificate
     * bundle entry.
     */
    @Test
    void noCertificatesForIotGoat() throws IOException {
        Path fixture = Path.of("src/test/resources/linux-kernel/iotgoat-x86-vmlinuz");

        try (VirtualDisk disk = DiskReader.open(fixture);
             FileSystem fs = BinaryContainerMount.mount(disk)
                     .orElseThrow(() -> new AssertionError("Expected kernel container to mount"))) {
            assertThat(fs.resolve("/certificates")).isEmpty();
        }
    }

    /**
     * Verifies that a kernel with no optional components still mounts and exposes
     * the payload entry.
     */
    @Test
    void handlesMissingComponents() throws IOException {
        Path fixture = Path.of(FIXTURE_DIR, "debian-armhf-vmlinuz");

        try (VirtualDisk disk = DiskReader.open(fixture);
             FileSystem fs = BinaryContainerMount.mount(disk)
                     .orElseThrow(() -> new AssertionError("Expected kernel container to mount"))) {
            assertThat(fs.resolve("/kernel-payload")).isPresent();
            assertThat(fs.walk().toList()).isNotEmpty();
        }
    }
}

/**
 * Phase 7 (R7.1): the memoized entry list is stable and consistent.
 *
 * <h2>LLM section</h2>
 * <p>entries() must return the identical list instance on every call
 * (the expensive gzip/DTB/certificate scans run once); findEntry uses
 * the memoized index; a fresh container over the same bytes produces
 * the same entry names (immutability contract).</p>
 */
class KernelMemoizationTest {
    @Test
    void entriesAreMemoizedAndStable() throws IOException {
        Path fixture = Path.of("src/test/resources/linux-kernel/iotgoat-x86-vmlinuz");
        if (!Files.exists(fixture)) {
            return;
        }
        byte[] data = Files.readAllBytes(fixture);
        try (LinuxKernelContainer container =
                     new LinuxKernelContainer(data)) {
            java.util.List<io.spicelabs.saffron.container.ContainerEntry> first = container.entries();
            java.util.List<io.spicelabs.saffron.container.ContainerEntry> second = container.entries();
            assertThat(second).isSameAs(first);
            assertThat(container.findEntry("/kernel-payload")).isPresent();
            // Fresh container over the same bytes: identical entry names.
            try (LinuxKernelContainer fresh = new LinuxKernelContainer(data)) {
                assertThat(fresh.entries().stream()
                        .map(io.spicelabs.saffron.container.ContainerEntry::name).toList())
                        .containsExactlyElementsOf(
                                first.stream()
                                        .map(io.spicelabs.saffron.container.ContainerEntry::name)
                                        .toList());
            }
        }
    }
}
