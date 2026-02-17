/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * VHD (Virtual Hard Disk) format support for Microsoft legacy virtual disk images.
 *
 * <p>The VHD format was introduced by Connectix (later acquired by Microsoft) and is
 * the predecessor to VHDX. It supports three disk types:
 * <ul>
 *   <li><b>Fixed</b>: Entire virtual disk space is allocated at creation</li>
 *   <li><b>Dynamic</b>: Disk grows as data is written (sparse allocation)</li>
 *   <li><b>Differencing</b>: Stores differences from a parent disk</li>
 * </ul>
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Footer at end of file (copy at beginning for dynamic/differencing)</li>
 *   <li>"conectix" magic signature</li>
 *   <li>2 TB maximum virtual disk size</li>
 *   <li>Block-based allocation for dynamic disks</li>
 * </ul>
 *
 * @see io.spicelabs.saffron.vhd.VhdDiskImpl
 * @see io.spicelabs.saffron.vhd.footer.VhdFooter
 */
package io.spicelabs.saffron.vhd;
