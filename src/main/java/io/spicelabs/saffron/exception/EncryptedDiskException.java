/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.exception;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when attempting to read an encrypted disk image.
 *
 * <p>Saffron does not support decryption; this exception indicates
 * that the disk cannot be read.
 */
public class EncryptedDiskException extends SaffronException.UnsupportedDiskException {

    private final @Nullable String encryptionMethod;

    public EncryptedDiskException(
            @NotNull String message,
            @Nullable DiskFormat format) {
        super(message, format);
        this.encryptionMethod = null;
    }

    public EncryptedDiskException(
            @NotNull String message,
            @Nullable String encryptionMethod,
            @Nullable DiskFormat format) {
        super(message, format);
        this.encryptionMethod = encryptionMethod;
    }

    /**
     * Returns the encryption method used, if known.
     */
    public @Nullable String getEncryptionMethod() {
        return encryptionMethod;
    }
}
