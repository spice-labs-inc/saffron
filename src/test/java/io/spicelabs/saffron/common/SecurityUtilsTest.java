/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.common;

import io.spicelabs.saffron.exception.CorruptedDiskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SecurityUtils}.
 */
class SecurityUtilsTest {

    @Test
    void validatePath_normalPath_returnsNormalized() {
        assertThat(SecurityUtils.validatePath("foo/bar/baz.txt"))
                .isEqualTo("foo/bar/baz.txt");
    }

    @Test
    void validatePath_absolutePath_throwsException() {
        assertThatThrownBy(() -> SecurityUtils.validatePath("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatePath_windowsAbsolutePath_throwsException() {
        assertThatThrownBy(() -> SecurityUtils.validatePath("C:\\Windows\\System32"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatePath_parentTraversal_throwsException() {
        assertThatThrownBy(() -> SecurityUtils.validatePath("foo/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatePath_startingWithParentTraversal_throwsException() {
        assertThatThrownBy(() -> SecurityUtils.validatePath("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatePath_nullByte_throwsException() {
        assertThatThrownBy(() -> SecurityUtils.validatePath("foo\0bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatePath_tooLong_throwsException() {
        String longPath = "a/".repeat(3000);
        assertThatThrownBy(() -> SecurityUtils.validatePath(longPath))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatePath_normalizesSlashes() {
        assertThat(SecurityUtils.validatePath("foo//bar///baz.txt"))
                .isEqualTo("foo/bar/baz.txt");
    }

    @Test
    void validatePath_normalizesBackslashes() {
        assertThat(SecurityUtils.validatePath("foo\\bar\\baz.txt"))
                .isEqualTo("foo/bar/baz.txt");
    }

    @Test
    void validateSymlinkTarget_escapesRoot_throwsException() {
        assertThatThrownBy(() -> SecurityUtils.validateSymlinkTarget("../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateSymlinkTarget_normalTarget_succeeds() {
        assertThatCode(() -> SecurityUtils.validateSymlinkTarget("relative/path/file.txt"))
                .doesNotThrowAnyException();
    }

    @Test
    void validateSymlinkTarget_nullByte_throwsException() {
        assertThatThrownBy(() -> SecurityUtils.validateSymlinkTarget("foo\0bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isAbsolutePath_unixAbsolute_returnsTrue() {
        assertThat(SecurityUtils.isAbsolutePath("/etc/passwd")).isTrue();
    }

    @Test
    void isAbsolutePath_windowsAbsolute_returnsTrue() {
        assertThat(SecurityUtils.isAbsolutePath("C:\\Windows")).isTrue();
        assertThat(SecurityUtils.isAbsolutePath("D:file")).isTrue();
    }

    @Test
    void isAbsolutePath_uncPath_returnsTrue() {
        assertThat(SecurityUtils.isAbsolutePath("\\\\server\\share")).isTrue();
    }

    @Test
    void isAbsolutePath_relativePath_returnsFalse() {
        assertThat(SecurityUtils.isAbsolutePath("foo/bar")).isFalse();
        assertThat(SecurityUtils.isAbsolutePath("./foo")).isFalse();
    }

    @Test
    @Timeout(5)
    void validateAllocationSize_negativeSize_throws() {
        assertThatThrownBy(() ->
                SecurityUtils.validateAllocationSize(-1, 1000, "test"))
                .isInstanceOf(CorruptedDiskException.class)
                .hasMessageContaining("Negative");
    }

    @Test
    @Timeout(5)
    void validateAllocationSize_exceedsLimit_throws() {
        assertThatThrownBy(() ->
                SecurityUtils.validateAllocationSize(1001, 1000, "test"))
                .isInstanceOf(CorruptedDiskException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @Timeout(5)
    void validateAllocationSize_withinLimit_succeeds() {
        assertThatCode(() ->
                SecurityUtils.validateAllocationSize(500, 1000, "test"))
                .doesNotThrowAnyException();
    }

    @Test
    @Timeout(5)
    void validateAllocationSize_maxLongValue_throws() {
        assertThatThrownBy(() ->
                SecurityUtils.validateAllocationSize(Long.MAX_VALUE, 16_000_000, "L1 table"))
                .isInstanceOf(CorruptedDiskException.class);
    }

    @Test
    void validateOffset_negativeOffset_throws() {
        assertThatThrownBy(() ->
                SecurityUtils.validateOffset(-1, 1000, "test"))
                .isInstanceOf(CorruptedDiskException.class)
                .hasMessageContaining("Negative");
    }

    @Test
    void validateOffset_exceedsMax_throws() {
        assertThatThrownBy(() ->
                SecurityUtils.validateOffset(1000, 1000, "test"))
                .isInstanceOf(CorruptedDiskException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void validateCount_negativeCount_throws() {
        assertThatThrownBy(() ->
                SecurityUtils.validateCount(-1, 1000, "test"))
                .isInstanceOf(CorruptedDiskException.class)
                .hasMessageContaining("Negative");
    }

    @Test
    void validatePowerOfTwo_validValues_succeeds() {
        assertThatCode(() -> SecurityUtils.validatePowerOfTwo(1, "test")).doesNotThrowAnyException();
        assertThatCode(() -> SecurityUtils.validatePowerOfTwo(2, "test")).doesNotThrowAnyException();
        assertThatCode(() -> SecurityUtils.validatePowerOfTwo(512, "test")).doesNotThrowAnyException();
        assertThatCode(() -> SecurityUtils.validatePowerOfTwo(65536, "test")).doesNotThrowAnyException();
    }

    @Test
    void validatePowerOfTwo_invalidValues_throws() {
        assertThatThrownBy(() -> SecurityUtils.validatePowerOfTwo(0, "test"))
                .isInstanceOf(CorruptedDiskException.class);
        assertThatThrownBy(() -> SecurityUtils.validatePowerOfTwo(3, "test"))
                .isInstanceOf(CorruptedDiskException.class);
        assertThatThrownBy(() -> SecurityUtils.validatePowerOfTwo(-1, "test"))
                .isInstanceOf(CorruptedDiskException.class);
    }

    @Test
    void sanitizeForLog_removesControlCharacters() {
        String result = SecurityUtils.sanitizeForLog("hello\0world\ntest");
        assertThat(result).doesNotContain("\0").doesNotContain("\n");
    }

    @Test
    void sanitizeForLog_truncatesLongStrings() {
        String longString = "a".repeat(200);
        String result = SecurityUtils.sanitizeForLog(longString);
        assertThat(result.length()).isLessThanOrEqualTo(105); // 100 + "..."
    }
}
