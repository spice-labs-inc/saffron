/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.devicetree;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-oriented tests for the device tree parser.
 *
 * <p>The parser must validate every offset and size against the source size
 * and reject integer overflow before reading any data.</p>
 */
class DeviceTreeBlobTest {

    private static final int DTB_MAGIC = 0xd00d_feed;
    private static final int FDT_BEGIN_NODE = 0x0000_0001;
    private static final int FDT_END_NODE = 0x0000_0002;
    private static final int FDT_PROP = 0x0000_0003;
    private static final int FDT_END = 0x0000_0009;

    @Test
    void rejectsOversizedStructureBlock() {
        ByteBuffer header = createDtbHeader(64, 40, 1000, 0, 0);

        Optional<DeviceTreeBlob> parsed = DeviceTreeBlob.parse(header);

        assertThat(parsed).isEmpty();
    }

    @Test
    void rejectsOverflowingOffsets() {
        ByteBuffer header = createDtbHeader(
                64,
                Integer.MAX_VALUE - 10L,
                100,
                0,
                0
        );

        Optional<DeviceTreeBlob> parsed = DeviceTreeBlob.parse(header);

        assertThat(parsed).isEmpty();
    }

    @Test
    void rejectsTruncatedHeader() {
        ByteBuffer header = ByteBuffer.allocate(2);

        Optional<DeviceTreeBlob> parsed = DeviceTreeBlob.parse(header);

        assertThat(parsed).isEmpty();
    }

    @Test
    void rejectsUnterminatedStringName() {
        // A valid structure block refers to a string that is not null-terminated
        // inside the strings block. The parser must reject this rather than
        // return a truncated or unbounded property name.
        ByteBuffer struct = ByteBuffer.allocate(64);
        struct.order(ByteOrder.BIG_ENDIAN);
        struct.putInt(FDT_BEGIN_NODE); // root node
        struct.put((byte) 0);          // empty root name, null-terminated
        struct.put(new byte[3]);        // pad to word boundary
        struct.putInt(FDT_PROP);
        struct.putInt(0);              // property length
        struct.putInt(0);              // name offset into strings block
        struct.putInt(FDT_END_NODE);
        struct.putInt(FDT_END);
        int sizeDtStruct = struct.position();
        struct.flip();

        // Strings block contains "compat" with no null terminator.
        byte[] strings = "compat".getBytes(StandardCharsets.US_ASCII);

        int offDtStruct = 40;
        int offDtStrings = offDtStruct + sizeDtStruct;
        int totalsize = offDtStrings + strings.length;

        ByteBuffer dtb = ByteBuffer.allocate(totalsize);
        dtb.order(ByteOrder.BIG_ENDIAN);
        dtb.putInt(0, DTB_MAGIC);
        dtb.putInt(4, totalsize);
        dtb.putInt(8, offDtStruct);
        dtb.putInt(12, offDtStrings);
        dtb.putInt(16, 0);  // off_mem_rsvmap
        dtb.putInt(20, 17); // version
        dtb.putInt(24, 16); // last_comp_version
        dtb.putInt(28, 0);  // boot_cpuid_phys
        dtb.putInt(32, strings.length);
        dtb.putInt(36, sizeDtStruct);
        dtb.position(offDtStruct);
        dtb.put(struct);
        dtb.position(offDtStrings);
        dtb.put(strings);

        Optional<DeviceTreeBlob> parsed = DeviceTreeBlob.parse(dtb);

        assertThat(parsed).isEmpty();
    }

    private static ByteBuffer createDtbHeader(
            long totalsize,
            long offDtStruct,
            long sizeDtStruct,
            long offDtStrings,
            long sizeDtStrings
    ) {
        ByteBuffer buffer = ByteBuffer.allocate((int) totalsize);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0, DTB_MAGIC);
        buffer.putInt(4, (int) totalsize);
        buffer.putInt(8, (int) offDtStruct);
        buffer.putInt(12, (int) offDtStrings);
        buffer.putInt(16, 0); // off_mem_rsvmap
        buffer.putInt(20, 17); // version
        buffer.putInt(24, 16); // last_comp_version
        buffer.putInt(28, 0); // boot_cpuid_phys
        buffer.putInt(32, (int) sizeDtStrings);
        buffer.putInt(36, (int) sizeDtStruct);
        return buffer;
    }
}
