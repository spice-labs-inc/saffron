/*
 * Copyright 2026 Spice Labs, Inc.
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package io.spicelabs.saffron.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link UnicodeSecurityUtils}.
 */
class UnicodeSecurityUtilsTest {

    @Test
    void validatePath_bidiOverride_rejected() {
        // Right-to-left override character
        String maliciousPath = "foo\u202Ebar.txt";

        assertThatThrownBy(() -> UnicodeSecurityUtils.validateAndNormalize(maliciousPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bidirectional");
    }

    @Test
    void validatePath_zeroWidthSpace_rejected() {
        String maliciousPath = "foo\u200Bbar.txt";

        assertThatThrownBy(() -> UnicodeSecurityUtils.validateAndNormalize(maliciousPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Zero-width");
    }

    @Test
    void validatePath_zeroWidthNonJoiner_rejected() {
        String maliciousPath = "foo\u200Cbar.txt";

        assertThatThrownBy(() -> UnicodeSecurityUtils.validateAndNormalize(maliciousPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Zero-width");
    }

    @Test
    void validatePath_zeroWidthJoiner_rejected() {
        String maliciousPath = "foo\u200Dbar.txt";

        assertThatThrownBy(() -> UnicodeSecurityUtils.validateAndNormalize(maliciousPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Zero-width");
    }

    @Test
    void validatePath_byteOrderMark_rejected() {
        String maliciousPath = "\uFEFFfoo.txt";

        assertThatThrownBy(() -> UnicodeSecurityUtils.validateAndNormalize(maliciousPath))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatePath_normalUnicode_normalized() {
        // NFD form: e + combining acute accent
        String nfdPath = "cafe\u0301.txt";
        String normalized = UnicodeSecurityUtils.validateAndNormalize(nfdPath);
        // Should be NFC form: single é character
        assertThat(normalized).isEqualTo("caf\u00e9.txt");
    }

    @Test
    void validatePath_normalAscii_passesThrough() {
        String path = "foo/bar/baz.txt";
        String normalized = UnicodeSecurityUtils.validateAndNormalize(path);
        assertThat(normalized).isEqualTo(path);
    }

    @Test
    void containsBidiCharacters_detectsLRM() {
        assertThat(UnicodeSecurityUtils.containsBidiCharacters("test\u200Efoo")).isTrue();
    }

    @Test
    void containsBidiCharacters_detectsRLM() {
        assertThat(UnicodeSecurityUtils.containsBidiCharacters("test\u200Ffoo")).isTrue();
    }

    @Test
    void containsBidiCharacters_detectsLRE() {
        assertThat(UnicodeSecurityUtils.containsBidiCharacters("test\u202Afoo")).isTrue();
    }

    @Test
    void containsBidiCharacters_detectsRLE() {
        assertThat(UnicodeSecurityUtils.containsBidiCharacters("test\u202Bfoo")).isTrue();
    }

    @Test
    void containsBidiCharacters_detectsRLO() {
        assertThat(UnicodeSecurityUtils.containsBidiCharacters("test\u202Efoo")).isTrue();
    }

    @Test
    void containsBidiCharacters_normalText_returnsFalse() {
        assertThat(UnicodeSecurityUtils.containsBidiCharacters("normal text")).isFalse();
    }

    @Test
    void containsZeroWidthCharacters_detectsZWSP() {
        assertThat(UnicodeSecurityUtils.containsZeroWidthCharacters("test\u200Bfoo")).isTrue();
    }

    @Test
    void containsZeroWidthCharacters_detectsZWNJ() {
        assertThat(UnicodeSecurityUtils.containsZeroWidthCharacters("test\u200Cfoo")).isTrue();
    }

    @Test
    void containsZeroWidthCharacters_detectsZWJ() {
        assertThat(UnicodeSecurityUtils.containsZeroWidthCharacters("test\u200Dfoo")).isTrue();
    }

    @Test
    void containsZeroWidthCharacters_normalText_returnsFalse() {
        assertThat(UnicodeSecurityUtils.containsZeroWidthCharacters("normal text")).isFalse();
    }

    @Test
    void containsByteOrderMark_detectsBOM() {
        assertThat(UnicodeSecurityUtils.containsByteOrderMark("\uFEFFtext")).isTrue();
    }

    @Test
    void containsByteOrderMark_emptyString_returnsFalse() {
        assertThat(UnicodeSecurityUtils.containsByteOrderMark("")).isFalse();
    }

    @Test
    void containsByteOrderMark_normalText_returnsFalse() {
        assertThat(UnicodeSecurityUtils.containsByteOrderMark("normal text")).isFalse();
    }

    @Test
    void stripBidiCharacters_removesAllBidiChars() {
        String input = "test\u202Efoo\u200Fbar";
        String result = UnicodeSecurityUtils.stripBidiCharacters(input);
        assertThat(result).isEqualTo("testfoobar");
    }

    @Test
    void stripZeroWidthCharacters_removesAllZeroWidthChars() {
        String input = "test\u200Bfoo\u200Cbar\u200Dbaz";
        String result = UnicodeSecurityUtils.stripZeroWidthCharacters(input);
        assertThat(result).isEqualTo("testfoobarbaz");
    }

    @Test
    void stripInvisibleCharacters_removesAllInvisibleChars() {
        String input = "\uFEFFtest\u202Efoo\u200Bbar";
        String result = UnicodeSecurityUtils.stripInvisibleCharacters(input);
        assertThat(result).isEqualTo("testfoobar");
    }

    @Test
    void containsMixedScripts_detectsCyrillicMixedWithLatin() {
        // Mix of Latin 'a' and Cyrillic 'а' (looks identical but different codepoints)
        String mixed = "pаypal"; // Contains Cyrillic 'а'
        assertThat(UnicodeSecurityUtils.containsMixedScripts(mixed)).isTrue();
    }

    @Test
    void containsMixedScripts_pureLatinReturnsFalse() {
        assertThat(UnicodeSecurityUtils.containsMixedScripts("paypal")).isFalse();
    }

    @Test
    void containsMixedScripts_pureCyrillicReturnsFalse() {
        assertThat(UnicodeSecurityUtils.containsMixedScripts("привет")).isFalse();
    }

    @Test
    void normalizeNFC_convertsNFDtoNFC() {
        // NFD: e + combining acute accent
        String nfd = "cafe\u0301";
        String nfc = UnicodeSecurityUtils.normalizeNFC(nfd);
        assertThat(nfc).isEqualTo("caf\u00e9");
    }

    @Test
    void normalizeNFD_convertsNFCtoNFD() {
        // NFC: single é character
        String nfc = "caf\u00e9";
        String nfd = UnicodeSecurityUtils.normalizeNFD(nfc);
        assertThat(nfd).isEqualTo("cafe\u0301");
    }

    @Test
    void isNormalized_returnsTrueForNFCString() {
        assertThat(UnicodeSecurityUtils.isNormalized("caf\u00e9")).isTrue();
    }

    @Test
    void isNormalized_returnsFalseForNFDString() {
        assertThat(UnicodeSecurityUtils.isNormalized("cafe\u0301")).isFalse();
    }
}
