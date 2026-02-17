/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * QCOW2 disk image format support.
 *
 * <p>This package provides support for reading QEMU Copy-On-Write v2/v3 disk images.
 *
 * <ul>
 *   <li>{@link io.spicelabs.saffron.qcow2.Qcow2DiskImpl} - Main implementation</li>
 *   <li>{@link io.spicelabs.saffron.qcow2.header.Qcow2Header} - Header parsing</li>
 *   <li>{@link io.spicelabs.saffron.qcow2.cluster.ClusterReader} - L1/L2 table navigation</li>
 * </ul>
 *
 * @see <a href="https://github.com/qemu/qemu/blob/master/docs/interop/qcow2.txt">QCOW2 Specification</a>
 */
package io.spicelabs.saffron.qcow2;
