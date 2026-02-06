/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.exception;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Exception thrown when a disk image has an invalid magic number/signature.
 */
public class InvalidMagicException extends SaffronException.InvalidDiskException {

    private final byte[] expectedMagic;
    private final byte[] actualMagic;
    private final long offset;

    public InvalidMagicException(
            @NotNull String message,
            byte[] expectedMagic,
            byte[] actualMagic,
            long offset,
            @Nullable DiskFormat format) {
        super(message, format);
        this.expectedMagic = expectedMagic != null ? Arrays.copyOf(expectedMagic, expectedMagic.length) : new byte[0];
        this.actualMagic = actualMagic != null ? Arrays.copyOf(actualMagic, actualMagic.length) : new byte[0];
        this.offset = offset;
    }

    /**
     * Returns a copy of the expected magic bytes.
     */
    public byte[] getExpectedMagic() {
        return Arrays.copyOf(expectedMagic, expectedMagic.length);
    }

    /**
     * Returns a copy of the actual magic bytes found.
     */
    public byte[] getActualMagic() {
        return Arrays.copyOf(actualMagic, actualMagic.length);
    }

    /**
     * Returns the offset in the file where the magic was expected.
     */
    public long getOffset() {
        return offset;
    }
}
