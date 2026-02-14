package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;

import java.io.IOException;
import java.util.List;

/**
 * Registry of file analyzers. Dispatches analysis requests to the
 * first matching specialized analyzer, falling back to {@link GenericAnalyzer}.
 *
 * <p>Analyzers are checked in priority order. The first analyzer that
 * returns {@code true} from {@link FileAnalyzer#canAnalyze} handles the file.
 */
public class AnalyzerRegistry {

    private final List<FileAnalyzer> analyzers;
    private final GenericAnalyzer fallback;

    public AnalyzerRegistry() {
        this.fallback = new GenericAnalyzer();
        this.analyzers = List.of(
                new MarkdownAnalyzer(),
                new CodeAnalyzer(),
                new YamlAnalyzer(),
                new PdfAnalyzer(),
                new ImageAnalyzer(),
                new VideoAnalyzer()
        );
    }

    /**
     * Analyzes a file using the most appropriate analyzer.
     *
     * @param metadata file metadata from the scanner
     * @return analysis result (never null)
     */
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        for (FileAnalyzer analyzer : analyzers) {
            if (analyzer.canAnalyze(metadata)) {
                return analyzer.analyze(metadata);
            }
        }
        return fallback.analyze(metadata);
    }
}
