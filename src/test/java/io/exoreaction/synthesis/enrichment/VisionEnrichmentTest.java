package io.exoreaction.synthesis.enrichment;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for vision-enriched companion generation (issue #140).
 *
 * <p>Uses a test subclass of {@link CompanionFileGenerator} to inject a known
 * vision response without requiring a live API key. This verifies that the
 * companion template correctly incorporates the routing-focused vision output.
 */
class VisionEnrichmentTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------------------
    // Test subclass — returns a fixed vision description so we can verify output
    // ---------------------------------------------------------------------------

    private static class FakeVisionGenerator extends CompanionFileGenerator {
        private final String fakeVisionResponse;
        private boolean visionWasCalled;

        FakeVisionGenerator(String fakeVisionResponse) {
            // AI level with forceRegenerate=false; aiClient is set in parent (null here —
            // we override generateVisionDescription so it never reaches the real client)
            super(EnrichmentLevel.AI, false, /* aiClient = */ null);
            this.fakeVisionResponse = fakeVisionResponse;
        }

        @Override
        protected String generateVisionDescription(FileMetadata metadata) {
            visionWasCalled = true;
            return fakeVisionResponse;
        }

        boolean wasVisionCalled() {
            return visionWasCalled;
        }
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void visionDescription_appearsInVisionAnalysisSection() throws IOException {
        Path imageFile = tempDir.resolve("7de34940-uuid.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        String fakeVision = """
                Type: diagram
                Title: Synthesis Architecture Overview
                Organizations: Synthesis, eXOReaction
                Topic: software architecture, knowledge management
                Description: A 4-layer architecture diagram showing indexing, search, and AI layers.
                Keywords: synthesis, exoreaction, architecture, knowledge-management, lucene, sqlite, diagram""";

        FakeVisionGenerator generator = new FakeVisionGenerator(fakeVision);
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 1024, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "", List.of(), List.of(), List.of(), "", Map.of(), "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent(), "Companion should be generated");
        String content = Files.readString(result.get());

        // Vision section must be present
        assertTrue(content.contains("## Vision Analysis"), "Companion must have Vision Analysis section");
        assertTrue(content.contains("Organizations: Synthesis, eXOReaction"),
                "Companion must contain visible organization names");
        assertTrue(content.contains("synthesis"), "Org keyword must appear in companion for routing");
        assertTrue(content.contains("exoreaction"), "Org keyword must appear in companion for routing");
    }

    @Test
    void visionDescription_isCalledForImageAtAiLevel() throws IOException {
        Path imageFile = tempDir.resolve("photo.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        FakeVisionGenerator generator = new FakeVisionGenerator("Type: photo\nKeywords: test");
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 512, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "", List.of(), List.of(), List.of(), "", Map.of(), "");

        generator.generate(metadata, analysis, List.of());

        assertTrue(generator.wasVisionCalled(),
                "generateVisionDescription() must be called for images at AI level");
    }

    @Test
    void visionDescription_notCalledAtBasicLevel() throws IOException {
        Path imageFile = tempDir.resolve("basic.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        // BASIC level — vision should NOT be invoked
        CompanionFileGenerator basicGenerator = new CompanionFileGenerator(
                EnrichmentLevel.BASIC, false);
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 512, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "", List.of(), List.of(), List.of(), "", Map.of(), "");

        Optional<Path> result = basicGenerator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent());
        String content = Files.readString(result.get());
        assertFalse(content.contains("Vision Analysis"),
                "BASIC level companion must not have a Vision Analysis section");
        assertFalse(content.contains("AI Description"),
                "BASIC level companion must not have an AI Description section");
    }

    @Test
    void visionDescription_nullReturnProducesNoVisionSection() throws IOException {
        Path imageFile = tempDir.resolve("no-vision.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        // Fake generator returns null (e.g., vision API failed)
        FakeVisionGenerator generator = new FakeVisionGenerator(null);
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 512, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "", List.of(), List.of(), List.of(), "", Map.of(), "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent(), "Companion should still be generated even if vision fails");
        String content = Files.readString(result.get());
        assertFalse(content.contains("## Vision Analysis"),
                "Vision Analysis section must be absent when vision returns null");
        // Basic metadata should still be present
        assertTrue(content.contains("companion_for: no-vision.png"));
    }

    @Test
    void visionDescription_emptyReturnProducesNoVisionSection() throws IOException {
        Path imageFile = tempDir.resolve("empty-vision.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        FakeVisionGenerator generator = new FakeVisionGenerator("");
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 512, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "", List.of(), List.of(), List.of(), "", Map.of(), "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent());
        assertFalse(Files.readString(result.get()).contains("## Vision Analysis"),
                "Empty vision response must not produce a Vision Analysis section");
    }

    @Test
    void visionCompanion_structureIsCorrect() throws IOException {
        Path imageFile = tempDir.resolve("diagram.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        String fakeVision = "Type: diagram\nOrganizations: Merkabit\nKeywords: merkabit, consulting";
        FakeVisionGenerator generator = new FakeVisionGenerator(fakeVision);
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, 2048, Instant.now(), null);
        AnalysisResult analysis = new AnalysisResult(
                "", List.of(), List.of(), List.of(), "",
                Map.of("dimensions", "1920x1080", "imageType", "diagram"), "");

        Optional<Path> result = generator.generate(metadata, analysis, List.of());

        assertTrue(result.isPresent());
        String content = Files.readString(result.get());

        // Standard fields
        assertTrue(content.contains("companion_for: diagram.png"));
        assertTrue(content.contains("type: IMAGE"));
        assertTrue(content.contains("enrichment_level: AI"));
        assertTrue(content.contains("Dimensions:"));
        // Vision section
        assertTrue(content.contains("## Vision Analysis"));
        assertTrue(content.contains("merkabit"));
        // Keywords section still present
        assertTrue(content.contains("## Keywords"));
    }
}
