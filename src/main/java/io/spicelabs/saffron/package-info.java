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

/**
 * Saffron - Pure Java library for reading virtual machine disk images.
 *
 * <h2>Overview</h2>
 * <p>Saffron provides a unified API for reading VM disk image formats and their
 * contained filesystems. It is designed for security scanning, forensic analysis,
 * and backup verification use cases.
 *
 * <h2>Supported Disk Formats</h2>
 * <ul>
 *   <li><b>QCOW2</b> - QEMU Copy-On-Write v2/v3 (read-only)</li>
 *   <li><b>VMDK</b> - VMware Virtual Machine Disk (read-only)</li>
 *   <li><b>VHD</b> - Microsoft Virtual Hard Disk legacy (read-only)</li>
 *   <li><b>VHDX</b> - Microsoft Virtual Hard Disk Extended (read-only)</li>
 *   <li><b>VDI</b> - VirtualBox Virtual Disk Image (read-only)</li>
 * </ul>
 *
 * <h2>Supported Filesystems</h2>
 * <ul>
 *   <li><b>ext4</b> - Linux Extended Filesystem 4</li>
 *   <li><b>NTFS</b> - Windows New Technology File System</li>
 *   <li><b>FAT32</b> - File Allocation Table 32</li>
 *   <li><b>XFS</b> - Linux XFS Filesystem</li>
 * </ul>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // Open a disk image with auto-detection
 * try (VirtualDisk disk = DiskReader.open(Path.of("image.qcow2"))) {
 *     System.out.println("Format: " + disk.format());
 *     System.out.println("Size: " + disk.virtualSize());
 *     System.out.println("pURL: " + disk.packageUrl());
 * }
 * }</pre>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link io.spicelabs.saffron.DiskReader} - Factory for opening disk images</li>
 *   <li>{@link io.spicelabs.saffron.VirtualDisk} - Represents an opened disk image</li>
 *   <li>{@link io.spicelabs.saffron.DiskFormat} - Enumeration of supported formats</li>
 *   <li>{@link io.spicelabs.saffron.SecurityPolicy} - Security configuration</li>
 * </ul>
 *
 * @see io.spicelabs.saffron.DiskReader
 * @see io.spicelabs.saffron.VirtualDisk
 */
package io.spicelabs.saffron;
