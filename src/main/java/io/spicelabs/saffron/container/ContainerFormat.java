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

import org.jetbrains.annotations.NotNull;

/**
 * Enumeration of supported binary container formats.
 *
 * <p>Values are added incrementally by phase. Unknown or unsupported containers
 * are represented by {@link #UNKNOWN} rather than {@code null}.</p>
 */
public enum ContainerFormat {
    LINUX_KERNEL("linux_kernel", "Linux kernel image (bzImage, zImage, Image, uImage)"),
    FIT_IMAGE("fit_image", "U-Boot Flattened Image Tree"),
    DTB("dtb", "Device tree blob"),
    ELF("elf", "Executable and Linkable Format"),
    RPI_FIRMWARE("rpi_firmware", "Raspberry Pi firmware blob"),
    ANDROID_BOOT("android_boot", "Android boot image"),
    COMPRESSED_SINGLE("compressed_single", "Single compressed non-archive payload (gzip, xz, bzip2)"),
    WIM("wim", "Windows Imaging Format"),
    DMG("dmg", "Apple disk image"),
    UNKNOWN("unknown", "Unknown binary container");

    private final String name;
    private final String description;

    ContainerFormat(@NotNull String name, @NotNull String description) {
        this.name = name;
        this.description = description;
    }

    public @NotNull String getName() {
        return name;
    }

    public @NotNull String getDescription() {
        return description;
    }
}
