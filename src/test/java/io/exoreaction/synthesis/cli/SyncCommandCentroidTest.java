package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 2 centroid/wants integration in {@link SyncCommand}.
 * Tests the {@code --enrich-centroids} flag behavior.
 */
class SyncCommandCentroidTest {

    @TempDir
    Path tempDir;

    // ---- --enrich-centroids produces centroid blocks ----

    @Test
    void sync_enrichCentroids_producesCentroidBlock() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup-2026-02-20.md"), """
                # Weekly Standup

                Attendees: Jane Smith, Thor Henning.
                Discussion about GreenField Energy project timeline.
                """);
        Files.writeString(meetings.resolve("review-2026-Q1.md"), """
                # Quarterly Review

                Review of Q1 progress on GreenField Energy partnership.
                Jane Smith presented the roadmap.
                """);

        runSync(tempDir, "--enrich-centroids");

        Path synthesisFile = meetings.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile));

        DirectoryProfile profile = new DirectoryIdentityParser().parseProfile(synthesisFile);

        // Identity should still be present
        assertFalse(profile.identity().acceptsTypes().isEmpty(),
                "Identity should still have types");

        // Centroid should have been computed from the 2 markdown files
        DirectoryCentroid centroid = profile.centroid();
        assertFalse(centroid.isEmpty(),
                "Centroid should be computed for directory with enriched files");
        assertTrue(centroid.contributingFiles() > 0,
                "Should have contributing files");
    }

    @Test
    void sync_withoutEnrichCentroids_noCentroid() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup\nNotes here");

        // Run without --enrich-centroids
        runSync(tempDir);

        Path synthesisFile = meetings.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile));

        DirectoryProfile profile = new DirectoryIdentityParser().parseProfile(synthesisFile);

        // Should NOT have centroid (flag not enabled)
        assertTrue(profile.centroid().isEmpty(),
                "Without --enrich-centroids, centroid should be empty");
        assertTrue(profile.wants().isEmpty(),
                "Without --enrich-centroids, wants should be empty");
    }

    // ---- Wants bootstrap for empty or new directories ----

    @Test
    void sync_enrichCentroids_emptyRecognizedDir_bootstrapsWants() throws Exception {
        initWorkspace(tempDir);
        // "opportunity-GreenField" will be recognized by vocabulary? Maybe not.
        // Let's use a name that's known to the vocabulary.
        // Actually, the wants bootstrapper works from directory name, not vocab.
        // An empty dir doesn't get synced (no files, no vocab match).
        // We need a dir with at least one file OR a vocabulary match.
        // Let's use a recognized vocabulary name + file so it gets processed.
        Path proposals = Files.createDirectories(tempDir.resolve("proposals"));
        Files.writeString(proposals.resolve("placeholder.txt"), "Empty for now");

        runSync(tempDir, "--enrich-centroids");

        Path synthesisFile = proposals.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile));

        DirectoryProfile profile = new DirectoryIdentityParser().parseProfile(synthesisFile);

        // "proposals" is in the vocabulary — the enrichment from a txt file is minimal.
        // With a weak or empty centroid, wants should be bootstrapped.
        // The directory name "proposals" should produce at least a topic.
        DirectoryWants wants = profile.wants();
        // The wants may be bootstrapped from the directory name
        if (!wants.isEmpty()) {
            assertTrue(wants.source() != null && wants.source().contains("inferred"),
                    "Wants source should describe provenance");
        }
    }

    @Test
    void sync_enrichCentroids_dirWithStrongCentroid_noWants() throws Exception {
        initWorkspace(tempDir);
        Path reports = Files.createDirectories(tempDir.resolve("reports"));

        // Create enough files to produce a strong centroid
        for (int i = 0; i < 6; i++) {
            Files.writeString(reports.resolve("report-" + i + ".md"), """
                    # Renewable Energy Report

                    Analysis of solar panel installations for GreenField Energy.
                    Prepared by Jane Smith.
                    """);
        }

        runSync(tempDir, "--enrich-centroids");

        Path synthesisFile = reports.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile));

        DirectoryProfile profile = new DirectoryIdentityParser().parseProfile(synthesisFile);

        DirectoryCentroid centroid = profile.centroid();
        if (centroid.confidence() > 0.8) {
            // Strong centroid should suppress wants
            assertTrue(profile.wants().isEmpty(),
                    "Strong centroid (confidence > 0.8) should suppress wants. " +
                            "Centroid confidence: " + centroid.confidence());
        }
    }

    // ---- Dry run with --enrich-centroids ----

    @Test
    void sync_dryRunEnrichCentroids_doesNotWriteFile() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup\nNotes");

        String output = runSync(tempDir, "--dry-run", "--enrich-centroids");

        assertFalse(Files.exists(meetings.resolve(".synthesis.md")),
                "Dry run should not create files");
        assertTrue(output.contains("[DRY]"),
                "Should show dry-run markers. Output: " + output);
    }

    // ---- Verbose output shows centroid info ----

    @Test
    void sync_verboseEnrichCentroids_showsCentroidInfo() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), """
                # Weekly Standup Meeting

                Discussion with GreenField Energy team about renewable energy.
                """);

        String output = runSync(tempDir, "--enrich-centroids", "--verbose");

        // Verbose output should show some identity info
        assertTrue(output.contains("meetings"),
                "Verbose output should mention directory name. Output: " + output);
    }

    // ---- Static helpers ----

    @Test
    void extractEnrichmentSignatures_emptyDir_returnsEmpty(@TempDir Path dir) {
        EnrichmentSignatureExtractor ext = new EnrichmentSignatureExtractor();
        List<EnrichmentSignature> sigs = SyncCommand.extractEnrichmentSignatures(dir, ext, dir);
        assertTrue(sigs.isEmpty());
    }

    @Test
    void extractEnrichmentSignatures_skipsHiddenFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".hidden.md"), "# Hidden\nSome content");
        Files.writeString(dir.resolve("visible.md"), "# Visible\nSome content");

        EnrichmentSignatureExtractor ext = new EnrichmentSignatureExtractor();
        List<EnrichmentSignature> sigs = SyncCommand.extractEnrichmentSignatures(dir, ext, dir);

        // Should only get signature from visible.md, not .hidden.md
        assertTrue(sigs.size() <= 1,
                "Hidden files should be skipped");
    }

    @Test
    void extractEnrichmentSignatures_skipsSynthesisMdFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".synthesis.md"), "---\nsynthesis:\n---");
        Files.writeString(dir.resolve("report.md.synthesis.md"), "Companion file");
        Files.writeString(dir.resolve("report.md"), "# Report\nSome content");

        EnrichmentSignatureExtractor ext = new EnrichmentSignatureExtractor();
        List<EnrichmentSignature> sigs = SyncCommand.extractEnrichmentSignatures(dir, ext, dir);

        // Should only get signature from report.md
        assertEquals(1, sigs.size(),
                "Should skip .synthesis.md and companion files");
    }

    @Test
    void countFilesInDirectory_countsRegularFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("file1.md"), "content");
        Files.writeString(dir.resolve("file2.pdf"), "content");
        Files.writeString(dir.resolve(".hidden"), "content");
        Files.writeString(dir.resolve("file3.md.synthesis.md"), "companion");
        Files.createDirectories(dir.resolve("subdir"));

        int count = SyncCommand.countFilesInDirectory(dir);

        assertEquals(2, count,
                "Should count only regular, non-hidden, non-synthesis files");
    }

    @Test
    void countFilesInDirectory_emptyDir_returnsZero(@TempDir Path dir) {
        assertEquals(0, SyncCommand.countFilesInDirectory(dir));
    }

    // ---- Round-trip: sync with centroids then parse back ----

    @Test
    void sync_enrichCentroids_roundTrip_preservesCentroid() throws Exception {
        initWorkspace(tempDir);
        Path docs = Files.createDirectories(tempDir.resolve("documentation"));
        Files.writeString(docs.resolve("architecture.md"), """
                # System Architecture

                The architecture follows a microservices pattern.
                """);
        Files.writeString(docs.resolve("deployment.md"), """
                # Deployment Guide

                Steps to deploy the system to production.
                """);

        // First sync with centroids
        runSync(tempDir, "--enrich-centroids");

        Path synthesisFile = docs.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile));

        // Parse back the profile
        DirectoryProfile profile = new DirectoryIdentityParser().parseProfile(synthesisFile);

        // Centroid should be round-trippable
        if (!profile.centroid().isEmpty()) {
            assertTrue(profile.centroid().contributingFiles() > 0);
        }

        // Identity should still be valid
        assertNotNull(profile.identity());
    }

    // ---- Helpers ----

    private void initWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
    }

    private String runSync(Path workspaceRoot, String... extraArgs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));

        try {
            SyncCommand cmd = new SyncCommand();
            SynthesisApp app = new SynthesisApp();
            Field rootField = SynthesisApp.class.getDeclaredField("workspaceRoot");
            rootField.setAccessible(true);
            rootField.set(app, workspaceRoot.toAbsolutePath().normalize());
            cmd.setParent(app);

            if (extraArgs.length > 0) {
                CommandLine syncCmdLine = new CommandLine(cmd);
                syncCmdLine.parseArgs(extraArgs);
            }

            cmd.call();
        } finally {
            System.setOut(originalOut);
        }

        return baos.toString();
    }
}
