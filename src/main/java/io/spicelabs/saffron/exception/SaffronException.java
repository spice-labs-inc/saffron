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
package io.spicelabs.saffron.exception;

import io.spicelabs.saffron.DiskFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Base exception for all Saffron-specific errors.
 *
 * <p>This exception hierarchy follows Baharat's {@code PackageException} pattern,
 * using nested static classes for related exception types and providing format
 * context for debugging.
 *
 * <p>All exceptions include:
 * <ul>
 *   <li>Human-readable message</li>
 *   <li>Optional {@link DiskFormat} context</li>
 *   <li>Optional cause for exception chaining</li>
 * </ul>
 *
 * <p>This is an unchecked exception (extends {@link RuntimeException}) following
 * the pattern established for format/parsing errors. I/O errors still use
 * checked {@link java.io.IOException}.
 */
public class SaffronException extends RuntimeException {

    private final @Nullable DiskFormat format;

    /**
     * Creates a new exception with a message.
     *
     * @param message the detail message
     */
    public SaffronException(@NotNull String message) {
        super(message);
        this.format = null;
    }

    /**
     * Creates a new exception with a message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public SaffronException(@NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.format = null;
    }

    /**
     * Creates a new exception with a message and format context.
     *
     * @param message the detail message
     * @param format the disk format being processed when the error occurred
     */
    public SaffronException(@NotNull String message, @Nullable DiskFormat format) {
        super(message);
        this.format = format;
    }

    /**
     * Creates a new exception with message, format context, and cause.
     *
     * @param message the detail message
     * @param format the disk format being processed when the error occurred
     * @param cause the cause of this exception
     */
    public SaffronException(@NotNull String message, @Nullable DiskFormat format, @Nullable Throwable cause) {
        super(message, cause);
        this.format = format;
    }

    /**
     * Returns the disk format context, if available.
     *
     * @return an Optional containing the format being processed when the error occurred
     */
    public @NotNull Optional<DiskFormat> getFormat() {
        return Optional.ofNullable(format);
    }

    /**
     * Returns the disk format context as a nullable value.
     *
     * @return the format being processed when the error occurred, or null
     */
    public @Nullable DiskFormat getFormatOrNull() {
        return format;
    }

    /**
     * Exception thrown when a disk image is invalid or malformed.
     *
     * <p>This includes wrong magic numbers, invalid header structures,
     * and other format violations.
     */
    public static class InvalidDiskException extends SaffronException {

        public InvalidDiskException(@NotNull String message) {
            super(message);
        }

        public InvalidDiskException(@NotNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }

        public InvalidDiskException(@NotNull String message, @Nullable DiskFormat format) {
            super(message, format);
        }

        public InvalidDiskException(@NotNull String message, @Nullable DiskFormat format, @Nullable Throwable cause) {
            super(message, format, cause);
        }
    }

    /**
     * Exception thrown when a disk format or feature is not supported.
     *
     * <p>This includes unsupported format versions, encrypted images,
     * and unimplemented compression algorithms.
     */
    public static class UnsupportedDiskException extends SaffronException {

        public UnsupportedDiskException(@NotNull String message) {
            super(message);
        }

        public UnsupportedDiskException(@NotNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }

        public UnsupportedDiskException(@NotNull String message, @Nullable DiskFormat format) {
            super(message, format);
        }

        public UnsupportedDiskException(@NotNull String message, @Nullable DiskFormat format, @Nullable Throwable cause) {
            super(message, format, cause);
        }
    }
}
