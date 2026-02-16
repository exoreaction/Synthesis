package io.exoreaction.synthesis.index;

import java.nio.file.Path;

/**
 * A single search result from querying the Lucene index.
 *
 * @param path         absolute path to the matching file
 * @param relativePath path relative to workspace root
 * @param score        Lucene relevance score
 * @param fileName     file name
 * @param fileType     file type classification
 * @param language     detected language (may be null)
 * @param summary      analysis summary
 * @param headings     document headings
 * @param structure    structural description
 * @param sizeBytes    file size
 * @param repository   repository identifier (may be null for single-repo workspaces)
 * @param subWorkspace sub-workspace identifier (may be null)
 */
public record SearchResult(
        Path path,
        String relativePath,
        float score,
        String fileName,
        String fileType,
        String language,
        String summary,
        String headings,
        String structure,
        long sizeBytes,
        String repository,
        String subWorkspace
) {
    /**
     * Backward-compatible constructor for existing code that doesn't provide repository.
     */
    public SearchResult(Path path, String relativePath, float score, String fileName,
                        String fileType, String language, String summary, String headings,
                        String structure, long sizeBytes) {
        this(path, relativePath, score, fileName, fileType, language,
             summary, headings, structure, sizeBytes, null, null);
    }

    /**
     * Backward-compatible constructor for existing code that provides repository but not subWorkspace.
     */
    public SearchResult(Path path, String relativePath, float score, String fileName,
                        String fileType, String language, String summary, String headings,
                        String structure, long sizeBytes, String repository) {
        this(path, relativePath, score, fileName, fileType, language,
             summary, headings, structure, sizeBytes, repository, null);
    }
}
