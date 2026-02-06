/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.exception;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when a disk image version is not supported.
 *
 * <p>For example, QCOW2 version 1 is not supported (only v2/v3).
 */
public class UnsupportedVersionException extends SaffronException.UnsupportedDiskException {

    private final int version;
    private final int minSupported;
    private final int maxSupported;

    public UnsupportedVersionException(
            @NotNull String message,
            int version,
            int minSupported,
            int maxSupported,
            @Nullable DiskFormat format) {
        super(message, format);
        this.version = version;
        this.minSupported = minSupported;
        this.maxSupported = maxSupported;
    }

    /**
     * Returns the unsupported version that was encountered.
     */
    public int getVersion() {
        return version;
    }

    /**
     * Returns the minimum supported version.
     */
    public int getMinSupported() {
        return minSupported;
    }

    /**
     * Returns the maximum supported version.
     */
    public int getMaxSupported() {
        return maxSupported;
    }
}
