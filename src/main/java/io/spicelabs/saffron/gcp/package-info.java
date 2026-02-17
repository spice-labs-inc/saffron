/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Google Cloud Platform disk image format support.
 *
 * <p>GCP disk images are tar.gz archives containing a file named "disk.raw".
 * The tar archive uses the oldgnu format, and the raw disk must have a size
 * that is a multiple of 1 GB.
 *
 * @see io.spicelabs.saffron.gcp.GcpDiskImpl
 */
package io.spicelabs.saffron.gcp;
