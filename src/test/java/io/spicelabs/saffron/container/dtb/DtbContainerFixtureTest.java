/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dtb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the DTB test fixtures match the documented SHA-256 hashes.
 */
class DtbContainerFixtureTest {

    @Test
    void bcm2710Rpi3BMatchesDocumentedSha() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/dtb/bcm2710-rpi-3-b.dtb"));
        String sha = sha256(bytes);
        assertThat(sha).isEqualTo("b8f457e6f6f2a99ce00003e056c9dd29a3e1a78864e7e64b45314fa39f14a92a");
    }

    @Test
    void bcm2710Rpi3BPlusMatchesDocumentedSha() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/dtb/bcm2710-rpi-3-b-plus.dtb"));
        String sha = sha256(bytes);
        assertThat(sha).isEqualTo("63e568aee11320b136bc64c78c00d6d52954a11c488d62913a7182af8bab0176");
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
