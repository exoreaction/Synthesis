package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportTopic enum — CLI parsing, display names, defaults.
 */
class ReportTopicTest {

    @Test
    void enumHasSevenValues() {
        assertEquals(7, ReportTopic.values().length);
    }

    @Test
    void weekly_hasCorrectCliValue() {
        assertEquals("weekly", ReportTopic.WEEKLY.cliValue());
    }

    @Test
    void pipeline_hasCorrectCliValue() {
        assertEquals("pipeline", ReportTopic.PIPELINE.cliValue());
    }

    @Test
    void activities_hasCorrectCliValue() {
        assertEquals("activities", ReportTopic.ACTIVITIES.cliValue());
    }

    @Test
    void executive_hasCorrectCliValue() {
        assertEquals("executive", ReportTopic.EXECUTIVE.cliValue());
    }

    @Test
    void decisions_hasCorrectCliValue() {
        assertEquals("decisions", ReportTopic.DECISIONS.cliValue());
    }

    @Test
    void product_hasCorrectCliValue() {
        assertEquals("product", ReportTopic.PRODUCT.cliValue());
    }

    @Test
    void client_hasCorrectCliValue() {
        assertEquals("client", ReportTopic.CLIENT.cliValue());
    }

    @Test
    void allTopics_haveNonBlankDisplayName() {
        for (ReportTopic t : ReportTopic.values()) {
            assertFalse(t.displayName().isBlank(), "Display name should not be blank for " + t);
        }
    }

    // --- fromString (lenient, defaults to WEEKLY) ---

    @Test
    void fromString_null_returnsWeekly() {
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString(null));
    }

    @Test
    void fromString_empty_returnsWeekly() {
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString(""));
    }

    @Test
    void fromString_unknown_returnsWeekly() {
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString("quarterly"));
    }

    @ParameterizedTest
    @CsvSource({
        "weekly,      WEEKLY",
        "WEEKLY,      WEEKLY",
        "pipeline,    PIPELINE",
        "PIPELINE,    PIPELINE",
        "activities,  ACTIVITIES",
        "ACTIVITIES,  ACTIVITIES",
        "executive,   EXECUTIVE",
        "EXECUTIVE,   EXECUTIVE",
        "decisions,   DECISIONS",
        "product,     PRODUCT",
        "client,      CLIENT"
    })
    void fromString_caseInsensitive(String input, String expectedName) {
        assertEquals(ReportTopic.valueOf(expectedName), ReportTopic.fromString(input.trim()));
    }

    // --- cliValue round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"weekly", "pipeline", "activities", "executive", "decisions", "product", "client"})
    void cliValue_roundTrip(String cliValue) {
        ReportTopic topic = ReportTopic.fromString(cliValue);
        assertEquals(cliValue, topic.cliValue());
    }
}
