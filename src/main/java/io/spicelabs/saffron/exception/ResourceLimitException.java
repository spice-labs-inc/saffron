/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when a resource limit is exceeded.
 *
 * <p>This is part of the security model to prevent decompression bombs
 * and other resource exhaustion attacks.
 */
public class ResourceLimitException extends SaffronException {

    private final @NotNull String resourceType;
    private final long limit;
    private final long attempted;

    public ResourceLimitException(
            @NotNull String message,
            @NotNull String resourceType,
            long limit,
            long attempted) {
        super(message);
        this.resourceType = resourceType;
        this.limit = limit;
        this.attempted = attempted;
    }

    /**
     * Returns the type of resource that exceeded the limit.
     *
     * <p>Examples: "decompressed_size", "allocation_size", "cluster_count"
     */
    public @NotNull String getResourceType() {
        return resourceType;
    }

    /**
     * Returns the configured limit.
     */
    public long getLimit() {
        return limit;
    }

    /**
     * Returns the attempted/requested value that exceeded the limit.
     */
    public long getAttempted() {
        return attempted;
    }

    /**
     * Creates an exception for exceeding decompression size limits.
     */
    public static ResourceLimitException decompressionBomb(long limit, long attempted) {
        return new ResourceLimitException(
                String.format("Decompression size %d exceeds limit %d (potential decompression bomb)",
                        attempted, limit),
                "decompressed_size",
                limit,
                attempted);
    }

    /**
     * Creates an exception for exceeding allocation size limits.
     */
    public static ResourceLimitException allocationTooLarge(long limit, long attempted) {
        return new ResourceLimitException(
                String.format("Allocation size %d exceeds limit %d", attempted, limit),
                "allocation_size",
                limit,
                attempted);
    }
}
