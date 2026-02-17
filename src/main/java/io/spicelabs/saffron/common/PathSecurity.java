/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.spicelabs.saffron.common;

import io.spicelabs.saffron.SecurityPolicy;
import org.jetbrains.annotations.NotNull;

/**
 * Security utilities for path validation.
 *
 * <p>This class provides methods to detect and prevent path traversal attacks
 * and Unicode security issues in filesystem paths.
 */
public final class PathSecurity {

    private PathSecurity() {
        // Utility class - no instantiation
    }

    /**
     * Validates a path against the security policy.
     *
     * @param path the path to validate
     * @param policy the security policy to apply
     * @throws SecurityException if the path violates the policy
     */
    public static void validatePath(@NotNull String path, @NotNull SecurityPolicy policy) {
        // Check for path traversal
        if (containsPathTraversal(path)) {
            throw new SecurityException("Path traversal detected: " + path);
        }

        // Check path depth
        int depth = countPathComponents(path);
        if (depth > policy.maxPathDepth()) {
            throw new SecurityException(
                    String.format("Path depth %d exceeds maximum %d: %s",
                            depth, policy.maxPathDepth(), path));
        }

        // Check for BIDI characters
        if (policy.rejectBidiChars() && containsBidiChars(path)) {
            throw new SecurityException("Bidirectional characters detected in path: " + path);
        }

        // Check for zero-width characters
        if (policy.rejectZeroWidthChars() && containsZeroWidthChars(path)) {
            throw new SecurityException("Zero-width characters detected in path: " + path);
        }
    }

    /**
     * Checks if a path contains traversal sequences.
     *
     * @param path the path to check
     * @return true if path traversal is detected
     */
    public static boolean containsPathTraversal(@NotNull String path) {
        // Normalize separators
        String normalized = path.replace('\\', '/');

        // Check for obvious traversal patterns
        if (normalized.contains("/../") || normalized.startsWith("../") ||
                normalized.endsWith("/..") || normalized.equals("..")) {
            return true;
        }

        // Check for encoded traversal
        String lower = normalized.toLowerCase();
        if (lower.contains("%2e%2e") || lower.contains("%2e%2e%2f") ||
                lower.contains("%2f%2e%2e")) {
            return true;
        }

        return false;
    }

    /**
     * Counts the number of path components.
     *
     * @param path the path to analyze
     * @return the number of components
     */
    public static int countPathComponents(@NotNull String path) {
        if (path.isEmpty()) {
            return 0;
        }
        String normalized = path.replace('\\', '/');
        int count = 0;
        int start = 0;
        while (start < normalized.length()) {
            int end = normalized.indexOf('/', start);
            if (end == -1) {
                if (start < normalized.length()) {
                    count++;
                }
                break;
            }
            if (end > start) {
                count++;
            }
            start = end + 1;
        }
        return count;
    }

    /**
     * Checks if a string contains Unicode BIDI (bidirectional) control characters.
     *
     * <p>BIDI characters can be used to make malicious paths appear benign.
     *
     * @param s the string to check
     * @return true if BIDI characters are present
     */
    public static boolean containsBidiChars(@NotNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // BIDI control characters
            if (c == '\u200E' || c == '\u200F' ||  // LRM, RLM
                    c == '\u202A' || c == '\u202B' || c == '\u202C' ||  // LRE, RLE, PDF
                    c == '\u202D' || c == '\u202E' ||  // LRO, RLO
                    c == '\u2066' || c == '\u2067' || c == '\u2068' || c == '\u2069') {  // LRI, RLI, FSI, PDI
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a string contains zero-width characters.
     *
     * <p>Zero-width characters can make different paths appear identical.
     *
     * @param s the string to check
     * @return true if zero-width characters are present
     */
    public static boolean containsZeroWidthChars(@NotNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Zero-width characters
            if (c == '\u200B' ||  // Zero-width space
                    c == '\u200C' ||  // Zero-width non-joiner
                    c == '\u200D' ||  // Zero-width joiner
                    c == '\uFEFF') {  // Zero-width no-break space (BOM)
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes a path by removing redundant separators and resolving . components.
     *
     * <p>Note: This does NOT resolve .. components for security reasons.
     * Use {@link #validatePath} to check for traversal first.
     *
     * @param path the path to normalize
     * @return the normalized path
     */
    public static @NotNull String normalizePath(@NotNull String path) {
        if (path.isEmpty()) {
            return path;
        }

        // Replace backslashes with forward slashes
        String normalized = path.replace('\\', '/');

        // Remove redundant slashes
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }

        // Remove trailing slash (except for root)
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // Remove . components
        normalized = normalized.replace("/./", "/");
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.endsWith("/.")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }

        return normalized;
    }
}
