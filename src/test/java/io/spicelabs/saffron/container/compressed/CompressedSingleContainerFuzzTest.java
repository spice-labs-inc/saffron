/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.compressed;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.spicelabs.saffron.container.BinaryContainerMount;
import io.spicelabs.saffron.container.ContainerDetector;
import io.spicelabs.saffron.fs.FileSystem;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Fuzz tests for compressed single non-archive payload containers.
 */
class CompressedSingleContainerFuzzTest {

    private static final int MUTATION_SIZE = 64;
    private static final byte[] PAYLOAD = ("a".repeat(128) + "fuzz payload").getBytes();

    /**
     * Mutates the first 64 bytes of a gzip-compressed payload and verifies that
     * detection and mounting either succeed, reject cleanly, or return empty; no
     * unchecked exception or crash occurs.
     */
    @FuzzTest(maxDuration = "30s")
    void gzipHeaderFuzz(FuzzedDataProvider data) throws IOException {
        fuzzRoundTrip(gzip(PAYLOAD), data);
    }

    /**
     * Mutates the first 64 bytes of an xz-compressed payload and verifies that
     * detection and mounting either succeed, reject cleanly, or return empty; no
     * unchecked exception or crash occurs.
     */
    @FuzzTest(maxDuration = "30s")
    void xzHeaderFuzz(FuzzedDataProvider data) throws IOException {
        fuzzRoundTrip(xz(PAYLOAD), data);
    }

    /**
     * Mutates the first 64 bytes of a bzip2-compressed payload and verifies that
     * detection and mounting either succeed, reject cleanly, or return empty; no
     * unchecked exception or crash occurs.
     */
    @FuzzTest(maxDuration = "30s")
    void bzip2HeaderFuzz(FuzzedDataProvider data) throws IOException {
        fuzzRoundTrip(bzip2(PAYLOAD), data);
    }

    private static void fuzzRoundTrip(byte[] original, FuzzedDataProvider data) throws IOException {
        byte[] mutated = data.consumeBytes(MUTATION_SIZE);
        byte[] image = original.clone();
        int toCopy = Math.min(MUTATION_SIZE, mutated.length);
        System.arraycopy(mutated, 0, image, 0, toCopy);

        ByteBuffer buffer = ByteBuffer.wrap(image);

        try {
            ContainerDetector.detect(buffer);
        } catch (IllegalArgumentException | ArithmeticException | UnsupportedOperationException e) {
            // Expected for malformed input
        }

        try {
            Optional<FileSystem> fs = BinaryContainerMount.mount(buffer);
            fs.ifPresent(f -> {
                try {
                    f.close();
                } catch (IOException ignored) {
                }
            });
        } catch (IllegalArgumentException | ArithmeticException | UnsupportedOperationException | IOException e) {
            // Expected for malformed or undetectable input
        }
    }

    private static byte[] gzip(byte[] payload) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(payload);
            gz.finish();
            return out.toByteArray();
        }
    }

    private static byte[] xz(byte[] payload) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             XZCompressorOutputStream xz = new XZCompressorOutputStream(out)) {
            xz.write(payload);
            return out.toByteArray();
        }
    }

    private static byte[] bzip2(byte[] payload) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             BZip2CompressorOutputStream bz = new BZip2CompressorOutputStream(out)) {
            bz.write(payload);
            return out.toByteArray();
        }
    }
}
