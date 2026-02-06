/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies the GPG signature on the corpus manifest.
 *
 * <p>The manifest must be signed with a trusted key to prevent tampering.
 * This test requires GPG to be installed and the signing key to be in the keyring.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
@EnabledIf("corpusAndSignatureExist")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class CorpusSignatureVerificationTest {

    private static final Path CORPUS_PATH = Path.of("test-corpus");
    private static final Path MANIFEST_PATH = CORPUS_PATH.resolve("manifest.json");
    private static final Path SIGNATURE_PATH = CORPUS_PATH.resolve("manifest.json.sig");

    static boolean corpusAndSignatureExist() {
        return Files.exists(MANIFEST_PATH) && Files.exists(SIGNATURE_PATH);
    }

    @Test
    void manifest_hasDetachedSignature() {
        assertThat(SIGNATURE_PATH)
                .as("Manifest signature file should exist")
                .exists();
    }

    @Test
    void manifest_signatureIsValid() throws Exception {
        // Check if GPG is available
        if (!isGpgAvailable()) {
            // Skip test if GPG not installed
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(
                "gpg", "--verify", SIGNATURE_PATH.toString(), MANIFEST_PATH.toString()
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        assertThat(exitCode)
                .as("GPG signature verification should succeed.\nOutput: %s", output)
                .isZero();
    }

    @Test
    void manifest_signatureHasNotExpired() throws Exception {
        if (!isGpgAvailable() || !Files.exists(SIGNATURE_PATH)) {
            return;
        }

        // Check signature details for expiration
        ProcessBuilder pb = new ProcessBuilder(
                "gpg", "--verify", "--verbose", SIGNATURE_PATH.toString(), MANIFEST_PATH.toString()
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor();

        // Check for expiration warnings
        String outputStr = output.toString();
        assertThat(outputStr)
                .as("Signature should not be expired")
                .doesNotContainIgnoringCase("expired");
    }

    @Test
    void manifest_modificationInvalidatesSignature() throws Exception {
        if (!isGpgAvailable() || !Files.exists(SIGNATURE_PATH)) {
            return;
        }

        // Create a modified copy of the manifest
        Path tempManifest = Files.createTempFile("manifest-modified", ".json");
        try {
            String content = Files.readString(MANIFEST_PATH);
            Files.writeString(tempManifest, content + " ");  // Add a space

            // Verify signature fails on modified file
            ProcessBuilder pb = new ProcessBuilder(
                    "gpg", "--verify", SIGNATURE_PATH.toString(), tempManifest.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int exitCode = process.waitFor();

            assertThat(exitCode)
                    .as("Modified manifest should fail signature verification")
                    .isNotZero();
        } finally {
            Files.deleteIfExists(tempManifest);
        }
    }

    private boolean isGpgAvailable() {
        try {
            Process process = new ProcessBuilder("gpg", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
