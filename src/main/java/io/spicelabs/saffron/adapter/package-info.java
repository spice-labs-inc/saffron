/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * I/O adapter interfaces and implementations for Saffron.
 *
 * <p>This package provides abstractions for different data sources:
 *
 * <ul>
 *   <li>{@link io.spicelabs.saffron.adapter.InputStreamSource} - Abstract interface for data sources</li>
 *   <li>{@link io.spicelabs.saffron.adapter.FileInputStreamSource} - File-backed implementation with random access</li>
 *   <li>{@link io.spicelabs.saffron.adapter.ByteArrayInputStreamSource} - In-memory implementation for testing</li>
 * </ul>
 *
 * <p>This follows the adapter pattern used in Baharat's PackageSource.
 */
package io.spicelabs.saffron.adapter;
