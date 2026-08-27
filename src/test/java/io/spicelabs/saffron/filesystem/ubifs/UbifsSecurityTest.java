/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ubifs;

import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Defensive tests for {@link UbifsFileSystemImpl}: rejection of dirty
 * (uncleanly rebooted) images, encrypted images, and malformed input.
 */
class UbifsSecurityTest {

    private static final String FIX = "src/test/resources/ubifs/fixtures";

    /**
     * A dirty master node (MST_DIRTY flag) must be rejected with a clear
     * IOException: journal replay is not supported.
     */
    @Test
    void dirtyMasterIsRejected() throws IOException {
        byte[] image = Files.readAllBytes(Path.of(FIX, "tree-zlib.ubifs"));
        // The master node sits at LEB 1 (leb_size = 126976). Set bit 0 of
        // the MST flags field and recompute the node CRC.
        int lebSize = 126976;
        int mstOff = lebSize;
        ByteBuffer b = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        int len = b.getInt(mstOff + 16);
        b.putInt(mstOff + UbifsNode.MST_FLAGS,
                b.getInt(mstOff + UbifsNode.MST_FLAGS) | UbifsNode.MST_DIRTY);
        // Fix the CRC over bytes 8..len (crc field excluded implicitly by
        // the range start).
        byte[] node = new byte[len];
        System.arraycopy(image, mstOff, node, 0, len);
        int crc = UbifsNode.crc32(node, 8, len - 8);
        b.putInt(mstOff + 4, crc);

        assertThatThrownBy(() -> UbifsFileSystemImpl.mount(new Region(image)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("dirty");
    }

    /**
     * An image whose superblock declares encryption must be rejected: no
     * key material is available.
     */
    @Test
    void encryptedImageIsRejected() throws IOException {
        byte[] image = Files.readAllBytes(Path.of(FIX, "tree-zlib.ubifs"));
        ByteBuffer b = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        int sbLen = b.getInt(16);
        b.putInt(UbifsNode.SB_FLAGS,
                b.getInt(UbifsNode.SB_FLAGS) | UbifsNode.FLG_ENCRYPTION);
        byte[] node = new byte[sbLen];
        System.arraycopy(image, 0, node, 0, sbLen);
        b.putInt(4, UbifsNode.crc32(node, 8, sbLen - 8));

        assertThatThrownBy(() -> UbifsFileSystemImpl.mount(new Region(image)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("encryption");
    }

    /**
     * Random garbage must not mount.
     */
    @Test
    void garbageFailsMountCleanly() {
        byte[] garbage = new byte[256 * 1024];
        new Random(11).nextBytes(garbage);

        assertThatThrownBy(() -> UbifsFileSystemImpl.mount(new Region(garbage)))
                .isInstanceOf(IOException.class);
    }

    static final class Region implements DiskRegion {
        private final byte[] data;

        Region(byte[] data) {
            this.data = data;
        }

        @Override
        public ByteBuffer read(long offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IOException("Read out of bounds");
            }
            byte[] copy = new byte[length];
            System.arraycopy(data, (int) offset, copy, 0, length);
            return ByteBuffer.wrap(copy);
        }

        @Override
        public long size() {
            return data.length;
        }
    }
}
