/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */

/**
 * RAW disk image format support.
 *
 * <p>RAW disk images are byte-for-byte copies of a disk with no container
 * format or metadata. They are the simplest disk image format and are used
 * by Google Cloud Platform (inside tar.gz containers) and other systems.
 *
 * @see io.spicelabs.saffron.raw.RawDiskImpl
 */
package io.spicelabs.saffron.raw;
