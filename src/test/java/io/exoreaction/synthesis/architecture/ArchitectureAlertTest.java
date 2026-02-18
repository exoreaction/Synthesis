package io.exoreaction.synthesis.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ArchitectureAlert record — all fields, enum values,
 * toSummaryLine formatting, and toMap serialization.
 */
class ArchitectureAlertTest {

    // --- Severity enum ---

    @Test
    void severity_threeValues_ERROR_WARNING_INFO() {
        assertEquals(3, ArchitectureAlert.Severity.values().length);
    }

    @Test
    void severity_ordinalOrdering_errorFirst() {
        assertTrue(ArchitectureAlert.Severity.ERROR.ordinal() <
                ArchitectureAlert.Severity.WARNING.ordinal());
        assertTrue(ArchitectureAlert.Severity.WARNING.ordinal() <
                ArchitectureAlert.Severity.INFO.ordinal());
    }

    @ParameterizedTest
    @EnumSource(ArchitectureAlert.Severity.class)
    void severity_valueOf_roundTrips(ArchitectureAlert.Severity severity) {
        assertEquals(severity, ArchitectureAlert.Severity.valueOf(severity.name()));
    }

    @Test
    void severity_ERROR_ordinal_isZero() {
        assertEquals(0, ArchitectureAlert.Severity.ERROR.ordinal());
    }

    @Test
    void severity_INFO_isLast() {
        ArchitectureAlert.Severity[] values = ArchitectureAlert.Severity.values();
        assertEquals(ArchitectureAlert.Severity.INFO, values[values.length - 1]);
    }

    // --- Category enum ---

    @ParameterizedTest
    @EnumSource(ArchitectureAlert.Category.class)
    void category_valueOf_roundTrips(ArchitectureAlert.Category cat) {
        assertEquals(cat, ArchitectureAlert.Category.valueOf(cat.name()));
    }

    @Test
    void category_includesAllExpectedValues() {
        var categories = ArchitectureAlert.Category.values();
        assertTrue(categories.length >= 7, "Should have at least 7 categories");

        var names = java.util.Arrays.stream(categories)
                .map(Enum::name).toList();
        assertTrue(names.contains("CIRCULAR_DEPENDENCY"));
        assertTrue(names.contains("GOD_CLASS"));
        assertTrue(names.contains("DEAD_CODE"));
        assertTrue(names.contains("MISSING_DOCUMENTATION"));
        assertTrue(names.contains("TEST_COVERAGE_GAP"));
        assertTrue(names.contains("HIGH_COUPLING"));
        assertTrue(names.contains("AI_DETECTED"));
    }

    // --- Record construction ---

    @Test
    void record_allFields_accessible() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.WARNING,
                ArchitectureAlert.Category.GOD_CLASS,
                "src/BigClass.java",
                "Too many lines",
                Map.of("lineCount", 2000L));

        assertEquals(ArchitectureAlert.Severity.WARNING, alert.severity());
        assertEquals(ArchitectureAlert.Category.GOD_CLASS, alert.category());
        assertEquals("src/BigClass.java", alert.filePath());
        assertEquals("Too many lines", alert.message());
        assertNotNull(alert.metadata());
        assertEquals(2000L, alert.metadata().get("lineCount"));
    }

    @Test
    void record_emptyMetadata_ok() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.INFO,
                ArchitectureAlert.Category.TEST_COVERAGE_GAP,
                "Service.java",
                "No test found",
                Map.of());
        assertNotNull(alert.metadata());
        assertTrue(alert.metadata().isEmpty());
    }

    @Test
    void record_equality_sameValues() {
        var a1 = new ArchitectureAlert(
                ArchitectureAlert.Severity.ERROR,
                ArchitectureAlert.Category.CIRCULAR_DEPENDENCY,
                "module/A",
                "Cycle",
                Map.of());
        var a2 = new ArchitectureAlert(
                ArchitectureAlert.Severity.ERROR,
                ArchitectureAlert.Category.CIRCULAR_DEPENDENCY,
                "module/A",
                "Cycle",
                Map.of());
        assertEquals(a1, a2);
        assertEquals(a1.hashCode(), a2.hashCode());
    }

    @Test
    void record_equality_differentSeverity_notEqual() {
        var a1 = new ArchitectureAlert(
                ArchitectureAlert.Severity.ERROR,
                ArchitectureAlert.Category.GOD_CLASS,
                "A.java", "msg", Map.of());
        var a2 = new ArchitectureAlert(
                ArchitectureAlert.Severity.WARNING,
                ArchitectureAlert.Category.GOD_CLASS,
                "A.java", "msg", Map.of());
        assertNotEquals(a1, a2);
    }

    @Test
    void record_toString_containsCategory() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.INFO,
                ArchitectureAlert.Category.DEAD_CODE,
                "Unused.java", "No incoming refs", Map.of());
        assertTrue(alert.toString().contains("DEAD_CODE") ||
                alert.toString().contains("Unused.java"));
    }

    // --- toSummaryLine ---

    @Test
    void toSummaryLine_ERROR_containsERROR() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.ERROR,
                ArchitectureAlert.Category.CIRCULAR_DEPENDENCY,
                "A -> B",
                "Circular dep detected",
                Map.of());
        String line = alert.toSummaryLine();
        assertTrue(line.contains("[ERROR]"), "ERROR severity should show [ERROR]");
        assertTrue(line.contains("CIRCULAR_DEPENDENCY"));
        assertTrue(line.contains("Circular dep detected"));
    }

    @Test
    void toSummaryLine_WARNING_containsWARN() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.WARNING,
                ArchitectureAlert.Category.GOD_CLASS,
                "BigClass.java",
                "Too big",
                Map.of());
        String line = alert.toSummaryLine();
        assertTrue(line.contains("[WARN]"), "WARNING severity should show [WARN]");
        assertTrue(line.contains("GOD_CLASS"));
    }

    @Test
    void toSummaryLine_INFO_containsINFO() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.INFO,
                ArchitectureAlert.Category.TEST_COVERAGE_GAP,
                "Service.java",
                "No test",
                Map.of());
        String line = alert.toSummaryLine();
        assertTrue(line.contains("[INFO]"), "INFO severity should show [INFO]");
        assertTrue(line.contains("TEST_COVERAGE_GAP"));
    }

    @Test
    void toSummaryLine_containsFilePath() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.WARNING,
                ArchitectureAlert.Category.HIGH_COUPLING,
                "src/core/Router.java",
                "Too many refs",
                Map.of());
        assertTrue(alert.toSummaryLine().contains("src/core/Router.java"));
    }

    @ParameterizedTest
    @EnumSource(ArchitectureAlert.Category.class)
    void toSummaryLine_allCategories_containCategoryName(ArchitectureAlert.Category cat) {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.INFO, cat, "file.java", "msg", Map.of());
        assertTrue(alert.toSummaryLine().contains(cat.name()),
                "toSummaryLine should contain category name: " + cat.name());
    }

    // --- toMap ---

    @Test
    void toMap_containsAllKeys() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.WARNING,
                ArchitectureAlert.Category.GOD_CLASS,
                "GodClass.java",
                "Too many lines",
                Map.of("lineCount", 1500));

        Map<String, Object> map = alert.toMap();
        assertTrue(map.containsKey("severity"));
        assertTrue(map.containsKey("category"));
        assertTrue(map.containsKey("filePath"));
        assertTrue(map.containsKey("message"));
        assertTrue(map.containsKey("metadata"));
    }

    @Test
    void toMap_severity_isStringName() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.ERROR,
                ArchitectureAlert.Category.CIRCULAR_DEPENDENCY,
                "A", "msg", Map.of());
        assertEquals("ERROR", alert.toMap().get("severity"));
    }

    @Test
    void toMap_category_isStringName() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.INFO,
                ArchitectureAlert.Category.DEAD_CODE,
                "A", "msg", Map.of());
        assertEquals("DEAD_CODE", alert.toMap().get("category"));
    }

    @Test
    void toMap_filePath_matches() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.WARNING,
                ArchitectureAlert.Category.HIGH_COUPLING,
                "src/Foo.java", "msg", Map.of());
        assertEquals("src/Foo.java", alert.toMap().get("filePath"));
    }

    @Test
    void toMap_message_matches() {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.INFO,
                ArchitectureAlert.Category.MISSING_DOCUMENTATION,
                "docs/", "Add README", Map.of());
        assertEquals("Add README", alert.toMap().get("message"));
    }

    @Test
    void toMap_metadata_preserved() {
        Map<String, Object> meta = Map.of("lineCount", 2000L, "threshold", 1000L);
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.ERROR,
                ArchitectureAlert.Category.GOD_CLASS,
                "Big.java", "msg", meta);
        Object storedMeta = alert.toMap().get("metadata");
        assertNotNull(storedMeta);
    }

    @ParameterizedTest
    @EnumSource(ArchitectureAlert.Severity.class)
    void toMap_allSeverities_returnStringName(ArchitectureAlert.Severity sev) {
        ArchitectureAlert alert = new ArchitectureAlert(
                sev, ArchitectureAlert.Category.DEAD_CODE, "f.java", "m", Map.of());
        assertEquals(sev.name(), alert.toMap().get("severity"));
    }

    // --- Combined parameterized ---

    @ParameterizedTest
    @CsvSource({
        "ERROR,   CIRCULAR_DEPENDENCY, moduleA/B,   Cycle detected",
        "WARNING, GOD_CLASS,           BigClass.java, 2000 lines",
        "INFO,    TEST_COVERAGE_GAP,   Service.java,  No test found",
        "INFO,    DEAD_CODE,           Unused.java,   Zero refs",
        "WARNING, HIGH_COUPLING,       Router.java,   Many refs",
        "INFO,    MISSING_DOCUMENTATION, docs/,       No README"
    })
    void alert_variousCombinations_allFieldsCorrect(
            String sev, String cat, String path, String msg) {
        ArchitectureAlert alert = new ArchitectureAlert(
                ArchitectureAlert.Severity.valueOf(sev),
                ArchitectureAlert.Category.valueOf(cat),
                path, msg, Map.of());

        assertEquals(sev, alert.severity().name());
        assertEquals(cat, alert.category().name());
        assertEquals(path, alert.filePath());
        assertEquals(msg, alert.message());

        // toSummaryLine should be non-null and non-empty
        assertNotNull(alert.toSummaryLine());
        assertFalse(alert.toSummaryLine().isEmpty());

        // toMap should be non-null
        assertNotNull(alert.toMap());
    }
}
