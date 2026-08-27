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

import io.spicelabs.saffron.io.ChunkedDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Optional;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses and validates an ELF executable header.
 *
 * <p>Parse-once architecture: both the in-memory ({@code ByteBuffer}) and
 * the bounded-disk ({@code ChunkedDisk}) entry points adapt to a single
 * parser driven by a {@code readAt(offset, length)} slice fetcher. The
 * ELF header fields AND every program/section header entry are fetched
 * once at parse time; accessors read from the stored copies only. There
 * is exactly one parsing code path per field — no duplicated
 * buffer/disk accessor sets (which previously shipped the disk path
 * broken).</p>
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

    /** Slice fetcher: both backings adapt to this single parser. */
    @FunctionalInterface
    private interface SliceFetcher {
        byte @NotNull [] readAt(long offset, int length) throws IOException;
    }

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
    private final @NotNull SliceFetcher fetch;
    private volatile byte[] @NotNull [] phdrs; // ePhnum entries, fetched on first use
    private volatile byte[] @NotNull [] shdrs; // eShnum entries, fetched on first use

    private ElfHeader(@NotNull ByteBuffer source, long sourceSize, boolean is64,
                      @NotNull ByteOrder order, int eType, int eMachine, long eEntry,
                      long ePhoff, long eShoff, int eFlags, int eEhsize, int ePhentsize,
                      int ePhnum, int eShentsize, int eShnum, int eShstrndx,
                      @NotNull SliceFetcher fetch) {
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
        this.fetch = fetch;
        this.phdrs = new byte[0][];
        this.shdrs = new byte[0][];
    }

    static @NotNull Optional<ElfHeader> parse(@NotNull ByteBuffer source, long sourceSize) {
        try {
            if (sourceSize < 5 || source.remaining() < 5) {
                return Optional.empty();
            }
            byte[] ident = new byte[16];
            if (source.remaining() < 16) {
                return Optional.empty();
            }
            source.get(0, ident); // absolute get: no position mutation
            if (ident[0] != ELF_MAGIC[0] || ident[1] != ELF_MAGIC[1]
                    || ident[2] != ELF_MAGIC[2] || ident[3] != ELF_MAGIC[3]) {
                return Optional.empty();
            }
            return parseFields((offset, length) -> {
                if (offset < 0 || length < 0 || offset + length > source.remaining()) {
                    throw new IOException("ELF read out of buffer bounds");
                }
                byte[] out = new byte[length];
                source.get((int) offset, out);
                return out;
            }, source, sourceSize, ident);
        } catch (IllegalArgumentException | ArithmeticException | IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Parses an ELF header through a chunked disk reader (bounded reads).
     */
    static @NotNull Optional<ElfHeader> parse(@NotNull ChunkedDisk disk, long sourceSize)
            throws IOException {
        try {
            if (sourceSize < 5) {
                return Optional.empty();
            }
            byte[] ident = disk.copyRange(0, 16);
            if (ident[0] != ELF_MAGIC[0] || ident[1] != ELF_MAGIC[1]
                    || ident[2] != ELF_MAGIC[2] || ident[3] != ELF_MAGIC[3]) {
                return Optional.empty();
            }
            return parseFields(disk::copyRange, ByteBuffer.allocate(0), sourceSize, ident);
        } catch (IndexOutOfBoundsException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    /**
     * The single parser: validates the header and fetches every program
     * and section header entry exactly once through the slice fetcher.
     */
    private static @NotNull Optional<ElfHeader> parseFields(@NotNull SliceFetcher fetch,
                                                            @NotNull ByteBuffer source,
                                                            long sourceSize, byte[] ident)
            throws IOException {
        int klass = ident[4] & 0xff;
        int data = ident[5] & 0xff;
        int version = ident[6] & 0xff;

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
        int shdrSize = is64 ? SHDR_SIZE_64 : SHDR_SIZE_32;
        ByteOrder order = data == ELFDATA2LSB ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

        if (sourceSize < headerSize) {
            return Optional.empty();
        }

        byte[] header = fetch.readAt(0, headerSize);
        ByteBuffer buf = ByteBuffer.wrap(header).order(order);
        int eType = buf.getShort(16) & 0xffff;
        int eMachine = buf.getShort(18) & 0xffff;
        int eVersion = buf.getInt(20);
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
            eEntry = buf.getLong(24);
            ePhoff = buf.getLong(32);
            eShoff = buf.getLong(40);
            eFlags = buf.getInt(48);
            eEhsize = buf.getShort(52) & 0xffff;
            ePhentsize = buf.getShort(54) & 0xffff;
            ePhnum = buf.getShort(56) & 0xffff;
            eShentsize = buf.getShort(58) & 0xffff;
            eShnum = buf.getShort(60) & 0xffff;
            eShstrndx = buf.getShort(62) & 0xffff;
            if (eEntry < 0 || ePhoff < 0 || eShoff < 0) {
                return Optional.empty();
            }
        } else {
            eEntry = buf.getInt(24) & 0xffffffffL;
            ePhoff = buf.getInt(28) & 0xffffffffL;
            eShoff = buf.getInt(32) & 0xffffffffL;
            eFlags = buf.getInt(36);
            eEhsize = buf.getShort(40) & 0xffff;
            ePhentsize = buf.getShort(42) & 0xffff;
            ePhnum = buf.getShort(44) & 0xffff;
            eShentsize = buf.getShort(46) & 0xffff;
            eShnum = buf.getShort(48) & 0xffff;
            eShstrndx = buf.getShort(50) & 0xffff;
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

        // Validate program header table bounds.
        if (ePhnum > 0) {
            if (ePhoff == 0 || ePhoff < headerSize) {
                return Optional.empty();
            }
            try {
                long tableSize = Math.multiplyExact((long) ePhnum, (long) ePhentsize);
                if (Math.addExact(ePhoff, tableSize) > sourceSize) {
                    return Optional.empty();
                }
            } catch (ArithmeticException e) {
                return Optional.empty();
            }
        }

        // Validate section header table bounds.
        if (eShnum > 0) {
            if (eShoff == 0 || eShoff < headerSize) {
                return Optional.empty();
            }
            try {
                long tableSize = Math.multiplyExact((long) eShnum, (long) eShentsize);
                if (Math.addExact(eShoff, tableSize) > sourceSize) {
                    return Optional.empty();
                }
            } catch (ArithmeticException e) {
                return Optional.empty();
            }
            if (eShstrndx == SHN_UNDEF || eShstrndx == SHN_XINDEX || eShstrndx >= eShnum) {
                return Optional.empty();
            }
        } else if (eShstrndx != SHN_UNDEF) {
            return Optional.empty();
        }

        return Optional.of(new ElfHeader(source, sourceSize, is64, order, eType, eMachine,
                eEntry, ePhoff, eShoff, eFlags, eEhsize, ePhentsize, ePhnum, eShentsize,
                eShnum, eShstrndx, fetch));
    }

    private static long xword(ByteBuffer buf) {
        long value = buf.getLong();
        if (value < 0) {
            throw new IllegalArgumentException("Negative 64-bit ELF value");
        }
        return value;
    }

    private static long wordUnsigned(ByteBuffer buf) {
        return buf.getInt() & 0xffffffffL;
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
     * Fetches entry bytes once, lazily, on first accessor use — the
     * detection path validates with a small header buffer and must not
     * read entry tables.
     */
    private void ensureEntriesFetched() {
        byte[][] p = phdrs;
        if (p.length != ePhnum) {
            synchronized (this) {
                if (phdrs.length != ePhnum) {
                    try {
                        byte[][] fetchedP = new byte[ePhnum][];
                        for (int i = 0; i < ePhnum; i++) {
                            fetchedP[i] = fetch.readAt(Math.addExact(ePhoff,
                                    Math.multiplyExact((long) i, (long) ePhentsize)), ePhentsize);
                        }
                        byte[][] fetchedS = new byte[eShnum][];
                        for (int i = 0; i < eShnum; i++) {
                            fetchedS[i] = fetch.readAt(Math.addExact(eShoff,
                                    Math.multiplyExact((long) i, (long) eShentsize)), eShentsize);
                        }
                        phdrs = fetchedP;
                        shdrs = fetchedS;
                    } catch (IOException | ArithmeticException e) {
                        throw new IllegalStateException("ELF entry tables unreadable", e);
                    }
                }
            }
        }
    }

    private @NotNull ByteBuffer phdrBuffer(int index) {
        ensureEntriesFetched();
        return ByteBuffer.wrap(phdrs[index]).order(order);
    }

    private @NotNull ByteBuffer shdrBuffer(int index) {
        ensureEntriesFetched();
        return ByteBuffer.wrap(shdrs[index]).order(order);
    }

    int phdrType(int index) {
        return phdrBuffer(index).getInt(0);
    }

    long phdrOffset(int index) {
        return is64 ? xword(phdrBuffer(index).position(8)) : wordUnsigned(phdrBuffer(index).position(4));
    }

    long phdrFileSize(int index) {
        return is64 ? xword(phdrBuffer(index).position(32)) : wordUnsigned(phdrBuffer(index).position(16));
    }

    int phdrFlags(int index) {
        return phdrBuffer(index).getShort(is64 ? 4 : 24) & 0xffff;
    }

    long phdrVaddr(int index) {
        return is64 ? xword(phdrBuffer(index).position(16)) : wordUnsigned(phdrBuffer(index).position(8));
    }

    long phdrPaddr(int index) {
        return is64 ? xword(phdrBuffer(index).position(24)) : wordUnsigned(phdrBuffer(index).position(12));
    }

    long phdrAlign(int index) {
        return is64 ? xword(phdrBuffer(index).position(48)) : wordUnsigned(phdrBuffer(index).position(28));
    }

    long shdrName(int index) {
        return wordUnsigned(shdrBuffer(index).position(0));
    }

    long shdrType(int index) {
        return wordUnsigned(shdrBuffer(index).position(4));
    }

    long shdrFlags(int index) {
        return is64 ? xword(shdrBuffer(index).position(8)) : wordUnsigned(shdrBuffer(index).position(8));
    }

    long shdrAddr(int index) {
        return is64 ? xword(shdrBuffer(index).position(16)) : wordUnsigned(shdrBuffer(index).position(12));
    }

    long shdrOffset(int index) {
        return is64 ? xword(shdrBuffer(index).position(24)) : wordUnsigned(shdrBuffer(index).position(16));
    }

    long shdrSize(int index) {
        return is64 ? xword(shdrBuffer(index).position(32)) : wordUnsigned(shdrBuffer(index).position(20));
    }

    long shdrLink(int index) {
        return wordUnsigned(shdrBuffer(index).position(is64 ? 40 : 24));
    }

    long shdrInfo(int index) {
        return wordUnsigned(shdrBuffer(index).position(is64 ? 44 : 28));
    }

    long shdrAddralign(int index) {
        return is64 ? xword(shdrBuffer(index).position(48)) : wordUnsigned(shdrBuffer(index).position(32));
    }

    long shdrEntsize(int index) {
        return is64 ? xword(shdrBuffer(index).position(56)) : wordUnsigned(shdrBuffer(index).position(36));
    }
}
