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
package io.spicelabs.saffron.container.elf;

import org.jetbrains.annotations.NotNull;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Parses and validates an ELF executable header.
 *
 * <p>Always operates on a duplicate of the provided buffer and never mutates the
 * caller's buffer state.</p>
 */
final class ElfHeader {

    private static final byte[] ELF_MAGIC = {0x7f, 0x45, 0x4c, 0x46};

    private static final int ELFCLASS32 = 1;
    private static final int ELFCLASS64 = 2;

    private static final int ELFDATA2LSB = 1;
    private static final int ELFDATA2MSB = 2;

    private static final int EV_CURRENT = 1;

    private static final int SHN_UNDEF = 0;
    private static final int SHN_XINDEX = 0xffff;

    private static final int EH_SIZE_32 = 52;
    private static final int EH_SIZE_64 = 64;
    private static final int PHDR_SIZE_32 = 32;
    private static final int PHDR_SIZE_64 = 56;
    private static final int SHDR_SIZE_32 = 40;
    private static final int SHDR_SIZE_64 = 64;

    private final ByteBuffer source;
    private final long sourceSize;
    private final boolean is64;
    private final ByteOrder order;
    private final int eType;
    private final int eMachine;
    private final long eEntry;
    private final long ePhoff;
    private final long eShoff;
    private final int eFlags;
    private final int eEhsize;
    private final int ePhentsize;
    private final int ePhnum;
    private final int eShentsize;
    private final int eShnum;
    private final int eShstrndx;

    private ElfHeader(@NotNull ByteBuffer source, long sourceSize, boolean is64, @NotNull ByteOrder order,
                      int eType, int eMachine, long eEntry, long ePhoff, long eShoff, int eFlags,
                      int eEhsize, int ePhentsize, int ePhnum, int eShentsize, int eShnum, int eShstrndx) {
        this.source = source;
        this.sourceSize = sourceSize;
        this.is64 = is64;
        this.order = order;
        this.eType = eType;
        this.eMachine = eMachine;
        this.eEntry = eEntry;
        this.ePhoff = ePhoff;
        this.eShoff = eShoff;
        this.eFlags = eFlags;
        this.eEhsize = eEhsize;
        this.ePhentsize = ePhentsize;
        this.ePhnum = ePhnum;
        this.eShentsize = eShentsize;
        this.eShnum = eShnum;
        this.eShstrndx = eShstrndx;
    }

    static @NotNull Optional<ElfHeader> parse(@NotNull ByteBuffer source, long sourceSize) {
        try {
            if (sourceSize < 5) {
                return Optional.empty();
            }
            if (source.remaining() < 5) {
                return Optional.empty();
            }
            if (!isMagic(source)) {
                return Optional.empty();
            }

            int klass = source.get(4) & 0xff;
            int data = source.get(5) & 0xff;
            int version = source.get(6) & 0xff;

            if (klass != ELFCLASS32 && klass != ELFCLASS64) {
                return Optional.empty();
            }
            if (data != ELFDATA2LSB && data != ELFDATA2MSB) {
                return Optional.empty();
            }
            if (version != EV_CURRENT) {
                return Optional.empty();
            }

            boolean is64 = klass == ELFCLASS64;
            int headerSize = is64 ? EH_SIZE_64 : EH_SIZE_32;
            int phdrSize = is64 ? PHDR_SIZE_64 : PHDR_SIZE_32;
            int shdrSize = is64 ? SHDR_SIZE_32 : SHDR_SIZE_32;
            if (is64) {
                shdrSize = SHDR_SIZE_64;
            }
            ByteOrder order = data == ELFDATA2LSB ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

            if (sourceSize < headerSize || source.remaining() < headerSize) {
                return Optional.empty();
            }

            ByteBuffer buf = source.duplicate();
            buf.order(order);
            int eType = readHalf(buf, 16);
            int eMachine = readHalf(buf, 18);
            int eVersion = readWord(buf, 20);
            if (eVersion != EV_CURRENT) {
                return Optional.empty();
            }

            long eEntry;
            long ePhoff;
            long eShoff;
            int eFlags;
            int eEhsize;
            int ePhentsize;
            int ePhnum;
            int eShentsize;
            int eShnum;
            int eShstrndx;

            if (is64) {
                eEntry = readXword(buf, 24);
                ePhoff = readXword(buf, 32);
                eShoff = readXword(buf, 40);
                eFlags = readWord(buf, 48);
                eEhsize = readHalf(buf, 52);
                ePhentsize = readHalf(buf, 54);
                ePhnum = readHalf(buf, 56);
                eShentsize = readHalf(buf, 58);
                eShnum = readHalf(buf, 60);
                eShstrndx = readHalf(buf, 62);
            } else {
                eEntry = readWordUnsigned(buf, 24);
                ePhoff = readWordUnsigned(buf, 28);
                eShoff = readWordUnsigned(buf, 32);
                eFlags = readWord(buf, 36);
                eEhsize = readHalf(buf, 40);
                ePhentsize = readHalf(buf, 42);
                ePhnum = readHalf(buf, 44);
                eShentsize = readHalf(buf, 46);
                eShnum = readHalf(buf, 48);
                eShstrndx = readHalf(buf, 50);
            }

            if (eEhsize != headerSize) {
                return Optional.empty();
            }
            if (ePhentsize != phdrSize) {
                return Optional.empty();
            }
            if (eShnum > 0 && eShentsize != shdrSize) {
                return Optional.empty();
            }

            if (ePhnum < 0 || eShnum < 0 || eShstrndx < 0) {
                return Optional.empty();
            }

            // Validate program header table bounds.
            if (ePhnum > 0) {
                if (ePhoff == 0 || ePhoff < headerSize) {
                    return Optional.empty();
                }
                long tableSize = Math.multiplyExact((long) ePhnum, (long) ePhentsize);
                long tableEnd = Math.addExact(ePhoff, tableSize);
                if (tableEnd > sourceSize) {
                    return Optional.empty();
                }
            }

            // Validate section header table bounds.
            if (eShnum > 0) {
                if (eShoff == 0 || eShoff < headerSize) {
                    return Optional.empty();
                }
                long tableSize = Math.multiplyExact((long) eShnum, (long) eShentsize);
                long tableEnd = Math.addExact(eShoff, tableSize);
                if (tableEnd > sourceSize) {
                    return Optional.empty();
                }
            }

            // Validate section string table index.
            if (eShnum > 0) {
                if (eShstrndx == SHN_UNDEF || eShstrndx == SHN_XINDEX || eShstrndx >= eShnum) {
                    return Optional.empty();
                }
            } else if (eShstrndx != SHN_UNDEF) {
                return Optional.empty();
            }

            // Validate source buffer is large enough for header (we already checked above).
            return Optional.of(new ElfHeader(source, sourceSize, is64, order, eType, eMachine, eEntry,
                    ePhoff, eShoff, eFlags, eEhsize, ePhentsize, ePhnum, eShentsize, eShnum, eShstrndx));
        } catch (IllegalArgumentException | ArithmeticException | BufferUnderflowException e) {
            return Optional.empty();
        }
    }

    private static boolean isMagic(@NotNull ByteBuffer source) {
        return source.remaining() >= 4
                && source.get(0) == ELF_MAGIC[0]
                && source.get(1) == ELF_MAGIC[1]
                && source.get(2) == ELF_MAGIC[2]
                && source.get(3) == ELF_MAGIC[3];
    }

    private static int readHalf(@NotNull ByteBuffer buffer, int offset) {
        return buffer.getShort(offset) & 0xffff;
    }

    private static int readWord(@NotNull ByteBuffer buffer, int offset) {
        return buffer.getInt(offset);
    }

    private static long readWordUnsigned(@NotNull ByteBuffer buffer, int offset) {
        return buffer.getInt(offset) & 0xffffffffL;
    }

    private static long readXword(@NotNull ByteBuffer buffer, int offset) {
        long value = buffer.getLong(offset);
        if (value < 0) {
            throw new IllegalArgumentException("Negative 64-bit ELF value");
        }
        return value;
    }

    boolean is64() {
        return is64;
    }

    long sourceSize() {
        return sourceSize;
    }

    @NotNull ByteBuffer source() {
        return source;
    }

    @NotNull ByteOrder order() {
        return order;
    }

    int eType() {
        return eType;
    }

    int eMachine() {
        return eMachine;
    }

    long eEntry() {
        return eEntry;
    }

    int eFlags() {
        return eFlags;
    }

    int ePhnum() {
        return ePhnum;
    }

    int eShnum() {
        return eShnum;
    }

    int eShstrndx() {
        return eShstrndx;
    }

    int ePhentsize() {
        return ePhentsize;
    }

    int eShentsize() {
        return eShentsize;
    }

    long ePhoff() {
        return ePhoff;
    }

    long eShoff() {
        return eShoff;
    }

    int eEhsize() {
        return eEhsize;
    }

    /**
     * Returns a read-only slice of the program header entry at the given index.
     */
    @NotNull ByteBuffer programHeader(int index) {
        if (index < 0 || index >= ePhnum) {
            throw new IllegalArgumentException("Program header index out of bounds: " + index);
        }
        long offset = Math.addExact(ePhoff, Math.multiplyExact((long) index, (long) ePhentsize));
        return source.duplicate()
                .position((int) offset)
                .limit((int) offset + ePhentsize)
                .slice()
                .order(order);
    }

    /**
     * Returns a read-only slice of the section header entry at the given index.
     */
    @NotNull ByteBuffer sectionHeader(int index) {
        if (index < 0 || index >= eShnum) {
            throw new IllegalArgumentException("Section header index out of bounds: " + index);
        }
        long offset = Math.addExact(eShoff, Math.multiplyExact((long) index, (long) eShentsize));
        return source.duplicate()
                .position((int) offset)
                .limit((int) offset + eShentsize)
                .slice()
                .order(order);
    }

    int phdrType(int index) {
        return programHeader(index).getInt(0);
    }

    long phdrOffset(int index) {
        return is64 ? readXword(programHeader(index), 8) : readWordUnsigned(programHeader(index), 4);
    }

    long phdrFileSize(int index) {
        return is64 ? readXword(programHeader(index), 32) : readWordUnsigned(programHeader(index), 16);
    }

    int phdrFlags(int index) {
        return is64 ? readHalf(programHeader(index), 4) : readHalf(programHeader(index), 24);
    }

    long phdrVaddr(int index) {
        return is64 ? readXword(programHeader(index), 16) : readWordUnsigned(programHeader(index), 8);
    }

    long phdrPaddr(int index) {
        return is64 ? readXword(programHeader(index), 24) : readWordUnsigned(programHeader(index), 12);
    }

    long phdrAlign(int index) {
        return is64 ? readXword(programHeader(index), 48) : readWordUnsigned(programHeader(index), 28);
    }

    long shdrName(int index) {
        return readWordUnsigned(sectionHeader(index), 0);
    }

    long shdrType(int index) {
        return readWordUnsigned(sectionHeader(index), 4);
    }

    long shdrFlags(int index) {
        return is64 ? readXword(sectionHeader(index), 8) : readWordUnsigned(sectionHeader(index), 8);
    }

    long shdrAddr(int index) {
        return is64 ? readXword(sectionHeader(index), 16) : readWordUnsigned(sectionHeader(index), 12);
    }

    long shdrOffset(int index) {
        return is64 ? readXword(sectionHeader(index), 24) : readWordUnsigned(sectionHeader(index), 16);
    }

    long shdrSize(int index) {
        return is64 ? readXword(sectionHeader(index), 32) : readWordUnsigned(sectionHeader(index), 20);
    }

    long shdrLink(int index) {
        return readWordUnsigned(sectionHeader(index), is64 ? 40 : 24);
    }

    long shdrInfo(int index) {
        return readWordUnsigned(sectionHeader(index), is64 ? 44 : 28);
    }

    long shdrAddralign(int index) {
        return is64 ? readXword(sectionHeader(index), 48) : readWordUnsigned(sectionHeader(index), 32);
    }

    long shdrEntsize(int index) {
        return is64 ? readXword(sectionHeader(index), 56) : readWordUnsigned(sectionHeader(index), 36);
    }
}
