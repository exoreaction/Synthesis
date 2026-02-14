package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;

import java.io.IOException;

/**
 * Analyzes a file's content and extracts structured intelligence.
 *
 * <p>Each implementation handles a specific file type (Markdown, code, YAML, etc.)
 * and produces an {@link AnalysisResult} enriching the raw {@link FileMetadata}
 * with content-specific information.
 *
 * <p>Analyzers are expected to be lightweight and fast. They should not make
 * network calls or perform heavy computation. AI-powered analysis is handled
 * separately in the {@code ai} package.
 */
public interface FileAnalyzer {

    /**
     * Analyzes a file and returns structured content intelligence.
     *
     * @param metadata the file's metadata (path, type, size, etc.)
     * @return analysis result with extracted content intelligence
     * @throws IOException if the file cannot be read
     */
    AnalysisResult analyze(FileMetadata metadata) throws IOException;

    /**
     * Whether this analyzer can handle the given file.
     *
     * @param metadata the file's metadata
     * @return true if this analyzer should process the file
     */
    boolean canAnalyze(FileMetadata metadata);
}
