/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.dtb;

import io.spicelabs.saffron.container.ContainerDetector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security and boundary tests for the DTB parser and container.
 */
class DtbContainerSecurityTest {

    private static final String DTB = "src/test/resources/dtb/bcm2710-rpi-3-b.dtb";
    private static final int DTB_HEADER_SIZE = 40;

    @Test
    void rejectsStructureBlockBeyondFile() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(DTB));
        // size_dt_struct at offset 36, big-endian
        data[36] = (byte) 0xff;
        data[37] = (byte) 0xff;
        data[38] = (byte) 0xff;
        data[39] = (byte) 0xff;

        Optional<?> container = DtbContainer.open(ByteBuffer.wrap(data), data.length);
        assertThat(container).isEmpty();
    }

    @Test
    void rejectsStringsBlockBeyondFile() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(DTB));
        // off_dt_strings at offset 12, size_dt_strings at offset 32
        data[12] = (byte) 0xff;
        data[13] = (byte) 0xff;
        data[14] = (byte) 0xff;
        data[15] = (byte) 0xff;

        Optional<?> container = DtbContainer.open(ByteBuffer.wrap(data), data.length);
        assertThat(container).isEmpty();
    }

    @Test
    void rejectsTruncatedDtb() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(DTB));
        byte[] truncated = new byte[DTB_HEADER_SIZE - 1];
        System.arraycopy(data, 0, truncated, 0, truncated.length);

        Optional<?> container = DtbContainer.open(ByteBuffer.wrap(truncated), truncated.length);
        assertThat(container).isEmpty();
    }

    @Test
    void rejectsTruncatedStructure() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(DTB));
        // Keep header intact but truncate structure block
        byte[] truncated = new byte[72 + 4];
        System.arraycopy(data, 0, truncated, 0, truncated.length);
        // Patch totalsize to match truncated length
        truncated[4] = (byte) ((truncated.length >>> 24) & 0xff);
        truncated[5] = (byte) ((truncated.length >>> 16) & 0xff);
        truncated[6] = (byte) ((truncated.length >>> 8) & 0xff);
        truncated[7] = (byte) (truncated.length & 0xff);

        Optional<?> container = DtbContainer.open(ByteBuffer.wrap(truncated), truncated.length);
        assertThat(container).isEmpty();
    }

    @Test
    void rejectsMalformedMagic() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(DTB));
        data[0] = (byte) 0xde;
        data[1] = (byte) 0xad;
        data[2] = (byte) 0xbe;
        data[3] = (byte) 0xef;

        assertThat(ContainerDetector.detect(ByteBuffer.wrap(data))).isEmpty();
        assertThat(DtbContainer.open(ByteBuffer.wrap(data), data.length)).isEmpty();
    }

    @Test
    void rejectsTotalSizeSmallerThanHeader() throws IOException {
        byte[] data = Files.readAllBytes(Path.of(DTB));
        data[4] = 0;
        data[5] = 0;
        data[6] = 0;
        data[7] = (byte) 20; // totalsize = 20 < DTB_HEADER_SIZE

        Optional<?> container = DtbContainer.open(ByteBuffer.wrap(data), data.length);
        assertThat(container).isEmpty();
    }

    @Test
    void rejectsTotalSizeBeyondBuffer() {
        byte[] data = new byte[60];
        // DTB magic
        data[0] = (byte) 0xd0;
        data[1] = (byte) 0x0d;
        data[2] = (byte) 0xfe;
        data[3] = (byte) 0xed;
        // totalsize = 1000, beyond the 60-byte buffer
        data[4] = 0;
        data[5] = 0;
        data[6] = 0;
        data[7] = (byte) 1000;

        Optional<?> container = DtbContainer.open(ByteBuffer.wrap(data), data.length);
        assertThat(container).isEmpty();
    }
}
