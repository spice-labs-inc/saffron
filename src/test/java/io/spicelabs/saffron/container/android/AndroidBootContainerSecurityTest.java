/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.android;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security tests for Android boot image parsing.
 */
class AndroidBootContainerSecurityTest {

    private static final String FIXTURE = "src/test/resources/android-boot/boot.img";

    @Test
    void rejectsIntegerOverflow() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // kernel_size at offset 8, set to Integer.MAX_VALUE.
        fixture[8] = (byte) 0xff;
        fixture[9] = (byte) 0xff;
        fixture[10] = (byte) 0xff;
        fixture[11] = (byte) 0x7f;
        assertThat(AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length)).isEmpty();
    }

    @Test
    void rejectsNegativeSize() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // kernel_size is unsigned; 0xffffffff is huge, effectively overflow.
        fixture[8] = (byte) 0xff;
        fixture[9] = (byte) 0xff;
        fixture[10] = (byte) 0xff;
        fixture[11] = (byte) 0xff;
        assertThat(AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length)).isEmpty();
    }

    @Test
    void rejectsHeaderSizeMismatch() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // header_size at offset 1644, set to a wrong value.
        fixture[1644] = 0x01;
        fixture[1645] = 0x00;
        fixture[1646] = 0x00;
        fixture[1647] = 0x00;
        assertThat(AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length)).isEmpty();
    }

    @Test
    void rejectsTruncatedComponent() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // Truncate the file to less than the kernel size.
        byte[] truncated = new byte[2048 + 512];
        System.arraycopy(fixture, 0, truncated, 0, truncated.length);
        assertThat(AndroidBootContainer.open(ByteBuffer.wrap(truncated), truncated.length)).isEmpty();
    }

    @Test
    void rejectsV1WithDtb() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // Change header version to 1 but leave dtb_size non-zero.
        fixture[40] = 0x01;
        assertThat(AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length)).isEmpty();
    }

    @Test
    void rejectsV0WithSecond() throws IOException {
        byte[] fixture = Files.readAllBytes(Path.of(FIXTURE));
        // Change header version to 0. second is allowed in v0, but dtb/recovery must be zero.
        // The fixture already has dtb_size = 256, which is invalid for v0.
        fixture[40] = 0x00;
        assertThat(AndroidBootContainer.open(ByteBuffer.wrap(fixture), fixture.length)).isEmpty();
    }
}
