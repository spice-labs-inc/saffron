/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.exception;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when a disk image structure is corrupted.
 *
 * <p>This includes checksum failures, invalid offsets, and other
 * structural inconsistencies.
 */
public class CorruptedDiskException extends SaffronException.InvalidDiskException {

    private final long offset;
    private final @Nullable String structureName;

    public CorruptedDiskException(
            @NotNull String message,
            @Nullable DiskFormat format) {
        super(message, format);
        this.offset = -1;
        this.structureName = null;
    }

    public CorruptedDiskException(
            @NotNull String message,
            long offset,
            @Nullable String structureName,
            @Nullable DiskFormat format) {
        super(message, format);
        this.offset = offset;
        this.structureName = structureName;
    }

    public CorruptedDiskException(
            @NotNull String message,
            long offset,
            @Nullable String structureName,
            @Nullable DiskFormat format,
            @Nullable Throwable cause) {
        super(message, format, cause);
        this.offset = offset;
        this.structureName = structureName;
    }

    /**
     * Returns the offset where corruption was detected, or -1 if unknown.
     */
    public long getOffset() {
        return offset;
    }

    /**
     * Returns the name of the corrupted structure, if known.
     */
    public @Nullable String getStructureName() {
        return structureName;
    }
}
