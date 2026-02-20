package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.DirectorySignalExtractor.DirectorySignals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DirectorySignalExtractorTest {

    private final DirectorySignalExtractor extractor = new DirectorySignalExtractor();

    @Test
    void extract_emptyDirectory_returnsZeroConfidence(@TempDir Path tempDir) {
        DirectorySignals signals = extractor.extract(tempDir);

        assertEquals(0, signals.fileCount());
        assertEquals(0.0, signals.confidence());
        assertTrue(signals.inferredTypes().isEmpty());
        assertTrue(signals.inferredFormats().isEmpty());
        assertTrue(signals.inferredPatterns().isEmpty());
        assertTrue(signals.formatCounts().isEmpty());
    }

    @Test
    void extract_fewFiles_returnsLowConfidence(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("file1.md"));
        Files.createFile(tempDir.resolve("file2.md"));

        DirectorySignals signals = extractor.extract(tempDir);

        assertEquals(2, signals.fileCount());
        assertEquals(0.5, signals.confidence());
    }

    @Test
    void extract_manyFiles_returnsHighConfidence(@TempDir Path tempDir) throws IOException {
        for (int i = 1; i <= 22; i++) {
            Files.createFile(tempDir.resolve("file-" + i + ".md"));
        }

        DirectorySignals signals = extractor.extract(tempDir);

        assertEquals(22, signals.fileCount());
        assertEquals(0.94, signals.confidence());
    }

    @Test
    void extract_countsByExtension(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("doc1.md"));
        Files.createFile(tempDir.resolve("doc2.md"));
        Files.createFile(tempDir.resolve("doc3.md"));
        Files.createFile(tempDir.resolve("report.pdf"));
        Files.createFile(tempDir.resolve("invoice.pdf"));

        DirectorySignals signals = extractor.extract(tempDir);

        assertEquals(5, signals.fileCount());
        assertEquals(3, signals.formatCounts().get("md"));
        assertEquals(2, signals.formatCounts().get("pdf"));
        assertTrue(signals.inferredFormats().contains("md"));
        assertTrue(signals.inferredFormats().contains("pdf"));
    }

    @Test
    void extract_commonToken_becomesPattern(@TempDir Path tempDir) throws IOException {
        // 8 files with "meeting" in the name — "meeting" should appear in >50% (all 8/8)
        for (int i = 1; i <= 8; i++) {
            Files.createFile(tempDir.resolve("meeting-topic-" + i + ".md"));
        }

        DirectorySignals signals = extractor.extract(tempDir);

        assertEquals(8, signals.fileCount());
        assertTrue(signals.inferredPatterns().contains("*meeting*"),
                "Expected '*meeting*' in patterns but got: " + signals.inferredPatterns());
    }

    @Test
    void extract_meetingFiles_infersMeetingNotesType(@TempDir Path tempDir) throws IOException {
        for (int i = 1; i <= 5; i++) {
            Files.createFile(tempDir.resolve("meeting-" + i + ".md"));
        }

        DirectorySignals signals = extractor.extract(tempDir);

        assertTrue(signals.inferredTypes().contains("meeting-notes"),
                "Expected 'meeting-notes' in types but got: " + signals.inferredTypes());
    }

    @Test
    void extract_imageFiles_infersMediaType(@TempDir Path tempDir) throws IOException {
        // 7 image files + 2 non-image = 7/9 > 60%
        Files.createFile(tempDir.resolve("photo1.png"));
        Files.createFile(tempDir.resolve("photo2.png"));
        Files.createFile(tempDir.resolve("photo3.jpg"));
        Files.createFile(tempDir.resolve("photo4.jpeg"));
        Files.createFile(tempDir.resolve("photo5.gif"));
        Files.createFile(tempDir.resolve("video1.mp4"));
        Files.createFile(tempDir.resolve("video2.mp4"));
        Files.createFile(tempDir.resolve("readme.md"));
        Files.createFile(tempDir.resolve("notes.txt"));

        DirectorySignals signals = extractor.extract(tempDir);

        assertTrue(signals.inferredTypes().contains("media"),
                "Expected 'media' in types but got: " + signals.inferredTypes());
    }

    @Test
    void extract_skipsHiddenAndSynthesisFiles(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("visible.md"));
        Files.createFile(tempDir.resolve(".hidden"));
        Files.createFile(tempDir.resolve(".gitignore"));
        Files.createFile(tempDir.resolve("report.synthesis.md"));

        DirectorySignals signals = extractor.extract(tempDir);

        assertEquals(1, signals.fileCount(), "Only 'visible.md' should be counted");
    }

    @Test
    void extract_singleFile_onlyFilesNotSubdirs(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("actual-file.md"));
        Files.createDirectory(tempDir.resolve("subdirectory"));
        // Also put a file inside the subdirectory to make sure it's not counted
        Files.createFile(tempDir.resolve("subdirectory").resolve("nested.md"));

        DirectorySignals signals = extractor.extract(tempDir);

        assertEquals(1, signals.fileCount(), "Subdirectory should not be counted");
    }
}
