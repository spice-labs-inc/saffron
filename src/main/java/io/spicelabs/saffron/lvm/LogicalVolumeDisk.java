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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.lvm;

import io.spicelabs.saffron.VirtualDisk;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Exposes an LVM Logical Volume as a readable disk region.
 *
 * <p>This class handles the mapping from logical volume offsets to physical
 * extents on the underlying Physical Volume(s). It supports:
 * <ul>
 *   <li>Linear (striped with stripe_count=1) segments</li>
 *   <li>Multiple segments within a single LV</li>
 *   <li>Reads that span segment boundaries</li>
 * </ul>
 *
 * <p>Striped volumes with stripe_count > 1 are not currently supported.
 */
public class LogicalVolumeDisk implements DiskRegion {

    private final VirtualDisk underlyingDisk;
    private final long partitionOffset;
    private final LvmMetadata metadata;
    private final LvmMetadata.LogicalVolume logicalVolume;
    private final long extentSizeBytes;
    private final long totalSizeBytes;

    // Cached segment mappings for efficient lookup
    private final List<SegmentMapping> segmentMappings;

    /**
     * Represents a pre-computed segment mapping for efficient reads.
     */
    private record SegmentMapping(
            long lvStartByte,     // Start byte offset in the LV
            long lvEndByte,       // End byte offset in the LV (exclusive)
            long pvStartByte      // Start byte offset on the PV (relative to partition)
    ) {}

    /**
     * Creates a LogicalVolumeDisk for the specified logical volume.
     *
     * @param underlyingDisk the virtual disk containing the LVM PV
     * @param partitionOffset the offset where the LVM partition starts
     * @param metadata the parsed LVM metadata
     * @param logicalVolume the logical volume to expose
     */
    public LogicalVolumeDisk(@NotNull VirtualDisk underlyingDisk,
                             long partitionOffset,
                             @NotNull LvmMetadata metadata,
                             @NotNull LvmMetadata.LogicalVolume logicalVolume) {
        this.underlyingDisk = underlyingDisk;
        this.partitionOffset = partitionOffset;
        this.metadata = metadata;
        this.logicalVolume = logicalVolume;
        this.extentSizeBytes = metadata.extentSizeBytes();
        this.totalSizeBytes = logicalVolume.sizeInExtents() * extentSizeBytes;
        this.segmentMappings = buildSegmentMappings();
    }

    /**
     * Builds pre-computed segment mappings for efficient read operations.
     */
    private List<SegmentMapping> buildSegmentMappings() {
        List<SegmentMapping> mappings = new ArrayList<>();

        for (LvmMetadata.Segment segment : logicalVolume.segments()) {
            if (segment.stripes().isEmpty()) {
                continue;
            }

            // For linear segments, there's only one stripe
            LvmMetadata.Stripe stripe = segment.stripes().get(0);

            // Find the PV for this stripe
            Optional<LvmMetadata.PhysicalVolume> pvOpt = metadata.findPhysicalVolume(stripe.pvName());
            if (pvOpt.isEmpty()) {
                continue;
            }

            LvmMetadata.PhysicalVolume pv = pvOpt.get();

            // Calculate byte offsets
            long lvStartByte = segment.startExtent() * extentSizeBytes;
            long lvEndByte = lvStartByte + (segment.extentCount() * extentSizeBytes);

            // PV start includes pe_start (offset to first physical extent) plus stripe's start extent
            long pvStartByte = (pv.peStart() * 512) + (stripe.startExtent() * extentSizeBytes);

            mappings.add(new SegmentMapping(lvStartByte, lvEndByte, pvStartByte));
        }

        // Sort by LV start byte for binary search
        mappings.sort(Comparator.comparingLong(SegmentMapping::lvStartByte));

        return mappings;
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Offset and length must be non-negative");
        }
        if (offset + length > totalSizeBytes) {
            throw new IllegalArgumentException(
                    String.format("Read extends beyond LV size: offset=%d, length=%d, size=%d",
                            offset, length, totalSizeBytes));
        }

        ByteBuffer result = ByteBuffer.allocate(length);
        result.order(ByteOrder.LITTLE_ENDIAN);

        long remaining = length;
        long currentOffset = offset;
        int resultPosition = 0;

        while (remaining > 0) {
            // Find the segment containing currentOffset
            SegmentMapping mapping = findSegmentMapping(currentOffset);
            if (mapping == null) {
                throw new IOException("No segment mapping found for offset: " + currentOffset);
            }

            // Calculate how much we can read from this segment
            long offsetInSegment = currentOffset - mapping.lvStartByte();
            long availableInSegment = mapping.lvEndByte() - currentOffset;
            int toRead = (int) Math.min(remaining, availableInSegment);

            // Calculate physical offset
            long physicalOffset = partitionOffset + mapping.pvStartByte() + offsetInSegment;

            // Read from underlying disk
            ByteBuffer segmentData = underlyingDisk.read(physicalOffset, toRead);

            // Copy to result
            segmentData.rewind();
            result.position(resultPosition);
            result.put(segmentData);

            remaining -= toRead;
            currentOffset += toRead;
            resultPosition += toRead;
        }

        result.rewind();
        return result;
    }

    /**
     * Finds the segment mapping containing the given LV offset.
     */
    private SegmentMapping findSegmentMapping(long lvOffset) {
        // Binary search for the segment
        int low = 0;
        int high = segmentMappings.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            SegmentMapping mapping = segmentMappings.get(mid);

            if (lvOffset < mapping.lvStartByte()) {
                high = mid - 1;
            } else if (lvOffset >= mapping.lvEndByte()) {
                low = mid + 1;
            } else {
                return mapping;
            }
        }

        return null;
    }

    @Override
    public long size() {
        return totalSizeBytes;
    }

    /**
     * Returns the logical volume name.
     */
    public @NotNull String name() {
        return logicalVolume.name();
    }

    /**
     * Returns the logical volume UUID.
     */
    public @NotNull String uuid() {
        return logicalVolume.uuid();
    }

    /**
     * Returns the volume group name.
     */
    public @NotNull String volumeGroupName() {
        return metadata.vgName();
    }

    /**
     * Returns the extent size in bytes.
     */
    public long extentSizeBytes() {
        return extentSizeBytes;
    }

    /**
     * Returns the number of segments in this logical volume.
     */
    public int segmentCount() {
        return logicalVolume.segments().size();
    }

    @Override
    public String toString() {
        return String.format("LogicalVolumeDisk[vg=%s, lv=%s, size=%d bytes, segments=%d]",
                metadata.vgName(), logicalVolume.name(), totalSizeBytes, segmentCount());
    }
}
