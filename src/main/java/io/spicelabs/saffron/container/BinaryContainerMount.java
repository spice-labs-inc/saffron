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
package io.spicelabs.saffron.container;

import io.spicelabs.saffron.SecurityPolicy;
import io.spicelabs.saffron.VirtualDisk;
import io.spicelabs.saffron.container.android.AndroidBootContainer;
import io.spicelabs.saffron.container.compressed.CompressedSingleContainerFactory;
import io.spicelabs.saffron.container.dmg.DmgContainer;
import io.spicelabs.saffron.container.dtb.DtbContainer;
import io.spicelabs.saffron.container.elf.ElfContainer;
import io.spicelabs.saffron.container.fit.FitContainer;
import io.spicelabs.saffron.container.linuxkernel.LinuxKernelContainerFactory;
import io.spicelabs.saffron.container.rpi.RpiFirmwareContainer;
import io.spicelabs.saffron.container.wim.WimContainer;
import io.spicelabs.saffron.fs.FileSystem;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Mounts binary containers as filesystems.
 */
public final class BinaryContainerMount {

    private BinaryContainerMount() {
        // Static utility class
    }

    /**
     * Attempts to mount a binary container from a file path using the default
     * security policy.
     *
     * @param path the path to examine
     * @return a filesystem if a supported container is detected, or empty
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FileSystem> mount(@NotNull Path path) throws IOException {
        return mount(path, SecurityPolicy.defaults());
    }

    /**
     * Attempts to mount a binary container from a file path.
     *
     * @param path the path to examine
     * @param policy the security policy governing decompression limits
     * @return a filesystem if a supported container is detected, or empty
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FileSystem> mount(@NotNull Path path,
                                                      @NotNull SecurityPolicy policy) throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(path);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        return mountByFormat(path, format.get(), policy);
    }

    /**
     * Attempts to mount a binary container from a byte buffer using the default
     * security policy.
     *
     * @param buffer the buffer to examine; position must be 0
     * @return a filesystem if a supported container is detected, or empty
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FileSystem> mount(@NotNull ByteBuffer buffer) throws IOException {
        return mount(buffer, SecurityPolicy.defaults());
    }

    /**
     * Attempts to mount a binary container from a byte buffer.
     *
     * @param buffer the buffer to examine; position must be 0
     * @param policy the security policy governing decompression limits
     * @return a filesystem if a supported container is detected, or empty
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FileSystem> mount(@NotNull ByteBuffer buffer,
                                                      @NotNull SecurityPolicy policy) throws IOException {
        long sourceSize = buffer.remaining();
        Optional<ContainerFormat> format = ContainerDetector.detect(buffer);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        return mountByFormat(buffer, sourceSize, format.get(), policy);
    }

    /**
     * Attempts to mount a binary container from a virtual disk using the default
     * security policy.
     *
     * @param disk the virtual disk to examine
     * @return a filesystem if a supported container is detected, or empty
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FileSystem> mount(@NotNull VirtualDisk disk) throws IOException {
        return mount(disk, SecurityPolicy.defaults());
    }

    /**
     * Attempts to mount a binary container from a virtual disk.
     *
     * @param disk the virtual disk to examine
     * @param policy the security policy governing decompression limits
     * @return a filesystem if a supported container is detected, or empty
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<FileSystem> mount(@NotNull VirtualDisk disk,
                                                      @NotNull SecurityPolicy policy) throws IOException {
        Optional<ContainerFormat> format = ContainerDetector.detect(disk);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        return mountByFormat(disk, format.get(), policy);
    }

    private static @NotNull Optional<FileSystem> mountByFormat(@NotNull Path path,
                                                                @NotNull ContainerFormat format,
                                                                @NotNull SecurityPolicy policy) throws IOException {
        return switch (format) {
            case FIT_IMAGE -> FitContainer.open(path)
                    .map(BinaryContainerFileSystemImpl::mount);
            case ELF -> ElfContainer.open(path)
                    .map(BinaryContainerFileSystemImpl::mount);
            case DTB -> DtbContainer.open(path)
                    .map(BinaryContainerFileSystemImpl::mount);
            case RPI_FIRMWARE -> RpiFirmwareContainer.open(path)
                    .map(BinaryContainerFileSystemImpl::mount);
            case ANDROID_BOOT -> AndroidBootContainer.open(path)
                    .map(BinaryContainerFileSystemImpl::mount);
            case COMPRESSED_SINGLE -> CompressedSingleContainerFactory.open(path, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            case WIM -> WimContainer.open(path, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            case DMG -> DmgContainer.open(path, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            default -> Optional.empty();
        };
    }

    private static @NotNull Optional<FileSystem> mountByFormat(@NotNull ByteBuffer buffer,
                                                                long sourceSize,
                                                                @NotNull ContainerFormat format,
                                                                @NotNull SecurityPolicy policy) {
        return switch (format) {
            case ELF -> ElfContainer.open(buffer, sourceSize)
                    .map(BinaryContainerFileSystemImpl::mount);
            case DTB -> DtbContainer.open(buffer, sourceSize)
                    .map(BinaryContainerFileSystemImpl::mount);
            case RPI_FIRMWARE -> RpiFirmwareContainer.open(buffer, sourceSize)
                    .map(BinaryContainerFileSystemImpl::mount);
            case ANDROID_BOOT -> AndroidBootContainer.open(buffer, sourceSize)
                    .map(BinaryContainerFileSystemImpl::mount);
            case COMPRESSED_SINGLE -> openCompressedSingle(buffer, sourceSize, policy);
            case WIM -> WimContainer.open(buffer, sourceSize, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            case DMG -> DmgContainer.open(buffer, sourceSize, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            default -> Optional.empty();
        };
    }

    private static @NotNull Optional<FileSystem> mountByFormat(@NotNull VirtualDisk disk,
                                                                @NotNull ContainerFormat format,
                                                                @NotNull SecurityPolicy policy) throws IOException {
        return switch (format) {
            case LINUX_KERNEL -> LinuxKernelContainerFactory.open(disk)
                    .map(BinaryContainerFileSystemImpl::mount);
            case FIT_IMAGE -> FitContainer.open(disk)
                    .map(BinaryContainerFileSystemImpl::mount);
            case ELF -> ElfContainer.open(disk)
                    .map(BinaryContainerFileSystemImpl::mount);
            case DTB -> DtbContainer.open(disk)
                    .map(BinaryContainerFileSystemImpl::mount);
            case RPI_FIRMWARE -> RpiFirmwareContainer.open(disk)
                    .map(BinaryContainerFileSystemImpl::mount);
            case ANDROID_BOOT -> AndroidBootContainer.open(disk)
                    .map(BinaryContainerFileSystemImpl::mount);
            case COMPRESSED_SINGLE -> CompressedSingleContainerFactory.open(disk, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            case WIM -> WimContainer.open(disk, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            case DMG -> DmgContainer.open(disk, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
            case UNKNOWN -> Optional.empty();
        };
    }

    private static @NotNull Optional<FileSystem> openCompressedSingle(@NotNull ByteBuffer buffer,
                                                                      long sourceSize,
                                                                      @NotNull SecurityPolicy policy) {
        try {
            return CompressedSingleContainerFactory.open(buffer, sourceSize, policy)
                    .map(BinaryContainerFileSystemImpl::mount);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
