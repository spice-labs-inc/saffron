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
package io.spicelabs.saffron.container;

import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.dtb.DtbContainer;
import io.spicelabs.saffron.container.elf.ElfContainer;
import io.spicelabs.saffron.container.linuxkernel.LinuxKernelContainerFactory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression test for the 1 MiB bounded-read guarantee on the
 * whole-artifact container readers.
 *
 * <h2>Why this test exists</h2>
 * <p>Goat Rodeo crashed with OOM while mounting container artifacts from
 * virtual disks: {@code ElfContainer}, {@code DtbContainer} (via
 * {@code DeviceTreeBlob}) and {@code LinuxKernelContainerFactory} all read
 * the entire artifact with {@code disk.read(0, size)} during
 * {@code DiskReader.open}/container mount. The requirement (user directive)
 * is: <b>no more than 1 MiB is read into memory at a time</b>, regardless of
 * artifact size.</p>
 *
 * <p>Each test mounts a multi-megabyte synthetic artifact through the public
 * {@code open(VirtualDisk)} API over a {@link RecordingRawDisk} that records
 * the largest single {@code read()} length issued against it, then verifies
 * every read stayed at or under 1 MiB and that the container still exposes
 * correct entry content.</p>
 *
 * <h2>LLM section</h2>
 * <ul>
 *   <li>Requirement: user OOM directive — no single disk read larger than
 *       1 MiB when opening/mounting ELF, DTB, or Linux kernel artifacts.</li>
 *   <li>Tested behavior: {@code maxRead <= 1 MiB} for open + entries() +
 *       full stream of every entry, on artifacts of 3–6 MiB.</li>
 *   <li>Content equality is verified so bounded reads are not achieved by
 *       truncating or corrupting the data.</li>
 *   <li>Boundary: artifacts larger than a single chunk, with entry content
 *       straddling chunk boundaries.</li>
 * </ul>
 */
class BoundedReadTest {

    /** The requirement: no single disk read may exceed 1 MiB. */
    private static final int MAX_READ_CAP = 1 << 20;

    /** Records the largest read issued against the underlying disk. */
    static final class RecordingRawDisk implements VirtualDisk.RawDisk {
        final byte[] content;
        long maxRead;
        long readCount;

        RecordingRawDisk(byte[] content) {
            this.content = content.clone();
        }

        @Override
        public ByteBuffer read(long offset, int length) {
            maxRead = Math.max(maxRead, length);
            readCount++;
            if (offset < 0 || offset >= content.length) {
                return ByteBuffer.allocate(length);
            }
            int available = (int) Math.min(length, content.length - offset);
            byte[] result = new byte[length];
            System.arraycopy(content, (int) offset, result, 0, available);
            return ByteBuffer.wrap(result);
        }

        @Override
        public long virtualSize() {
            return content.length;
        }

        @Override
        public Optional<String> backingFile() {
            return Optional.empty();
        }

        @Override
        public long allocatedSize() {
            return content.length;
        }

        @Override
        public boolean isEncrypted() {
            return false;
        }

        @Override
        public boolean isCompressed() {
            return false;
        }

        @Override
        public DiskFormat format() {
            return DiskFormat.RAW;
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of();
        }

        @Override
        public Stream<VirtualDisk.Snapshot> snapshots() {
            return Stream.empty();
        }

        @Override
        public int sectorSize() {
            return 512;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public PackageURL packageUrl() {
            try {
                return new PackageURL("pkg:vmdisk/raw/test@1.0");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    // ---------------------------------------------------------------- ELF

    /**
     * Builds a minimal ELF64 little-endian image with one loadable segment
     * and one large {@code .data} section of {@code content.length} bytes.
     */
    static byte[] buildElf64(byte[] content) {
        int headerSize = 64;
        int phdrSize = 56;
        int shdrSize = 64;
        String strtab = "\0.data\0.shstrtab\0";
        byte[] strtabBytes = strtab.getBytes();

        int phoff = headerSize;
        int dataOffset = phoff + phdrSize;
        int strtabOffset = dataOffset + content.length;
        int shoff = strtabOffset + strtabBytes.length;
        int totalSize = shoff + 3 * shdrSize;

        byte[] image = new byte[totalSize];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);

        image[0] = 0x7f;
        image[1] = 'E';
        image[2] = 'L';
        image[3] = 'F';
        image[4] = 2;   // ELFCLASS64
        image[5] = 1;   // ELFDATA2LSB
        image[6] = 1;   // EV_CURRENT
        putHalf(buf, 16, 2);   // ET_EXEC
        putHalf(buf, 18, 0x3e); // EM_X86_64
        putWord(buf, 20, 1);   // e_version
        putXword(buf, 24, 0);  // e_entry
        putXword(buf, 32, phoff);
        putXword(buf, 40, shoff);
        putWord(buf, 48, 0);   // e_flags
        putHalf(buf, 52, headerSize);
        putHalf(buf, 54, phdrSize);
        putHalf(buf, 56, 1);   // e_phnum
        putHalf(buf, 58, shdrSize);
        putHalf(buf, 60, 3);   // e_shnum
        putHalf(buf, 62, 2);   // e_shstrndx

        // PT_LOAD segment covering the section data.
        int phdrBase = phoff;
        putWord(buf, phdrBase, 1);         // PT_LOAD
        putWord(buf, phdrBase + 4, 5);     // PF_R | PF_X
        putXword(buf, phdrBase + 8, dataOffset);
        putXword(buf, phdrBase + 16, 0);   // p_vaddr
        putXword(buf, phdrBase + 24, 0);   // p_paddr
        putXword(buf, phdrBase + 32, content.length);
        putXword(buf, phdrBase + 40, content.length);
        putXword(buf, phdrBase + 48, 1);   // p_align

        System.arraycopy(content, 0, image, dataOffset, content.length);
        System.arraycopy(strtabBytes, 0, image, strtabOffset, strtabBytes.length);

        // Section 0: NULL. Section 1: .data. Section 2: .shstrtab.
        putShdr(buf, shoff + shdrSize, 1, 1, dataOffset, content.length); // .data
        putShdr(buf, shoff + 2 * shdrSize, 7, 3, strtabOffset, strtabBytes.length); // .shstrtab

        return image;
    }

    private static void putShdr(ByteBuffer buf, int base, int shName, int shType,
                                int shOffset, int shSize) {
        putWord(buf, base, shName);
        putWord(buf, base + 4, shType);
        putXword(buf, base + 8, 0);    // sh_flags
        putXword(buf, base + 16, 0);   // sh_addr
        putXword(buf, base + 24, shOffset);
        putXword(buf, base + 32, shSize);
        putWord(buf, base + 40, 0);    // sh_link
        putWord(buf, base + 44, 0);    // sh_info
        putXword(buf, base + 48, 1);   // sh_addralign
        putXword(buf, base + 56, 0);   // sh_entsize
    }

    private static void putHalf(ByteBuffer buf, int offset, int value) {
        buf.putShort(offset, (short) value);
    }

    private static void putWord(ByteBuffer buf, int offset, int value) {
        buf.putInt(offset, value);
    }

    private static void putXword(ByteBuffer buf, int offset, long value) {
        buf.putLong(offset, value);
    }

    /**
     * Requirement: opening and enumerating a multi-MB ELF through
     * {@link ElfContainer#open(VirtualDisk)} issues no read larger than
     * 1 MiB, and section content streams back byte-identical.
     */
    @Test
    void elfContainerReadsStayBounded() throws IOException {
        byte[] section = new byte[5 * 1024 * 1024];
        for (int i = 0; i < section.length; i++) {
            section[i] = (byte) (i * 17 + 3);
        }
        byte[] image = buildElf64(section);
        RecordingRawDisk disk = new RecordingRawDisk(image);

        Optional<BinaryContainer> opened = ElfContainer.open(disk);
        assertTrue(opened.isPresent(), "ELF container must open");

        BinaryContainer container = opened.get();
        assertFalse(container.entries().isEmpty(), "ELF must expose entries");

        Optional<ContainerEntry> dataSection = container.findEntry("/sections/.data");
        assertTrue(dataSection.isPresent(), "ELF must expose the .data section entry");
        try (InputStream in = dataSection.get().openStream()) {
            byte[] actual = in.readAllBytes();
            assertTrue(Arrays.equals(section, actual), "section content must match");
        }

        for (ContainerEntry entry : container.entries()) {
            try (InputStream in = entry.openStream()) {
                in.readAllBytes();
            }
        }

        assertTrue(disk.maxRead <= MAX_READ_CAP,
                "max single read was " + disk.maxRead + " bytes");
    }

    // ------------------------------------------------------------- kernel

    /**
     * Builds a U-Boot uImage header (64 bytes, uncompressed payload) followed
     * by a large payload.
     */
    static byte[] buildUImageKernel(byte[] payload) {
        byte[] image = new byte[64 + payload.length];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0, 0x2705_1956);      // uImage magic
        buf.putInt(4, 0);                // header CRC (ignored)
        buf.putInt(8, 0);                // timestamp
        buf.putInt(12, payload.length);  // data size
        buf.putInt(16, 0x4000_8000);     // load address
        buf.putInt(20, 0x4000_8000);     // entry point
        buf.putInt(24, 0);               // data CRC (ignored)
        image[28] = 5;                   // OS: linux
        image[29] = 2;                   // arch: arm
        image[30] = 2;                   // type: kernel
        image[31] = 0;                   // compression: none
        byte[] name = "bounded-test-kernel".getBytes();
        System.arraycopy(name, 0, image, 32, name.length);
        System.arraycopy(payload, 0, image, 64, payload.length);
        return image;
    }

    /**
     * Requirement: opening and enumerating a multi-MB kernel image through
     * {@link LinuxKernelContainerFactory#open(VirtualDisk)} issues no read
     * larger than 1 MiB, and the payload entry streams back byte-identical.
     */
    @Test
    void kernelContainerReadsStayBounded() throws IOException {
        byte[] payload = new byte[4 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 13 + 1);
        }
        byte[] image = buildUImageKernel(payload);
        RecordingRawDisk disk = new RecordingRawDisk(image);

        Optional<BinaryContainer> opened = LinuxKernelContainerFactory.open(disk);
        assertTrue(opened.isPresent(), "kernel container must open");

        BinaryContainer container = opened.get();
        Optional<ContainerEntry> payloadEntry = container.findEntry("/kernel-payload");
        assertTrue(payloadEntry.isPresent(), "kernel must expose /kernel-payload");

        try (InputStream in = payloadEntry.get().openStream()) {
            byte[] actual = in.readAllBytes();
            assertTrue(Arrays.equals(payload, actual), "payload content must match");
        }
        container.entries().forEach(entry -> {
            try (InputStream in = entry.openStream()) {
                in.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(disk.maxRead <= MAX_READ_CAP,
                "max single read was " + disk.maxRead + " bytes");
    }

    // ---------------------------------------------------------------- DTB

    /**
     * Builds a minimal DTB whose root node carries one large property.
     */
    static byte[] buildDtb(byte[] propertyValue) {
        int headerSize = 40;
        int offMemRsvmap = headerSize;
        int memRsvSize = 16; // two zero entries: terminator
        int offDtStruct = offMemRsvmap + memRsvSize;

        // structure: BEGIN_NODE "\0" (8B) + PROP + len + nameoff + value
        //            + END_NODE (4B) + END (4B)
        int beginNodeSize = 8;                       // FDT_BEGIN_NODE + "\0\0\0\0"
        int propHeader = 12;                         // FDT_PROP + len + nameoff
        int endNodeSize = 4;                         // FDT_END_NODE
        int endSize = 4;                             // FDT_END
        int sizeDtStruct = beginNodeSize + propHeader + propertyValue.length
                + endNodeSize + endSize;

        byte[] strings = "\0big-prop\0".getBytes();
        int offDtStrings = offDtStruct + sizeDtStruct;
        int totalsize = offDtStrings + strings.length;

        byte[] dtb = new byte[totalsize];
        ByteBuffer buf = ByteBuffer.wrap(dtb).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0, 0xd00d_feed);      // magic
        buf.putInt(4, totalsize);
        buf.putInt(8, offDtStruct);
        buf.putInt(12, offDtStrings);
        buf.putInt(16, offMemRsvmap);
        buf.putInt(20, 17);              // version
        buf.putInt(24, 16);              // last compatible version
        buf.putInt(28, 0);               // boot_cpuid_phys
        buf.putInt(32, strings.length);
        buf.putInt(36, sizeDtStruct);

        // mem reserve map: terminator only
        // structure block
        int pos = offDtStruct;
        buf.putInt(pos, 1);              // FDT_BEGIN_NODE
        pos += 4;
        buf.putInt(pos, 0);              // node name "\0\0\0\0"
        pos += 4;
        buf.putInt(pos, 3);              // FDT_PROP
        buf.putInt(pos + 4, propertyValue.length);
        buf.putInt(pos + 8, 1);          // nameoff -> "big-prop"
        pos += 12;
        System.arraycopy(propertyValue, 0, dtb, pos, propertyValue.length);
        pos += propertyValue.length;
        buf.putInt(pos, 2);              // FDT_END_NODE
        pos += 4;
        buf.putInt(pos, 9);              // FDT_END

        System.arraycopy(strings, 0, dtb, offDtStrings, strings.length);
        return dtb;
    }

    /**
     * Requirement: opening a multi-MB DTB through
     * {@link DtbContainer#open(VirtualDisk)} issues no read larger than
     * 1 MiB, and both the raw entry and the decoded property stream back
     * byte-identical.
     */
    @Test
    void dtbContainerReadsStayBounded() throws IOException {
        byte[] propertyValue = new byte[4 * 1024 * 1024];
        for (int i = 0; i < propertyValue.length; i++) {
            propertyValue[i] = (byte) (i * 29 + 5);
        }
        byte[] dtb = buildDtb(propertyValue);
        RecordingRawDisk disk = new RecordingRawDisk(dtb);

        Optional<BinaryContainer> opened = DtbContainer.open(disk);
        assertTrue(opened.isPresent(), "DTB container must open");

        BinaryContainer container = opened.get();

        Optional<ContainerEntry> rawEntry = container.findEntry("/dtb");
        assertTrue(rawEntry.isPresent(), "DTB must expose /dtb raw entry");
        try (InputStream in = rawEntry.get().openStream()) {
            byte[] actual = in.readAllBytes();
            assertTrue(Arrays.equals(dtb, actual), "raw DTB content must match");
        }

        Optional<ContainerEntry> propEntry = container.findEntry("/big-prop");
        assertTrue(propEntry.isPresent(), "DTB must expose /big-prop property");
        try (InputStream in = propEntry.get().openStream()) {
            byte[] actual = in.readAllBytes();
            assertTrue(Arrays.equals(propertyValue, actual), "property content must match");
        }

        assertTrue(disk.maxRead <= MAX_READ_CAP,
                "max single read was " + disk.maxRead + " bytes");
    }
}
