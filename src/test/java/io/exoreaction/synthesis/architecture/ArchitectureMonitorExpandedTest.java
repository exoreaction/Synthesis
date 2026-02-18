package io.exoreaction.synthesis.architecture;

import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expanded tests for ArchitectureMonitor — detection edge cases, entry-point/test/config
 * skip logic, god-class method count, documentation thresholds, and constant verification.
 */
class ArchitectureMonitorExpandedTest {

    @TempDir
    Path tempDir;

    private ArchitectureMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ArchitectureMonitor(tempDir);
    }

    // --- Constants ---

    @Test
    void constant_godClassLineThreshold_is1000() {
        assertEquals(1000, ArchitectureMonitor.GOD_CLASS_LINE_THRESHOLD);
    }

    @Test
    void constant_godClassMethodThreshold_is50() {
        assertEquals(50, ArchitectureMonitor.GOD_CLASS_METHOD_THRESHOLD);
    }

    @Test
    void constant_highCouplingThreshold_is20() {
        assertEquals(20, ArchitectureMonitor.HIGH_COUPLING_THRESHOLD);
    }

    @Test
    void constant_deadCodeThreshold_isZero() {
        assertEquals(0, ArchitectureMonitor.DEAD_CODE_THRESHOLD);
    }

    // --- detectGodClasses: method count via structure field ---

    @Test
    void detectGodClasses_highMethodCount_generatesAlert() throws IOException {
        Path file = tempDir.resolve("BigService.java");
        Files.writeString(file, "public class BigService {}");

        // Structure field says "60 methods"
        SearchResult result = new SearchResult(
                file, "BigService.java", 1.0f, "BigService.java",
                "CODE", "Java", "", "", "BigService with 60 methods and fields",
                Files.size(file));

        List<ArchitectureAlert> alerts = monitor.detectGodClasses(List.of(result));

        // Should have an alert for method count exceeding threshold (50)
        boolean hasMethodAlert = alerts.stream()
                .anyMatch(a -> a.category() == ArchitectureAlert.Category.GOD_CLASS
                        && a.message().contains("method"));
        assertTrue(hasMethodAlert, "Should detect god class via method count");
    }

    @Test
    void detectGodClasses_nullStructure_noMethodAlert() throws IOException {
        Path file = tempDir.resolve("Normal.java");
        Files.writeString(file, "class Normal {}");

        SearchResult result = new SearchResult(
                file, "Normal.java", 1.0f, "Normal.java",
                "CODE", "Java", null, "", "", Files.size(file));

        // Null structure → method count = 0 → no method alert
        List<ArchitectureAlert> alerts = monitor.detectGodClasses(List.of(result));
        boolean hasMethodAlert = alerts.stream()
                .anyMatch(a -> a.message().contains("method"));
        assertFalse(hasMethodAlert, "Null structure should not produce method alert");
    }

    @Test
    void detectGodClasses_exactThreshold_noAlert() throws IOException {
        // Exactly 1000 lines — should NOT trigger (threshold is > 1000)
        Path file = tempDir.resolve("Exactly1000.java");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append("// line ").append(i).append("\n");
        Files.writeString(file, sb.toString());

        SearchResult result = new SearchResult(
                file, "Exactly1000.java", 1.0f, "Exactly1000.java",
                "CODE", "Java", "", "", "", Files.size(file));

        List<ArchitectureAlert> alerts = monitor.detectGodClasses(List.of(result));
        // 1000 lines is exactly the threshold; implementation uses > so no alert
        boolean hasSizeAlert = alerts.stream()
                .anyMatch(a -> a.message().contains("1000"));
        assertFalse(hasSizeAlert, "Exactly at threshold should not trigger (uses >)");
    }

    @Test
    void detectGodClasses_nonExistentFile_noAlert() {
        // File doesn't exist on disk
        SearchResult result = new SearchResult(
                tempDir.resolve("Ghost.java"), "Ghost.java", 1.0f, "Ghost.java",
                "CODE", "Java", "", "", "", 500);

        List<ArchitectureAlert> alerts = monitor.detectGodClasses(List.of(result));
        // Non-existent file → skipped
        assertTrue(alerts.isEmpty() ||
                alerts.stream().noneMatch(a -> a.message().contains("lines")),
                "Non-existent files should be skipped");
    }

    @Test
    void detectGodClasses_emptyCriticalList_noAlert() {
        List<ArchitectureAlert> alerts = monitor.detectGodClasses(List.of());
        assertTrue(alerts.isEmpty());
    }

    // --- detectMissingDocumentation: edge cases ---

    @Test
    void detectMissingDocumentation_emptyList_noAlerts() {
        List<ArchitectureAlert> alerts = monitor.detectMissingDocumentation(List.of());
        assertTrue(alerts.isEmpty());
    }

    @Test
    void detectMissingDocumentation_twoFilesOnly_skipped() {
        // Directory with < 3 files → skip (implementation checks files.size() < 3)
        List<SearchResult> files = List.of(
                makeSearchResult("src/A.java", "A.java", "CODE"),
                makeSearchResult("src/B.java", "B.java", "CODE")
        );
        List<ArchitectureAlert> alerts = monitor.detectMissingDocumentation(files);
        assertTrue(alerts.isEmpty(), "Directories with < 3 files should be skipped");
    }

    @Test
    void detectMissingDocumentation_noCodeFiles_skipped() {
        // Directory with only markdown files (no code) → skipped
        List<SearchResult> files = List.of(
                makeSearchResult("docs/guide.md", "guide.md", "MARKDOWN"),
                makeSearchResult("docs/setup.md", "setup.md", "MARKDOWN"),
                makeSearchResult("docs/api.md", "api.md", "MARKDOWN")
        );
        List<ArchitectureAlert> alerts = monitor.detectMissingDocumentation(files);
        assertTrue(alerts.isEmpty(), "Directories with only non-code files should be skipped");
    }

    @Test
    void detectMissingDocumentation_bareName_README_accepted() {
        // A file named exactly "README" (no extension) should satisfy documentation check
        List<SearchResult> files = List.of(
                makeSearchResult("src/A.java", "A.java", "CODE"),
                makeSearchResult("src/B.java", "B.java", "CODE"),
                makeSearchResult("src/C.java", "C.java", "CODE"),
                makeSearchResult("src/README", "README", "OTHER")
        );
        List<ArchitectureAlert> alerts = monitor.detectMissingDocumentation(files);
        assertTrue(alerts.isEmpty(), "Bare 'README' file should satisfy documentation requirement");
    }

    @Test
    void detectMissingDocumentation_readmeTxt_accepted() {
        List<SearchResult> files = List.of(
                makeSearchResult("module/A.java", "A.java", "CODE"),
                makeSearchResult("module/B.java", "B.java", "CODE"),
                makeSearchResult("module/C.java", "C.java", "CODE"),
                makeSearchResult("module/README.txt", "README.txt", "OTHER")
        );
        List<ArchitectureAlert> alerts = monitor.detectMissingDocumentation(files);
        assertTrue(alerts.isEmpty(), "README.txt should satisfy documentation requirement");
    }

    @Test
    void detectMissingDocumentation_infoSeverity() {
        List<SearchResult> files = List.of(
                makeSearchResult("svc/A.java", "A.java", "CODE"),
                makeSearchResult("svc/B.java", "B.java", "CODE"),
                makeSearchResult("svc/C.java", "C.java", "CODE")
        );
        List<ArchitectureAlert> alerts = monitor.detectMissingDocumentation(files);
        assertFalse(alerts.isEmpty());
        assertEquals(ArchitectureAlert.Severity.INFO, alerts.get(0).severity());
        assertEquals(ArchitectureAlert.Category.MISSING_DOCUMENTATION, alerts.get(0).category());
    }

    @Test
    void detectMissingDocumentation_metadata_containsFileCount() {
        List<SearchResult> files = List.of(
                makeSearchResult("svc/A.java", "A.java", "CODE"),
                makeSearchResult("svc/B.java", "B.java", "CODE"),
                makeSearchResult("svc/C.java", "C.java", "CODE")
        );
        List<ArchitectureAlert> alerts = monitor.detectMissingDocumentation(files);
        assertFalse(alerts.isEmpty());
        assertTrue(alerts.get(0).metadata().containsKey("fileCount"),
                "metadata should contain fileCount");
    }

    // --- detectTestCoverageGaps: various naming patterns ---

    @Test
    void detectTestCoverageGaps_emptyList_noAlerts() {
        assertTrue(monitor.detectTestCoverageGaps(List.of()).isEmpty());
    }

    @Test
    void detectTestCoverageGaps_infoSeverity() {
        List<SearchResult> files = List.of(
                makeSearchResult("AuthService.java", "AuthService.java", "CODE")
        );
        List<ArchitectureAlert> alerts = monitor.detectTestCoverageGaps(files);
        assertFalse(alerts.isEmpty());
        assertEquals(ArchitectureAlert.Severity.INFO, alerts.get(0).severity());
        assertEquals(ArchitectureAlert.Category.TEST_COVERAGE_GAP, alerts.get(0).category());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Main.java", "App.java", "Application.java",
        "index.js", "index.ts", "main.py", "main.go", "main.rs",
        "mod.rs", "__init__.py", "__main__.py"
    })
    void detectTestCoverageGaps_entryPointFiles_skipped(String filename) {
        List<SearchResult> files = List.of(
                makeSearchResult(filename, filename, "CODE")
        );
        List<ArchitectureAlert> alerts = monitor.detectTestCoverageGaps(files);
        assertTrue(alerts.isEmpty(), "Entry point '" + filename + "' should be skipped");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FooTest.java", "BarTest.java", "ServiceTest.java",
        "foo.spec.ts", "bar.spec.js", "TestHelper.java"
    })
    void detectTestCoverageGaps_testFiles_skipped(String filename) {
        List<SearchResult> files = List.of(
                makeSearchResult(filename, filename, "CODE")
        );
        List<ArchitectureAlert> alerts = monitor.detectTestCoverageGaps(files);
        assertTrue(alerts.isEmpty(), "Test file '" + filename + "' should be skipped");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "pom.xml", "build.gradle", "package.json", "tsconfig.json",
        "cargo.toml", "config.yaml", "app.yml", "app.properties", "server.toml"
    })
    void detectTestCoverageGaps_configFiles_skipped(String filename) {
        List<SearchResult> files = List.of(
                makeSearchResult(filename, filename, "CODE")
        );
        List<ArchitectureAlert> alerts = monitor.detectTestCoverageGaps(files);
        assertTrue(alerts.isEmpty(), "Config file '" + filename + "' should be skipped");
    }

    // --- detectDeadCode: skip logic ---

    @Test
    void detectDeadCode_emptyLists_noAlerts() {
        assertTrue(monitor.detectDeadCode(List.of(), List.of()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Main.java", "App.java", "Application.java",
        "index.js", "main.py", "main.go"
    })
    void detectDeadCode_entryPoints_skipped(String filename) {
        SearchResult file = makeSearchResult(filename, filename, "CODE");
        List<ArchitectureAlert> alerts = monitor.detectDeadCode(
                List.of(file), List.of(file));
        assertTrue(alerts.isEmpty(), "Entry point '" + filename + "' should not be flagged as dead code");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FooTest.java", "ServiceSpec.java", "TestHelper.java"
    })
    void detectDeadCode_testFiles_skipped(String filename) {
        SearchResult file = makeSearchResult(filename, filename, "CODE");
        List<ArchitectureAlert> alerts = monitor.detectDeadCode(
                List.of(file), List.of(file));
        assertTrue(alerts.isEmpty(), "Test file '" + filename + "' should not be flagged as dead code");
    }

    @Test
    void detectDeadCode_orphanNonEntryPoint_flagged() {
        SearchResult orphan = makeSearchResult("Unused.java", "Unused.java", "CODE");
        List<ArchitectureAlert> alerts = monitor.detectDeadCode(
                List.of(orphan), List.of(orphan));
        assertFalse(alerts.isEmpty(), "Unreferenced non-entry file should be flagged");
        assertEquals(ArchitectureAlert.Category.DEAD_CODE, alerts.get(0).category());
        assertEquals(ArchitectureAlert.Severity.INFO, alerts.get(0).severity());
    }

    @Test
    void detectDeadCode_multipleOrphans_allFlagged() {
        List<SearchResult> orphans = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            orphans.add(makeSearchResult("Util" + i + ".java", "Util" + i + ".java", "CODE"));
        }
        List<ArchitectureAlert> alerts = monitor.detectDeadCode(orphans, orphans);
        assertEquals(5, alerts.size(), "All 5 orphan files should be flagged");
    }

    // --- Alert model constants ---

    @Test
    void alert_allCategoryValues_atLeast7() {
        assertTrue(ArchitectureAlert.Category.values().length >= 7);
    }

    @Test
    void alert_allSeverityValues_exactly3() {
        assertEquals(3, ArchitectureAlert.Severity.values().length);
    }

    @Test
    void alert_toSummaryLine_INFO_format() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.INFO,
                ArchitectureAlert.Category.TEST_COVERAGE_GAP,
                "svc/Service.java",
                "No test file",
                Map.of());
        String line = alert.toSummaryLine();
        assertTrue(line.startsWith("[INFO]"), "INFO alerts should start with [INFO]");
    }

    @Test
    void alert_toSummaryLine_WARNING_format() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.WARNING,
                ArchitectureAlert.Category.GOD_CLASS,
                "Big.java",
                "Too big",
                Map.of());
        assertTrue(alert.toSummaryLine().startsWith("[WARN]"),
                "WARNING alerts should start with [WARN]");
    }

    @Test
    void alert_toSummaryLine_ERROR_format() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.ERROR,
                ArchitectureAlert.Category.CIRCULAR_DEPENDENCY,
                "A -> B",
                "Cycle",
                Map.of());
        assertTrue(alert.toSummaryLine().startsWith("[ERROR]"),
                "ERROR alerts should start with [ERROR]");
    }

    // --- analyzeFile: edge cases ---

    @Test
    void analyzeFile_nonExistentFile_returnsEmpty() throws IOException {
        // Create a minimal mock-like scenario
        Path ghost = tempDir.resolve("Ghost.java");
        // Don't create the file
        List<ArchitectureAlert> alerts = monitor.analyzeFile(ghost, null);
        assertTrue(alerts.isEmpty(), "Non-existent file should return empty alerts");
    }

    // --- Helper ---

    private SearchResult makeSearchResult(String relativePath, String fileName, String fileType) {
        return new SearchResult(
                tempDir.resolve(fileName), relativePath, 1.0f, fileName,
                fileType, "Java", "", "", "", 200);
    }
}
