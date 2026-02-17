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
package io.spicelabs.saffron;

import io.spicelabs.saffron.ami.AmiDiskImpl;
import io.spicelabs.saffron.exception.SaffronException;
import io.spicelabs.saffron.gcp.GcpDiskImpl;
import io.spicelabs.saffron.qcow2.Qcow2DiskImpl;
import io.spicelabs.saffron.raw.RawDiskImpl;
import io.spicelabs.saffron.vdi.VdiDiskImpl;
import io.spicelabs.saffron.vhd.VhdDiskImpl;
import io.spicelabs.saffron.vhdx.VhdxDiskImpl;
import io.spicelabs.saffron.vmdk.VmdkDiskImpl;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Factory for opening virtual machine disk images.
 *
 * <p>This class follows Baharat's {@code PackageReader} pattern, providing
 * static factory methods for opening disk images with automatic format detection.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Auto-detect format
 * try (VirtualDisk disk = DiskReader.open(path)) {
 *     // work with disk
 * }
 *
 * // Explicit format
 * try (VirtualDisk disk = DiskReader.open(path, DiskFormat.QCOW2)) {
 *     // work with QCOW2 disk
 * }
 * }</pre>
 *
 * @see VirtualDisk
 * @see DiskFormat
 */
public final class DiskReader {

    private DiskReader() {
        // Static factory class - no instantiation
    }

    /**
     * Opens a disk image file with automatic format detection.
     *
     * <p>The format is detected by examining magic bytes in the file header.
     * If magic-based detection fails, the file extension is used as a fallback.
     *
     * @param path the path to the disk image file
     * @return the opened VirtualDisk
     * @throws IOException if an I/O error occurs
     * @throws SaffronException.InvalidDiskException if the file is not a valid disk image
     * @throws SaffronException.UnsupportedDiskException if the format is not supported
     */
    public static @NotNull VirtualDisk open(@NotNull Path path) throws IOException {
        Optional<DiskFormat> format = DiskFormat.detect(path);
        if (format.isEmpty()) {
            throw new SaffronException.UnsupportedDiskException(
                    "Unable to detect disk format: " + path);
        }
        return open(path, format.get());
    }

    /**
     * Opens a disk image file with the specified format.
     *
     * <p>Use this method when you know the format ahead of time, or when
     * automatic detection fails.
     *
     * @param path the path to the disk image file
     * @param format the expected disk format
     * @return the opened VirtualDisk
     * @throws IOException if an I/O error occurs
     * @throws SaffronException.InvalidDiskException if the file is not a valid disk image
     * @throws SaffronException.UnsupportedDiskException if the format version is not supported
     */
    public static @NotNull VirtualDisk open(@NotNull Path path, @NotNull DiskFormat format) throws IOException {
        return switch (format) {
            case QCOW2 -> openQcow2(path);
            case VMDK -> openVmdk(path);
            case VHD -> openVhd(path);
            case VHDX -> openVhdx(path);
            case VDI -> openVdi(path);
            case RAW -> openRaw(path);
            case GCP -> openGcp(path);
            case AMI -> openAmi(path);
        };
    }

    /**
     * Opens a disk image from an InputStream with automatic format detection.
     *
     * <p>Note: The stream must support mark/reset for format detection,
     * or be wrapped in a BufferedInputStream.
     *
     * @param stream the input stream containing the disk image
     * @param sourceName a name for the source (used in error messages and pURL)
     * @return the opened VirtualDisk
     * @throws IOException if an I/O error occurs
     * @throws SaffronException.InvalidDiskException if the stream is not a valid disk image
     * @throws SaffronException.UnsupportedDiskException if the format is not supported
     */
    public static @NotNull VirtualDisk open(@NotNull InputStream stream, @NotNull String sourceName)
            throws IOException {
        // Implementation will buffer and detect format from magic bytes
        throw new UnsupportedOperationException("InputStream support not yet implemented");
    }

    /**
     * Opens a disk image from an InputStream with the specified format.
     *
     * @param stream the input stream containing the disk image
     * @param sourceName a name for the source (used in error messages and pURL)
     * @param format the expected disk format
     * @return the opened VirtualDisk
     * @throws IOException if an I/O error occurs
     * @throws SaffronException.InvalidDiskException if the stream is not a valid disk image
     */
    public static @NotNull VirtualDisk open(@NotNull InputStream stream, @NotNull String sourceName,
                                            @NotNull DiskFormat format) throws IOException {
        throw new UnsupportedOperationException("InputStream support not yet implemented");
    }

    /**
     * Checks if a file appears to be a supported disk image.
     *
     * <p>This performs a quick check based on magic bytes without fully
     * parsing the disk structure.
     *
     * @param path the path to check
     * @return true if the file appears to be a supported disk image
     */
    public static boolean isSupported(@NotNull Path path) {
        try {
            return DiskFormat.detect(path).isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    // ========================================================================
    // Format-specific openers (internal)
    // ========================================================================

    private static VirtualDisk openQcow2(Path path) throws IOException {
        return Qcow2DiskImpl.open(path);
    }

    private static VirtualDisk openVmdk(Path path) throws IOException {
        return VmdkDiskImpl.open(path);
    }

    private static VirtualDisk openVhd(Path path) throws IOException {
        return VhdDiskImpl.open(path);
    }

    private static VirtualDisk openVhdx(Path path) throws IOException {
        return VhdxDiskImpl.open(path);
    }

    private static VirtualDisk openVdi(Path path) throws IOException {
        return VdiDiskImpl.open(path);
    }

    private static VirtualDisk openRaw(Path path) throws IOException {
        return RawDiskImpl.open(path);
    }

    private static VirtualDisk openGcp(Path path) throws IOException {
        return GcpDiskImpl.open(path);
    }

    private static VirtualDisk openAmi(Path path) throws IOException {
        return AmiDiskImpl.open(path);
    }
}
