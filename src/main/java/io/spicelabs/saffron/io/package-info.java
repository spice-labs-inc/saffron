/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Low-level I/O utilities for Saffron.
 *
 * <p>This package provides foundational I/O components:
 *
 * <ul>
 *   <li>{@link io.spicelabs.saffron.io.BinaryReader} - Endian-aware binary reading from streams</li>
 *   <li>{@link io.spicelabs.saffron.io.BoundedInputStream} - Stream wrapper with byte limit protection</li>
 *   <li>{@link io.spicelabs.saffron.io.SafeMath} - Overflow-safe arithmetic operations</li>
 * </ul>
 *
 * <p>These utilities are designed for security and correctness when processing
 * untrusted disk image data.
 */
package io.spicelabs.saffron.io;
