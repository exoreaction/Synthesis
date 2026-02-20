package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.SynthesisConfig.StagingConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.org.RoutingHints;
import io.exoreaction.synthesis.org.RoutingHints.RoutingHint;
import io.exoreaction.synthesis.staging.StagingManager;
import io.exoreaction.synthesis.staging.StagingManager.StagedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the staging resolve and hints functionality.
 *
 * <p>Since the CLI subcommands require full picocli wiring through SynthesisApp,
 * these tests exercise the underlying mechanisms directly: routing hints creation
 * from resolve --learn, file copying for --also, and hints listing/management.
 */
class StagingResolveCommandTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private StagingManager staging;
    private Path stagingDir;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        StagingConfig config = new StagingConfig();
        config.setEnabled(true);
        config.setRetentionDays(30);
        staging = new StagingManager(db, config, tempDir);

        // Create a staging directory with a test file
        stagingDir = tempDir.resolve("staging");
        Files.createDirectories(stagingDir);
    }

    @Test
    void resolve_routesFileToDestination() throws Exception {
        // Ingest a file into staging
        Path sourceFile = stagingDir.resolve("meeting-notes.pdf");
        Files.writeString(sourceFile, "PDF content");
        StagedFile stagedFile = staging.ingest(
                "staging/meeting-notes.pdf", "Inbox", 11, "PDF", null);

        // Create destination
        Path destDir = tempDir.resolve("meetings");
        Files.createDirectories(destDir);

        // Route the file
        Path destFile = destDir.resolve("meeting-notes.pdf");
        boolean success = staging.routeTo(stagedFile, destFile, false);

        assertTrue(success, "routeTo should succeed");
        assertTrue(Files.exists(destFile), "File should exist at destination");
        assertEquals("PDF content", Files.readString(destFile));
    }

    @Test
    void resolve_withLearn_createsHint() throws Exception {
        // Create a staged file
        Path sourceFile = stagingDir.resolve("mynder-meeting-2026-02-20.pdf");
        Files.writeString(sourceFile, "Meeting PDF");
        staging.ingest("staging/mynder-meeting-2026-02-20.pdf", "Inbox", 11, "PDF", null);

        // Simulate what resolve --learn does: derive pattern and save hint
        String pattern = RoutingHints.derivePattern("mynder-meeting-2026-02-20.pdf");
        assertEquals("*mynder*meeting*.pdf", pattern,
                "Pattern should extract meaningful tokens");

        Path destDir = tempDir.resolve("meetings");
        Files.createDirectories(destDir);

        RoutingHints routingHints = new RoutingHints(tempDir);
        routingHints.load();
        routingHints.addOrUpdate(new RoutingHint(
                pattern, destDir.toAbsolutePath().toString(),
                Instant.now(), 0));

        // Verify hints file was created
        assertTrue(Files.exists(routingHints.getHintsFilePath()),
                "routing-hints.json should be created");

        // Verify hint can be loaded
        RoutingHints reloaded = new RoutingHints(tempDir);
        List<RoutingHint> hints = reloaded.load();
        assertEquals(1, hints.size());
        assertEquals(pattern, hints.get(0).filenamePattern());
        assertEquals(destDir.toAbsolutePath().toString(), hints.get(0).destinationPath());
    }

    @Test
    void resolve_withAlso_copiesFile() throws Exception {
        // Create a staged file and route it
        Path sourceFile = stagingDir.resolve("cross-company-report.pdf");
        Files.writeString(sourceFile, "Report content");
        StagedFile stagedFile = staging.ingest(
                "staging/cross-company-report.pdf", "Inbox", 14, "PDF", null);

        // Route to primary destination
        Path primaryDir = tempDir.resolve("company-a");
        Files.createDirectories(primaryDir);
        Path primaryDest = primaryDir.resolve("cross-company-report.pdf");
        boolean success = staging.routeTo(stagedFile, primaryDest, false);
        assertTrue(success);

        // Simulate --also: copy from primary to secondary
        Path secondaryDir = tempDir.resolve("company-b");
        Files.createDirectories(secondaryDir);
        Path secondaryDest = secondaryDir.resolve("cross-company-report.pdf");
        Files.copy(primaryDest, secondaryDest);

        assertTrue(Files.exists(primaryDest), "Primary destination should have the file");
        assertTrue(Files.exists(secondaryDest), "Secondary destination should have a copy");
        assertEquals("Report content", Files.readString(secondaryDest));
    }

    @Test
    void hints_list_showsHints() throws Exception {
        // Save some hints
        RoutingHints routingHints = new RoutingHints(tempDir);
        Instant now = Instant.parse("2026-02-20T10:00:00Z");
        routingHints.save(List.of(
                new RoutingHint("*mynder*meeting*.pdf", "/home/user/meetings/", now, 3),
                new RoutingHint("*invoice*.pdf", "/home/user/billing/", now, 1)
        ));

        // Verify we can load and list them
        RoutingHints loaded = new RoutingHints(tempDir);
        List<RoutingHint> hints = loaded.load();

        assertEquals(2, hints.size(), "Should list 2 hints");

        // Verify format of hints
        RoutingHint firstHint = hints.get(0);
        assertEquals("*mynder*meeting*.pdf", firstHint.filenamePattern());
        assertEquals("/home/user/meetings/", firstHint.destinationPath());
        assertEquals(3, firstHint.hitCount());
        assertEquals("2026-02-20", firstHint.learnedAt().toString().substring(0, 10));

        RoutingHint secondHint = hints.get(1);
        assertEquals("*invoice*.pdf", secondHint.filenamePattern());
        assertEquals(1, secondHint.hitCount());
    }

    @Test
    void hints_delete_removesHint() throws Exception {
        RoutingHints routingHints = new RoutingHints(tempDir);
        Instant now = Instant.now();
        routingHints.save(List.of(
                new RoutingHint("*meeting*.pdf", "/meetings/", now, 0),
                new RoutingHint("*invoice*.pdf", "/billing/", now, 0)
        ));
        routingHints.load();

        routingHints.delete(1); // Remove first hint

        RoutingHints reloaded = new RoutingHints(tempDir);
        List<RoutingHint> remaining = reloaded.load();
        assertEquals(1, remaining.size());
        assertEquals("*invoice*.pdf", remaining.get(0).filenamePattern());
    }

    @Test
    void hints_promote_conceptual() throws Exception {
        // This test verifies that a hint's pattern and destination can be used
        // to construct a RoutingRule. The actual config.yaml writing is tested
        // through the CLI integration.
        RoutingHints routingHints = new RoutingHints(tempDir);
        Instant now = Instant.now();
        routingHints.save(List.of(
                new RoutingHint("*mynder*meeting*.pdf", "/home/user/meetings/", now, 5)
        ));
        routingHints.load();

        RoutingHint hint = routingHints.getHints().get(0);

        // Verify hint data can be used for a routing rule
        assertNotNull(hint.filenamePattern());
        assertNotNull(hint.destinationPath());
        assertEquals(5, hint.hitCount(), "High hit count suggests a good promotion candidate");

        // After promotion, hint should be deletable
        routingHints.delete(1);
        assertTrue(routingHints.getHints().isEmpty());
    }
}
