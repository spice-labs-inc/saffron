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
package io.spicelabs.saffron.filesystem.ubi;

import io.spicelabs.saffron.io.SafeMath;
import io.spicelabs.saffron.lvm.DiskRegion;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * UBI container detection and read-only attach.
 *
 * <p>Detection infers the PEB (physical erase block) size — UBI does not
 * record it — by requiring every non-erased PEB to start with a valid
 * erase-counter header ("UBI#"). Attach scans all PEBs, resolves the
 * LEB-to-PEB mapping (the highest sequence number wins per (volume,
 * logical block)), and parses the volume table from the layout volume.
 */
public final class UbiSuperblock {

    /** Candidate PEB sizes (UBI permits powers of two in this range). */
    private static final int[] PEB_SIZES = {2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576};

    private final DiskRegion region;
    private final long pebSize;
    private final long pebCount;
    private final long imageSeq;
    private final Map<Long, List<UbiVolume>> volumes;

    private UbiSuperblock(DiskRegion region, long pebSize, long pebCount,
                          long imageSeq, Map<Long, List<UbiVolume>> volumes) {
        this.region = region;
        this.pebSize = pebSize;
        this.pebCount = pebCount;
        this.imageSeq = imageSeq;
        this.volumes = volumes;
    }

    /** A volume mapping: logical block number to physical PEB. */
    public record UbiVolume(
            long volId,
            @NotNull String name,
            int volType,
            long lebSize,
            long dataOffset,
            long[] lnumToPeb) {
    }

    public long pebSize() {
        return pebSize;
    }

    public long pebCount() {
        return pebCount;
    }

    public long imageSeq() {
        return imageSeq;
    }

    public @NotNull Map<Long, List<UbiVolume>> volumes() {
        return volumes;
    }

    /** Returns all non-layout volumes, flattened. */
    public @NotNull List<UbiVolume> volumesFlat() {
        List<UbiVolume> out = new ArrayList<>();
        for (Map.Entry<Long, List<UbiVolume>> e : volumes.entrySet()) {
            if (e.getKey() != UbiNode.LAYOUT_VOLUME_ID) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }

    /**
     * Detects and attaches a UBI image from a region.
     *
     * @param region the candidate region
     * @return the attached UBI superblock, or empty if not UBI
     * @throws IOException if an I/O error occurs
     */
    public static @NotNull Optional<UbiSuperblock> attach(@NotNull DiskRegion region)
            throws IOException {
        long size = region.size();
        for (int pebSize : PEB_SIZES) {
            long pebCount = size / pebSize;
            // A truncated final PEB is tolerated (partial dumps); the scan
            // only visits complete PEBs.
            if (pebCount < 2) {
                continue;
            }
            Optional<UbiSuperblock> attached = tryAttach(region, pebSize, pebCount);
            if (attached.isPresent()) {
                return attached;
            }
        }
        return Optional.empty();
    }

    private static Optional<UbiSuperblock> tryAttach(DiskRegion region, long pebSize,
                                                     long pebCount) throws IOException {
        long imageSeq = -1;
        int validEc = 0;
        int erasedEc = 0;

        for (long p = 0; p < pebCount; p++) {
            byte[] ecBytes = UbiNode.readBytes(region, p * pebSize, UbiNode.EC_HDR_SIZE);
            UbiNode.EcHdr ec = UbiNode.EcHdr.parse(ecBytes);
            if (ec == null) {
                return Optional.empty(); // Corrupt EC header: not UBI.
            }
            if (ec.erased()) {
                erasedEc++;
                continue;
            }
            validEc++;
            if (ec.vidHdrOffset() < UbiNode.EC_HDR_SIZE
                    || ec.dataOffset() < ec.vidHdrOffset() + UbiNode.VID_HDR_SIZE
                    || ec.dataOffset() >= pebSize) {
                return Optional.empty();
            }
            if (imageSeq < 0) {
                imageSeq = ec.imageSeq();
            } else if (ec.imageSeq() != imageSeq) {
                return Optional.empty();
            }
        }
        if (validEc == 0 || imageSeq < 0) {
            return Optional.empty();
        }

        // Attach: map (volId, lnum) -> best PEB by sqnum.
        Map<Long, Map<Long, long[]>> best = new HashMap<>(); // volId -> lnum -> [peb, sqnum]
        Map<Long, UbiNode.VidHdr> vidByPeb = new HashMap<>();
        for (long p = 0; p < pebCount; p++) {
            byte[] ecBytes = UbiNode.readBytes(region, p * pebSize, UbiNode.EC_HDR_SIZE);
            UbiNode.EcHdr ec = UbiNode.EcHdr.parse(ecBytes);
            if (ec == null || ec.erased()) {
                continue;
            }
            byte[] vidBytes = UbiNode.readBytes(region,
                    SafeMath.safeAdd(p * pebSize, ec.vidHdrOffset()), UbiNode.VID_HDR_SIZE);
            UbiNode.VidHdr vid = UbiNode.VidHdr.parse(vidBytes);
            if (vid == null || vid.erased()) {
                continue;
            }
            vidByPeb.put(p, vid);
            Map<Long, long[]> byLnum = best.computeIfAbsent(vid.volId(), k -> new HashMap<>());
            long[] existing = byLnum.get(vid.lnum());
            if (existing == null || vid.sqnum() > existing[1]) {
                byLnum.put(vid.lnum(), new long[] {p, vid.sqnum()});
            }
        }

        // Volume table from the layout volume.
        Map<Long, List<UbiVolume>> volumes = new HashMap<>();
        Map<Long, long[]> layoutMap = best.get(UbiNode.LAYOUT_VOLUME_ID);
        if (layoutMap == null || layoutMap.isEmpty()) {
            return Optional.empty();
        }
        long[] layoutPebs = layoutMap.values().stream()
                .mapToLong(pe -> pe[0]).sorted().toArray();
        // The volume table lives at data_offset of the layout volume's first LEB.
        long vtblOffset = -1;
        for (long peb : layoutPebs) {
            byte[] ecBytes = UbiNode.readBytes(region, peb * pebSize, UbiNode.EC_HDR_SIZE);
            UbiNode.EcHdr ec = UbiNode.EcHdr.parse(ecBytes);
            if (ec != null && !ec.erased()) {
                vtblOffset = SafeMath.safeAdd(SafeMath.safeMultiply(peb, pebSize),
                        ec.dataOffset());
                break;
            }
        }
        if (vtblOffset < 0) {
            return Optional.empty();
        }
        Map<Long, UbiNode.VtblRecord> vtbl = new HashMap<>();
        long recordCount = Math.min(128, (pebSize - 0) / UbiNode.VTBL_RECORD_SIZE);
        for (long i = 0; i < recordCount; i++) {
            byte[] rec = UbiNode.readBytes(region,
                    SafeMath.safeAdd(vtblOffset, SafeMath.safeMultiply(i, UbiNode.VTBL_RECORD_SIZE)),
                    UbiNode.VTBL_RECORD_SIZE);
            UbiNode.VtblRecord r = UbiNode.VtblRecord.parse(rec);
            // A record with a bad CRC in a partial image is skipped rather
            // than failing the whole attach.
            if (r != null && !r.empty()) {
                vtbl.put(i, r);
            }
        }

        // Build volume records.
        for (Map.Entry<Long, UbiNode.VtblRecord> e : vtbl.entrySet()) {
            long volId = e.getKey();
            UbiNode.VtblRecord rec = e.getValue();
            Map<Long, long[]> byLnum = best.getOrDefault(volId, Map.of());
            long maxLnum = byLnum.keySet().stream().mapToLong(l -> l).max().orElse(0);
            // A hostile VID header can declare an enormous lnum; the array
            // must stay bounded by the image's PEB count.
            if (maxLnum < 0 || maxLnum >= pebCount || maxLnum > Integer.MAX_VALUE) {
                return Optional.empty();
            }
            long[] lnumToPeb = new long[(int) maxLnum + 1];
            java.util.Arrays.fill(lnumToPeb, -1);
            for (Map.Entry<Long, long[]> be : byLnum.entrySet()) {
                lnumToPeb[(int) be.getKey().longValue()] = be.getValue()[0];
            }
            long dataOffset = -1;
            long dataPad = rec.dataPad();
            for (long peb : lnumToPeb) {
                if (peb < 0) {
                    continue;
                }
                byte[] ecBytes = UbiNode.readBytes(region, peb * pebSize, UbiNode.EC_HDR_SIZE);
                UbiNode.EcHdr ec = UbiNode.EcHdr.parse(ecBytes);
                if (ec != null) {
                    dataOffset = ec.dataOffset();
                    break;
                }
            }
            if (dataOffset < 0) {
                continue;
            }
            long lebSize = SafeMath.safeSubtract(
                    SafeMath.safeSubtract(pebSize, dataOffset), dataPad);
            volumes.computeIfAbsent(volId, k -> new ArrayList<>())
                    .add(new UbiVolume(volId, rec.name(), rec.volType(), lebSize,
                            dataOffset, lnumToPeb));
        }

        return Optional.of(new UbiSuperblock(region, pebSize, pebCount, imageSeq, volumes));
    }

    /** Returns the attached region. */
    public @NotNull DiskRegion region() {
        return region;
    }
}
