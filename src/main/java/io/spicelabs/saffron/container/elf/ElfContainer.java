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

import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.io.ChunkedDisk;
import io.spicelabs.saffron.raw.RawDiskImpl;
import io.spicelabs.saffron.container.BinaryContainer;
import io.spicelabs.saffron.container.ContainerEntry;
import io.spicelabs.saffron.container.ContainerFormat;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An ELF binary exposed as a Saffron binary container.
 *
 * <p>Both program segments and section headers are exposed. Section entries are
 * named after their string-table names; segments are named by index. The source
 * bytes are stored once and entries are zero-copy slices into that array.</p>
 */
public final class ElfContainer implements BinaryContainer {

    private static final int SHT_NULL = 0;
    private static final int SHT_PROGBITS = 1;
    private static final int SHT_STRTAB = 3;
    private static final int SHT_NOBITS = 8;

    private static final int PT_LOAD = 1;

    private final long sourceSize;
    private final byte[] source;
    private final ChunkedDisk disk;
    private final ElfHeader header;
    private final List<ContainerEntry> entries;
    private final Map<String, ContainerEntry> entryByName;

    private ElfContainer(long sourceSize, byte @NotNull [] source, @NotNull ElfHeader header,
                         @NotNull List<ContainerEntry> entries) {
        this.sourceSize = sourceSize;
        this.source = source;
        this.disk = null;
        this.header = header;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
    }

    private ElfContainer(long sourceSize, @NotNull ChunkedDisk disk, @NotNull ElfHeader header,
                         @NotNull List<ContainerEntry> entries) {
        this.sourceSize = sourceSize;
        this.source = null;
        this.disk = disk;
        this.header = header;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.entryByName = this.entries.stream()
                .collect(Collectors.toMap(ContainerEntry::name, e -> e, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Attempts to open an ELF container from a file path.
     *
     * <p>Reads are bounded (see {@link ChunkedDisk}): the file is never
     * loaded into memory as a whole; entries stream from the file on demand.
     * The container owns the file handle and closes it on
     * {@link #close()}.</p>
     *
     * @param path the path to examine
     * @return the container, or empty if the file is not an ELF
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path) throws IOException {
        long size = Files.size(path);
        if (size < 4 || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        ChunkedDisk chunked = new ChunkedDisk(RawDiskImpl.open(path), true);
        try {
            Optional<ElfHeader> headerOpt = ElfHeader.parse(chunked, size);
            if (headerOpt.isEmpty()) {
                chunked.close();
                return Optional.empty();
            }
            ElfHeader header = headerOpt.get();
            List<ContainerEntry> entries = buildEntries(header, chunked);
            return Optional.of(new ElfContainer(size, chunked, header, entries));
        } catch (RuntimeException | Error e) {
            // Defensive: malformed input must not escape as unchecked.
            chunked.close();
            return Optional.empty();
        }
    }

    /**
     * Attempts to open an ELF container from a virtual disk.
     *
     * <p>Reads are bounded (see {@link ChunkedDisk}): the artifact is never
     * loaded into memory as a whole; entries stream from the disk on demand.
     *
     * @param disk the virtual disk to examine
     * @return the container, or empty if the disk is not an ELF
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size < 4 || size > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        ChunkedDisk chunked = new ChunkedDisk(disk);
        try {
            Optional<ElfHeader> headerOpt = ElfHeader.parse(chunked, size);
            if (headerOpt.isEmpty()) {
                return Optional.empty();
            }
            ElfHeader header = headerOpt.get();
            List<ContainerEntry> entries = buildEntries(header, chunked);
            return Optional.of(new ElfContainer(size, chunked, header, entries));
        } catch (RuntimeException | Error e) {
            // Defensive: malformed input must not escape as unchecked
            // (parity with open(Path)).
            return Optional.empty();
        }
    }

    /**
     * Returns true if the buffer begins with a valid ELF header and structural
     * sizes/offsets are consistent with the given source size. This performs only
     * header validation and does not build entries, so it is safe to call with a
     * small header buffer.
     *
     * @param source the bytes to examine; the buffer is not modified
     * @param sourceSize the total size of the ELF source
     * @return true if the source is an ELF
     */
    public static boolean isElf(@NotNull ByteBuffer source, long sourceSize) {
        return ElfHeader.parse(source, sourceSize).isPresent();
    }

    /**
     * Attempts to open an ELF container from a byte buffer.
     *
     * @param source the full ELF bytes; the buffer is not modified
     * @param sourceSize the total size of the ELF source
     * @return the container, or empty if the buffer is not a valid ELF
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull ByteBuffer source, long sourceSize) {
        try {
            Optional<ElfHeader> headerOpt = ElfHeader.parse(source, sourceSize);
            if (headerOpt.isEmpty()) {
                return Optional.empty();
            }
            ElfHeader header = headerOpt.get();
            byte[] sourceBytes = toByteArray(source);
            List<ContainerEntry> entries = buildEntries(header, sourceBytes);
            return Optional.of(new ElfContainer(sourceSize, sourceBytes, header, entries));
        } catch (IOException | RuntimeException | Error e) {
            // Defensive: malformed input must not escape as unchecked.
            return Optional.empty();
        }
    }

    private static byte @NotNull [] toByteArray(@NotNull ByteBuffer source) {
        ByteBuffer dup = source.duplicate();
        if (dup.hasArray() && !dup.isReadOnly()
                && dup.position() == 0
                && dup.remaining() == dup.array().length) {
            return dup.array();
        }
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return bytes;
    }

    private static @NotNull List<ContainerEntry> buildEntries(@NotNull ElfHeader header, byte @NotNull [] source)
            throws IOException {
        List<ContainerEntry> entries = new ArrayList<>();
        buildSectionEntries(header, source, entries);
        buildSegmentEntries(header, source, entries);
        return entries;
    }

    private static @NotNull List<ContainerEntry> buildEntries(@NotNull ElfHeader header,
                                                              @NotNull ChunkedDisk disk)
            throws IOException {
        List<ContainerEntry> entries = new ArrayList<>();
        buildSectionEntries(header, disk, entries);
        buildSegmentEntries(header, disk, entries);
        return entries;
    }

    private static void buildSectionEntries(@NotNull ElfHeader header, @NotNull ChunkedDisk disk,
                                            @NotNull List<ContainerEntry> entries)
            throws IOException {
        int shnum = header.eShnum();
        if (shnum <= 0) {
            return;
        }
        int shstrndx = header.eShstrndx();
        long strtabOffset = header.shdrOffset(shstrndx);
        long strtabSize = header.shdrSize(shstrndx);
        long strtabType = header.shdrType(shstrndx);
        if (strtabType != SHT_STRTAB) {
            throw new IllegalArgumentException("Section string table is not SHT_STRTAB");
        }
        if (!withinBounds(strtabOffset, strtabSize, header.sourceSize())) {
            throw new IllegalArgumentException("Section string table out of bounds");
        }

        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < shnum; i++) {
            long type = header.shdrType(i);
            if (type == SHT_NULL) {
                continue;
            }

            long nameOffset = header.shdrName(i);
            long nameAbs = Math.addExact(strtabOffset, nameOffset);
            if (nameAbs < strtabOffset || nameAbs >= strtabOffset + strtabSize) {
                throw new IllegalArgumentException("Section name offset out of string table");
            }
            String name = readString(disk, nameAbs, strtabOffset + strtabSize);
            if (!isValidSectionName(name)) {
                continue;
            }

            long offset = type == SHT_NOBITS ? 0 : header.shdrOffset(i);
            long size = type == SHT_NOBITS ? 0 : header.shdrSize(i);
            if (type != SHT_NOBITS && !withinBounds(offset, size, header.sourceSize())) {
                throw new IllegalArgumentException("Section data out of bounds");
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", sectionTypeName((int) type));
            meta.put("flags", "0x" + Long.toHexString(header.shdrFlags(i)));
            meta.put("flags_human", sectionFlagsHuman(header.shdrFlags(i)));
            meta.put("addr", "0x" + Long.toHexString(header.shdrAddr(i)));
            meta.put("addralign", Long.toUnsignedString(header.shdrAddralign(i)));
            meta.put("entsize", Long.toUnsignedString(header.shdrEntsize(i)));

            String path = uniqueName("/sections/" + name, usedNames);
            entries.add(new ElfEntry(path, disk, offset, size, meta));
        }
    }

    private static void buildSegmentEntries(@NotNull ElfHeader header, @NotNull ChunkedDisk disk,
                                            @NotNull List<ContainerEntry> entries)
            throws IOException {
        int phnum = header.ePhnum();
        for (int i = 0; i < phnum; i++) {
            long offset = header.phdrOffset(i);
            long filesz = header.phdrFileSize(i);
            if (!withinBounds(offset, filesz, header.sourceSize())) {
                throw new IllegalArgumentException("Segment data out of bounds");
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", segmentTypeName(header.phdrType(i)));
            meta.put("flags", "0x" + Long.toHexString(header.phdrFlags(i)));
            meta.put("flags_human", segmentFlagsHuman(header.phdrFlags(i)));
            meta.put("vaddr", "0x" + Long.toHexString(header.phdrVaddr(i)));
            meta.put("paddr", "0x" + Long.toHexString(header.phdrPaddr(i)));
            meta.put("align", Long.toUnsignedString(header.phdrAlign(i)));

            entries.add(new ElfEntry("/segments/" + i, disk, offset, filesz, meta));
        }
    }

    private static @NotNull String readString(@NotNull ChunkedDisk disk, long start, long limit)
            throws IOException {
        if (start < 0 || start >= limit) {
            throw new IllegalArgumentException("Section name out of bounds");
        }
        StringBuilder sb = new StringBuilder();
        long pos = start;
        while (pos < limit) {
            int b = disk.get(pos++);
            if (b == 0) {
                return sb.toString();
            }
            sb.append((char) b);
        }
        throw new IllegalArgumentException("Section name missing null terminator");
    }

    private static void buildSectionEntries(@NotNull ElfHeader header, byte @NotNull [] source,
                                            @NotNull List<ContainerEntry> entries)
            throws IOException {
        int shnum = header.eShnum();
        if (shnum <= 0) {
            return;
        }
        int shstrndx = header.eShstrndx();
        long strtabOffset = header.shdrOffset(shstrndx);
        long strtabSize = header.shdrSize(shstrndx);
        long strtabType = header.shdrType(shstrndx);
        if (strtabType != SHT_STRTAB) {
            throw new IllegalArgumentException("Section string table is not SHT_STRTAB");
        }
        if (!withinBounds(strtabOffset, strtabSize, header.sourceSize())) {
            throw new IllegalArgumentException("Section string table out of bounds");
        }

        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < shnum; i++) {
            long type = header.shdrType(i);
            if (type == SHT_NULL) {
                continue;
            }

            long nameOffset = header.shdrName(i);
            long nameAbs = Math.addExact(strtabOffset, nameOffset);
            if (nameAbs < strtabOffset || nameAbs >= strtabOffset + strtabSize) {
                throw new IllegalArgumentException("Section name offset out of string table");
            }
            String name = readString(source, (int) nameAbs, (int) (strtabOffset + strtabSize));
            if (!isValidSectionName(name)) {
                continue;
            }

            long offset = type == SHT_NOBITS ? 0 : header.shdrOffset(i);
            long size = type == SHT_NOBITS ? 0 : header.shdrSize(i);
            if (type != SHT_NOBITS && !withinBounds(offset, size, header.sourceSize())) {
                throw new IllegalArgumentException("Section data out of bounds");
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", sectionTypeName((int) type));
            meta.put("flags", "0x" + Long.toHexString(header.shdrFlags(i)));
            meta.put("flags_human", sectionFlagsHuman(header.shdrFlags(i)));
            meta.put("addr", "0x" + Long.toHexString(header.shdrAddr(i)));
            meta.put("addralign", Long.toUnsignedString(header.shdrAddralign(i)));
            meta.put("entsize", Long.toUnsignedString(header.shdrEntsize(i)));

            String path = uniqueName("/sections/" + name, usedNames);
            entries.add(new ElfEntry(path, source, (int) offset, (int) size, meta));
        }
    }

    private static void buildSegmentEntries(@NotNull ElfHeader header, byte @NotNull [] source,
                                            @NotNull List<ContainerEntry> entries)
            throws IOException {
        int phnum = header.ePhnum();
        for (int i = 0; i < phnum; i++) {
            long offset = header.phdrOffset(i);
            long filesz = header.phdrFileSize(i);
            if (!withinBounds(offset, filesz, header.sourceSize())) {
                throw new IllegalArgumentException("Segment data out of bounds");
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("type", segmentTypeName(header.phdrType(i)));
            meta.put("flags", "0x" + Long.toHexString(header.phdrFlags(i)));
            meta.put("flags_human", segmentFlagsHuman(header.phdrFlags(i)));
            meta.put("vaddr", "0x" + Long.toHexString(header.phdrVaddr(i)));
            meta.put("paddr", "0x" + Long.toHexString(header.phdrPaddr(i)));
            meta.put("align", Long.toUnsignedString(header.phdrAlign(i)));

            entries.add(new ElfEntry("/segments/" + i, source, (int) offset, (int) filesz, meta));
        }
    }

    private static @NotNull String readString(byte @NotNull [] source, int start, int limit) {
        if (start < 0 || start >= limit || limit > source.length) {
            throw new IllegalArgumentException("Section name out of bounds");
        }
        int end = start;
        while (end < limit && source[end] != 0) {
            end++;
        }
        if (end >= limit) {
            throw new IllegalArgumentException("Section name missing null terminator");
        }
        return new String(source, start, end - start, StandardCharsets.UTF_8);
    }

    private static boolean isValidSectionName(@NotNull String name) {
        if (name.isEmpty()) {
            return false;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("\0") || name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull String uniqueName(@NotNull String base, @NotNull Set<String> used) {
        if (!used.contains(base)) {
            used.add(base);
            return base;
        }
        int suffix = 1;
        while (true) {
            String candidate = base + "_" + suffix;
            if (!used.contains(candidate)) {
                used.add(candidate);
                return candidate;
            }
            suffix++;
        }
    }

    private static boolean withinBounds(long offset, long length, long limit) {
        if (offset < 0 || length < 0) {
            return false;
        }
        long end = Math.addExact(offset, length);
        return end <= limit;
    }

    private static @NotNull String sectionTypeName(int type) {
        return switch (type) {
            case SHT_NULL -> "NULL";
            case SHT_PROGBITS -> "PROGBITS";
            case 2 -> "SYMTAB";
            case SHT_STRTAB -> "STRTAB";
            case 4 -> "RELA";
            case 5 -> "HASH";
            case 6 -> "DYNAMIC";
            case 7 -> "NOTE";
            case SHT_NOBITS -> "NOBITS";
            case 9 -> "REL";
            case 10 -> "SHLIB";
            case 11 -> "DYNSYM";
            default -> "0x" + Integer.toHexString(type);
        };
    }

    private static @NotNull String sectionFlagsHuman(long flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & 0x2) != 0) sb.append('A');
        if ((flags & 0x1) != 0) sb.append('W');
        if ((flags & 0x4) != 0) sb.append('X');
        return sb.toString();
    }

    private static @NotNull String segmentTypeName(int type) {
        return switch (type) {
            case 0 -> "PT_NULL";
            case PT_LOAD -> "PT_LOAD";
            case 2 -> "PT_DYNAMIC";
            case 3 -> "PT_INTERP";
            case 4 -> "PT_NOTE";
            case 5 -> "PT_SHLIB";
            case 6 -> "PT_PHDR";
            default -> "0x" + Integer.toHexString(type);
        };
    }

    private static @NotNull String segmentFlagsHuman(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & 0x4) != 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('R');
        }
        if ((flags & 0x2) != 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('W');
        }
        if ((flags & 0x1) != 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('X');
        }
        return sb.toString();
    }

    private static @NotNull String elfTypeName(int type) {
        return switch (type) {
            case 0 -> "ET_NONE";
            case 1 -> "ET_REL";
            case 2 -> "ET_EXEC";
            case 3 -> "ET_DYN";
            case 4 -> "ET_CORE";
            default -> "0x" + Integer.toHexString(type);
        };
    }

    private static @NotNull String elfMachineName(int machine) {
        return switch (machine) {
            case 0x00 -> "EM_NONE";
            case 0x02 -> "EM_SPARC";
            case 0x03 -> "EM_386";
            case 0x04 -> "EM_68K";
            case 0x05 -> "EM_88K";
            case 0x07 -> "EM_860";
            case 0x08 -> "EM_MIPS";
            case 0x0f -> "EM_HPPA";
            case 0x14 -> "EM_PPC";
            case 0x15 -> "EM_PPC64";
            case 0x16 -> "EM_S390";
            case 0x28 -> "EM_ARM";
            case 0x2a -> "EM_SUPERH";
            case 0x2b -> "EM_SPARCV9";
            case 0x2c -> "EM_TRICORE";
            case 0x2d -> "EM_ARC";
            case 0x2e -> "EM_H8_300";
            case 0x2f -> "EM_H8_300H";
            case 0x30 -> "EM_H8S";
            case 0x31 -> "EM_H8_500";
            case 0x32 -> "EM_IA_64";
            case 0x3e -> "EM_X86_64";
            case 0x8c -> "EM_COLDFIRE";
            case 0x8d -> "EM_68HC12";
            case 0x8e -> "EM_MMA";
            case 0xb7 -> "EM_AARCH64";
            case 0x14d -> "EM_XTENSA";
            default -> "0x" + Integer.toHexString(machine);
        };
    }

    @Override
    public @NotNull ContainerFormat format() {
        return ContainerFormat.ELF;
    }

    @Override
    public @NotNull List<ContainerEntry> entries() {
        return entries;
    }

    @Override
    public @NotNull Optional<ContainerEntry> findEntry(@NotNull String path) {
        return Optional.ofNullable(entryByName.get(path));
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("format", ContainerFormat.ELF.getName());
        meta.put("type", elfTypeName(header.eType()));
        meta.put("machine", elfMachineName(header.eMachine()));
        meta.put("entry", "0x" + Long.toHexString(header.eEntry()));
        meta.put("source_size", Long.toString(sourceSize));
        meta.put("entry_count", Integer.toString(entries.size()));
        return Collections.unmodifiableMap(meta);
    }

    @Override
    public long size() {
        return sourceSize;
    }

    /**
     * Releases the backing source when this container opened it itself
     * (path-based opens). Containers created over a caller-provided
     * {@link VirtualDisk} leave the caller's disk untouched.
     */
    @Override
    public void close() throws IOException {
        if (disk != null) {
            disk.close();
        }
    }
}
