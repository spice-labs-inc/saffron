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
package io.spicelabs.saffron;

import org.jetbrains.annotations.NotNull;

/**
 * Security policy configuration for Saffron operations.
 *
 * <p>This class provides configurable limits to protect against various
 * attack vectors including decompression bombs, integer overflows, and
 * resource exhaustion.
 *
 * <p>Use {@link #defaults()} for sensible defaults, or use the builder
 * to customize limits for specific use cases.
 *
 * <p>Example usage:
 * <pre>{@code
 * SecurityPolicy policy = SecurityPolicy.builder()
 *     .maxDecompressedSize(1024 * 1024 * 1024)  // 1 GB
 *     .maxAllocationSize(256 * 1024 * 1024)      // 256 MB
 *     .build();
 *
 * try (VirtualDisk disk = DiskReader.open(path, policy)) {
 *     // work with disk
 * }
 * }</pre>
 */
public record SecurityPolicy(
        long maxDecompressedSize,
        long maxAllocationSize,
        int maxClusterSize,
        long maxVirtualDiskSize,
        int maxPathDepth,
        int maxSymlinkDepth,
        boolean validateChecksums,
        boolean rejectBidiChars,
        boolean rejectZeroWidthChars
) {

    /**
     * Default maximum decompressed size: 16 GB
     */
    public static final long DEFAULT_MAX_DECOMPRESSED_SIZE = 16L * 1024 * 1024 * 1024;

    /**
     * Default maximum single allocation: 256 MB
     */
    public static final long DEFAULT_MAX_ALLOCATION_SIZE = 256L * 1024 * 1024;

    /**
     * Default maximum cluster size: 2 MB
     */
    public static final int DEFAULT_MAX_CLUSTER_SIZE = 2 * 1024 * 1024;

    /**
     * Default maximum virtual disk size: 64 TB
     */
    public static final long DEFAULT_MAX_VIRTUAL_DISK_SIZE = 64L * 1024 * 1024 * 1024 * 1024;

    /**
     * Default maximum path depth: 256 components
     */
    public static final int DEFAULT_MAX_PATH_DEPTH = 256;

    /**
     * Default maximum symlink resolution depth: 40
     */
    public static final int DEFAULT_MAX_SYMLINK_DEPTH = 40;

    /**
     * Compact constructor with validation.
     */
    public SecurityPolicy {
        if (maxDecompressedSize <= 0) {
            throw new IllegalArgumentException("maxDecompressedSize must be positive");
        }
        if (maxAllocationSize <= 0) {
            throw new IllegalArgumentException("maxAllocationSize must be positive");
        }
        if (maxClusterSize <= 0) {
            throw new IllegalArgumentException("maxClusterSize must be positive");
        }
        if (maxVirtualDiskSize <= 0) {
            throw new IllegalArgumentException("maxVirtualDiskSize must be positive");
        }
        if (maxPathDepth <= 0) {
            throw new IllegalArgumentException("maxPathDepth must be positive");
        }
        if (maxSymlinkDepth <= 0) {
            throw new IllegalArgumentException("maxSymlinkDepth must be positive");
        }
    }

    /**
     * Returns the default security policy with sensible limits.
     *
     * @return the default policy
     */
    public static @NotNull SecurityPolicy defaults() {
        return new SecurityPolicy(
                DEFAULT_MAX_DECOMPRESSED_SIZE,
                DEFAULT_MAX_ALLOCATION_SIZE,
                DEFAULT_MAX_CLUSTER_SIZE,
                DEFAULT_MAX_VIRTUAL_DISK_SIZE,
                DEFAULT_MAX_PATH_DEPTH,
                DEFAULT_MAX_SYMLINK_DEPTH,
                true,   // validateChecksums
                true,   // rejectBidiChars
                true    // rejectZeroWidthChars
        );
    }

    /**
     * Returns a permissive policy with higher limits.
     *
     * <p>Use with caution - this is intended for trusted inputs only.
     *
     * @return a permissive policy
     */
    public static @NotNull SecurityPolicy permissive() {
        return new SecurityPolicy(
                Long.MAX_VALUE / 2,
                Long.MAX_VALUE / 2,
                Integer.MAX_VALUE / 2,
                Long.MAX_VALUE / 2,
                1024,
                100,
                false,  // validateChecksums
                false,  // rejectBidiChars
                false   // rejectZeroWidthChars
        );
    }

    /**
     * Returns a new builder initialized with default values.
     *
     * @return a new builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Builder for SecurityPolicy.
     */
    public static final class Builder {
        private long maxDecompressedSize = DEFAULT_MAX_DECOMPRESSED_SIZE;
        private long maxAllocationSize = DEFAULT_MAX_ALLOCATION_SIZE;
        private int maxClusterSize = DEFAULT_MAX_CLUSTER_SIZE;
        private long maxVirtualDiskSize = DEFAULT_MAX_VIRTUAL_DISK_SIZE;
        private int maxPathDepth = DEFAULT_MAX_PATH_DEPTH;
        private int maxSymlinkDepth = DEFAULT_MAX_SYMLINK_DEPTH;
        private boolean validateChecksums = true;
        private boolean rejectBidiChars = true;
        private boolean rejectZeroWidthChars = true;

        private Builder() {}

        /**
         * Sets the maximum decompressed size allowed.
         */
        public @NotNull Builder maxDecompressedSize(long size) {
            this.maxDecompressedSize = size;
            return this;
        }

        /**
         * Sets the maximum single allocation size.
         */
        public @NotNull Builder maxAllocationSize(long size) {
            this.maxAllocationSize = size;
            return this;
        }

        /**
         * Sets the maximum cluster size.
         */
        public @NotNull Builder maxClusterSize(int size) {
            this.maxClusterSize = size;
            return this;
        }

        /**
         * Sets the maximum virtual disk size.
         */
        public @NotNull Builder maxVirtualDiskSize(long size) {
            this.maxVirtualDiskSize = size;
            return this;
        }

        /**
         * Sets the maximum path depth.
         */
        public @NotNull Builder maxPathDepth(int depth) {
            this.maxPathDepth = depth;
            return this;
        }

        /**
         * Sets the maximum symlink resolution depth.
         */
        public @NotNull Builder maxSymlinkDepth(int depth) {
            this.maxSymlinkDepth = depth;
            return this;
        }

        /**
         * Sets whether to validate checksums.
         */
        public @NotNull Builder validateChecksums(boolean validate) {
            this.validateChecksums = validate;
            return this;
        }

        /**
         * Sets whether to reject BIDI (bidirectional) characters in paths.
         */
        public @NotNull Builder rejectBidiChars(boolean reject) {
            this.rejectBidiChars = reject;
            return this;
        }

        /**
         * Sets whether to reject zero-width characters in paths.
         */
        public @NotNull Builder rejectZeroWidthChars(boolean reject) {
            this.rejectZeroWidthChars = reject;
            return this;
        }

        /**
         * Builds the SecurityPolicy.
         *
         * @return the configured policy
         */
        public @NotNull SecurityPolicy build() {
            return new SecurityPolicy(
                    maxDecompressedSize,
                    maxAllocationSize,
                    maxClusterSize,
                    maxVirtualDiskSize,
                    maxPathDepth,
                    maxSymlinkDepth,
                    validateChecksums,
                    rejectBidiChars,
                    rejectZeroWidthChars
            );
        }
    }
}
