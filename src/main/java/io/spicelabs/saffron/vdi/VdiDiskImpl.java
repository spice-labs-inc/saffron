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
package io.spicelabs.saffron.vdi;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.vdi.header.VdiHeader;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Implementation of {@link VirtualDisk.VdiDisk} for VDI format disk images.
 *
 * <p>VDI (Virtual Disk Image) is Oracle VirtualBox's native disk format.
 * It supports dynamic (sparse) and fixed (preallocated) disk types.
 *
 * <p>The format uses a Block Allocation Map (BAM) to track which blocks
 * are allocated and where they are stored in the file.
 */
public final class VdiDiskImpl implements VirtualDisk.VdiDisk {

    private final Path path;
    private final SeekableByteChannel channel;
    private final VdiHeader header;
    private final int[] blockAllocationMap;
    private final long allocatedSize;

    private VdiDiskImpl(Path path, SeekableByteChannel channel, VdiHeader header, int[] blockAllocationMap) {
        this.path = path;
        this.channel = channel;
        this.header = header;
        this.blockAllocationMap = blockAllocationMap;
        this.allocatedSize = computeAllocatedSize();
    }

    /**
     * Opens a VDI disk image file.
     *
     * @param path the path to the VDI file
     * @return the opened VDI disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VdiDiskImpl open(@NotNull Path path) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        boolean success = false;
        try {
            VdiHeader header = VdiHeader.read(channel);
            int[] bam = readBlockAllocationMap(channel, header);
            VdiDiskImpl disk = new VdiDiskImpl(path, channel, header, bam);
            success = true;
            return disk;
        } finally {
            if (!success) {
                channel.close();
            }
        }
    }

    /**
     * Reads the Block Allocation Map from the file.
     */
    private static int[] readBlockAllocationMap(SeekableByteChannel channel, VdiHeader header) throws IOException {
        int numBlocks = header.blocksInHdd();
        int[] bam = new int[numBlocks];

        if (numBlocks == 0) {
            return bam;
        }

        ByteBuffer buffer = ByteBuffer.allocate(numBlocks * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(header.blocksOffset());
        int read = channel.read(buffer);
        if (read < numBlocks * 4) {
            throw new IOException("Failed to read VDI block allocation map: got " + read +
                    " bytes, expected " + (numBlocks * 4));
        }
        buffer.flip();

        for (int i = 0; i < numBlocks; i++) {
            bam[i] = buffer.getInt();
        }

        return bam;
    }

    private long computeAllocatedSize() {
        try {
            return channel.size();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.VDI;
    }

    @Override
    public long virtualSize() {
        return header.diskSize();
    }

    @Override
    public long allocatedSize() {
        return allocatedSize;
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Offset and length must be non-negative");
        }
        if (offset + length > virtualSize()) {
            throw new IllegalArgumentException("Read extends beyond virtual size");
        }

        ByteBuffer result = ByteBuffer.allocate(length);
        int remaining = length;
        long currentOffset = offset;

        while (remaining > 0) {
            int blockIndex = (int) (currentOffset / header.blockSize());
            int offsetInBlock = (int) (currentOffset % header.blockSize());
            int toRead = Math.min(remaining, header.blockSize() - offsetInBlock);

            int blockEntry = blockAllocationMap[blockIndex];

            if (blockEntry == VdiHeader.BLOCK_FREE || blockEntry == VdiHeader.BLOCK_ZERO) {
                // Unallocated or zero block - return zeros
                byte[] zeros = new byte[toRead];
                result.put(zeros);
            } else {
                // Allocated block - read from file
                long blockFileOffset = header.dataOffset() +
                        ((long) blockEntry * (header.blockSize() + header.blockExtraSize())) +
                        header.blockExtraSize() + offsetInBlock;

                ByteBuffer blockData = ByteBuffer.allocate(toRead);
                synchronized (channel) {
                    channel.position(blockFileOffset);
                    int read = channel.read(blockData);
                    if (read < toRead) {
                        // Partial read - pad with zeros
                        while (blockData.position() < toRead) {
                            blockData.put((byte) 0);
                        }
                    }
                }
                blockData.flip();
                result.put(blockData);
            }

            currentOffset += toRead;
            remaining -= toRead;
        }

        result.flip();
        return result;
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return new VdiInputStream(this, 0);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("vdi.version", header.versionString());
        metadata.put("vdi.imageType", header.imageType().typeName());
        metadata.put("vdi.virtualSize", String.valueOf(header.diskSize()));
        metadata.put("vdi.blockSize", String.valueOf(header.blockSize()));
        metadata.put("vdi.blocksInHdd", String.valueOf(header.blocksInHdd()));
        metadata.put("vdi.blocksAllocated", String.valueOf(header.blocksAllocated()));
        metadata.put("vdi.imageUuid", header.imageUuid().toString());

        if (header.comment() != null && !header.comment().isEmpty()) {
            metadata.put("vdi.comment", header.comment());
        }

        if (header.hasParent() && header.parentUuid() != null) {
            metadata.put("vdi.parentUuid", header.parentUuid().toString());
        }

        return Map.copyOf(metadata);
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            String name = path.getFileName().toString();
            // Remove extension
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }

            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("image_type", header.imageType().typeName());
            qualifiers.put("version", header.versionString());

            return new PackageURL(
                    "vmdisk",
                    null,
                    name,
                    header.imageUuid().toString(),
                    qualifiers,
                    null
            );
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException("Failed to create PackageURL", e);
        }
    }

    @Override
    public @NotNull Optional<String> backingFile() {
        // VDI stores parent UUID, not path
        if (header.hasParent() && header.parentUuid() != null) {
            return Optional.of("UUID:" + header.parentUuid().toString());
        }
        return Optional.empty();
    }

    @Override
    public boolean isEncrypted() {
        // VDI encryption is indicated by image flags
        // Bit 1 (0x02) indicates encryption
        return (header.imageFlags() & 0x02) != 0;
    }

    @Override
    public boolean isCompressed() {
        // VDI doesn't support compression in the traditional sense
        return false;
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        // VDI stores snapshot information in the VirtualBox XML, not in the VDI file itself
        return Stream.empty();
    }

    @Override
    public @NotNull String imageType() {
        return header.imageType().typeName();
    }

    @Override
    public int vdiVersion() {
        return header.versionMajor();
    }

    @Override
    public int blockSize() {
        return header.blockSize();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * InputStream implementation for VDI virtual disk contents.
     */
    private static class VdiInputStream extends InputStream {
        private final VdiDiskImpl disk;
        private long position;
        private final long size;

        VdiInputStream(VdiDiskImpl disk, long startPosition) {
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
