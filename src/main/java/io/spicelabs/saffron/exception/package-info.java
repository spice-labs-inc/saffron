/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

/**
 * Exception types for Saffron operations.
 *
 * <p>The exception hierarchy follows Baharat's pattern with nested static classes:
 *
 * <pre>
 * SaffronException (base, unchecked)
 * ├── InvalidDiskException
 * │   ├── InvalidMagicException
 * │   ├── CorruptedDiskException
 * │   │   └── ChecksumException
 * │   └── ...
 * ├── UnsupportedDiskException
 * │   ├── UnsupportedVersionException
 * │   ├── EncryptedDiskException
 * │   └── ...
 * └── ResourceLimitException
 * </pre>
 *
 * <p>All exceptions include optional {@link io.spicelabs.saffron.DiskFormat} context
 * to help identify which format was being processed when the error occurred.
 *
 * @see io.spicelabs.saffron.exception.SaffronException
 */
package io.spicelabs.saffron.exception;
