/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.exception;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when a checksum validation fails.
 *
 * <p>This is used for formats that include checksums (CRC32, etc.)
 * to verify data integrity.
 */
public class ChecksumException extends CorruptedDiskException {

    private final long expectedChecksum;
    private final long actualChecksum;
    private final @Nullable String checksumType;

    public ChecksumException(
            @NotNull String message,
            long expectedChecksum,
            long actualChecksum,
            @Nullable String checksumType,
            long offset,
            @Nullable String structureName,
            @Nullable DiskFormat format) {
        super(message, offset, structureName, format);
        this.expectedChecksum = expectedChecksum;
        this.actualChecksum = actualChecksum;
        this.checksumType = checksumType;
    }

    /**
     * Returns the expected checksum value.
     */
    public long getExpectedChecksum() {
        return expectedChecksum;
    }

    /**
     * Returns the actual computed checksum value.
     */
    public long getActualChecksum() {
        return actualChecksum;
    }

    /**
     * Returns the checksum algorithm type (e.g., "CRC32", "CRC32C").
     */
    public @Nullable String getChecksumType() {
        return checksumType;
    }
}
