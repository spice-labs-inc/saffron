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
package io.spicelabs.saffron.qcow2;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.DiskReader;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.qcow2.cluster.ClusterReader;
import io.spicelabs.saffron.qcow2.header.Qcow2Header;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li>Snapshot table parsing (metadata only, not snapshot data access)</li>
 * </ul>
 *
 * <p>Features not yet supported:
 * <ul>
 *   <li>Compression</li>
 *   <li>Encryption</li>
 *   <li>Snapshot data access (reading from a snapshot's L1 table)</li>
 *   <li>External data files</li>
 * </ul>
 */
public final class Qcow2DiskImpl implements VirtualDisk.Qcow2Disk {

    /** Maximum depth for backing file chains to prevent infinite recursion. */
    private static final int MAX_BACKING_CHAIN_DEPTH = 16;

    /** Maximum number of snapshots to prevent DoS from malformed headers. */
    private static final int MAX_SNAPSHOTS = 65536;

    /** Maximum snapshot extra data size to prevent excessive allocation. */
    private static final int MAX_EXTRA_DATA_SIZE = 1024 * 1024;

    private final Path path;
    private final SeekableByteChannel channel;
    private final Qcow2Header header;
    private final ClusterReader clusterReader;
    private final long allocatedSize;
    private final VirtualDisk backingDisk;
    private final List<Snapshot> snapshots;

    /**
     * Opens a QCOW2 disk image from a file path.
     *
     * @param path the path to the QCOW2 file
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Qcow2DiskImpl open(@NotNull Path path) throws IOException {
        return open(path, 0);
    }

    /**
     * Opens a QCOW2 disk image from a file path, tracking backing chain depth.
     *
     * @param path the path to the QCOW2 file
     * @param depth the current depth in the backing file chain (0 for top-level)
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    static @NotNull Qcow2DiskImpl open(@NotNull Path path, int depth) throws IOException {
        if (depth >= MAX_BACKING_CHAIN_DEPTH) {
            throw new IOException("QCOW2 backing file chain exceeds maximum depth of "
                    + MAX_BACKING_CHAIN_DEPTH + ": " + path);
        }

        SeekableByteChannel channel = Files.newByteChannel(path);
        try {
            // Read and validate header (using channel overload to resolve backing file name)
            Qcow2Header header = Qcow2Header.read(channel);

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

            // Resolve and open backing file if present
            VirtualDisk backingDisk = null;
            if (header.backingFile() != null) {
                Path backingPath = resolveBackingFilePath(path, header.backingFile());
                backingDisk = openBackingDisk(backingPath, depth + 1);
            }

            // Create cluster reader with optional backing disk
            ClusterReader clusterReader = new ClusterReader(channel, header, backingDisk);

            // Calculate allocated size
            long allocatedSize = Files.size(path);

            // Parse snapshot table
            List<Snapshot> snapshots = parseSnapshotTable(channel, header);

            return new Qcow2DiskImpl(path, channel, header, clusterReader, allocatedSize, backingDisk, snapshots);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Resolves a backing file path relative to the QCOW2 image's parent directory.
     *
     * @param qcow2Path the path to the current QCOW2 file
     * @param backingFileName the backing file name from the header
     * @return the resolved path
     */
    private static @NotNull Path resolveBackingFilePath(@NotNull Path qcow2Path, @NotNull String backingFileName) {
        Path backingPath = Path.of(backingFileName);
        if (backingPath.isAbsolute()) {
            return backingPath;
        }
        // Relative path: resolve against the parent directory of the QCOW2 file
        Path parentDir = qcow2Path.toAbsolutePath().getParent();
        if (parentDir == null) {
            return backingPath;
        }
        return parentDir.resolve(backingPath).normalize();
    }

    /**
     * Opens a backing disk image. The backing file can be any format supported by DiskReader,
     * or it can be another QCOW2 file (which continues the chain).
     *
     * @param backingPath the resolved path to the backing file
     * @param depth the current chain depth
     * @return the opened backing disk
     * @throws IOException if the backing file cannot be opened
     */
    private static @NotNull VirtualDisk openBackingDisk(@NotNull Path backingPath, int depth) throws IOException {
        // Check if the backing file is a QCOW2 (to continue depth tracking)
        Optional<DiskFormat> format = DiskFormat.detect(backingPath);
        if (format.isPresent() && format.get() == DiskFormat.QCOW2) {
            return Qcow2DiskImpl.open(backingPath, depth);
        }
        // Non-QCOW2 backing file (e.g., raw) — open via DiskReader
        return DiskReader.open(backingPath);
    }

    private Qcow2DiskImpl(Path path, SeekableByteChannel channel, Qcow2Header header,
                          ClusterReader clusterReader, long allocatedSize,
                          VirtualDisk backingDisk, List<Snapshot> snapshots) {
        this.path = path;
        this.channel = channel;
        this.header = header;
        this.clusterReader = clusterReader;
        this.allocatedSize = allocatedSize;
        this.backingDisk = backingDisk;
        this.snapshots = snapshots;
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

        if (!snapshots.isEmpty()) {
            meta.put("qcow2.snapshotCount", String.valueOf(snapshots.size()));
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
        return snapshots.stream();
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

    /**
     * Parses the QCOW2 snapshot table from the image file.
     *
     * <p>Each snapshot table entry has the following layout (big-endian):
     * <pre>
     * Offset  Size   Field
     * 0       8      l1_table_offset
     * 8       4      l1_size
     * 12      2      id_str_size
     * 14      2      name_size
     * 16      4      date_sec
     * 20      4      date_nsec
     * 24      8      vm_clock_nsec
     * 32      4      vm_state_size
     * 36      4      extra_data_size (0 for v2 images)
     * 40      extra_data_size bytes of extra data
     * ...     id_str_size bytes of snapshot ID string (not null-terminated)
     * ...     name_size bytes of snapshot name string (not null-terminated)
     * Padded to 8-byte boundary
     * </pre>
     *
     * @param channel the seekable byte channel positioned in the QCOW2 file
     * @param header the parsed QCOW2 header
     * @return an unmodifiable list of parsed snapshots
     * @throws IOException if an I/O error occurs during reading
     */
    private static @NotNull List<Snapshot> parseSnapshotTable(
            @NotNull SeekableByteChannel channel,
            @NotNull Qcow2Header header) throws IOException {

        int nbSnapshots = header.nbSnapshots();
        long snapshotsOffset = header.snapshotsOffset();

        if (nbSnapshots <= 0 || snapshotsOffset <= 0) {
            return List.of();
        }

        if (nbSnapshots > MAX_SNAPSHOTS) {
            throw new io.spicelabs.saffron.exception.CorruptedDiskException(
                    "Too many snapshots: " + nbSnapshots + " (max " + MAX_SNAPSHOTS + ")",
                    60L, "header.nb_snapshots", DiskFormat.QCOW2);
        }

        channel.position(snapshotsOffset);

        List<Snapshot> result = new ArrayList<>(nbSnapshots);

        for (int i = 0; i < nbSnapshots; i++) {
            // Read the fixed-size portion of the snapshot entry (40 bytes)
            ByteBuffer entryBuf = ByteBuffer.allocate(40);
            entryBuf.order(ByteOrder.BIG_ENDIAN);
            readFully(channel, entryBuf);
            entryBuf.flip();

            long l1TableOffset = entryBuf.getLong();        // offset 0
            int l1Size = entryBuf.getInt();                 // offset 8
            int idStrSize = entryBuf.getShort() & 0xFFFF;   // offset 12
            int nameSize = entryBuf.getShort() & 0xFFFF;    // offset 14
            long dateSec = entryBuf.getInt() & 0xFFFFFFFFL; // offset 16
            int dateNsec = entryBuf.getInt();               // offset 20
            long vmClockNsec = entryBuf.getLong();           // offset 24
            long vmStateSize = entryBuf.getInt() & 0xFFFFFFFFL; // offset 32
            int extraDataSize = entryBuf.getInt();           // offset 36

            // Validate extra_data_size
            if (extraDataSize < 0 || extraDataSize > MAX_EXTRA_DATA_SIZE) {
                throw new io.spicelabs.saffron.exception.CorruptedDiskException(
                        "Invalid snapshot extra_data_size: " + extraDataSize,
                        snapshotsOffset, "snapshot_table", DiskFormat.QCOW2);
            }

            // Skip extra data
            if (extraDataSize > 0) {
                // For v3, extra data may contain a 64-bit vm_state_size override at offset 0
                if (extraDataSize >= 8) {
                    ByteBuffer extraBuf = ByteBuffer.allocate(8);
                    extraBuf.order(ByteOrder.BIG_ENDIAN);
                    readFully(channel, extraBuf);
                    extraBuf.flip();
                    long vmStateSizeV3 = extraBuf.getLong();
                    // The v3 extra data field at offset 0 is the 64-bit vm_state_size;
                    // it overrides the 32-bit field if present
                    if (vmStateSizeV3 != 0 || vmStateSize == 0) {
                        vmStateSize = vmStateSizeV3;
                    }

                    // Skip remaining extra data beyond the first 8 bytes
                    int remainingExtra = extraDataSize - 8;
                    if (remainingExtra > 0) {
                        skipBytes(channel, remainingExtra);
                    }
                } else {
                    skipBytes(channel, extraDataSize);
                }
            }

            // Read snapshot ID string
            String id;
            if (idStrSize > 0) {
                ByteBuffer idBuf = ByteBuffer.allocate(idStrSize);
                readFully(channel, idBuf);
                idBuf.flip();
                id = new String(idBuf.array(), 0, idStrSize, StandardCharsets.UTF_8);
            } else {
                id = String.valueOf(i);
            }

            // Read snapshot name string
            String name;
            if (nameSize > 0) {
                ByteBuffer nameBuf = ByteBuffer.allocate(nameSize);
                readFully(channel, nameBuf);
                nameBuf.flip();
                name = new String(nameBuf.array(), 0, nameSize, StandardCharsets.UTF_8);
            } else {
                name = null;
            }

            // Each snapshot entry is padded to an 8-byte boundary.
            // The variable-size portion is: extra_data_size + id_str_size + name_size
            // The total entry size is 40 + extra_data_size + id_str_size + name_size,
            // rounded up to the next multiple of 8.
            int variableSize = extraDataSize + idStrSize + nameSize;
            int totalEntrySize = 40 + variableSize;
            int padding = (8 - (totalEntrySize % 8)) % 8;
            if (padding > 0) {
                skipBytes(channel, padding);
            }

            result.add(new Snapshot(id, name, vmStateSize, dateSec, dateNsec));
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Reads fully from a channel into a buffer.
     */
    private static void readFully(@NotNull SeekableByteChannel channel,
                                   @NotNull ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n < 0) {
                throw new IOException("Unexpected end of QCOW2 file while reading snapshot table");
            }
        }
    }

    /**
     * Skips the specified number of bytes in a channel by advancing the position.
     */
    private static void skipBytes(@NotNull SeekableByteChannel channel, long count) throws IOException {
        channel.position(channel.position() + count);
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            if (backingDisk != null) {
                backingDisk.close();
            }
        }
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
