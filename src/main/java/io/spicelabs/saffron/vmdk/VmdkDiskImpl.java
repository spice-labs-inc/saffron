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
package io.spicelabs.saffron.vmdk;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.vmdk.descriptor.VmdkDescriptor;
import io.spicelabs.saffron.vmdk.sparse.SparseExtentHeader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Implementation of {@link VirtualDisk.VmdkDisk} for VMDK format disk images.
 *
 * <p>This implementation supports:
 * <ul>
 *   <li>Monolithic sparse VMDK (single file, sparse allocation)</li>
 *   <li>Stream-optimized VMDK (used in OVA/OVF exports)</li>
 *   <li>Hosted sparse VMDK with embedded descriptor</li>
 * </ul>
 *
 * <p>Note: Split VMDKs and flat VMDKs are not yet fully supported.
 */
public final class VmdkDiskImpl implements VirtualDisk.VmdkDisk {

    private final Path path;
    private final SeekableByteChannel channel;
    private final SparseExtentHeader header;
    private final @Nullable VmdkDescriptor descriptor;
    private final int[] grainDirectory;
    private final long allocatedSize;

    private VmdkDiskImpl(Path path, SeekableByteChannel channel,
                         SparseExtentHeader header, @Nullable VmdkDescriptor descriptor,
                         int[] grainDirectory, long allocatedSize) {
        this.path = path;
        this.channel = channel;
        this.header = header;
        this.descriptor = descriptor;
        this.grainDirectory = grainDirectory;
        this.allocatedSize = allocatedSize;
    }

    /**
     * Opens a VMDK disk image file.
     *
     * @param path the path to the VMDK file
     * @return the opened VMDK disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VmdkDiskImpl open(@NotNull Path path) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        boolean success = false;
        try {
            SparseExtentHeader header = SparseExtentHeader.read(channel);

            // Read embedded descriptor if present
            VmdkDescriptor descriptor = null;
            if (header.hasEmbeddedDescriptor()) {
                descriptor = VmdkDescriptor.read(channel,
                        header.descriptorOffsetBytes(),
                        header.descriptorSizeBytes());
            }

            // Read grain directory
            int[] grainDirectory = readGrainDirectory(channel, header);

            long allocatedSize = Files.size(path);

            VmdkDiskImpl disk = new VmdkDiskImpl(path, channel, header, descriptor,
                    grainDirectory, allocatedSize);
            success = true;
            return disk;
        } finally {
            if (!success) {
                channel.close();
            }
        }
    }

    /**
     * Reads the grain directory from the file.
     */
    private static int[] readGrainDirectory(SeekableByteChannel channel,
                                             SparseExtentHeader header) throws IOException {
        // For stream-optimized VMDKs, we may not have a grain directory at a fixed offset
        if (header.isStreamOptimized() && header.gdOffset() == 0) {
            return new int[0];
        }

        // Stream-optimized VMDKs with gdOffset == -1 store the real GD offset in a footer
        // at the end of the file (last 2 sectors: footer marker + footer header copy)
        SparseExtentHeader effectiveHeader = header;
        if (header.gdOffset() < 0) {
            long fileSize = channel.size();
            if (fileSize > SparseExtentHeader.HEADER_SIZE * 2) {
                try {
                    SparseExtentHeader footer = SparseExtentHeader.read(channel,
                            fileSize - SparseExtentHeader.HEADER_SIZE * 2);
                    if (footer.gdOffset() > 0) {
                        effectiveHeader = footer;
                    } else {
                        // Try last sector
                        footer = SparseExtentHeader.read(channel,
                                fileSize - SparseExtentHeader.HEADER_SIZE);
                        if (footer.gdOffset() > 0) {
                            effectiveHeader = footer;
                        } else {
                            return new int[0];
                        }
                    }
                } catch (Exception e) {
                    return new int[0];
                }
            } else {
                return new int[0];
            }
        }

        // Calculate number of grain directory entries
        int grainSizeBytes = effectiveHeader.grainSizeBytes();
        if (grainSizeBytes == 0) {
            return new int[0];
        }

        long capacity = effectiveHeader.capacity() * SparseExtentHeader.SECTOR_SIZE;
        long grainsPerGT = effectiveHeader.numGTEsPerGT();
        if (grainsPerGT == 0) {
            grainsPerGT = 512; // Default
        }

        long totalGrains = (capacity + grainSizeBytes - 1) / grainSizeBytes;
        int numGDEntries = (int) ((totalGrains + grainsPerGT - 1) / grainsPerGT);

        // Always use the primary grain directory (GD). The RGD is a backup copy
        // for recovery; the GD is the authoritative copy for reading.
        long gdSectorOffset = effectiveHeader.gdOffset();

        if (numGDEntries == 0 || gdSectorOffset <= 0) {
            return new int[0];
        }

        // Limit grain directory size for safety
        if (numGDEntries > 1_000_000) {
            numGDEntries = 1_000_000;
        }

        int[] gd = new int[numGDEntries];
        ByteBuffer buffer = ByteBuffer.allocate(numGDEntries * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.position(gdSectorOffset * SparseExtentHeader.SECTOR_SIZE);
        int read = channel.read(buffer);
        if (read < numGDEntries * 4) {
            // Partial read is okay for some VMDKs
            buffer.flip();
            int entries = read / 4;
            for (int i = 0; i < entries; i++) {
                gd[i] = buffer.getInt();
            }
            return gd;
        }
        buffer.flip();

        for (int i = 0; i < numGDEntries; i++) {
            gd[i] = buffer.getInt();
        }

        return gd;
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.VMDK;
    }

    @Override
    public long virtualSize() {
        return header.virtualSizeBytes();
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

        int grainSizeBytes = header.grainSizeBytes();
        if (grainSizeBytes == 0) {
            grainSizeBytes = 65536; // Default 128 sectors
        }

        while (remaining > 0) {
            int grainIndex = (int) (currentOffset / grainSizeBytes);
            int offsetInGrain = (int) (currentOffset % grainSizeBytes);
            int toRead = Math.min(remaining, grainSizeBytes - offsetInGrain);

            byte[] grainData = readGrain(grainIndex, offsetInGrain, toRead);
            result.put(grainData);

            currentOffset += toRead;
            remaining -= toRead;
        }

        result.flip();
        return result;
    }

    /**
     * Reads data from a specific grain.
     */
    private byte[] readGrain(int grainIndex, int offsetInGrain, int length) throws IOException {
        int grainSizeBytes = header.grainSizeBytes();
        int grainsPerGT = header.numGTEsPerGT();
        if (grainsPerGT == 0) {
            grainsPerGT = 512;
        }

        // Find grain table index and entry
        int gdIndex = grainIndex / grainsPerGT;
        int gtIndex = grainIndex % grainsPerGT;

        // Check if grain directory entry exists and is valid
        if (gdIndex >= grainDirectory.length || grainDirectory[gdIndex] == 0) {
            // Unallocated - return zeros
            return new byte[length];
        }

        // Read grain table entry (sector offsets are unsigned 32-bit)
        long gtOffset = Integer.toUnsignedLong(grainDirectory[gdIndex]) * SparseExtentHeader.SECTOR_SIZE;
        ByteBuffer gtBuffer = ByteBuffer.allocate(4);
        gtBuffer.order(ByteOrder.LITTLE_ENDIAN);

        synchronized (channel) {
            channel.position(gtOffset + gtIndex * 4L);
            channel.read(gtBuffer);
        }
        gtBuffer.flip();
        long grainOffset = Integer.toUnsignedLong(gtBuffer.getInt());

        if (grainOffset == 0) {
            // Unallocated grain - return zeros
            return new byte[length];
        }

        // Read grain data
        long grainDataOffset = grainOffset * SparseExtentHeader.SECTOR_SIZE;

        if (header.isCompressed()) {
            return readCompressedGrain(grainDataOffset, offsetInGrain, length, grainSizeBytes);
        } else {
            return readUncompressedGrain(grainDataOffset + offsetInGrain, length);
        }
    }

    /**
     * Reads uncompressed grain data.
     */
    private byte[] readUncompressedGrain(long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);

        synchronized (channel) {
            channel.position(offset);
            int read = channel.read(buffer);
            if (read < length) {
                // Pad with zeros
                while (buffer.position() < length) {
                    buffer.put((byte) 0);
                }
            }
        }

        buffer.flip();
        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }

    /**
     * Reads compressed grain data.
     */
    private byte[] readCompressedGrain(long grainOffset, int offsetInGrain,
                                        int length, int grainSize) throws IOException {
        // Stream-optimized VMDK has a marker before each grain
        // Marker format: LBA (8 bytes) + size (4 bytes)
        ByteBuffer markerBuffer = ByteBuffer.allocate(12);
        markerBuffer.order(ByteOrder.LITTLE_ENDIAN);

        synchronized (channel) {
            channel.position(grainOffset);
            channel.read(markerBuffer);
        }
        markerBuffer.flip();

        long lba = markerBuffer.getLong();
        int compressedSize = markerBuffer.getInt();

        if (compressedSize == 0 || compressedSize > grainSize * 2) {
            // Invalid or uncompressed - return zeros
            return new byte[length];
        }

        // Read compressed data
        byte[] compressed = new byte[compressedSize];
        ByteBuffer compBuffer = ByteBuffer.wrap(compressed);

        synchronized (channel) {
            channel.position(grainOffset + 12);
            channel.read(compBuffer);
        }

        // Decompress (may require multiple inflate() calls for large grains)
        byte[] decompressed = new byte[grainSize];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int totalDecompressed = 0;
            while (!inflater.finished() && totalDecompressed < grainSize) {
                int n = inflater.inflate(decompressed, totalDecompressed, grainSize - totalDecompressed);
                if (n == 0 && inflater.needsInput()) {
                    break; // No more input data
                }
                totalDecompressed += n;
            }
            if (totalDecompressed < grainSize) {
                Arrays.fill(decompressed, totalDecompressed, grainSize, (byte) 0);
            }
        } catch (DataFormatException e) {
            // Return zeros if decompression fails
            return new byte[length];
        } finally {
            inflater.end();
        }

        // Extract requested portion
        byte[] result = new byte[length];
        System.arraycopy(decompressed, offsetInGrain, result, 0, length);
        return result;
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return new VmdkInputStream(this, 0);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("vmdk.version", String.valueOf(header.version()));
        metadata.put("vmdk.createType", descriptor != null ? descriptor.createType() : "sparse");
        metadata.put("vmdk.virtualSize", String.valueOf(virtualSize()));
        metadata.put("vmdk.grainSize", String.valueOf(header.grainSizeBytes()));
        metadata.put("vmdk.compressed", String.valueOf(header.isCompressed()));
        metadata.put("vmdk.streamOptimized", String.valueOf(header.isStreamOptimized()));

        if (descriptor != null) {
            metadata.put("vmdk.cid", descriptor.cid());
            if (descriptor.virtualHWVersion() != null) {
                metadata.put("vmdk.virtualHWVersion", descriptor.virtualHWVersion());
            }
            if (descriptor.adapterType() != null) {
                metadata.put("vmdk.adapterType", descriptor.adapterType());
            }
        }

        return Map.copyOf(metadata);
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            String name = path.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }

            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("create_type", descriptor != null ? descriptor.createType() : "sparse");
            qualifiers.put("version", String.valueOf(header.version()));

            return new PackageURL(
                    "vmdisk",
                    null,
                    name,
                    descriptor != null ? descriptor.cid() : "unknown",
                    qualifiers,
                    null
            );
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException("Failed to create PackageURL", e);
        }
    }

    @Override
    public @NotNull Optional<String> backingFile() {
        if (descriptor != null && descriptor.hasParent()) {
            // Look for parent extent in extents
            return descriptor.extents().stream()
                    .filter(e -> e.type() == VmdkDescriptor.ExtentType.SPARSE ||
                                 e.type() == VmdkDescriptor.ExtentType.FLAT)
                    .map(VmdkDescriptor.Extent::filename)
                    .findFirst();
        }
        return Optional.empty();
    }

    @Override
    public boolean isEncrypted() {
        // VMDK encryption is indicated in descriptor
        if (descriptor != null) {
            return descriptor.ddb().containsKey("encryption.key");
        }
        return false;
    }

    @Override
    public boolean isCompressed() {
        return header.isCompressed();
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        // VMDK snapshots are managed at the VM level, not in individual disk files
        return Stream.empty();
    }

    @Override
    public @NotNull String descriptorType() {
        return descriptor != null ? descriptor.createType() : "sparse";
    }

    @Override
    public @NotNull Optional<String> adapterType() {
        if (descriptor != null && descriptor.adapterType() != null) {
            return Optional.of(descriptor.adapterType());
        }
        return Optional.empty();
    }

    @Override
    public @NotNull Optional<String> hardwareVersion() {
        if (descriptor != null && descriptor.virtualHWVersion() != null) {
            return Optional.of(descriptor.virtualHWVersion());
        }
        return Optional.empty();
    }

    /**
     * Returns the sparse extent header.
     */
    public @NotNull SparseExtentHeader getHeader() {
        return header;
    }

    /**
     * Returns the descriptor if present.
     */
    public @NotNull Optional<VmdkDescriptor> getDescriptor() {
        return Optional.ofNullable(descriptor);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * InputStream implementation for VMDK virtual disk contents.
     */
    private static class VmdkInputStream extends InputStream {
        private final VmdkDiskImpl disk;
        private long position;
        private final long size;

        VmdkInputStream(VmdkDiskImpl disk, long startPosition) {
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
