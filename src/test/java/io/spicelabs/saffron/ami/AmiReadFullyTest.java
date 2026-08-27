/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.ami;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seam test for {@link AmiDiskImpl#readFully} (phase 1, R1.6).
 *
 * <p>Partial/truncated reads are not reproducible through a real file, so
 * the package-visible seam lets the test force the failure mode directly:
 * a stream that returns short chunks then EOF must throw
 * {@code IOException}, never under-fill with zeros.</p>
 */
class AmiReadFullyTest {

    @Test
    void readFullyThrowsOnShortStream() {
        InputStream shortStream = new InputStream() {
            private int remaining = 64;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                remaining--;
                return 0x42;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = Math.min(Math.min(len, 16), remaining);
                for (int i = 0; i < n; i++) {
                    b[off + i] = 0x42;
                }
                remaining -= n;
                return n;
            }
        };

        assertThatThrownBy(() -> AmiDiskImpl.readFully(shortStream, new byte[100], 0, 100))
                .isInstanceOf(IOException.class);
    }

    @Test
    void readFullySucceedsOnFullStream() throws IOException {
        byte[] in = new byte[100];
        for (int i = 0; i < 100; i++) {
            in[i] = (byte) i;
        }
        byte[] out = new byte[100];
        AmiDiskImpl.readFully(new java.io.ByteArrayInputStream(in), out, 0, 100);
        org.assertj.core.api.Assertions.assertThat(out).isEqualTo(in);
    }
}
