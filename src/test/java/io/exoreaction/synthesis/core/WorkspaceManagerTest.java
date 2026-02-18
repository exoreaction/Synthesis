package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WorkspaceManager}, including the config-aware {@code getReportsPath(SynthesisConfig)}.
 */
class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void getReportsPath_returnsDefaultWhenConfigHasNoOutputDir() {
        SynthesisConfig config = new SynthesisConfig(); // report.outputDir is null
        WorkspaceManager manager = new WorkspaceManager(tempDir);

        Path result = manager.getReportsPath(config);

        assertEquals(manager.getReportsPath(), result,
                "Should return default .synthesis/reports/ when outputDir is not set");
        assertTrue(result.startsWith(tempDir.resolve(".synthesis/reports")));
    }

    @Test
    void getReportsPath_returnsCustomDirWhenConfigured() {
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.ReportConfig reportConfig = new SynthesisConfig.ReportConfig();
        reportConfig.setOutputDir("custom-reports");
        config.setReport(reportConfig);

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        Path result = manager.getReportsPath(config);

        assertEquals(tempDir.resolve("custom-reports").toAbsolutePath().normalize(), result,
                "Should resolve relative outputDir against workspace root");
    }

    @Test
    void getReportsPath_returnsAbsolutePathAsIs() {
        Path absolutePath = tempDir.resolve("somewhere-else");
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.ReportConfig reportConfig = new SynthesisConfig.ReportConfig();
        reportConfig.setOutputDir(absolutePath.toAbsolutePath().toString());
        config.setReport(reportConfig);

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        Path result = manager.getReportsPath(config);

        assertEquals(absolutePath.toAbsolutePath(), result,
                "Absolute outputDir should be used without modification");
    }

    @Test
    void getReportsPath_treatsNullConfigAsDefault() {
        WorkspaceManager manager = new WorkspaceManager(tempDir);

        Path result = manager.getReportsPath(null);

        assertEquals(manager.getReportsPath(), result,
                "Null config should fall back to default .synthesis/reports/");
    }

    @Test
    void getReportsPath_treatsBlankOutputDirAsDefault() {
        SynthesisConfig config = new SynthesisConfig();
        SynthesisConfig.ReportConfig reportConfig = new SynthesisConfig.ReportConfig();
        reportConfig.setOutputDir("   "); // blank
        config.setReport(reportConfig);

        WorkspaceManager manager = new WorkspaceManager(tempDir);
        Path result = manager.getReportsPath(config);

        assertEquals(manager.getReportsPath(), result,
                "Blank outputDir should fall back to default .synthesis/reports/");
    }
}
