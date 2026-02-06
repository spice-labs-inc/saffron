/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

/**
 * Partition table support for virtual disk images.
 *
 * <p>This package provides classes for detecting and parsing partition
 * tables from virtual disk images. Both MBR (Master Boot Record) and
 * GPT (GUID Partition Table) schemes are supported.
 *
 * <h2>Supported partition schemes:</h2>
 * <ul>
 *   <li><b>MBR</b> - Legacy partition table (up to 4 primary partitions,
 *       or 3 primary + extended with logical partitions)</li>
 *   <li><b>GPT</b> - Modern GUID-based partition table (up to 128+ partitions)</li>
 * </ul>
 *
 * <h2>Example usage:</h2>
 * <pre>{@code
 * try (VirtualDisk disk = DiskReader.open(path)) {
 *     Optional<PartitionTable> table = PartitionTable.detect(disk);
 *
 *     if (table.isPresent()) {
 *         System.out.println("Partition table type: " + table.get().type());
 *         System.out.println("Disk signature: " + table.get().diskSignature());
 *
 *         for (Partition partition : table.get().partitions()) {
 *             System.out.printf("Partition %d: %s (%d - %d)%n",
 *                 partition.index(),
 *                 partition.typeName(),
 *                 partition.startLba(),
 *                 partition.endLba());
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see io.spicelabs.saffron.partition.PartitionTable
 * @see io.spicelabs.saffron.partition.MbrPartitionTable
 * @see io.spicelabs.saffron.partition.GptPartitionTable
 */
package io.spicelabs.saffron.partition;
