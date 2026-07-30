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
package io.spicelabs.saffron.container.compressed;

import io.spicelabs.saffron.SecurityPolicy;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.BinaryContainer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * Factory for detecting and opening compressed single non-archive payloads.
 */
public final class CompressedSingleContainerFactory {

    private static final int BUFFER_SIZE = 8192;
    private static final int MIN_XZ_MEMORY_LIMIT_KB = 1;

    private CompressedSingleContainerFactory() {
        // Static utility class
    }

    /**
     * Returns true if the source appears to be a single compressed non-archive payload.
     * For paths, archive-in-compression and compressed-disk-image extensions are excluded.
     *
     * @param path the source path (may be null for buffer/disk detection)
     * @param header the first bytes of the source
     * @param sourceSize the total size of the source in bytes
     * @return true if the source looks like a compressed single payload
     */
    public static boolean looksLikeCompressedSingle(@NotNull Path path,
                                                    @NotNull ByteBuffer header,
                                                    long sourceSize) {
        if (sourceSize < 2) {
            return false;
        }
        if (CompressedSingleFormat.isExcludedPath(path)) {
            return false;
        }
        return CompressedSingleFormat.detect(header).isPresent();
    }

    /**
     * Returns true if the buffer begins with a supported compression magic.
     * This version does not have a filename, so it cannot exclude tar archives.
     *
     * @param buffer the buffer to inspect
     * @param sourceSize the total size of the source in bytes
     * @return true if the buffer looks like a compressed single payload
     */
    public static boolean looksLikeCompressedSingle(@NotNull ByteBuffer buffer, long sourceSize) {
        if (sourceSize < 2) {
            return false;
        }
        return CompressedSingleFormat.detect(buffer).isPresent();
    }

    /**
     * Returns true if the virtual disk begins with a supported compression magic.
     * This version does not have a filename, so it cannot exclude tar archives.
     *
     * @param disk the virtual disk to inspect
     * @return true if the disk looks like a compressed single payload
     * @throws IOException if an I/O error occurs
     */
    public static boolean looksLikeCompressedSingle(@NotNull VirtualDisk disk) throws IOException {
        long size = disk.virtualSize();
        if (size < 2) {
            return false;
        }
        int probeLength = (int) Math.min(8, size);
        ByteBuffer header = disk.read(0, probeLength);
        return CompressedSingleFormat.detect(header).isPresent();
    }

    /**
     * Attempts to open a compressed single container from a file path.
     *
     * @param path the path to examine
     * @param policy the security policy governing decompression limits
     * @return the container, or empty if the file is not a compressed single payload
     * @throws IOException if an I/O error occurs
     * @throws DecompressionBombException if the decompressed size exceeds the configured limit
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull Path path,
                                                         @NotNull SecurityPolicy policy) throws IOException {
        long sourceSize = Files.size(path);
        if (sourceSize < 2) {
            return Optional.empty();
        }
        if (CompressedSingleFormat.isExcludedPath(path)) {
            return Optional.empty();
        }
        byte[] header = new byte[(int) Math.min(8, sourceSize)];
        try (InputStream is = Files.newInputStream(path)) {
            if (is.read(header) != header.length) {
                return Optional.empty();
            }
        }
        ByteBuffer headerBuffer = ByteBuffer.wrap(header);
        Optional<CompressedSingleFormat> format = CompressedSingleFormat.detect(headerBuffer);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        Path payloadPath = decompressToTemp(path, format.get(), sourceSize, policy);
        return Optional.of(new CompressedSingleContainer(sourceSize, format.get(), Files.size(payloadPath), payloadPath));
    }

    /**
     * Attempts to open a compressed single container from a byte buffer.
     * The buffer is not modified.
     *
     * @param source the compressed bytes; position must be 0
     * @param sourceSize the total size of the compressed source
     * @param policy the security policy governing decompression limits
     * @return the container, or empty if the buffer is not a compressed single payload
     * @throws IOException if an I/O error occurs
     * @throws DecompressionBombException if the decompressed size exceeds the configured limit
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull ByteBuffer source,
                                                         long sourceSize,
                                                         @NotNull SecurityPolicy policy) throws IOException {
        if (sourceSize < 2) {
            return Optional.empty();
        }
        ByteBuffer header = source.slice();
        Optional<CompressedSingleFormat> format = CompressedSingleFormat.detect(header);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        Path payloadPath = decompressToTemp(source, format.get(), sourceSize, policy);
        return Optional.of(new CompressedSingleContainer(sourceSize, format.get(), Files.size(payloadPath), payloadPath));
    }

    /**
     * Attempts to open a compressed single container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @param policy the security policy governing decompression limits
     * @return the container, or empty if the disk is not a compressed single payload
     * @throws IOException if an I/O error occurs
     * @throws DecompressionBombException if the decompressed size exceeds the configured limit
     */
    public static @NotNull Optional<BinaryContainer> open(@NotNull VirtualDisk disk,
                                                         @NotNull SecurityPolicy policy) throws IOException {
        long sourceSize = disk.virtualSize();
        if (sourceSize < 2) {
            return Optional.empty();
        }
        int probeLength = (int) Math.min(8, sourceSize);
        ByteBuffer header = disk.read(0, probeLength);
        Optional<CompressedSingleFormat> format = CompressedSingleFormat.detect(header);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        Path payloadPath = decompressToTemp(disk, format.get(), sourceSize, policy);
        return Optional.of(new CompressedSingleContainer(sourceSize, format.get(), Files.size(payloadPath), payloadPath));
    }

    /**
     * Decompresses the source path to a temporary file with size and memory limits.
     *
     * @param path the compressed source path
     * @param format the detected compression format
     * @param sourceSize the compressed source size
     * @param policy the security policy
     * @return the path to the temporary decompressed file
     * @throws IOException if decompression fails or limits are exceeded
     */
    static @NotNull Path decompressToTemp(@NotNull Path path,
                                          @NotNull CompressedSingleFormat format,
                                          long sourceSize,
                                          @NotNull SecurityPolicy policy) throws IOException {
        Path tempPath = createTempPayloadPath();
        try (InputStream in = Files.newInputStream(path);
             InputStream decompressed = format.openDecompressor(in, xzMemoryLimitInKb(policy));
             OutputStream out = Files.newOutputStream(tempPath)) {
            copyBounded(decompressed, out, policy.maxDecompressedSize(), tempPath);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tempPath);
            throw e;
        }
        tempPath.toFile().deleteOnExit();
        return tempPath;
    }

    /**
     * Decompresses the byte buffer to a temporary file with size and memory limits.
     */
    static @NotNull Path decompressToTemp(@NotNull ByteBuffer source,
                                          @NotNull CompressedSingleFormat format,
                                          long sourceSize,
                                          @NotNull SecurityPolicy policy) throws IOException {
        Path tempPath = createTempPayloadPath();
        byte[] bytes = toByteArray(source);
        try (InputStream in = new java.io.ByteArrayInputStream(bytes);
             InputStream decompressed = format.openDecompressor(in, xzMemoryLimitInKb(policy));
             OutputStream out = Files.newOutputStream(tempPath)) {
            copyBounded(decompressed, out, policy.maxDecompressedSize(), tempPath);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tempPath);
            throw e;
        }
        tempPath.toFile().deleteOnExit();
        return tempPath;
    }

    /**
     * Decompresses the virtual disk to a temporary file with size and memory limits.
     */
    static @NotNull Path decompressToTemp(@NotNull VirtualDisk disk,
                                          @NotNull CompressedSingleFormat format,
                                          long sourceSize,
                                          @NotNull SecurityPolicy policy) throws IOException {
        Path tempPath = createTempPayloadPath();
        try (InputStream in = disk.openStream();
             InputStream decompressed = format.openDecompressor(in, xzMemoryLimitInKb(policy));
             OutputStream out = Files.newOutputStream(tempPath)) {
            copyBounded(decompressed, out, policy.maxDecompressedSize(), tempPath);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tempPath);
            throw e;
        }
        tempPath.toFile().deleteOnExit();
        return tempPath;
    }

    /**
     * Copies from {@code in} to {@code out} while enforcing a byte limit.
     * Throws {@link ResourceLimitException} if the limit is exceeded.
     */
    private static void copyBounded(@NotNull InputStream in,
                                      @NotNull OutputStream out,
                                      long maxDecompressedSize,
                                      @NotNull Path tempPath) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        int len;
        while ((len = in.read(buffer)) > 0) {
            if (written + len > maxDecompressedSize) {
                throw DecompressionBombException.of(maxDecompressedSize, written + len);
            }
            out.write(buffer, 0, len);
            written += len;
        }
    }

    /**
     * Computes the XZ dictionary memory limit in KiB from the security policy.
     */
    static int xzMemoryLimitInKb(@NotNull SecurityPolicy policy) {
        long kb = policy.maxAllocationSize() / 1024;
        return (int) Math.max(MIN_XZ_MEMORY_LIMIT_KB, Math.min(kb, Integer.MAX_VALUE));
    }

    /**
     * Creates a temporary file for the decompressed payload, with restrictive permissions when possible.
     */
    static @NotNull Path createTempPayloadPath() throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            FileAttribute<Set<PosixFilePermission>> attrs = PosixFilePermissions.asFileAttribute(ownerOnly);
            return Files.createTempFile("saffron-compressed-single-", ".payload", attrs);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem: use default permissions.
            return Files.createTempFile("saffron-compressed-single-", ".payload");
        }
    }

    private static void deleteQuietly(@NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best effort cleanup
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
}
