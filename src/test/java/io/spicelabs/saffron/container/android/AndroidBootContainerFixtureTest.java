/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.android;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture integrity tests for the synthetic Android boot image.
 */
class AndroidBootContainerFixtureTest {

    private static final String FIXTURE = "src/test/resources/android-boot/boot.img";

    @Test
    void fixtureShaMatchesDocumented() throws IOException, NoSuchAlgorithmException {
        byte[] data = Files.readAllBytes(Path.of(FIXTURE));
        String sha = sha256(data);
        assertThat(sha).isEqualTo("a617fc68f5f36783d7feabb1348877f91c13b14dac12a2db1c931ea303045fc3");
    }

    @Test
    void fixtureIsUnderSizeLimit() throws IOException {
        long size = Files.size(Path.of(FIXTURE));
        assertThat(size).isLessThan(6L * 1024 * 1024);
    }

    private static String sha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
