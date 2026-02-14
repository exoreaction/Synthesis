package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.util.Map;

/**
 * Fallback analyzer for file types without a specialized analyzer.
 * Provides basic metadata: size, type, extension, and a content preview
 * for text-based files.
 */
public class GenericAnalyzer implements FileAnalyzer {

    private static final int CONTENT_PREVIEW_LIMIT = 10240;

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        // Accepts everything -- used as fallback
        return true;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        String summary = metadata.fileType() + " file: " + metadata.fileName();

        String contentPreview = "";
        if (metadata.isIndexableContent()) {
            contentPreview = FileUtils.readPreview(metadata.path(), CONTENT_PREVIEW_LIMIT);
        }

        return AnalysisResult.builder()
                .summary(summary)
                .contentPreview(contentPreview)
                .metrics(Map.of(
                        "sizeBytes", metadata.sizeBytes(),
                        "extension", metadata.extension()
                ))
                .build();
    }
}
