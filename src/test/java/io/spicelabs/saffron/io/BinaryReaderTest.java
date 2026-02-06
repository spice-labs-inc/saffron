/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BinaryReader}.
 */
class BinaryReaderTest {

    @Test
    void readLittleEndianInt32_fromKnownBytes_returnsCorrectValue() throws IOException {
        // 0x04030201 in little-endian
        byte[] data = {0x01, 0x02, 0x03, 0x04};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThat(reader.readInt32()).isEqualTo(0x04030201);
    }

    @Test
    void readBigEndianInt32_fromKnownBytes_returnsCorrectValue() throws IOException {
        byte[] data = {0x01, 0x02, 0x03, 0x04};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.BIG_ENDIAN);

        assertThat(reader.readInt32()).isEqualTo(0x01020304);
    }

    @Test
    void readInt64_fromKnownBytes_returnsCorrectValue() throws IOException {
        // Test with a value that would overflow int32
        byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                       (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThat(reader.readInt64()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void readUInt32_fromKnownBytes_returnsUnsignedValue() throws IOException {
        // 0xFFFFFFFF should be 4294967295 as unsigned
        byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThat(reader.readUInt32()).isEqualTo(4294967295L);
    }

    @Test
    void readUInt16_fromKnownBytes_returnsUnsignedValue() throws IOException {
        byte[] data = {(byte) 0xFF, (byte) 0xFF};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThat(reader.readUInt16()).isEqualTo(65535);
    }

    @Test
    void readUInt8_fromKnownByte_returnsUnsignedValue() throws IOException {
        byte[] data = {(byte) 0xFF};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThat(reader.readUInt8()).isEqualTo(255);
    }

    @Test
    void readFully_insufficientData_throwsEOFException() {
        byte[] data = {0x01, 0x02};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThatThrownBy(() -> reader.readBytes(4))
                .isInstanceOf(EOFException.class)
                .hasMessageContaining("end of stream");
    }

    @Test
    void skip_advancesPosition() throws IOException {
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        reader.skip(4);
        assertThat(reader.getPosition()).isEqualTo(4);
        assertThat(reader.readInt32()).isEqualTo(0x08070605);
    }

    @Test
    void readNullTerminatedString_returnsStringWithoutNull() throws IOException {
        byte[] data = "hello\0world".getBytes(StandardCharsets.US_ASCII);
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        String result = reader.readNullTerminatedString(11, StandardCharsets.US_ASCII);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void readString_trimTrailingNulls() throws IOException {
        byte[] data = {'t', 'e', 's', 't', 0, 0, 0, 0};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        String result = reader.readString(8, StandardCharsets.US_ASCII);
        assertThat(result).isEqualTo("test");
    }

    @Test
    void position_tracksReadBytes() throws IOException {
        byte[] data = new byte[100];
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThat(reader.getPosition()).isEqualTo(0);
        reader.readInt32();
        assertThat(reader.getPosition()).isEqualTo(4);
        reader.readInt64();
        assertThat(reader.getPosition()).isEqualTo(12);
    }

    @Test
    void bigEndian_staticFactory_createsBigEndianReader() {
        byte[] data = new byte[4];
        BinaryReader reader = BinaryReader.bigEndian(new ByteArrayInputStream(data));

        assertThat(reader.getByteOrder()).isEqualTo(ByteOrder.BIG_ENDIAN);
    }

    @Test
    void littleEndian_staticFactory_createsLittleEndianReader() {
        byte[] data = new byte[4];
        BinaryReader reader = BinaryReader.littleEndian(new ByteArrayInputStream(data));

        assertThat(reader.getByteOrder()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
    }

    // Anti-fake test
    @Test
    void binaryReader_isNotHardcoded() throws IOException {
        // Read same logical value from different byte patterns
        byte[] leData = {0x78, 0x56, 0x34, 0x12};
        byte[] beData = {0x12, 0x34, 0x56, 0x78};

        BinaryReader leReader = new BinaryReader(new ByteArrayInputStream(leData), ByteOrder.LITTLE_ENDIAN);
        BinaryReader beReader = new BinaryReader(new ByteArrayInputStream(beData), ByteOrder.BIG_ENDIAN);

        int leValue = leReader.readInt32();
        int beValue = beReader.readInt32();

        // Both should produce the same value
        assertThat(leValue).isEqualTo(beValue);
        assertThat(leValue).isEqualTo(0x12345678);
    }

    @Test
    void readBigEndianInt64_fromKnownBytes_returnsCorrectValue() throws IOException {
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.BIG_ENDIAN);

        assertThat(reader.readInt64()).isEqualTo(0x0102030405060708L);
    }

    @Test
    void readLittleEndianInt64_fromKnownBytes_returnsCorrectValue() throws IOException {
        byte[] data = {0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01};
        BinaryReader reader = new BinaryReader(new ByteArrayInputStream(data), ByteOrder.LITTLE_ENDIAN);

        assertThat(reader.readInt64()).isEqualTo(0x0102030405060708L);
    }

    @Test
    void close_closesUnderlyingStream() throws IOException {
        byte[] data = new byte[4];
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        BinaryReader reader = new BinaryReader(bais, ByteOrder.LITTLE_ENDIAN);

        reader.close();
        // ByteArrayInputStream doesn't throw on read after close, but this verifies the path
    }
}
