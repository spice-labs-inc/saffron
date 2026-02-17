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
package io.spicelabs.saffron.raw;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Implementation of {@link VirtualDisk.RawDisk} for RAW format disk images.
 *
 * <p>RAW disk images are byte-for-byte copies of a disk with no container
 * format or metadata. The virtual size equals the file size, and reads
 * are simple offset-based accesses.
 */
public final class RawDiskImpl implements VirtualDisk.RawDisk {

    private static final int DEFAULT_SECTOR_SIZE = 512;

    private final Path path;
    private final SeekableByteChannel channel;
    private final long size;
    private final int sectorSize;

    /**
     * Opens a RAW disk image from a file path.
     *
     * @param path the path to the RAW file
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull RawDiskImpl open(@NotNull Path path) throws IOException {
        return open(path, DEFAULT_SECTOR_SIZE);
    }

    /**
     * Opens a RAW disk image from a file path with specified sector size.
     *
     * @param path the path to the RAW file
     * @param sectorSize the sector size in bytes
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull RawDiskImpl open(@NotNull Path path, int sectorSize) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path);
        try {
            long size = channel.size();
            return new RawDiskImpl(path, channel, size, sectorSize);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Creates a RAW disk from an existing channel (used by GCP reader).
     *
     * @param path the path (may be virtual)
     * @param channel the channel to read from
     * @param size the total size
     * @param sectorSize the sector size
     * @return the disk implementation
     */
    public static @NotNull RawDiskImpl fromChannel(@NotNull Path path,
                                                    @NotNull SeekableByteChannel channel,
                                                    long size,
                                                    int sectorSize) {
        return new RawDiskImpl(path, channel, size, sectorSize);
    }

    private RawDiskImpl(Path path, SeekableByteChannel channel, long size, int sectorSize) {
        this.path = path;
        this.channel = channel;
        this.size = size;
        this.sectorSize = sectorSize;
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.RAW;
    }

    @Override
    public long virtualSize() {
        return size;
    }

    @Override
    public long allocatedSize() {
        return size; // RAW images are not sparse
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Offset and length must be non-negative");
        }
        if (offset + length > size) {
            throw new IllegalArgumentException(
                    "Read beyond end of disk: offset=" + offset + ", length=" + length + ", size=" + size);
        }

        ByteBuffer buffer = ByteBuffer.allocate(length);
        synchronized (channel) {
            channel.position(offset);
            int totalRead = 0;
            while (totalRead < length) {
                int read = channel.read(buffer);
                if (read == -1) {
                    break;
                }
                totalRead += read;
            }
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        // Create a new channel for streaming
        SeekableByteChannel streamChannel = Files.newByteChannel(path);
        return Channels.newInputStream(streamChannel);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("raw.size", String.valueOf(size));
        meta.put("raw.sectorSize", String.valueOf(sectorSize));
        meta.put("raw.sectors", String.valueOf(size / sectorSize));
        return meta;
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
            qualifiers.put("size", String.valueOf(size));

            return new PackageURL(
                    PackageURL.StandardTypes.GENERIC,
                    "vmdisk",
                    name,
                    "1.0",
                    qualifiers,
                    null
            );
        } catch (MalformedPackageURLException e) {
            throw new IllegalStateException("Failed to create package URL", e);
        }
    }

    @Override
    public @NotNull Optional<String> backingFile() {
        return Optional.empty(); // RAW images don't support backing files
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
    public @NotNull Stream<Snapshot> snapshots() {
        return Stream.empty(); // RAW images don't support snapshots
    }

    @Override
    public int sectorSize() {
        return sectorSize;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * Returns the path to this disk image.
     */
    public @NotNull Path path() {
        return path;
    }
}
