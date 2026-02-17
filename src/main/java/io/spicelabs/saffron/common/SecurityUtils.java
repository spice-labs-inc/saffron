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

import io.spicelabs.saffron.exception.CorruptedDiskException;
import org.jetbrains.annotations.NotNull;

/**
 * Security utilities for Saffron operations.
 *
 * <p>This class provides security validation methods including:
 * <ul>
 *   <li>Allocation size validation to prevent memory exhaustion</li>
 *   <li>Path validation to prevent traversal attacks</li>
 *   <li>Symlink target validation</li>
 * </ul>
 *
 * <p>See also {@link PathSecurity} for path-specific security checks and
 * {@link UnicodeSecurityUtils} for Unicode normalization.
 */
public final class SecurityUtils {

    /** Maximum path length in bytes */
    public static final int MAX_PATH_LENGTH = 4096;

    private SecurityUtils() {
        // Utility class - no instantiation
    }

    /**
     * Validates that a requested allocation size is within limits.
     *
     * <p>This is critical for preventing maliciously crafted disk images
     * from causing memory exhaustion by specifying huge allocation sizes.
     *
     * @param requested the requested allocation size
     * @param limit the maximum allowed size
     * @param context description of what is being allocated (for error messages)
     * @throws CorruptedDiskException if the size is negative or exceeds the limit
     */
    public static void validateAllocationSize(long requested, long limit, @NotNull String context) {
        if (requested < 0) {
            throw new CorruptedDiskException(
                    String.format("Negative size for %s: %d", context, requested),
                    null);
        }
        if (requested > limit) {
            throw new CorruptedDiskException(
                    String.format("%s size %d exceeds limit %d", context, requested, limit),
                    null);
        }
    }

    /**
     * Validates a filesystem path for security issues.
     *
     * <p>This method checks for:
     * <ul>
     *   <li>Absolute paths (starting with / or drive letter)</li>
     *   <li>Path traversal sequences (..)</li>
     *   <li>Null bytes</li>
     *   <li>Excessive length</li>
     * </ul>
     *
     * @param path the path to validate
     * @return the normalized path if valid
     * @throws IllegalArgumentException if the path is invalid
     */
    public static @NotNull String validatePath(@NotNull String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be null or empty");
        }

        // Check for null bytes
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path contains null byte: " + sanitizeForLog(path));
        }

        // Check length
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Path length %d exceeds maximum %d", path.length(), MAX_PATH_LENGTH));
        }

        // Check for absolute paths
        if (isAbsolutePath(path)) {
            throw new IllegalArgumentException("Absolute paths not allowed: " + sanitizeForLog(path));
        }

        // Check for path traversal
        if (PathSecurity.containsPathTraversal(path)) {
            throw new IllegalArgumentException("Path traversal detected: " + sanitizeForLog(path));
        }

        return PathSecurity.normalizePath(path);
    }

    /**
     * Validates a symlink target for security issues.
     *
     * <p>Symlink targets have the same restrictions as regular paths,
     * plus additional checks for escape attempts.
     *
     * @param target the symlink target to validate
     * @throws IllegalArgumentException if the target is invalid
     */
    public static void validateSymlinkTarget(@NotNull String target) {
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("Symlink target cannot be null or empty");
        }

        // Check for null bytes
        if (target.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Symlink target contains null byte");
        }

        // Check for escape via parent traversal
        String normalized = target.replace('\\', '/');
        if (normalized.startsWith("../") || normalized.contains("/../")) {
            throw new IllegalArgumentException(
                    "Symlink target attempts to escape root: " + sanitizeForLog(target));
        }
    }

    /**
     * Checks if a path is absolute.
     *
     * @param path the path to check
     * @return true if the path is absolute
     */
    public static boolean isAbsolutePath(@NotNull String path) {
        if (path.isEmpty()) {
            return false;
        }
        // Unix absolute path
        if (path.charAt(0) == '/' || path.charAt(0) == '\\') {
            return true;
        }
        // Windows drive letter (C:\, D:\, etc.)
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            return true;
        }
        // UNC path
        if (path.startsWith("\\\\")) {
            return true;
        }
        return false;
    }

    /**
     * Validates that an offset is within valid bounds.
     *
     * @param offset the offset to validate
     * @param maxOffset the maximum valid offset (exclusive)
     * @param context description of the offset (for error messages)
     * @throws CorruptedDiskException if the offset is invalid
     */
    public static void validateOffset(long offset, long maxOffset, @NotNull String context) {
        if (offset < 0) {
            throw new CorruptedDiskException(
                    String.format("Negative %s offset: %d", context, offset),
                    null);
        }
        if (offset >= maxOffset) {
            throw new CorruptedDiskException(
                    String.format("%s offset %d exceeds maximum %d", context, offset, maxOffset),
                    null);
        }
    }

    /**
     * Validates that a count is within valid bounds.
     *
     * @param count the count to validate
     * @param maxCount the maximum valid count
     * @param context description of what is being counted (for error messages)
     * @throws CorruptedDiskException if the count is invalid
     */
    public static void validateCount(long count, long maxCount, @NotNull String context) {
        if (count < 0) {
            throw new CorruptedDiskException(
                    String.format("Negative %s count: %d", context, count),
                    null);
        }
        if (count > maxCount) {
            throw new CorruptedDiskException(
                    String.format("%s count %d exceeds maximum %d", context, count, maxCount),
                    null);
        }
    }

    /**
     * Validates that a value is a power of two.
     *
     * <p>Many disk format values (cluster size, block size, etc.) must be
     * powers of two.
     *
     * @param value the value to check
     * @param context description of the value (for error messages)
     * @throws CorruptedDiskException if the value is not a power of two
     */
    public static void validatePowerOfTwo(long value, @NotNull String context) {
        if (value <= 0 || (value & (value - 1)) != 0) {
            throw new CorruptedDiskException(
                    String.format("%s must be a positive power of two, got: %d", context, value),
                    null);
        }
    }

    /**
     * Compares two byte arrays in constant time.
     *
     * <p>This method is designed to prevent timing attacks when comparing
     * sensitive data like checksums or MACs. The comparison time does not
     * depend on where (or if) the arrays differ.
     *
     * @param a first byte array
     * @param b second byte array
     * @return true if the arrays are equal
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Sanitizes a string for safe inclusion in log messages.
     *
     * <p>Removes or escapes characters that could be used for log injection.
     *
     * @param input the string to sanitize
     * @return sanitized string safe for logging
     */
    public static @NotNull String sanitizeForLog(@NotNull String input) {
        if (input.length() > 100) {
            input = input.substring(0, 100) + "...";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < 32 || c == 127) {
                sb.append("\\x").append(String.format("%02x", (int) c));
            } else if (c == '\\') {
                sb.append("\\\\");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
