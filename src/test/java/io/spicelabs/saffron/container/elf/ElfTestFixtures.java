/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.container.elf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Helpers for building synthetic ELF files for tests.
 */
final class ElfTestFixtures {

    private ElfTestFixtures() {
    }

    static final byte[] ELF_MAGIC = {0x7f, 0x45, 0x4c, 0x46};

    static final int ELFCLASS32 = 1;
    static final int ELFCLASS64 = 2;
    static final int ELFDATA2LSB = 1;
    static final int ELFDATA2MSB = 2;

    static final int ET_EXEC = 2;
    static final int EM_386 = 0x03;
    static final int EM_ARM = 0x28;
    static final int EM_X86_64 = 0x3e;
    static final int EM_AARCH64 = 0xb7;

    static final int SHT_NULL = 0;
    static final int SHT_PROGBITS = 1;
    static final int SHT_STRTAB = 3;
    static final int SHT_NOBITS = 8;

    static final int PT_LOAD = 1;
    static final int PT_DYNAMIC = 2;

    static final int SHN_UNDEF = 0;
    static final int SHN_XINDEX = 0xffff;

    static final int PF_X = 1;
    static final int PF_W = 2;
    static final int PF_R = 4;

    static final byte[] DEFAULT_CONTENT = "Hello, ELF!BEGIN CERTIFICATE-----END CERTIFICATE".getBytes();

    /**
     * Builds a minimal valid ELF with one loadable segment and one data section.
     */
    static ByteBuffer buildValidElf(int elfClass, boolean littleEndian) {
        return buildValidElf(elfClass, littleEndian, DEFAULT_CONTENT, ".data");
    }

    static ByteBuffer buildValidElf(int elfClass, boolean littleEndian, byte[] content, String sectionName) {
        return buildElf(elfClass, littleEndian, content, sectionName, new ElfOverrides());
    }

    /**
     * Overrides applied to the generated ELF header. Fields are long to allow
     * out-of-bounds and overflow values for negative tests.
     */
    static final class ElfOverrides {
        long eType = ET_EXEC;
        long eMachine = EM_386;
        long eEntry = 0;
        long ePhoff = -1; // computed if negative
        long eShoff = -1; // computed if negative
        long eFlags = 0;
        long eEhsize = -1; // computed if negative
        long ePhentsize = -1; // computed if negative
        long ePhnum = 1;
        long eShentsize = -1; // computed if negative
        long eShnum = 3;
        long eShstrndx = 2;
        long pType = PT_LOAD;
        long pOffset = -1; // computed if negative
        long pVaddr = 0;
        long pPaddr = 0;
        long pFilesz = -1; // computed if negative
        long pMemsz = -1; // computed if negative
        long pFlags = PF_R | PF_X;
        long pAlign = 1;
        long shName1 = 1; // offset of section name in string table
        long shType1 = SHT_PROGBITS;
        long shFlags1 = 0;
        long shAddr1 = 0;
        long shOffset1 = -1; // computed if negative
        long shSize1 = -1; // computed if negative
        long shLink1 = 0;
        long shInfo1 = 0;
        long shAddralign1 = 1;
        long shEntsize1 = 0;
        long shNameStrtab = -1; // computed if negative
        long shTypeStrtab = SHT_STRTAB;
        long shFlagsStrtab = 0;
        long shAddrStrtab = 0;
        long shOffsetStrtab = -1; // computed if negative
        long shSizeStrtab = -1; // computed if negative
        long shLinkStrtab = 0;
        long shInfoStrtab = 0;
        long shAddralignStrtab = 1;
        long shEntsizeStrtab = 0;
    }

    static ByteBuffer buildElf(int elfClass, boolean littleEndian, byte[] content, String sectionName,
                               ElfOverrides overrides) {
        boolean is64 = elfClass == ELFCLASS64;
        int headerSize = is64 ? 64 : 52;
        int phdrSize = is64 ? 56 : 32;
        int shdrSize = is64 ? 64 : 40;
        ByteOrder order = littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

        String strtab = "\0" + sectionName + "\0.shstrtab\0";
        byte[] strtabBytes = strtab.getBytes();
        int strtabNameOffset = 1 + sectionName.length() + 1;

        // Natural layout used for sizing and for placing content/string table.
        long naturalPhoff = headerSize;
        long naturalDataOffset = naturalPhoff + phdrSize * (int) overrides.ePhnum;
        long naturalStrtabOffset = naturalDataOffset + content.length;
        long naturalShoff = naturalStrtabOffset + strtabBytes.length;
        long totalSize = naturalShoff + shdrSize * (int) overrides.eShnum;

        // Header fields may be overridden to point out of bounds, but the image
        // size stays natural so that the offsets are genuinely beyond the file.
        long phoff = overrides.ePhoff >= 0 ? overrides.ePhoff : naturalPhoff;
        long shoff = overrides.eShoff >= 0 ? overrides.eShoff : naturalShoff;
        long dataOffset = naturalDataOffset;
        long strtabOffset = naturalStrtabOffset;

        long ehsize = overrides.eEhsize >= 0 ? overrides.eEhsize : headerSize;
        long phentsize = overrides.ePhentsize >= 0 ? overrides.ePhentsize : phdrSize;
        long shentsize = overrides.eShentsize >= 0 ? overrides.eShentsize : shdrSize;

        long pOffset = overrides.pOffset >= 0 ? overrides.pOffset : dataOffset;
        long pFilesz = overrides.pFilesz >= 0 ? overrides.pFilesz : content.length;
        long pMemsz = overrides.pMemsz >= 0 ? overrides.pMemsz : content.length;

        long shOffset1 = overrides.shOffset1 >= 0 ? overrides.shOffset1 : dataOffset;
        long shSize1 = overrides.shSize1 >= 0 ? overrides.shSize1 : content.length;
        long shOffsetStrtab = overrides.shOffsetStrtab >= 0 ? overrides.shOffsetStrtab : strtabOffset;
        long shSizeStrtab = overrides.shSizeStrtab >= 0 ? overrides.shSizeStrtab : strtabBytes.length;
        long shNameStrtab = overrides.shNameStrtab >= 0 ? overrides.shNameStrtab : strtabNameOffset;

        byte[] image = new byte[(int) totalSize];
        ByteBuffer buffer = ByteBuffer.wrap(image).order(order);

        // e_ident
        buffer.put(0, ELF_MAGIC[0]);
        buffer.put(1, ELF_MAGIC[1]);
        buffer.put(2, ELF_MAGIC[2]);
        buffer.put(3, ELF_MAGIC[3]);
        buffer.put(4, (byte) elfClass);
        buffer.put(5, (byte) (littleEndian ? ELFDATA2LSB : ELFDATA2MSB));
        buffer.put(6, (byte) 1); // EV_CURRENT
        buffer.put(7, (byte) 0); // OSABI
        for (int i = 8; i < 16; i++) {
            buffer.put(i, (byte) 0);
        }

        writeHalf(buffer, 16, overrides.eType, order);
        writeHalf(buffer, 18, overrides.eMachine, order);
        writeWord(buffer, 20, 1, order); // e_version
        if (is64) {
            writeXword(buffer, 24, overrides.eEntry, order);
            writeXword(buffer, 32, phoff, order);
            writeXword(buffer, 40, shoff, order);
            writeWord(buffer, 48, overrides.eFlags, order);
            writeHalf(buffer, 52, ehsize, order);
            writeHalf(buffer, 54, phentsize, order);
            writeHalf(buffer, 56, overrides.ePhnum, order);
            writeHalf(buffer, 58, shentsize, order);
            writeHalf(buffer, 60, overrides.eShnum, order);
            writeHalf(buffer, 62, overrides.eShstrndx, order);
        } else {
            writeWord(buffer, 24, overrides.eEntry, order);
            writeWord(buffer, 28, phoff, order);
            writeWord(buffer, 32, shoff, order);
            writeWord(buffer, 36, overrides.eFlags, order);
            writeHalf(buffer, 40, ehsize, order);
            writeHalf(buffer, 42, phentsize, order);
            writeHalf(buffer, 44, overrides.ePhnum, order);
            writeHalf(buffer, 46, shentsize, order);
            writeHalf(buffer, 48, overrides.eShnum, order);
            writeHalf(buffer, 50, overrides.eShstrndx, order);
        }

        // Program header
        long phdrTableSize = overrides.ePhnum > 0 ? (long) phdrSize * (int) overrides.ePhnum : 0;
        if (phoff + phdrTableSize <= totalSize) {
            long phdrBase = phoff;
            if (is64) {
                writeWord(buffer, (int) phdrBase, overrides.pType, order);
                writeWord(buffer, (int) phdrBase + 4, overrides.pFlags, order);
                writeXword(buffer, (int) phdrBase + 8, pOffset, order);
                writeXword(buffer, (int) phdrBase + 16, overrides.pVaddr, order);
                writeXword(buffer, (int) phdrBase + 24, overrides.pPaddr, order);
                writeXword(buffer, (int) phdrBase + 32, pFilesz, order);
                writeXword(buffer, (int) phdrBase + 40, pMemsz, order);
                writeXword(buffer, (int) phdrBase + 48, overrides.pAlign, order);
            } else {
                writeWord(buffer, (int) phdrBase, overrides.pType, order);
                writeWord(buffer, (int) phdrBase + 4, pOffset, order);
                writeWord(buffer, (int) phdrBase + 8, overrides.pVaddr, order);
                writeWord(buffer, (int) phdrBase + 12, overrides.pPaddr, order);
                writeWord(buffer, (int) phdrBase + 16, pFilesz, order);
                writeWord(buffer, (int) phdrBase + 20, pMemsz, order);
                writeWord(buffer, (int) phdrBase + 24, overrides.pFlags, order);
                writeWord(buffer, (int) phdrBase + 28, overrides.pAlign, order);
            }
        }

        // Section data
        System.arraycopy(content, 0, image, (int) dataOffset, content.length);
        System.arraycopy(strtabBytes, 0, image, (int) strtabOffset, strtabBytes.length);

        // Section headers
        long shdrTableSize = overrides.eShnum > 0 ? (long) shdrSize * (int) overrides.eShnum : 0;
        if (shoff + shdrTableSize <= totalSize) {
            long shdrBase = shoff;
            for (int i = 0; i < overrides.eShnum; i++) {
                long base = shdrBase + (long) i * shdrSize;
                if (i == 0) {
                    // NULL section
                } else if (i == 1) {
                    writeShdr(buffer, (int) base, is64, order, overrides.shName1, overrides.shType1,
                            overrides.shFlags1, overrides.shAddr1, shOffset1, shSize1,
                            overrides.shLink1, overrides.shInfo1, overrides.shAddralign1, overrides.shEntsize1);
                } else if (i == 2) {
                    writeShdr(buffer, (int) base, is64, order, shNameStrtab, overrides.shTypeStrtab,
                            overrides.shFlagsStrtab, overrides.shAddrStrtab, shOffsetStrtab, shSizeStrtab,
                            overrides.shLinkStrtab, overrides.shInfoStrtab, overrides.shAddralignStrtab,
                            overrides.shEntsizeStrtab);
                }
            }
        }

        return ByteBuffer.wrap(image).order(order);
    }

    private static void writeShdr(ByteBuffer buffer, int base, boolean is64, ByteOrder order,
                                  long shName, long shType, long shFlags, long shAddr, long shOffset,
                                  long shSize, long shLink, long shInfo, long shAddralign, long shEntsize) {
        writeWord(buffer, base, shName, order);
        writeWord(buffer, base + 4, shType, order);
        if (is64) {
            writeXword(buffer, base + 8, shFlags, order);
            writeXword(buffer, base + 16, shAddr, order);
            writeXword(buffer, base + 24, shOffset, order);
            writeXword(buffer, base + 32, shSize, order);
            writeWord(buffer, base + 40, shLink, order);
            writeWord(buffer, base + 44, shInfo, order);
            writeXword(buffer, base + 48, shAddralign, order);
            writeXword(buffer, base + 56, shEntsize, order);
        } else {
            writeWord(buffer, base + 8, shFlags, order);
            writeWord(buffer, base + 12, shAddr, order);
            writeWord(buffer, base + 16, shOffset, order);
            writeWord(buffer, base + 20, shSize, order);
            writeWord(buffer, base + 24, shLink, order);
            writeWord(buffer, base + 28, shInfo, order);
            writeWord(buffer, base + 32, shAddralign, order);
            writeWord(buffer, base + 36, shEntsize, order);
        }
    }

    private static void writeHalf(ByteBuffer buffer, int offset, long value, ByteOrder order) {
        ByteBuffer dup = buffer.duplicate().order(order);
        dup.putShort(offset, (short) value);
    }

    private static void writeWord(ByteBuffer buffer, int offset, long value, ByteOrder order) {
        ByteBuffer dup = buffer.duplicate().order(order);
        dup.putInt(offset, (int) value);
    }

    private static void writeXword(ByteBuffer buffer, int offset, long value, ByteOrder order) {
        ByteBuffer dup = buffer.duplicate().order(order);
        dup.putLong(offset, value);
    }
}
