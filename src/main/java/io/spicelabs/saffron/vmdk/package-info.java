/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * VMDK (VMware Virtual Machine Disk) format support.
 *
 * <p>VMware's virtual disk format used by VMware Workstation, Fusion,
 * ESXi, and other VMware products.
 *
 * <h2>Supported VMDK types:</h2>
 * <ul>
 *   <li>monolithicSparse - Single sparse file</li>
 *   <li>streamOptimized - Stream-optimized for OVA/OVF</li>
 *   <li>twoGbMaxExtentSparse - Split sparse (partial support)</li>
 * </ul>
 *
 * <h2>File structure:</h2>
 * <ol>
 *   <li>Sparse extent header (512 bytes, "KDMV" magic)</li>
 *   <li>Embedded descriptor (optional)</li>
 *   <li>Grain directory</li>
 *   <li>Grain tables</li>
 *   <li>Grain data (optionally compressed)</li>
 * </ol>
 *
 * @see io.spicelabs.saffron.vmdk.VmdkDiskImpl
 * @see io.spicelabs.saffron.vmdk.sparse.SparseExtentHeader
 * @see io.spicelabs.saffron.vmdk.descriptor.VmdkDescriptor
 */
package io.spicelabs.saffron.vmdk;
