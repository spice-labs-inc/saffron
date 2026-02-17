/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Test corpus infrastructure for Saffron.
 *
 * <p>This package contains:
 * <ul>
 *   <li>{@link io.spicelabs.saffron.corpus.CorpusManifest} - Reads the corpus manifest.json</li>
 *   <li>{@link io.spicelabs.saffron.corpus.CorpusImage} - Represents an image in the corpus</li>
 *   <li>Test classes for corpus validation and coverage</li>
 * </ul>
 *
 * <p>The test corpus is stored separately from the source code (~50-100GB)
 * and contains 200+ real VM disk images for comprehensive testing.
 */
package io.spicelabs.saffron.corpus;
