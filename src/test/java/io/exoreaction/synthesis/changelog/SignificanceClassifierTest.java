package io.exoreaction.synthesis.changelog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignificanceClassifierTest {

    private final SignificanceClassifier classifier = new SignificanceClassifier();

    @Test
    void classify_noiseFiles() {
        assertEquals(ChangeSignificance.NOISE,
                classifier.classify(".DS_Store", "BINARY", 0, ChangeEvent.ChangeType.ADDED));
        assertEquals(ChangeSignificance.NOISE,
                classifier.classify("Thumbs.db", "BINARY", 0, ChangeEvent.ChangeType.ADDED));
        assertEquals(ChangeSignificance.NOISE,
                classifier.classify("package-lock.json", "JSON", 10000, ChangeEvent.ChangeType.MODIFIED));
    }

    @Test
    void classify_noiseByPath() {
        assertEquals(ChangeSignificance.NOISE,
                classifier.classify(".synthesis/scan-state.json", "JSON", 100,
                        ChangeEvent.ChangeType.MODIFIED));
        assertEquals(ChangeSignificance.NOISE,
                classifier.classify("node_modules/some-pkg/index.js", "CODE", 100,
                        ChangeEvent.ChangeType.ADDED));
        assertEquals(ChangeSignificance.NOISE,
                classifier.classify("target/classes/Main.class", "BINARY", 100,
                        ChangeEvent.ChangeType.ADDED));
    }

    @Test
    void classify_notableFiles() {
        assertEquals(ChangeSignificance.NOTABLE,
                classifier.classify("README.md", "MARKDOWN", 500, ChangeEvent.ChangeType.MODIFIED));
        assertEquals(ChangeSignificance.NOTABLE,
                classifier.classify("pom.xml", "XML", 3000, ChangeEvent.ChangeType.MODIFIED));
        assertEquals(ChangeSignificance.NOTABLE,
                classifier.classify("Dockerfile", "CODE", 200, ChangeEvent.ChangeType.MODIFIED));
    }

    @Test
    void classify_notableForLargeNewFiles() {
        assertEquals(ChangeSignificance.NOTABLE,
                classifier.classify("big-report.pdf", "PDF", 2_000_000,
                        ChangeEvent.ChangeType.ADDED));
    }

    @Test
    void classify_criticalFiles() {
        assertEquals(ChangeSignificance.CRITICAL,
                classifier.classify(".env", "TEXT", 50, ChangeEvent.ChangeType.MODIFIED));
        assertEquals(ChangeSignificance.CRITICAL,
                classifier.classify("credentials.json", "JSON", 200,
                        ChangeEvent.ChangeType.ADDED));
        assertEquals(ChangeSignificance.CRITICAL,
                classifier.classify("server.key", "BINARY", 2048,
                        ChangeEvent.ChangeType.ADDED));
    }

    @Test
    void classify_normalFiles() {
        assertEquals(ChangeSignificance.NORMAL,
                classifier.classify("src/Main.java", "CODE", 500,
                        ChangeEvent.ChangeType.MODIFIED));
        assertEquals(ChangeSignificance.NORMAL,
                classifier.classify("docs/guide.md", "MARKDOWN", 3000,
                        ChangeEvent.ChangeType.ADDED));
    }

    @Test
    void isMassDeletion_respectsThreshold() {
        SignificanceClassifier custom = new SignificanceClassifier(List.of(), List.of(), 5);
        assertFalse(custom.isMassDeletion(4));
        assertTrue(custom.isMassDeletion(5));
        assertTrue(custom.isMassDeletion(100));
    }

    @Test
    void customPaths_extendDefaults() {
        SignificanceClassifier custom = new SignificanceClassifier(
                List.of("**/logs/**"),
                List.of("**/production.config"),
                10
        );

        assertEquals(ChangeSignificance.NOISE,
                custom.classify("logs/app.log", "TEXT", 100, ChangeEvent.ChangeType.ADDED));
        assertEquals(ChangeSignificance.CRITICAL,
                custom.classify("production.config", "TEXT", 100, ChangeEvent.ChangeType.MODIFIED));
    }
}
