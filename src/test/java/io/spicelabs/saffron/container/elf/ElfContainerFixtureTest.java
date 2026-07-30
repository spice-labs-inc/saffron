/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the ELF test fixtures match the documented SHA-256 hashes.
 */
class ElfContainerFixtureTest {

    @Test
    void libElfMatchesDocumentedSha() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/elf/libmbedx509.so"));
        String sha = sha256(bytes);
        assertThat(sha).isEqualTo("a2dfb2235f397e44aa875279b69ba6d762c841bdb43abd8f855da9650b49d63a");
    }

    @Test
    void startElfMatchesDocumentedSha() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/elf/start.elf"));
        String sha = sha256(bytes);
        assertThat(sha).isEqualTo("7e69d068c249cd859f5c44a8cf80a5e96a3f3fbeeeb0260de7373130a1d9b0fe");
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
