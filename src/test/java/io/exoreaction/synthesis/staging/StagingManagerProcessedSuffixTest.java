package io.exoreaction.synthesis.staging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StagingManager.withProcessedSuffix.
 */
class StagingManagerProcessedSuffixTest {

    @ParameterizedTest
    @CsvSource({
        "unnamed (22).png,         unnamed (22)_processed.png",
        "report.pdf,               report_processed.pdf",
        "document.docx,            document_processed.docx",
        "TILBUD_Item Consulting.pdf, TILBUD_Item Consulting_processed.pdf",
        "chaos-to-clarity.png,     chaos-to-clarity_processed.png",
        "file.tar.gz,              file.tar_processed.gz"
    })
    void withProcessedSuffix_insertsBeforeExtension(String input, String expected) {
        Path result = StagingManager.withProcessedSuffix(Path.of(input));
        assertEquals(expected, result.getFileName().toString());
    }

    @Test
    void withProcessedSuffix_noExtension_appendsSuffix() {
        Path result = StagingManager.withProcessedSuffix(Path.of("noextension"));
        assertEquals("noextension_processed", result.getFileName().toString());
    }

    @Test
    void withProcessedSuffix_preservesParentDirectory() {
        Path input = Path.of("/home/totto/Downloads/report.pdf");
        Path result = StagingManager.withProcessedSuffix(input);
        assertEquals(Path.of("/home/totto/Downloads"), result.getParent());
        assertEquals("report_processed.pdf", result.getFileName().toString());
    }

    @Test
    void withProcessedSuffix_relativePathWithParent_preservesParent() {
        Path input = Path.of("subdir/file.png");
        Path result = StagingManager.withProcessedSuffix(input);
        assertEquals("subdir", result.getParent().toString());
        assertEquals("file_processed.png", result.getFileName().toString());
    }

    @Test
    void withProcessedSuffix_noParent_returnsFilenameOnly() {
        Path input = Path.of("file.txt");
        Path result = StagingManager.withProcessedSuffix(input);
        assertNull(result.getParent());
        assertEquals("file_processed.txt", result.getFileName().toString());
    }
}
