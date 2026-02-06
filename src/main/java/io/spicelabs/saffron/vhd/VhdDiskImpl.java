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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.vhd;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.vhd.dynamic.VhdDynamicHeader;
import io.spicelabs.saffron.vhd.footer.VhdFooter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Implementation of {@link VirtualDisk.VhdDisk} for VHD format disk images.
 *
 * <p>This class handles all three VHD disk types:
 * <ul>
 *   <li><b>Fixed</b>: Data stored contiguously, simple offset mapping</li>
 *   <li><b>Dynamic</b>: Sparse allocation using Block Allocation Table (BAT)</li>
 *   <li><b>Differencing</b>: Changes stored relative to parent (not yet fully supported)</li>
 * </ul>
 */
public final class VhdDiskImpl implements VirtualDisk.VhdDisk {

    private final Path path;
    private final SeekableByteChannel channel;
    private final VhdFooter footer;
    private final @Nullable VhdDynamicHeader dynamicHeader;
    private final int @Nullable [] bat; // Block Allocation Table
    private final long allocatedSize;

    /**
     * Opens a VHD disk image from a file path.
     *
     * @param path the path to the VHD file
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VhdDiskImpl open(@NotNull Path path) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path);
        try {
            // Read footer from end of file
            VhdFooter footer = VhdFooter.read(channel);

            VhdDynamicHeader dynamicHeader = null;
            int[] bat = null;

            // For dynamic/differencing disks, read the dynamic header and BAT
            if (!footer.isFixed()) {
                // Dynamic header follows the copy of footer at the beginning
                dynamicHeader = VhdDynamicHeader.read(channel, VhdFooter.FOOTER_SIZE);

                // Read Block Allocation Table
                bat = readBat(channel, dynamicHeader);
            }

            long allocatedSize = Files.size(path);

            return new VhdDiskImpl(path, channel, footer, dynamicHeader, bat, allocatedSize);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    private VhdDiskImpl(Path path, SeekableByteChannel channel, VhdFooter footer,
                        @Nullable VhdDynamicHeader dynamicHeader, int @Nullable [] bat,
                        long allocatedSize) {
        this.path = path;
        this.channel = channel;
        this.footer = footer;
        this.dynamicHeader = dynamicHeader;
        this.bat = bat;
        this.allocatedSize = allocatedSize;
    }

    private static int[] readBat(SeekableByteChannel channel, VhdDynamicHeader header)
            throws IOException {
        int entries = header.maxTableEntries();
        int[] bat = new int[entries];

        ByteBuffer buffer = ByteBuffer.allocate(entries * 4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        channel.position(header.tableOffset());
        int read = channel.read(buffer);
        if (read < entries * 4) {
            throw new IOException("Failed to read VHD BAT: got " + read + " bytes");
        }
        buffer.flip();

        for (int i = 0; i < entries; i++) {
            bat[i] = buffer.getInt();
        }

        return bat;
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.VHD;
    }

    @Override
    public long virtualSize() {
        return footer.virtualSize();
    }

    @Override
    public long allocatedSize() {
        return allocatedSize;
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        if (offset < 0 || offset >= virtualSize()) {
            throw new IllegalArgumentException("Offset out of range: " + offset);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive: " + length);
        }

        // Clamp to virtual size
        long maxLength = virtualSize() - offset;
        if (length > maxLength) {
            length = (int) maxLength;
        }

        ByteBuffer result = ByteBuffer.allocate(length);

        if (footer.isFixed()) {
            // Fixed disk: direct mapping (data starts after header copy for some files,
            // but the VHD spec says fixed disks have data starting at offset 0)
            readFromChannel(offset, result, length);
        } else {
            // Dynamic/differencing: use BAT
            readDynamic(offset, result, length);
        }

        result.flip();
        return result;
    }

    private void readDynamic(long virtualOffset, ByteBuffer dest, int length) throws IOException {
        if (dynamicHeader == null || bat == null) {
            throw new IllegalStateException("Dynamic header not loaded");
        }

        int blockSize = dynamicHeader.blockSize();
        int bitmapSize = dynamicHeader.blockBitmapSize();

        while (dest.position() < length) {
            long currentOffset = virtualOffset + dest.position();
            int blockIndex = (int) (currentOffset / blockSize);
            int offsetInBlock = (int) (currentOffset % blockSize);
            int bytesToRead = Math.min(length - dest.position(), blockSize - offsetInBlock);

            if (blockIndex >= bat.length) {
                // Beyond BAT - return zeros
                byte[] zeros = new byte[bytesToRead];
                dest.put(zeros);
            } else {
                int batEntry = bat[blockIndex];
                if (batEntry == VhdDynamicHeader.BAT_ENTRY_UNUSED) {
                    // Unallocated block - return zeros
                    byte[] zeros = new byte[bytesToRead];
                    dest.put(zeros);
                } else {
                    // Allocated block - read from file
                    // Physical offset = BAT entry (in sectors) * 512 + bitmap size + offset in block
                    long physicalOffset = (long) batEntry * 512 + bitmapSize + offsetInBlock;
                    readFromChannel(physicalOffset, dest, bytesToRead);
                }
            }
        }
    }

    private void readFromChannel(long position, ByteBuffer dest, int length) throws IOException {
        ByteBuffer temp = ByteBuffer.allocate(length);
        channel.position(position);

        int totalRead = 0;
        while (totalRead < length) {
            int read = channel.read(temp);
            if (read < 0) {
                break;
            }
            totalRead += read;
        }

        temp.flip();
        dest.put(temp);
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return new VhdInputStream(this, 0);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("vhd.diskType", footer.diskType().name().toLowerCase());
        meta.put("vhd.creatorApplication", footer.creatorApplication());
        meta.put("vhd.creatorVersion",
                String.format("%d.%d", footer.creatorVersion() >> 16, footer.creatorVersion() & 0xFFFF));
        meta.put("vhd.creatorHostOs", footer.creatorHostOs());
        meta.put("vhd.virtualSize", String.valueOf(footer.virtualSize()));
        meta.put("vhd.uniqueId", footer.uniqueId().toString());

        if (dynamicHeader != null) {
            meta.put("vhd.blockSize", String.valueOf(dynamicHeader.blockSize()));
            meta.put("vhd.maxTableEntries", String.valueOf(dynamicHeader.maxTableEntries()));
        }

        return Map.copyOf(meta);
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("disk_type", footer.diskType().name().toLowerCase());
            qualifiers.put("creator", footer.creatorApplication().trim());

            String name = path.getFileName().toString();
            if (name.endsWith(".vhd")) {
                name = name.substring(0, name.length() - 4);
            }

            return new PackageURL(
                    PackageURL.StandardTypes.GENERIC,
                    "vmdisk",
                    name,
                    "1.0",
                    qualifiers,
                    null
            );
        } catch (MalformedPackageURLException e) {
            throw new RuntimeException("Failed to create PackageURL", e);
        }
    }

    @Override
    public @NotNull Optional<String> backingFile() {
        if (dynamicHeader != null && dynamicHeader.parentUnicodeName() != null) {
            return Optional.of(dynamicHeader.parentUnicodeName());
        }
        return Optional.empty();
    }

    @Override
    public boolean isEncrypted() {
        return false; // VHD doesn't support encryption
    }

    @Override
    public boolean isCompressed() {
        return false; // VHD doesn't support compression
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        return Stream.empty(); // VHD doesn't support snapshots
    }

    @Override
    public @NotNull String diskType() {
        return footer.diskType().name().toLowerCase();
    }

    @Override
    public @NotNull String uniqueId() {
        return footer.uniqueId().toString();
    }

    @Override
    public @NotNull String creatorApplication() {
        return footer.creatorApplication();
    }

    /**
     * Returns the parsed VHD footer.
     */
    public @NotNull VhdFooter getFooter() {
        return footer;
    }

    /**
     * Returns the dynamic header if this is a dynamic/differencing disk.
     */
    public @NotNull Optional<VhdDynamicHeader> getDynamicHeader() {
        return Optional.ofNullable(dynamicHeader);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * InputStream implementation for VHD virtual disk contents.
     */
    private static class VhdInputStream extends InputStream {
        private final VhdDiskImpl disk;
        private long position;
        private final long size;

        VhdInputStream(VhdDiskImpl disk, long startPosition) {
            this.disk = disk;
            this.position = startPosition;
            this.size = disk.virtualSize();
        }

        @Override
        public int read() throws IOException {
            if (position >= size) {
                return -1;
            }
            ByteBuffer buf = disk.read(position, 1);
            position++;
            return buf.get() & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= size) {
                return -1;
            }

            int toRead = (int) Math.min(len, size - position);
            ByteBuffer buf = disk.read(position, toRead);
            int read = buf.remaining();
            buf.get(b, off, read);
            position += read;
            return read;
        }

        @Override
        public long skip(long n) {
            if (n <= 0) {
                return 0;
            }
            long toSkip = Math.min(n, size - position);
            position += toSkip;
            return toSkip;
        }

        @Override
        public int available() {
            return (int) Math.min(size - position, Integer.MAX_VALUE);
        }
    }
}
