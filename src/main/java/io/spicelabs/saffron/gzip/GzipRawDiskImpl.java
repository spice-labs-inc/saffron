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
package io.spicelabs.saffron.gzip;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.SecurityPolicy;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.exception.ResourceLimitException;
import io.spicelabs.saffron.raw.RawDiskImpl;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Implementation of {@link VirtualDisk.RawDisk} for gzip-compressed RAW disk images.
 *
 * <p>Some raw disk images are distributed as single gzip streams (e.g. {@code .img.gz}).
 * This implementation decompresses the stream to a temporary file with a size bound
 * and delegates to {@link RawDiskImpl}.
 */
public final class GzipRawDiskImpl implements VirtualDisk.RawDisk {

    private static final int BUFFER_SIZE = 8192;

    private final Path sourcePath;
    private final Path extractedPath;
    private final RawDiskImpl innerDisk;

    /**
     * Opens a gzip-compressed raw disk image using the default security policy.
     *
     * @param path the path to the .img.gz (or .raw.gz) file
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull GzipRawDiskImpl open(@NotNull Path path) throws IOException {
        return open(path, SecurityPolicy.defaults());
    }

    /**
     * Opens a gzip-compressed raw disk image.
     *
     * @param path the path to the .img.gz (or .raw.gz) file
     * @param policy the security policy governing decompression limits
     * @return the opened disk
     * @throws IOException if an I/O error occurs
     * @throws ResourceLimitException if the decompressed size exceeds the configured limit
     */
    public static @NotNull GzipRawDiskImpl open(@NotNull Path path,
                                                @NotNull SecurityPolicy policy) throws IOException {
        Path tempDir = Files.createTempDirectory("saffron-gzip-raw-");
        Path extractedPath = tempDir.resolve("disk.raw");

        try (InputStream fis = Files.newInputStream(path);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             OutputStream out = Files.newOutputStream(extractedPath)) {
            copyBounded(gzis, out, policy.maxDecompressedSize(), extractedPath, path);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(extractedPath);
            deleteQuietly(tempDir);
            throw e;
        }
        extractedPath.toFile().deleteOnExit();
        tempDir.toFile().deleteOnExit();

        return new GzipRawDiskImpl(path, extractedPath, RawDiskImpl.open(extractedPath));
    }

    private static void copyBounded(@NotNull InputStream in, @NotNull OutputStream out,
                                    long maxDecompressedSize, @NotNull Path extractedPath,
                                    @NotNull Path sourcePath) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        int len;
        while ((len = in.read(buffer)) > 0) {
            if (written + len > maxDecompressedSize) {
                throw ResourceLimitException.decompressionBomb(maxDecompressedSize, written + len);
            }
            out.write(buffer, 0, len);
            written += len;
        }
    }

    private static void deleteQuietly(@NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best effort cleanup
        }
    }

    private GzipRawDiskImpl(Path sourcePath, Path extractedPath, RawDiskImpl innerDisk) {
        this.sourcePath = sourcePath;
        this.extractedPath = extractedPath;
        this.innerDisk = innerDisk;
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.RAW;
    }

    @Override
    public long virtualSize() {
        return innerDisk.virtualSize();
    }

    @Override
    public long allocatedSize() {
        try {
            return Files.size(sourcePath);
        } catch (IOException e) {
            return innerDisk.allocatedSize();
        }
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        return innerDisk.read(offset, length);
    }

    @Override
    public @NotNull InputStream openStream() throws IOException {
        return innerDisk.openStream();
    }

    @Override
    public @NotNull Map<String, String> metadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("gzip.sourcePath", sourcePath.toString());
        meta.put("gzip.extractedPath", extractedPath.toString());
        meta.putAll(innerDisk.metadata());
        return meta;
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            String name = sourcePath.getFileName().toString();
            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("size", String.valueOf(virtualSize()));
            qualifiers.put("format", "raw-gzip");

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
        return Optional.empty();
    }

    @Override
    public boolean isEncrypted() {
        return false;
    }

    @Override
    public boolean isCompressed() {
        return true;
    }

    @Override
    public int sectorSize() {
        return innerDisk.sectorSize();
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        return Stream.empty();
    }

    @Override
    public void close() throws IOException {
        innerDisk.close();
        try {
            Files.deleteIfExists(extractedPath);
            Files.deleteIfExists(extractedPath.getParent());
        } catch (IOException e) {
            // Best effort cleanup
        }
    }
}
