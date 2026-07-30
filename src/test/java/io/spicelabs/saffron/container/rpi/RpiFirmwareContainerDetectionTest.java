/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.rpi;

import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.container.ContainerFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Raspberry Pi firmware detection.
 */
class RpiFirmwareContainerDetectionTest {

    private static final String BOOTCODE = "src/test/resources/rpi-firmware/bootcode.bin";
    private static final String FIXUP = "src/test/resources/rpi-firmware/fixup.dat";
    private static final String START_ELF = "src/test/resources/elf/start.elf";
    private static final String RANDOM = "src/test/resources/invalid-random.bin";

    @Test
    void detectsBootcode() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(BOOTCODE));
        assertThat(format).contains(ContainerFormat.RPI_FIRMWARE);
    }

    @Test
    void detectsFixup() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(FIXUP));
        assertThat(format).contains(ContainerFormat.RPI_FIRMWARE);
    }

    @Test
    void detectsBootcodeFromVirtualDisk() throws IOException {
        try (VirtualDisk disk = DiskReader.open(Path.of(BOOTCODE))) {
            Optional<ContainerFormat> format = ContainerDetector.detect(disk);
            assertThat(format).contains(ContainerFormat.RPI_FIRMWARE);
        }
    }

    @Test
    void rejectsRandomData() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(RANDOM));
        assertThat(format).isEmpty();
    }

    @Test
    void rejectsTruncatedHeader() throws IOException {
        Path tmp = Files.createTempFile("truncated-rpi", ".bin");
        tmp.toFile().deleteOnExit();
        Files.write(tmp, new byte[]{0x7f, 0x45, 0x4c});

        Optional<ContainerFormat> format = ContainerDetector.detect(tmp);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsBootcodeWithNonZeroPadding() throws IOException {
        byte[] bootcode = Files.readAllBytes(Path.of(BOOTCODE));
        bootcode[0x100] = 0x01;
        ByteBuffer buffer = ByteBuffer.wrap(bootcode);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsBootcodeZeroAt0x200() {
        byte[] data = new byte[513];
        // First 512 bytes are zero by default; byte at 0x200 is also zero.
        ByteBuffer buffer = ByteBuffer.wrap(data);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsFixupFromBuffer() throws IOException {
        byte[] fixup = Files.readAllBytes(Path.of(FIXUP));
        ByteBuffer buffer = ByteBuffer.wrap(fixup);

        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);

        assertThat(format).isEmpty();
    }

    @Test
    void rejectsElfRenamedToBootcode() throws IOException {
        Path dir = Files.createTempDirectory("rpi-test");
        Path target = dir.resolve("bootcode.bin");
        Files.copy(Path.of(START_ELF), target);
        target.toFile().deleteOnExit();

        Optional<ContainerFormat> format = ContainerDetector.detect(target);

        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void rejectsDtbRenamedToFixup() throws IOException {
        Path dtb = Path.of("src/test/resources/fit/mediatek_mt7981-rfb.dtb");
        Path dir = Files.createTempDirectory("rpi-test");
        Path target = dir.resolve("fixup.dat");
        Files.copy(dtb, target);
        target.toFile().deleteOnExit();

        Optional<ContainerFormat> format = ContainerDetector.detect(target);

        assertThat(format).contains(ContainerFormat.DTB);
    }

    @Test
    void rejectsStartElfAsRpiFirmware() throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(Path.of(START_ELF));
        assertThat(format).contains(ContainerFormat.ELF);
    }

    @Test
    void caseInsensitiveBootcodeFilename() throws IOException {
        for (String name : new String[]{"BOOTCODE.BIN", "Bootcode.Bin", "bootcode.BIN"}) {
            Path dir = Files.createTempDirectory("rpi-test");
            Path target = dir.resolve(name);
            Files.copy(Path.of(BOOTCODE), target);
            target.toFile().deleteOnExit();

            Optional<ContainerFormat> format = ContainerDetector.detect(target);
            assertThat(format).as("for filename %s", name).contains(ContainerFormat.RPI_FIRMWARE);
        }
    }

    @Test
    void caseInsensitiveFixupFilename() throws IOException {
        for (String name : new String[]{"FIXUP.DAT", "Fixup.Dat", "fixup.DAT"}) {
            Path dir = Files.createTempDirectory("rpi-test");
            Path target = dir.resolve(name);
            Files.copy(Path.of(FIXUP), target);
            target.toFile().deleteOnExit();

            Optional<ContainerFormat> format = ContainerDetector.detect(target);
            assertThat(format).as("for filename %s", name).contains(ContainerFormat.RPI_FIRMWARE);
        }
    }
}
