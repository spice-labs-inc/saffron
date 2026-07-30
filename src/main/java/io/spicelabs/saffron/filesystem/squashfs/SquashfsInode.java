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
package io.spicelabs.saffron.filesystem.squashfs;

public abstract sealed class SquashfsInode {

    public final int inodeType;
    public final int mode;
    public final int uidIndex;
    public final int gidIndex;
    public final long modifiedTime;
    public final long inodeNumber;
    public final long xattrIndex;

    SquashfsInode(int inodeType, int mode, int uidIndex, int gidIndex,
                  long modifiedTime, long inodeNumber, long xattrIndex) {
        this.inodeType = inodeType;
        this.mode = mode;
        this.uidIndex = uidIndex;
        this.gidIndex = gidIndex;
        this.modifiedTime = modifiedTime;
        this.inodeNumber = inodeNumber;
        this.xattrIndex = xattrIndex;
    }

    public boolean isDirectory() {
        return inodeType == 1 || inodeType == 8;
    }

    public boolean isRegularFile() {
        return inodeType == 2 || inodeType == 9;
    }

    public boolean isSymbolicLink() {
        return inodeType == 3 || inodeType == 10;
    }

    public boolean isSpecialFile() {
        return inodeType >= 4 && inodeType <= 7 || inodeType >= 11 && inodeType <= 14;
    }

    public static final class DirectoryInode extends SquashfsInode {
        public final long hardLinkCount;
        public final long fileSize;
        public final long dirBlockStart;
        public final long parentInodeNumber;
        public final int blockOffset;

        DirectoryInode(int inodeType, int mode, int uidIndex, int gidIndex,
                       long modifiedTime, long inodeNumber, long xattrIndex,
                       long hardLinkCount, long fileSize, long dirBlockStart,
                       long parentInodeNumber, int blockOffset) {
            super(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex);
            this.hardLinkCount = hardLinkCount;
            this.fileSize = fileSize;
            this.dirBlockStart = dirBlockStart;
            this.parentInodeNumber = parentInodeNumber;
            this.blockOffset = blockOffset;
        }
    }

    public static final class FileInode extends SquashfsInode {
        public final long blocksStart;
        public final long fileSize;
        public final long sparse;
        public final long hardLinkCount;
        public final int fragmentBlockIndex;
        public final int fragmentOffset;
        public final int[] blockSizes;

        FileInode(int inodeType, int mode, int uidIndex, int gidIndex,
                  long modifiedTime, long inodeNumber, long xattrIndex,
                  long blocksStart, long fileSize, long sparse, long hardLinkCount,
                  int fragmentBlockIndex, int fragmentOffset, int[] blockSizes) {
            super(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex);
            this.blocksStart = blocksStart;
            this.fileSize = fileSize;
            this.sparse = sparse;
            this.hardLinkCount = hardLinkCount;
            this.fragmentBlockIndex = fragmentBlockIndex;
            this.fragmentOffset = fragmentOffset;
            this.blockSizes = blockSizes;
        }

        public boolean hasFragment() {
            return fragmentBlockIndex != 0xffffffff;
        }
    }

    public static final class SymlinkInode extends SquashfsInode {
        public final long hardLinkCount;
        public final String target;

        SymlinkInode(int inodeType, int mode, int uidIndex, int gidIndex,
                     long modifiedTime, long inodeNumber, long xattrIndex,
                     long hardLinkCount, String target) {
            super(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex);
            this.hardLinkCount = hardLinkCount;
            this.target = target;
        }
    }

    public static final class SpecialInode extends SquashfsInode {
        public final long hardLinkCount;
        public final long device;

        SpecialInode(int inodeType, int mode, int uidIndex, int gidIndex,
                     long modifiedTime, long inodeNumber, long xattrIndex,
                     long hardLinkCount, long device) {
            super(inodeType, mode, uidIndex, gidIndex, modifiedTime, inodeNumber, xattrIndex);
            this.hardLinkCount = hardLinkCount;
            this.device = device;
        }
    }
}
