/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.filesystem.fat32;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link FatDirectoryEntry}.
 */
class FatDirectoryEntryTest {

    @Test
    void parse_lowercaseBaseAndExtFlags_lowercasesWholeName() {
        assertThat(parseName("GRUBX64 EFI", 0x18, FatDirectoryEntry.ATTR_ARCHIVE)).isEqualTo("grubx64.efi");
    }

    @Test
    void parse_lowercaseBaseFlagOnly_lowercasesBaseOnly() {
        assertThat(parseName("GRUBX64 EFI", 0x08, FatDirectoryEntry.ATTR_ARCHIVE)).isEqualTo("grubx64.EFI");
    }

    @Test
    void parse_lowercaseExtFlagOnly_lowercasesExtOnly() {
        assertThat(parseName("GRUBX64 EFI", 0x10, FatDirectoryEntry.ATTR_ARCHIVE)).isEqualTo("GRUBX64.efi");
    }

    @Test
    void parse_noCaseFlags_keepsUpperCase() {
        assertThat(parseName("BOOTX64 EFI", 0x00, FatDirectoryEntry.ATTR_ARCHIVE)).isEqualTo("BOOTX64.EFI");
    }

    @Test
    void parse_directoryWithoutExtension_lowercasesBase() {
        assertThat(parseName("BOOT       ", 0x08, FatDirectoryEntry.ATTR_DIRECTORY)).isEqualTo("boot");
    }

    @Test
    void parse_caseFlagsWithLongName_usesLongNameVerbatim() {
        ByteBuffer buffer = entry("SHIMX6~1EFI", 0x18, FatDirectoryEntry.ATTR_ARCHIVE);
        FatDirectoryEntry parsed = FatDirectoryEntry.parse(buffer, Optional.of("shimx64-AlmaLinux.efi")).orElseThrow();
        assertThat(parsed.name()).isEqualTo("shimx64-AlmaLinux.efi");
    }

    private static String parseName(String name83, int ntFlags, byte attr) {
        return FatDirectoryEntry.parse(entry(name83, ntFlags, attr), Optional.empty()).orElseThrow().name();
    }

    private static ByteBuffer entry(String name83, int ntFlags, byte attr) {
        ByteBuffer buffer = ByteBuffer.allocate(FatDirectoryEntry.ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(0, name83.getBytes(StandardCharsets.US_ASCII));
        buffer.put(11, attr);
        buffer.put(12, (byte) ntFlags);
        buffer.putShort(26, (short) 3);
        buffer.putInt(28, 1234);
        return buffer;
    }
}
