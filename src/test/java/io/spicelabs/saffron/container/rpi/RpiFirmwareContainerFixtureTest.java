/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.rpi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture integrity tests for Raspberry Pi firmware.
 */
class RpiFirmwareContainerFixtureTest {

    private static final String BOOTCODE = "src/test/resources/rpi-firmware/bootcode.bin";
    private static final String FIXUP = "src/test/resources/rpi-firmware/fixup.dat";

    @Test
    void bootcodeShaMatchesDocumented() throws IOException, NoSuchAlgorithmException {
        byte[] data = Files.readAllBytes(Path.of(BOOTCODE));
        String sha = sha256(data);
        assertThat(sha).isEqualTo("7b24659eb049333eec69f59cf0c5aa0d49eab5ed67726af3c6f0c9bcf1e3f9e3");
    }

    @Test
    void fixupShaMatchesDocumented() throws IOException, NoSuchAlgorithmException {
        byte[] data = Files.readAllBytes(Path.of(FIXUP));
        String sha = sha256(data);
        assertThat(sha).isEqualTo("5e5946fe7c0b1f5e270f43a17aef5f6da5758d5e544ed37137628c972c9b8061");
    }

    private static String sha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
