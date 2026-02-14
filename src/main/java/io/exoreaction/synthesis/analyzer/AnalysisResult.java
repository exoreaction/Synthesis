package io.exoreaction.synthesis.analyzer;

import java.util.List;
import java.util.Map;

/**
 * Result of analyzing a file's content.
 * Produced by {@link FileAnalyzer} implementations, consumed by the indexer.
 *
 * <p>Not all fields are populated for every file type. Analyzers set the fields
 * relevant to their domain and leave others as defaults.
 *
 * @param summary       brief description of the file's purpose (1-2 sentences)
 * @param headings      extracted headings/titles (for markdown, YAML name fields, etc.)
 * @param keywords      extracted keywords for search enrichment
 * @param links         links found in the file (URLs, file references)
 * @param structure     structural metadata (e.g., "14 headings, 3 code blocks")
 * @param metrics       numeric metrics (e.g., LOC, word count, test count)
 * @param contentPreview first N characters of indexable content
 */
public record AnalysisResult(
        String summary,
        List<String> headings,
        List<String> keywords,
        List<String> links,
        String structure,
        Map<String, Object> metrics,
        String contentPreview
) {

    /** Creates an empty result (used when analysis is skipped or not applicable). */
    public static AnalysisResult empty() {
        return new AnalysisResult("", List.of(), List.of(), List.of(), "", Map.of(), "");
    }

    /** Creates a minimal result with just a summary and content preview. */
    public static AnalysisResult minimal(String summary, String contentPreview) {
        return new AnalysisResult(summary, List.of(), List.of(), List.of(), "", Map.of(), contentPreview);
    }

    /** Builder for constructing AnalysisResult incrementally. */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String summary = "";
        private List<String> headings = List.of();
        private List<String> keywords = List.of();
        private List<String> links = List.of();
        private String structure = "";
        private Map<String, Object> metrics = Map.of();
        private String contentPreview = "";

        public Builder summary(String summary) { this.summary = summary; return this; }
        public Builder headings(List<String> headings) { this.headings = List.copyOf(headings); return this; }
        public Builder keywords(List<String> keywords) { this.keywords = List.copyOf(keywords); return this; }
        public Builder links(List<String> links) { this.links = List.copyOf(links); return this; }
        public Builder structure(String structure) { this.structure = structure; return this; }
        public Builder metrics(Map<String, Object> metrics) { this.metrics = Map.copyOf(metrics); return this; }
        public Builder contentPreview(String contentPreview) { this.contentPreview = contentPreview; return this; }

        public AnalysisResult build() {
            return new AnalysisResult(summary, headings, keywords, links, structure, metrics, contentPreview);
        }
    }
}
