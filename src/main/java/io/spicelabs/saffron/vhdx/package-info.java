/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

/**
 * VHDX (Virtual Hard Disk Extended) format support for Microsoft virtual disk images.
 *
 * <p>VHDX is the successor to VHD, introduced in Windows Server 2012, with
 * significant improvements:
 * <ul>
 *   <li>64 TB maximum virtual disk size (vs 2 TB for VHD)</li>
 *   <li>4 KB logical sector size support</li>
 *   <li>Improved corruption protection with log-based metadata</li>
 *   <li>Block sizes up to 256 MB</li>
 *   <li>Built-in alignment for large sector drives</li>
 * </ul>
 *
 * <p>VHDX file structure:
 * <ul>
 *   <li>File Type Identifier (1 MB region)</li>
 *   <li>Header 1 and Header 2 (64 KB each)</li>
 *   <li>Region Tables (two copies)</li>
 *   <li>Log Region</li>
 *   <li>Metadata Region</li>
 *   <li>BAT Region (Block Allocation Table)</li>
 *   <li>Data Region</li>
 * </ul>
 *
 * @see io.spicelabs.saffron.vhdx.VhdxDiskImpl
 * @see io.spicelabs.saffron.vhdx.header.VhdxHeader
 */
package io.spicelabs.saffron.vhdx;
