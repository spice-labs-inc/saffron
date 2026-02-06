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
package io.spicelabs.saffron.qcow2;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.qcow2.cluster.ClusterReader;
import io.spicelabs.saffron.qcow2.header.Qcow2Header;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Implementation of {@link VirtualDisk.Qcow2Disk} for QCOW2 format disk images.
 *
 * <p>This class handles the QCOW2 format, supporting both version 2 and version 3
 * images. It provides transparent access to the virtual disk contents, handling
 * the L1/L2 table indirection and sparse allocation.
 *
 * <p>QCOW2 features supported:
 * <ul>
 *   <li>Version 2 and 3 headers</li>
 *   <li>Variable cluster sizes (512 bytes to 2 MB)</li>
 *   <li>Sparse allocation (unallocated clusters read as zeros)</li>
 *   <li>Backing files (read-only, no write support)</li>
 * </ul>
 *
 * <p>Features not yet supported:
 * <ul>
 *   <li>Compression</li>
 *   <li>Encryption</li>
 *   <li>Snapshots</li>
 *   <li>External data files</li>
 * </ul>
 */
public final class Qcow2DiskImpl implements VirtualDisk.Qcow2Disk {

    private final Path path;
    private final SeekableByteChannel channel;
    private final Qcow2Header header;
    private final ClusterReader clusterReader;
    private final long allocatedSize;

    /**
     * Opens a QCOW2 disk image from a file path.
     *
     * @param path the path to the QCOW2 file
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Qcow2DiskImpl open(@NotNull Path path) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path);
        try {
            // Read and validate header
            Qcow2Header header = Qcow2Header.read(Files.newInputStream(path));

            // Check for unsupported features
            if (header.isEncrypted()) {
                throw new io.spicelabs.saffron.exception.EncryptedDiskException(
                        "QCOW2 encryption is not supported",
                        header.cryptMethod() == Qcow2Header.CRYPT_AES ? "AES" : "LUKS",
                        DiskFormat.QCOW2);
            }

            if (header.hasExternalDataFile()) {
                throw new io.spicelabs.saffron.exception.SaffronException.UnsupportedDiskException(
                        "QCOW2 external data files are not supported",
                        DiskFormat.QCOW2);
            }

            // Create cluster reader
            ClusterReader clusterReader = new ClusterReader(channel, header);

            // Calculate allocated size
            long allocatedSize = Files.size(path);

            return new Qcow2DiskImpl(path, channel, header, clusterReader, allocatedSize);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    private Qcow2DiskImpl(Path path, SeekableByteChannel channel, Qcow2Header header,
                          ClusterReader clusterReader, long allocatedSize) {
        this.path = path;
        this.channel = channel;
        this.header = header;
        this.clusterReader = clusterReader;
        this.allocatedSize = allocatedSize;
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.QCOW2;
    }

    @Override
    public long virtualSize() {
        return header.virtualSize();
    }

    @Override
    public long allocatedSize() {
        return allocatedSize;
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        return clusterReader.read(offset, length);
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return new Qcow2InputStream(this, 0);
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("qcow2.version", String.valueOf(header.version()));
        meta.put("qcow2.clusterSize", String.valueOf(header.clusterSize()));
        meta.put("qcow2.size", String.valueOf(header.virtualSize()));
        meta.put("qcow2.refcountOrder", String.valueOf(header.refcountOrder()));

        if (header.backingFile() != null) {
            meta.put("qcow2.backingFile", header.backingFile());
        }

        if (header.version() >= 3) {
            meta.put("qcow2.incompatibleFeatures",
                    String.format("0x%016x", header.incompatibleFeatures()));
            meta.put("qcow2.compatibleFeatures",
                    String.format("0x%016x", header.compatibleFeatures()));
        }

        if (header.isEncrypted()) {
            meta.put("qcow2.encrypted", "true");
            meta.put("qcow2.cryptMethod", String.valueOf(header.cryptMethod()));
        }

        return Map.copyOf(meta);
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("qcow_version", String.valueOf(header.version()));
            qualifiers.put("cluster_size", String.valueOf(header.clusterSize()));

            String name = path.getFileName().toString();
            // Remove extension for cleaner pURL
            if (name.endsWith(".qcow2")) {
                name = name.substring(0, name.length() - 6);
            } else if (name.endsWith(".qcow")) {
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
            // Should never happen with our controlled inputs
            throw new RuntimeException("Failed to create PackageURL", e);
        }
    }

    @Override
    public @NotNull Optional<String> backingFile() {
        return Optional.ofNullable(header.backingFile());
    }

    @Override
    public boolean isEncrypted() {
        return header.isEncrypted();
    }

    @Override
    public boolean isCompressed() {
        // QCOW2 can have individual compressed clusters
        // We can't easily determine this without scanning
        return false;
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        // TODO: Parse snapshots from header.snapshotsOffset
        return Stream.empty();
    }

    @Override
    public int version() {
        return header.version();
    }

    @Override
    public int clusterSize() {
        return header.clusterSize();
    }

    @Override
    public int refcountOrder() {
        return header.refcountOrder();
    }

    @Override
    public @NotNull Optional<String> compressionType() {
        if ((header.incompatibleFeatures() & Qcow2Header.INCOMPAT_COMPRESSION_TYPE) != 0) {
            // Would need to read compression type from header extension
            return Optional.of("zstd"); // v3 default for this feature
        }
        return Optional.of("zlib"); // Default for v2/v3 without explicit type
    }

    /**
     * Returns the parsed QCOW2 header.
     *
     * @return the header
     */
    public @NotNull Qcow2Header getHeader() {
        return header;
    }

    /**
     * Checks if a virtual region is allocated.
     *
     * @param offset the virtual offset
     * @param length the length to check
     * @return true if any part of the region is allocated
     * @throws IOException if an I/O error occurs
     */
    public boolean isAllocated(long offset, long length) throws IOException {
        return clusterReader.isAllocated(offset, length);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * InputStream implementation for QCOW2 virtual disk contents.
     */
    private static class Qcow2InputStream extends InputStream {
        private final Qcow2DiskImpl disk;
        private long position;
        private final long size;

        Qcow2InputStream(Qcow2DiskImpl disk, long startPosition) {
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
        public long skip(long n) throws IOException {
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
