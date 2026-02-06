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
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.common;

import org.jetbrains.annotations.NotNull;

import java.text.Normalizer;

/**
 * Security utilities for Unicode string handling.
 *
 * <p>This class provides methods to detect and prevent Unicode-based attacks
 * in filesystem paths and other string data extracted from disk images.
 *
 * <h2>Attack Vectors Mitigated</h2>
 * <ul>
 *   <li><b>Bidirectional (BIDI) Override Attacks</b>: Characters like RLO (U+202E)
 *       can make malicious paths appear benign (e.g., "gpj.exe" appears as "exe.jpg")</li>
 *   <li><b>Zero-Width Character Attacks</b>: Characters like ZWSP (U+200B) can make
 *       different paths appear identical</li>
 *   <li><b>Homoglyph Attacks</b>: Characters that look similar but are different
 *       (e.g., Cyrillic 'а' vs Latin 'a')</li>
 *   <li><b>Normalization Attacks</b>: Different Unicode representations of the
 *       same visual character</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * String path = UnicodeSecurityUtils.validateAndNormalize(extractedPath);
 * }</pre>
 */
public final class UnicodeSecurityUtils {

    private UnicodeSecurityUtils() {
        // Utility class - no instantiation
    }

    /**
     * Validates a string for Unicode security issues and returns the normalized form.
     *
     * <p>This method:
     * <ol>
     *   <li>Rejects BIDI override characters</li>
     *   <li>Rejects zero-width characters</li>
     *   <li>Rejects byte order marks</li>
     *   <li>Normalizes to NFC form</li>
     * </ol>
     *
     * @param input the string to validate and normalize
     * @return the NFC-normalized string
     * @throws IllegalArgumentException if the string contains forbidden characters
     */
    public static @NotNull String validateAndNormalize(@NotNull String input) {
        // Check for BIDI characters
        if (containsBidiCharacters(input)) {
            throw new IllegalArgumentException(
                    "Bidirectional override characters detected in string");
        }

        // Check for zero-width characters
        if (containsZeroWidthCharacters(input)) {
            throw new IllegalArgumentException(
                    "Zero-width characters detected in string");
        }

        // Check for byte order marks
        if (containsByteOrderMark(input)) {
            throw new IllegalArgumentException(
                    "Byte order mark detected in string");
        }

        // Normalize to NFC
        return Normalizer.normalize(input, Normalizer.Form.NFC);
    }

    /**
     * Checks if a string contains Unicode BIDI (bidirectional) override characters.
     *
     * <p>BIDI characters can be used to reverse the visual display of text,
     * making malicious paths appear benign.
     *
     * @param s the string to check
     * @return true if BIDI override characters are present
     */
    public static boolean containsBidiCharacters(@NotNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isBidiCharacter(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a character is a BIDI control character.
     *
     * @param c the character to check
     * @return true if it's a BIDI control character
     */
    public static boolean isBidiCharacter(char c) {
        return c == '\u200E' ||  // LEFT-TO-RIGHT MARK (LRM)
               c == '\u200F' ||  // RIGHT-TO-LEFT MARK (RLM)
               c == '\u202A' ||  // LEFT-TO-RIGHT EMBEDDING (LRE)
               c == '\u202B' ||  // RIGHT-TO-LEFT EMBEDDING (RLE)
               c == '\u202C' ||  // POP DIRECTIONAL FORMATTING (PDF)
               c == '\u202D' ||  // LEFT-TO-RIGHT OVERRIDE (LRO)
               c == '\u202E' ||  // RIGHT-TO-LEFT OVERRIDE (RLO)
               c == '\u2066' ||  // LEFT-TO-RIGHT ISOLATE (LRI)
               c == '\u2067' ||  // RIGHT-TO-LEFT ISOLATE (RLI)
               c == '\u2068' ||  // FIRST STRONG ISOLATE (FSI)
               c == '\u2069';    // POP DIRECTIONAL ISOLATE (PDI)
    }

    /**
     * Checks if a string contains zero-width characters.
     *
     * <p>Zero-width characters are invisible but can make two visually
     * identical strings have different byte representations.
     *
     * @param s the string to check
     * @return true if zero-width characters are present
     */
    public static boolean containsZeroWidthCharacters(@NotNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isZeroWidthCharacter(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a character is a zero-width character.
     *
     * @param c the character to check
     * @return true if it's a zero-width character
     */
    public static boolean isZeroWidthCharacter(char c) {
        return c == '\u200B' ||  // ZERO WIDTH SPACE (ZWSP)
               c == '\u200C' ||  // ZERO WIDTH NON-JOINER (ZWNJ)
               c == '\u200D' ||  // ZERO WIDTH JOINER (ZWJ)
               c == '\u2060' ||  // WORD JOINER
               c == '\u180E';    // MONGOLIAN VOWEL SEPARATOR
    }

    /**
     * Checks if a string contains a byte order mark (BOM).
     *
     * @param s the string to check
     * @return true if a BOM is present
     */
    public static boolean containsByteOrderMark(@NotNull String s) {
        if (s.isEmpty()) {
            return false;
        }
        char first = s.charAt(0);
        // UTF-8/UTF-16 BOM
        if (first == '\uFEFF') {
            return true;
        }
        // Also check for BOM anywhere in string (unusual but worth checking)
        return s.indexOf('\uFEFF') >= 0;
    }

    /**
     * Removes all BIDI control characters from a string.
     *
     * <p>Use this for sanitization when you need to accept the string
     * but strip dangerous characters.
     *
     * @param s the string to sanitize
     * @return the string with BIDI characters removed
     */
    public static @NotNull String stripBidiCharacters(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isBidiCharacter(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Removes all zero-width characters from a string.
     *
     * @param s the string to sanitize
     * @return the string with zero-width characters removed
     */
    public static @NotNull String stripZeroWidthCharacters(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isZeroWidthCharacter(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Strips all potentially dangerous invisible characters from a string.
     *
     * <p>This includes BIDI characters, zero-width characters, and BOMs.
     *
     * @param s the string to sanitize
     * @return the sanitized string
     */
    public static @NotNull String stripInvisibleCharacters(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isBidiCharacter(c) && !isZeroWidthCharacter(c) && c != '\uFEFF') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Checks if a string contains characters from multiple scripts.
     *
     * <p>This can indicate a homoglyph attack where characters from different
     * scripts that look similar are mixed (e.g., Cyrillic 'а' with Latin 'a').
     *
     * <p>Note: This is a heuristic check and may have false positives for
     * legitimate multilingual paths.
     *
     * @param s the string to check
     * @return true if multiple scripts are detected
     */
    public static boolean containsMixedScripts(@NotNull String s) {
        boolean hasLatin = false;
        boolean hasCyrillic = false;
        boolean hasGreek = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);

            if (block == Character.UnicodeBlock.BASIC_LATIN ||
                block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
                block == Character.UnicodeBlock.LATIN_EXTENDED_A ||
                block == Character.UnicodeBlock.LATIN_EXTENDED_B) {
                hasLatin = true;
            } else if (block == Character.UnicodeBlock.CYRILLIC ||
                       block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) {
                hasCyrillic = true;
            } else if (block == Character.UnicodeBlock.GREEK ||
                       block == Character.UnicodeBlock.GREEK_EXTENDED) {
                hasGreek = true;
            }
        }

        // Check for suspicious combinations
        int scriptCount = (hasLatin ? 1 : 0) + (hasCyrillic ? 1 : 0) + (hasGreek ? 1 : 0);
        return scriptCount > 1;
    }

    /**
     * Normalizes a string to NFC (Canonical Decomposition, followed by Canonical Composition).
     *
     * <p>NFC is the recommended normalization form for most uses. It ensures that
     * characters with multiple representations (e.g., é as single char vs e+combining accent)
     * are represented consistently.
     *
     * @param s the string to normalize
     * @return the NFC-normalized string
     */
    public static @NotNull String normalizeNFC(@NotNull String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /**
     * Normalizes a string to NFD (Canonical Decomposition).
     *
     * @param s the string to normalize
     * @return the NFD-normalized string
     */
    public static @NotNull String normalizeNFD(@NotNull String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD);
    }

    /**
     * Checks if a string is in NFC normalized form.
     *
     * @param s the string to check
     * @return true if the string is already NFC-normalized
     */
    public static boolean isNormalized(@NotNull String s) {
        return Normalizer.isNormalized(s, Normalizer.Form.NFC);
    }
}
