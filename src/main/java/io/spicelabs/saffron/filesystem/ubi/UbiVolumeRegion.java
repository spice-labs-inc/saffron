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
import java.nio.ByteBuffer;

/**
 * A read-only {@link DiskRegion} over one UBI volume: logical byte offsets
 * map to (logical eraseblock, offset within LEB) and then to the mapped
 * physical eraseblock, skipping the EC/VID header area.
 */
public final class UbiVolumeRegion implements DiskRegion {

    private final UbiSuperblock superblock;
    private final UbiSuperblock.UbiVolume volume;

    UbiVolumeRegion(UbiSuperblock superblock, UbiSuperblock.UbiVolume volume) {
        this.superblock = superblock;
        this.volume = volume;
    }

    public static @NotNull UbiVolumeRegion of(@NotNull UbiSuperblock superblock,
                                              @NotNull UbiSuperblock.UbiVolume volume) {
        return new UbiVolumeRegion(superblock, volume);
    }

    public @NotNull UbiSuperblock.UbiVolume volume() {
        return volume;
    }

    @Override
    public @NotNull ByteBuffer read(long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > size()) {
            throw new IOException("UbiVolumeRegion read out of bounds: offset=" + offset
                    + " length=" + length + " size=" + size());
        }
        ByteBuffer out = ByteBuffer.allocate(length);
        long remaining = length;
        long pos = offset;
        while (remaining > 0) {
            long lnum = pos / volume.lebSize();
            long within = pos % volume.lebSize();
            int chunk = (int) Math.min(remaining, volume.lebSize() - within);
            long peb = lnum < volume.lnumToPeb().length ? volume.lnumToPeb()[(int) lnum] : -1;
            if (peb < 0) {
                // Unmapped LEB: reads as zeros.
                byte[] zeros = new byte[chunk];
                out.put(zeros);
            } else {
                long abs = SafeMath.safeAdd(SafeMath.safeAdd(
                        SafeMath.safeMultiply(peb, superblock.pebSize()),
                        volume.dataOffset()), within);
                ByteBuffer buf = superblock.region().read(abs, chunk);
                out.put(buf);
            }
            pos += chunk;
            remaining -= chunk;
        }
        out.flip();
        return out;
    }

    @Override
    public long size() {
        return SafeMath.safeMultiply(volume.lnumToPeb().length, volume.lebSize());
    }
}
