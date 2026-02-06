/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

/**
 * VDI (VirtualBox Disk Image) format support.
 *
 * <p>Oracle VirtualBox's native disk image format.
 *
 * <h2>Key characteristics:</h2>
 * <ul>
 *   <li>Simple block-based sparse format</li>
 *   <li>Little-endian byte order</li>
 *   <li>Block Allocation Map (BAM) for sparse allocation</li>
 *   <li>Supports dynamic and fixed disk types</li>
 *   <li>Supports differencing images</li>
 * </ul>
 *
 * <h2>File structure:</h2>
 * <ol>
 *   <li>Text preamble ("<<< Oracle VM VirtualBox Disk Image >>>")</li>
 *   <li>Header (starting at offset 0x40)</li>
 *   <li>Block Allocation Map (BAM)</li>
 *   <li>Data blocks</li>
 * </ol>
 *
 * @see io.spicelabs.saffron.vdi.VdiDiskImpl
 * @see io.spicelabs.saffron.vdi.header.VdiHeader
 */
package io.spicelabs.saffron.vdi;
