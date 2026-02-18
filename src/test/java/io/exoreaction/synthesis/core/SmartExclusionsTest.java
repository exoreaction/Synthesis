package io.exoreaction.synthesis.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmartExclusions — verifying the UNIVERSAL exclusion list
 * contains expected patterns for version control, IDE files, OS artifacts,
 * logs, and Synthesis-specific directories.
 */
class SmartExclusionsTest {

    // --- UNIVERSAL list is not null/empty ---

    @Test
    void universal_isNotNull() {
        assertNotNull(SmartExclusions.UNIVERSAL);
    }

    @Test
    void universal_isNotEmpty() {
        assertFalse(SmartExclusions.UNIVERSAL.isEmpty());
    }

    @Test
    void universal_hasMoreThan20Entries() {
        assertTrue(SmartExclusions.UNIVERSAL.size() > 20,
                "UNIVERSAL exclusions should have more than 20 patterns");
    }

    // --- version control patterns ---

    @ParameterizedTest
    @ValueSource(strings = {".git/**", "**/.git/**", ".svn/**", "**/.svn/**"})
    void universal_containsVersionControlPattern(String pattern) {
        assertTrue(SmartExclusions.UNIVERSAL.contains(pattern),
                "Should contain VCS pattern: " + pattern);
    }

    // --- IDE patterns ---

    @ParameterizedTest
    @ValueSource(strings = {".idea/**", "**/.idea/**", ".vscode/**", "**/.vscode/**"})
    void universal_containsIdePattern(String pattern) {
        assertTrue(SmartExclusions.UNIVERSAL.contains(pattern),
                "Should contain IDE pattern: " + pattern);
    }

    // --- OS artifact patterns ---

    @ParameterizedTest
    @ValueSource(strings = {".DS_Store", "**/.DS_Store", "Thumbs.db", "**/Thumbs.db"})
    void universal_containsOsArtifactPattern(String pattern) {
        assertTrue(SmartExclusions.UNIVERSAL.contains(pattern),
                "Should contain OS artifact pattern: " + pattern);
    }

    // --- log / temp patterns ---

    @ParameterizedTest
    @ValueSource(strings = {"**/*.log", "logs/**", "tmp/**", "temp/**"})
    void universal_containsLogOrTempPattern(String pattern) {
        assertTrue(SmartExclusions.UNIVERSAL.contains(pattern),
                "Should contain log/temp pattern: " + pattern);
    }

    // --- Synthesis internal directory ---

    @ParameterizedTest
    @ValueSource(strings = {".synthesis/**", "**/.synthesis/**"})
    void universal_containsSynthesisPattern(String pattern) {
        assertTrue(SmartExclusions.UNIVERSAL.contains(pattern),
                "Should exclude .synthesis directory: " + pattern);
    }

    // --- editor backup files ---

    @ParameterizedTest
    @ValueSource(strings = {"**/*.swp", "**/*.swo"})
    void universal_containsEditorBackupPattern(String pattern) {
        assertTrue(SmartExclusions.UNIVERSAL.contains(pattern),
                "Should contain editor backup pattern: " + pattern);
    }

    // --- list is immutable (List.of returns immutable list) ---

    @Test
    void universal_isImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> SmartExclusions.UNIVERSAL.add("extra"),
                "UNIVERSAL list should be immutable");
    }

    // --- no nulls in the list ---

    @Test
    void universal_containsNoNullPatterns() {
        for (String pattern : SmartExclusions.UNIVERSAL) {
            assertNotNull(pattern, "No pattern in UNIVERSAL should be null");
        }
    }

    // --- no blank/empty patterns ---

    @Test
    void universal_containsNoBlankPatterns() {
        for (String pattern : SmartExclusions.UNIVERSAL) {
            assertFalse(pattern.isBlank(), "No pattern in UNIVERSAL should be blank");
        }
    }

    // --- all patterns contain wildcards or start with dot (sanity check) ---

    @Test
    void universal_allPatternsAreMeaningful() {
        for (String pattern : SmartExclusions.UNIVERSAL) {
            boolean hasStar = pattern.contains("*");
            boolean hasDot = pattern.contains(".");
            boolean hasSlash = pattern.contains("/");
            assertTrue(hasStar || hasDot || hasSlash,
                    "Pattern '" + pattern + "' should be a meaningful glob");
        }
    }
}
