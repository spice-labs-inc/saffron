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
package io.spicelabs.saffron.gcp;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.saffron.DiskFormat;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.raw.RawDiskImpl;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Implementation of {@link VirtualDisk.GcpDisk} for Google Cloud Platform disk images.
 *
 * <p>GCP images are tar.gz archives containing a file named "disk.raw". This implementation
 * extracts the raw disk to a temporary file and delegates to {@link RawDiskImpl}.
 */
public final class GcpDiskImpl implements VirtualDisk.GcpDisk {

    private final Path sourcePath;
    private final Path extractedPath;
    private final RawDiskImpl innerDisk;
    private final boolean ownsExtractedFile;

    /**
     * Opens a GCP disk image from a tar.gz file.
     *
     * @param path the path to the .tar.gz file
     * @return the opened disk
     * @throws IOException if an I/O error occurs or the archive doesn't contain disk.raw
     */
    public static @NotNull GcpDiskImpl open(@NotNull Path path) throws IOException {
        // Extract disk.raw to a temporary file
        Path tempDir = Files.createTempDirectory("saffron-gcp-");
        Path extractedPath = tempDir.resolve("disk.raw");

        try (InputStream fis = Files.newInputStream(path);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

            TarArchiveEntry entry;
            boolean found = false;

            while ((entry = tais.getNextTarEntry()) != null) {
                String name = entry.getName();
                // GCP requires the file to be named disk.raw
                if (name.equals("disk.raw") || name.endsWith("/disk.raw")) {
                    // Extract this entry
                    try (OutputStream out = Files.newOutputStream(extractedPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = tais.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Clean up temp directory
                Files.deleteIfExists(extractedPath);
                Files.deleteIfExists(tempDir);
                throw new IOException("GCP archive does not contain disk.raw: " + path);
            }
        } catch (Exception e) {
            // Clean up on error
            Files.deleteIfExists(extractedPath);
            Files.deleteIfExists(tempDir);
            throw e;
        }

        // Open the extracted raw disk
        RawDiskImpl innerDisk = RawDiskImpl.open(extractedPath);
        return new GcpDiskImpl(path, extractedPath, innerDisk, true);
    }

    private GcpDiskImpl(Path sourcePath, Path extractedPath, RawDiskImpl innerDisk, boolean ownsExtractedFile) {
        this.sourcePath = sourcePath;
        this.extractedPath = extractedPath;
        this.innerDisk = innerDisk;
        this.ownsExtractedFile = ownsExtractedFile;
    }

    @Override
    public @NotNull DiskFormat format() {
        return DiskFormat.GCP;
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
        meta.put("gcp.sourcePath", sourcePath.toString());
        meta.put("gcp.extractedPath", extractedPath.toString());
        meta.putAll(innerDisk.metadata());
        return meta;
    }

    @Override
    public @NotNull PackageURL packageUrl() {
        try {
            String name = sourcePath.getFileName().toString();
            // Remove .tar.gz extension
            if (name.endsWith(".tar.gz")) {
                name = name.substring(0, name.length() - 7);
            }

            TreeMap<String, String> qualifiers = new TreeMap<>();
            qualifiers.put("size", String.valueOf(virtualSize()));
            qualifiers.put("format", "gcp");

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
        return true; // The source is gzip compressed
    }

    @Override
    public @NotNull Stream<Snapshot> snapshots() {
        return Stream.empty();
    }

    @Override
    public @NotNull RawDisk innerDisk() {
        return innerDisk;
    }

    @Override
    public void close() throws IOException {
        innerDisk.close();

        // Clean up extracted file if we own it
        if (ownsExtractedFile) {
            try {
                Files.deleteIfExists(extractedPath);
                Files.deleteIfExists(extractedPath.getParent());
            } catch (IOException e) {
                // Best effort cleanup
            }
        }
    }
}
