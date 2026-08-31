/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.ntfs;

import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MFT cache budget tests (phase 6, T6.1).
 *
 * <h2>LLM section</h2>
 * <p>A synthetic volume with 4200 minimal records drives the entry cap
 * (cache stays ≤ 4096); a record whose $DATA declares an 8 MiB payload
 * exceeds the per-record skip threshold (4 MiB) and is never cached.</p>
 */
class NtfsCacheBudgetTest {

    private static final int RECORD_SIZE = 1024;
    private static final int VOLUME_SIZE = 16 * 1024 * 1024;
    private static final int MFT_OFFSET = 6 * 4096;

    /** Boot sector + MFT region containing {@code recordCount} minimal records. */
    private static byte[] volume(int recordCount, int hugeDataRecord) {
        byte[] data = new byte[VOLUME_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0xEB);
        buf.put(1, (byte) 0x52);
        buf.put(2, (byte) 0x90);
        buf.position(3);
        buf.put("NTFS    ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buf.putShort(11, (short) 512);
        buf.put(13, (byte) 8);               // sectors per cluster
        buf.put(21, (byte) 0xF8);
        buf.putLong(40, VOLUME_SIZE / 512L);
        buf.putLong(48, 6);                  // MFT cluster
        buf.putLong(56, 7);
        buf.put(64, (byte) -10);             // 2^10 = 1024-byte records
        buf.put(68, (byte) -12);
        buf.putLong(72, 0x1234567890ABCDEFL);
        buf.putShort(510, (short) 0xAA55);

        for (int r = 0; r < recordCount; r++) {
            int offset = MFT_OFFSET + r * RECORD_SIZE;
            buf.position(offset);
            buf.putInt(0x454C4946);          // "FILE"
            buf.putShort((short) 48);
            buf.putShort((short) 3);
            buf.putLong(0);
            buf.putShort((short) 1);
            buf.putShort((short) 1);
            buf.putShort((short) 56);        // first attribute offset
            buf.putShort((short) MftRecord.FLAG_IN_USE);
            buf.putInt(512);                 // used size
            // One minimal resident $STANDARD_INFORMATION-like attribute.
            buf.position(offset + 56);
            buf.putInt(NtfsAttribute.TYPE_STANDARD_INFORMATION);
            buf.putInt(32);                  // attribute length
            buf.put((byte) 0);               // resident
            buf.put((byte) 0);
            buf.putShort((short) 24);
            buf.putShort((short) 0);
            buf.putShort((short) 0);
            buf.putInt(0);                   // value length
            buf.putShort((short) 24);
            buf.putShort((short) 0);
            buf.position(offset + 56 + 32);
            buf.putInt(0xFFFFFFFF);          // end of attributes

            if (r == hugeDataRecord) {
                // A non-resident $DATA declaring an 8 MiB payload (sparse
                // run: nothing on disk) - payloadBytes exceeds the 4 MiB
                // per-record cache threshold.
                buf.position(offset + 56);
                buf.putInt(NtfsAttribute.TYPE_DATA);
                buf.putInt(72);
                buf.put((byte) 1);           // non-resident
                buf.put((byte) 0);
                buf.putShort((short) 64);
                buf.putShort((short) 0);
                buf.putShort((short) 0);
                buf.putLong(0);              // start VCN
                buf.putLong(2047);           // end VCN (2048 clusters x 4096 = 8 MiB)
                buf.putShort((short) 64);
                buf.putShort((short) 0);
                buf.putInt(0);
                buf.putLong(8L * 1024 * 1024);
                buf.putLong(8L * 1024 * 1024);
                buf.putLong(8L * 1024 * 1024);
                buf.position(offset + 56 + 64);
                buf.put((byte) 0x21);        // 2-byte offset, 1-byte length
                buf.put((byte) 0);           // zero-length run -> sparse
                buf.putShort((short) 0);
                buf.put((byte) 0);
                buf.position(offset + 56 + 72);
                buf.putInt(0xFFFFFFFF);
            }
        }
        return data;
    }

    private static NtfsFileSystemImpl mountBytes(byte[] data) throws IOException {
        return NtfsFileSystemImpl.mount(new DiskRegion() {
            @Override
            public ByteBuffer read(long offset, int length) {
                byte[] out = new byte[length];
                System.arraycopy(data, (int) offset, out, 0, length);
                return ByteBuffer.wrap(out);
            }

            @Override
            public long size() {
                return data.length;
            }
        });
    }

    @Test
    void mftCacheStaysWithinEntryCap() throws IOException {
        NtfsFileSystemImpl fs = mountBytes(volume(4200, -1));
        for (int r = 0; r < 4200; r++) {
            fs.readMftRecord(r);
        }
        assertThat(fs.mftCacheEntries()).isLessThanOrEqualTo(4096);
    }

    @Test
    void recordsWithHugePayloadsAreNeverCached() throws IOException {
        NtfsFileSystemImpl fs = mountBytes(volume(8, 6));
        for (int r = 0; r < 8; r++) {
            fs.readMftRecord(r);
        }
        // Records 0..5,7 are cached; record 6 (8 MiB payload) is not.
        assertThat(fs.mftCacheEntries()).isEqualTo(7);
        assertThat(fs.mftCacheBytes()).isLessThan(1024 * 1024);
    }
}
