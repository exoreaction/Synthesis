package io.exoreaction.synthesis.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeEnricherTest {

    private Connection conn;
    private final KnowledgeEnricher enricher = new KnowledgeEnricher();

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.createStatement().execute(
            "CREATE TABLE knowledge_edges (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  skill_path TEXT NOT NULL," +
            "  source_path TEXT NOT NULL," +
            "  entity_name TEXT," +
            "  coverage_type TEXT DEFAULT 'mentioned'," +
            "  skill_modified_at INTEGER," +
            "  source_modified_at INTEGER," +
            "  drift_days INTEGER," +
            "  confidence TEXT DEFAULT 'HIGH'," +
            "  last_reconciled_at INTEGER," +
            "  UNIQUE(skill_path, source_path, entity_name)" +
            ")"
        );
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private void insertEdge(String skillPath, String sourcePath, String entity,
                            int driftDays, String confidence) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO knowledge_edges " +
                "(skill_path, source_path, entity_name, drift_days, confidence) VALUES (?,?,?,?,?)")) {
            ps.setString(1, skillPath);
            ps.setString(2, sourcePath);
            ps.setString(3, entity);
            ps.setInt(4, driftDays);
            ps.setString(5, confidence);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------
    // enrichForSource() — gap detection
    // -----------------------------------------------------------------------

    @Test
    void enrichForSource_returnsGapWhenNoEdges() throws Exception {
        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/Undocumented.java", conn);
        assertTrue(r.hasGap());
        assertFalse(r.hasDocumentation());
        assertEquals("NONE", r.overallConfidence());
        assertTrue(r.skills().isEmpty());
    }

    @Test
    void enrichForSource_returnsEdgesWhenPresent() throws Exception {
        insertEdge("docs/skill.md", "src/main/SearchIndex.java", "SearchIndex", 0, "HIGH");
        insertEdge("docs/skill.md", "src/main/SearchIndex.java", "openReadOnly", 0, "HIGH");

        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/SearchIndex.java", conn);

        assertFalse(r.hasGap());
        assertTrue(r.hasDocumentation());
        assertEquals(2, r.skills().size());
        assertEquals("HIGH", r.overallConfidence());
    }

    @Test
    void enrichForSource_groupsBySkill() throws Exception {
        insertEdge("docs/skill-a.md", "src/main/Foo.java", "Foo", 0, "HIGH");
        insertEdge("docs/skill-b.md", "src/main/Foo.java", "Foo", 5, "MEDIUM");

        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/Foo.java", conn);

        assertEquals(2, r.bySkill().size());
        assertTrue(r.bySkill().containsKey("docs/skill-a.md"));
        assertTrue(r.bySkill().containsKey("docs/skill-b.md"));
    }

    @Test
    void enrichForSource_overallConfidenceIsWorst() throws Exception {
        // Mix of HIGH, MEDIUM, and LOW — worst should be LOW
        insertEdge("docs/a.md", "src/main/Bar.java", "Bar", 0, "HIGH");
        insertEdge("docs/b.md", "src/main/Bar.java", "Bar", 5, "MEDIUM");
        insertEdge("docs/c.md", "src/main/Bar.java", "Bar", 20, "LOW");

        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/Bar.java", conn);

        assertEquals("LOW", r.overallConfidence());
    }

    @Test
    void enrichForSource_staleIsWorstConfidence() throws Exception {
        insertEdge("docs/a.md", "src/main/Baz.java", "Baz", 0, "HIGH");
        insertEdge("docs/b.md", "src/main/Baz.java", "Baz", 35, "STALE");

        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/Baz.java", conn);

        assertEquals("STALE", r.overallConfidence());
    }

    @Test
    void enrichForSource_doesNotReturnEdgesForOtherPaths() throws Exception {
        insertEdge("docs/skill.md", "src/main/Alpha.java", "Alpha", 0, "HIGH");

        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/Beta.java", conn);

        assertTrue(r.hasGap());
    }

    // -----------------------------------------------------------------------
    // enrichForSources() — batch
    // -----------------------------------------------------------------------

    @Test
    void enrichForSources_returnsBothPaths() throws Exception {
        insertEdge("docs/skill.md", "src/main/A.java", "A", 0, "HIGH");
        // B.java intentionally has no edges → gap

        Map<String, KnowledgeEnricher.EnrichmentResult> results =
            enricher.enrichForSources(List.of("src/main/A.java", "src/main/B.java"), conn);

        assertEquals(2, results.size());
        assertFalse(results.get("src/main/A.java").hasGap());
        assertTrue(results.get("src/main/B.java").hasGap());
    }

    // -----------------------------------------------------------------------
    // formatForCli()
    // -----------------------------------------------------------------------

    @Test
    void formatForCli_showsGapMessage() throws Exception {
        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/Missing.java", conn);
        String text = enricher.formatForCli(r);
        assertTrue(text.contains("No skill"), "Gap message expected");
    }

    @Test
    void formatForCli_showsSkillPathAndEntities() throws Exception {
        insertEdge("docs/synthesis-dev.md", "src/main/IndexService.java", "IndexService", 3, "MEDIUM");

        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/IndexService.java", conn);
        String text = enricher.formatForCli(r);

        assertTrue(text.contains("synthesis-dev.md"), "Should show skill path");
        assertTrue(text.contains("MEDIUM"), "Should show confidence");
        assertTrue(text.contains("IndexService"), "Should show covered entity");
    }

    @Test
    void formatForCli_showsDriftWhenPositive() throws Exception {
        insertEdge("docs/guide.md", "src/main/Thing.java", "Thing", 14, "LOW");

        KnowledgeEnricher.EnrichmentResult r =
            enricher.enrichForSource("src/main/Thing.java", conn);
        String text = enricher.formatForCli(r);

        assertTrue(text.contains("14 days stale"), "Should show drift");
    }
}
