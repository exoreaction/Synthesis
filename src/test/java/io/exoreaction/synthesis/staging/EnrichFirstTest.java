package io.exoreaction.synthesis.staging;

import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.enrichment.CompanionFileGenerator;
import io.exoreaction.synthesis.enrichment.EnrichmentLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the enrich-then-classify path used by {@code staging route --enrich-first}.
 *
 * <p>Verifies that {@link CompanionFileGenerator} can be invoked directly on
 * staging area files (i.e., without a Lucene index) to generate companion
 * {@code .synthesis.md} files suitable for content-intelligence classification.
 */
class EnrichFirstTest {

    @TempDir
    Path tempDir;

    @Test
    void enrichFirst_generatesCompanionForImageWithoutExistingCompanion() throws IOException {
        Path imageFile = tempDir.resolve("7de34940-uuid.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}); // PNG header bytes

        CompanionFileGenerator generator = new CompanionFileGenerator(EnrichmentLevel.BASIC, false);
        AnalyzerRegistry analyzers = new AnalyzerRegistry();

        BasicFileAttributes attrs = Files.readAttributes(imageFile, BasicFileAttributes.class);
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, attrs.size(),
                attrs.lastModifiedTime().toInstant(), null);

        Optional<Path> companion = generator.generate(metadata, analyzers.analyze(metadata), List.of());

        assertTrue(companion.isPresent(), "Companion should be created for IMAGE file");
        assertTrue(Files.exists(companion.get()), "Companion file should exist on disk");
        String content = Files.readString(companion.get());
        assertTrue(content.contains("IMAGE") || content.contains("companion_for"),
                "Companion should contain type or companion header");
    }

    @Test
    void enrichFirst_skipsFileIfCompanionAlreadyExists() throws IOException {
        Path imageFile = tempDir.resolve("photo.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        Path existingCompanion = Path.of(imageFile + ".synthesis.md");
        Files.writeString(existingCompanion, "# existing companion\n");

        CompanionFileGenerator generator = new CompanionFileGenerator(EnrichmentLevel.BASIC, false);
        AnalyzerRegistry analyzers = new AnalyzerRegistry();

        BasicFileAttributes attrs = Files.readAttributes(imageFile, BasicFileAttributes.class);
        FileMetadata metadata = FileMetadata.of(imageFile, tempDir, attrs.size(),
                attrs.lastModifiedTime().toInstant(), null);

        Optional<Path> result = generator.generate(metadata, analyzers.analyze(metadata), List.of());

        assertTrue(result.isEmpty(),
                "Should not overwrite an existing companion (idempotent)");
        assertEquals("# existing companion\n", Files.readString(existingCompanion),
                "Existing companion content should be unchanged");
    }

    @Test
    void enrichFirst_generatesCompanionForPdf() throws IOException {
        Path pdfFile = tempDir.resolve("document.pdf");
        // Minimal PDF header
        Files.write(pdfFile, "%PDF-1.4".getBytes());

        CompanionFileGenerator generator = new CompanionFileGenerator(EnrichmentLevel.BASIC, false);
        AnalyzerRegistry analyzers = new AnalyzerRegistry();

        BasicFileAttributes attrs = Files.readAttributes(pdfFile, BasicFileAttributes.class);
        FileMetadata metadata = FileMetadata.of(pdfFile, tempDir, attrs.size(),
                attrs.lastModifiedTime().toInstant(), null);

        Optional<Path> companion = generator.generate(metadata, analyzers.analyze(metadata), List.of());

        assertTrue(companion.isPresent(), "Companion should be created for PDF file");
        assertTrue(Files.exists(companion.get()));
    }

    @Test
    void enrichFirst_doesNotGenerateCompanionForCodeFile() throws IOException {
        Path codeFile = tempDir.resolve("Main.java");
        Files.writeString(codeFile, "public class Main {}");

        CompanionFileGenerator generator = new CompanionFileGenerator(EnrichmentLevel.BASIC, false);
        AnalyzerRegistry analyzers = new AnalyzerRegistry();

        BasicFileAttributes attrs = Files.readAttributes(codeFile, BasicFileAttributes.class);
        FileMetadata metadata = FileMetadata.of(codeFile, tempDir, attrs.size(),
                attrs.lastModifiedTime().toInstant(), null);

        Optional<Path> result = generator.generate(metadata, analyzers.analyze(metadata), List.of());

        assertTrue(result.isEmpty(),
                "Companion should NOT be generated for text/code files");
    }
}
