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
package io.spicelabs.saffron.vhdx;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.common.SecurityUtils;
import io.spicelabs.saffron.exception.CorruptedDiskException;
import io.spicelabs.saffron.vhdx.header.VhdxFileIdentifier;
import io.spicelabs.saffron.vhdx.header.VhdxHeader;
import io.spicelabs.saffron.vhdx.metadata.VhdxMetadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Implementation of {@link VirtualDisk.VhdxDisk} for VHDX format disk images.
 *
 * <p>VHDX is the successor to VHD with improved features:
 * <ul>
 *   <li>64 TB maximum virtual disk size</li>
 *   <li>4 KB logical sector size support</li>
 *   <li>Block sizes up to 256 MB</li>
 *   <li>Log-based metadata for corruption protection</li>
 * </ul>
 */
public final class VhdxDiskImpl implements VirtualDisk.VhdxDisk {

    /** Region table signature */
    private static final byte[] REGION_TABLE_MAGIC = "regi".getBytes(StandardCharsets.US_ASCII);

    /** BAT region GUID */
    private static final UUID BAT_REGION_GUID =
            UUID.fromString("2dc27766-f623-4200-9d64-115e9bfd4a08");

    /** Metadata region GUID */
    private static final UUID METADATA_REGION_GUID =
            UUID.fromString("8b7ca206-4790-4b9a-b8fe-575f050f886e");

    /** Region table offset (192 KB) */
    private static final long REGION_TABLE1_OFFSET = 192 * 1024;

    /** Second region table offset (256 KB) */
    private static final long REGION_TABLE2_OFFSET = 256 * 1024;

    /** BAT entry state: not present */
    private static final int BAT_STATE_NOT_PRESENT = 0;

    /** BAT entry state: undefined */
    private static final int BAT_STATE_UNDEFINED = 1;

    /** BAT entry state: zero */
    private static final int BAT_STATE_ZERO = 2;

    /** BAT entry state: unmapped */
    private static final int BAT_STATE_UNMAPPED = 3;

    /** BAT entry state: fully present */
    private static final int BAT_STATE_FULLY_PRESENT = 6;

    /** BAT entry state: partially present */
    private static final int BAT_STATE_PARTIALLY_PRESENT = 7;

    private final Path path;
    private final SeekableByteChannel channel;
    private final VhdxFileIdentifier fileIdentifier;
    private final VhdxHeader header;
    private final VhdxMetadata metadata;
    private final long batOffset;
    private final long @Nullable [] bat;
    private final long allocatedSize;

    /**
     * Opens a VHDX disk image from a file path.
     *
     * @param path the path to the VHDX file
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull VhdxDiskImpl open(@NotNull Path path) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path);
        try {
            // Read file identifier
            VhdxFileIdentifier fileIdentifier = VhdxFileIdentifier.read(channel);

            // Read header (the one with higher sequence number)
            VhdxHeader header = VhdxHeader.readCurrent(channel);

            // Read region table to find metadata and BAT regions
            long[] regionInfo = readRegionTable(channel);
            long metadataOffset = regionInfo[0];
            int metadataLength = (int) regionInfo[1];
            long batOffset = regionInfo[2];

            // Read metadata
            VhdxMetadata metadata = VhdxMetadata.read(channel, metadataOffset, metadataLength);

            // Differencing disks read zeros for unallocated blocks because
            // the parent is never resolved; reject loudly.
            if (metadata.hasParent()) {
                throw new IOException("Differencing VHDX images are not supported: "
                        + path.getFileName());
            }

            validateMetadata(metadata, channel.size());

            // Read BAT
            long[] bat = readBat(channel, batOffset, metadata.virtualDiskSize(), metadata.blockSize());

            long allocatedSize = Files.size(path);

            return new VhdxDiskImpl(path, channel, fileIdentifier, header, metadata,
                    batOffset, bat, allocatedSize);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    /** Maximum VHDX virtual disk size per the format (64 TiB). */
    private static final long MAX_VHDX_SIZE = 64L * 1024 * 1024 * 1024 * 1024;

    /** VHDX spec: block size must be a power of two, 1 MiB..256 MiB. */
    private static final int MIN_VHDX_BLOCK_SIZE = 1024 * 1024;
    private static final int MAX_VHDX_BLOCK_SIZE = 256 * 1024 * 1024;

    /** Memory budget: a single BAT read must not exceed 16 MiB. */
    private static final long MAX_BAT_BYTES = 16L * 1024 * 1024;

    private static void validateMetadata(VhdxMetadata metadata, long fileSize)
            throws IOException {
        int blockSize = metadata.blockSize();
        if (blockSize < MIN_VHDX_BLOCK_SIZE || blockSize > MAX_VHDX_BLOCK_SIZE
                || (blockSize & (blockSize - 1)) != 0) {
            throw new IOException("Invalid VHDX block size: " + blockSize);
        }
        long virtualSize = metadata.virtualDiskSize();
        if (virtualSize <= 0 || virtualSize > MAX_VHDX_SIZE) {
            throw new IOException("Invalid VHDX virtual disk size: " + virtualSize);
        }
    }

    private VhdxDiskImpl(Path path, SeekableByteChannel channel, VhdxFileIdentifier fileIdentifier,
                         VhdxHeader header, VhdxMetadata metadata, long batOffset,
                         long[] bat, long allocatedSize) {
        this.path = path;
        this.channel = channel;
        this.fileIdentifier = fileIdentifier;
        this.header = header;
        this.metadata = metadata;
        this.batOffset = batOffset;
        this.bat = bat;
        this.allocatedSize = allocatedSize;
    }

    private static long[] readRegionTable(SeekableByteChannel channel) throws IOException {
        // Try first region table
        long[] result = tryReadRegionTable(channel, REGION_TABLE1_OFFSET);
        if (result != null) {
            return result;
        }

        // Try second region table
        result = tryReadRegionTable(channel, REGION_TABLE2_OFFSET);
        if (result != null) {
            return result;
        }

        throw new IOException("Both VHDX region tables are invalid");
    }

    private static long @Nullable [] tryReadRegionTable(SeekableByteChannel channel, long offset)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int read;
        synchronized (channel) {
            channel.position(offset);
            read = channel.read(buffer);
        }
        if (read < 16) {
            return null;
        }
        buffer.flip();

        // Check signature
        byte[] signature = new byte[4];
        buffer.get(signature);
        if (!SecurityUtils.constantTimeEquals(signature, REGION_TABLE_MAGIC)) {
            return null;
        }

        // Skip checksum
        buffer.getInt();

        // Entry count (VHDX spec: 1..2047)
        int entryCount = buffer.getInt();
        if (entryCount < 1 || entryCount > 2047) {
            return null;
        }

        // Skip reserved
        buffer.getInt();

        // Read entries (each 32 bytes)
        int entriesBytes = Math.multiplyExact(entryCount, 32);
        if (offset + 16 + entriesBytes > channel.size()) {
            return null;
        }
        ByteBuffer entriesBuffer = ByteBuffer.allocate(entriesBytes);
        entriesBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int totalRead = 0;
        synchronized (channel) {
            channel.position(offset + 16);
            while (totalRead < entriesBytes) {
                int n = channel.read(entriesBuffer);
                if (n < 0) {
                    return null;
                }
                if (n == 0) {
                    return null;
                }
                totalRead += n;
            }
        }
        entriesBuffer.flip();

        long metadataOffset = 0;
        int metadataLength = 0;
        long batOffset = 0;

        for (int i = 0; i < entryCount && entriesBuffer.remaining() >= 32; i++) {
            UUID guid = readGuid(entriesBuffer);
            long regionOffset = entriesBuffer.getLong();
            int regionLength = entriesBuffer.getInt();
            int required = entriesBuffer.getInt();

            if (guid.equals(METADATA_REGION_GUID)) {
                metadataOffset = regionOffset;
                metadataLength = regionLength;
            } else if (guid.equals(BAT_REGION_GUID)) {
                batOffset = regionOffset;
            }
        }

        if (metadataOffset == 0 || batOffset == 0) {
            return null;
        }

        return new long[]{metadataOffset, metadataLength, batOffset};
    }

    private static long[] readBat(SeekableByteChannel channel, long batOffset,
                                   long virtualSize, int blockSize) throws IOException {
        if (blockSize <= 0) {
            throw new IOException("Invalid block size: " + blockSize);
        }

        long totalBlocksLong;
        try {
            totalBlocksLong = Math.addExact(virtualSize, (long) blockSize - 1) / blockSize;
        } catch (ArithmeticException e) {
            throw new IOException("VHDX virtual size overflows block count: " + virtualSize);
        }
        if (totalBlocksLong <= 0 || totalBlocksLong > Integer.MAX_VALUE) {
            throw new IOException("Invalid VHDX block count: " + totalBlocksLong);
        }
        long batBytes = Math.multiplyExact(totalBlocksLong, 8L);
        if (batBytes > MAX_BAT_BYTES) {
            throw new IOException("VHDX BAT too large for the 16 MiB read budget: "
                    + batBytes + " bytes (unsupported geometry)");
        }
        final long batEnd;
        try {
            batEnd = Math.addExact(batOffset, batBytes);
        } catch (ArithmeticException e) {
            throw new IOException("VHDX BAT bounds overflow: batOffset="
                    + batOffset + ", bytes=" + batBytes, e);
        }
        if (batEnd > channel.size()) {
            throw new IOException("VHDX BAT out of bounds: batOffset=" + batOffset
                    + ", bytes=" + batBytes + ", fileSize=" + channel.size());
        }
        int totalBlocks = (int) totalBlocksLong;
        long[] bat = new long[totalBlocks];

        ByteBuffer buffer = ByteBuffer.allocate(totalBlocks * 8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int totalRead = 0;
        synchronized (channel) {
            channel.position(batOffset);
            while (totalRead < totalBlocks * 8) {
                int n = channel.read(buffer);
                if (n < 0) {
                    throw new IOException("Truncated VHDX BAT: expected " + (totalBlocks * 8)
                            + " bytes, got " + totalRead);
                }
                if (n == 0) {
                    throw new IOException("No progress reading VHDX BAT");
                }
                totalRead += n;
            }
        }
        buffer.flip();

        for (int i = 0; i < totalBlocks; i++) {
            bat[i] = buffer.getLong();
        }

        return bat;
    }

    private static UUID readGuid(ByteBuffer buffer) {
        int data1 = buffer.getInt();
        short data2 = buffer.getShort();
        short data3 = buffer.getShort();
        byte[] data4 = new byte[8];
        buffer.get(data4);

        long msb = ((long) data1 << 32) | ((long) (data2 & 0xFFFF) << 16) | (data3 & 0xFFFF);
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            lsb = (lsb << 8) | (data4[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.VHDX;
    }

    @Override
    public long virtualSize() {
        return metadata.virtualDiskSize();
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

        long maxLength = virtualSize() - offset;
        if (length > maxLength) {
            length = (int) maxLength;
        }

        ByteBuffer result = ByteBuffer.allocate(length);
        int blockSize = metadata.blockSize();

        while (result.position() < length) {
            long currentOffset = offset + result.position();
            int blockIndex = (int) (currentOffset / blockSize);
            int offsetInBlock = (int) (currentOffset % blockSize);
            int bytesToRead = Math.min(length - result.position(), blockSize - offsetInBlock);

            if (bat == null || blockIndex >= bat.length) {
                // Beyond BAT - return zeros
                byte[] zeros = new byte[bytesToRead];
                result.put(zeros);
            } else {
                long batEntry = bat[blockIndex];
                int state = (int) (batEntry & 0x07);

                if (state == BAT_STATE_NOT_PRESENT || state == BAT_STATE_UNDEFINED ||
                    state == BAT_STATE_ZERO || state == BAT_STATE_UNMAPPED) {
                    // Unallocated or zero block
                    byte[] zeros = new byte[bytesToRead];
                    result.put(zeros);
                } else if (state == BAT_STATE_FULLY_PRESENT || state == BAT_STATE_PARTIALLY_PRESENT) {
                    // Allocated block - extract file offset (bits 20-63, in MB units)
                    long fileOffsetMB = (batEntry >> 20) & 0xFFFFFFFFFFFFL;
                    long physicalOffset = fileOffsetMB * 1024 * 1024 + offsetInBlock;
                    readFromChannel(physicalOffset, result, bytesToRead);
                } else {
                    // Unknown state - return zeros
                    byte[] zeros = new byte[bytesToRead];
                    result.put(zeros);
                }
            }
        }

        result.flip();
        return result;
    }

    private void readFromChannel(long position, ByteBuffer dest, int length) throws IOException {
        ByteBuffer temp = ByteBuffer.allocate(length);
        synchronized (channel) {
            channel.position(position);

            int totalRead = 0;
            while (totalRead < length) {
                int read = channel.read(temp);
                if (read < 0) {
                    throw new IOException("Truncated VHDX file: expected " + length
                            + " bytes at offset " + position + ", got " + totalRead);
                }
                if (read == 0) {
                    throw new IOException("No progress reading VHDX file at offset " + position);
                }
                totalRead += read;
            }
        }

        temp.flip();
        dest.put(temp);
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return new VhdxInputStream(this, 0);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("vhdx.version", String.valueOf(header.version()));
        meta.put("vhdx.logVersion", String.valueOf(header.logVersion()));
        meta.put("vhdx.blockSize", String.valueOf(metadata.blockSize()));
        meta.put("vhdx.logicalSectorSize", String.valueOf(metadata.logicalSectorSize()));
        meta.put("vhdx.physicalSectorSize", String.valueOf(metadata.physicalSectorSize()));
        meta.put("vhdx.virtualSize", String.valueOf(metadata.virtualDiskSize()));
        meta.put("vhdx.hasParent", String.valueOf(metadata.hasParent()));

        if (fileIdentifier.creator() != null) {
            meta.put("vhdx.creator", fileIdentifier.creator());
        }

        if (metadata.virtualDiskId() != null) {
            meta.put("vhdx.virtualDiskId", metadata.virtualDiskId().toString());
        }

        return Map.copyOf(meta);
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("version", String.valueOf(header.version()));
            qualifiers.put("block_size", String.valueOf(metadata.blockSize()));

            String name = path.getFileName().toString();
            if (name.endsWith(".vhdx")) {
                name = name.substring(0, name.length() - 5);
            }

            return new PackageURL(
                    PackageURL.StandardTypes.GENERIC,
                    "vmdisk",
                    name,
                    String.valueOf(header.version()),
                    qualifiers,
                    null
            );
        } catch (MalformedPackageURLException e) {
            throw new RuntimeException("Failed to create PackageURL", e);
        }
    }

    @Override
    public @NotNull Optional<String> backingFile() {
        // VHDX parent locator not yet implemented
        return Optional.empty();
    }

    @Override
    public boolean isEncrypted() {
        return false; // Basic VHDX doesn't support encryption
    }

    @Override
    public boolean isCompressed() {
        return false; // Basic VHDX doesn't support compression
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        return Stream.empty();
    }

    @Override
    public int logVersion() {
        return header.logVersion();
    }

    @Override
    public int blockSize() {
        return metadata.blockSize();
    }

    @Override
    public int logicalSectorSize() {
        return metadata.logicalSectorSize();
    }

    @Override
    public int physicalSectorSize() {
        return metadata.physicalSectorSize();
    }

    /**
     * Returns the VHDX file identifier.
     */
    public @NotNull VhdxFileIdentifier getFileIdentifier() {
        return fileIdentifier;
    }

    /**
     * Returns the VHDX header.
     */
    public @NotNull VhdxHeader getHeader() {
        return header;
    }

    /**
     * Returns the VHDX metadata.
     */
    public @NotNull VhdxMetadata getMetadata() {
        return metadata;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * InputStream implementation for VHDX virtual disk contents.
     */
    private static class VhdxInputStream extends InputStream {
        private final VhdxDiskImpl disk;
        private long position;
        private final long size;

        VhdxInputStream(VhdxDiskImpl disk, long startPosition) {
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
            if (len == 0) {
                return 0;
            }
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
