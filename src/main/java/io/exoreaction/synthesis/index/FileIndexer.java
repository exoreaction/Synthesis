package io.exoreaction.synthesis.index;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import org.apache.lucene.document.*;

import java.time.Instant;

/**
 * Converts {@link FileMetadata} and {@link AnalysisResult} into
 * Lucene {@link Document} objects for indexing.
 *
 * <p>Field design decisions:
 * <ul>
 *   <li>PATH: stored only (retrieval), not tokenized</li>
 *   <li>FILENAME: stored + tokenized (searchable by name)</li>
 *   <li>CONTENT: tokenized only (too large to store, reconstruct from file)</li>
 *   <li>HEADINGS/KEYWORDS/SUMMARY: stored + tokenized (boosted search fields)</li>
 *   <li>FILE_TYPE/LANGUAGE/EXTENSION: keyword fields (exact match filtering)</li>
 * </ul>
 */
public class FileIndexer {

    /**
     * Creates a Lucene Document from file metadata and analysis result.
     */
    public Document createDocument(FileMetadata metadata, AnalysisResult analysis) {
        return createDocument(metadata, analysis, null);
    }

    /**
     * Creates a Lucene Document from file metadata, analysis result, and optional repository name.
     */
    public Document createDocument(FileMetadata metadata, AnalysisResult analysis, String repository) {
        Document doc = new Document();

        // Identity fields
        doc.add(new StoredField(DocumentFields.PATH, metadata.path().toString()));
        doc.add(new StringField(DocumentFields.RELATIVE_PATH, metadata.relativePath(), Field.Store.YES));
        doc.add(new TextField(DocumentFields.FILENAME, metadata.fileName(), Field.Store.YES));
        doc.add(new StringField(DocumentFields.EXTENSION, metadata.extension(), Field.Store.YES));

        // Classification fields (keyword = exact match)
        doc.add(new StringField(DocumentFields.FILE_TYPE, metadata.fileType().name(), Field.Store.YES));
        if (metadata.language() != null) {
            doc.add(new StringField(DocumentFields.LANGUAGE, metadata.language(), Field.Store.YES));
        }

        // Content field (tokenized, not stored -- content is in the file itself)
        if (!analysis.contentPreview().isEmpty()) {
            doc.add(new TextField(DocumentFields.CONTENT, analysis.contentPreview(), Field.Store.NO));
        }

        // Enriched fields from analysis (tokenized AND stored for display)
        if (!analysis.summary().isEmpty()) {
            doc.add(new TextField(DocumentFields.SUMMARY, analysis.summary(), Field.Store.YES));
        }

        if (!analysis.headings().isEmpty()) {
            String headingsText = String.join(" | ", analysis.headings());
            doc.add(new TextField(DocumentFields.HEADINGS, headingsText, Field.Store.YES));
        }

        if (!analysis.keywords().isEmpty()) {
            String keywordsText = String.join(" ", analysis.keywords());
            doc.add(new TextField(DocumentFields.KEYWORDS, keywordsText, Field.Store.YES));
        }

        // Media type from analysis (presentation, document, spreadsheet, etc.)
        Object mediaType = analysis.metrics().get("mediaType");
        if (mediaType instanceof String mt && !mt.isEmpty()) {
            doc.add(new StringField(DocumentFields.MEDIA_TYPE, mt, Field.Store.YES));
        }

        // Image dimensions
        Object width = analysis.metrics().get("width");
        Object height = analysis.metrics().get("height");
        if (width instanceof Integer w && height instanceof Integer h) {
            doc.add(new StoredField(DocumentFields.DIMENSIONS, w + "x" + h));
        }

        // Companion file
        Object companionFile = analysis.metrics().get("companionFile");
        if (companionFile instanceof String cf && !cf.isEmpty()) {
            doc.add(new StoredField(DocumentFields.COMPANION_FILE, cf));
        }

        // Metadata fields (stored for display)
        doc.add(new StoredField(DocumentFields.SIZE, Long.toString(metadata.sizeBytes())));
        doc.add(new LongPoint(DocumentFields.LAST_MODIFIED, metadata.lastModified().toEpochMilli()));
        doc.add(new StoredField(DocumentFields.LAST_MODIFIED, Long.toString(metadata.lastModified().toEpochMilli())));

        if (metadata.contentHash() != null) {
            doc.add(new StringField(DocumentFields.CONTENT_HASH, metadata.contentHash(), Field.Store.YES));
        }

        if (!analysis.structure().isEmpty()) {
            doc.add(new StoredField(DocumentFields.STRUCTURE, analysis.structure()));
        }

        // Repository field for multi-repo workspaces
        if (repository != null && !repository.isEmpty()) {
            doc.add(new StringField(DocumentFields.REPOSITORY, repository, Field.Store.YES));
        }

        return doc;
    }

    /**
     * Creates a Lucene Document with full organizational context.
     *
     * @param metadata     file metadata
     * @param analysis     analysis results
     * @param repository   optional repository name
     * @param organization optional organization name (e.g., "eXOReaction")
     * @param client       optional client name (e.g., "SpareBank1")
     */
    public Document createDocument(FileMetadata metadata, AnalysisResult analysis,
                                    String repository, String organization, String client) {
        Document doc = createDocument(metadata, analysis, repository);

        // Organization field
        if (organization != null && !organization.isEmpty()) {
            doc.add(new StringField(DocumentFields.ORGANIZATION, organization, Field.Store.YES));
        }

        // Client field
        if (client != null && !client.isEmpty()) {
            doc.add(new StringField(DocumentFields.CLIENT, client, Field.Store.YES));
        }

        return doc;
    }

    /**
     * Creates a Lucene Document with full organizational context and sub-workspace tagging.
     *
     * @param metadata     file metadata
     * @param analysis     analysis results
     * @param repository   optional repository name
     * @param organization optional organization name (e.g., "eXOReaction")
     * @param client       optional client name (e.g., "SpareBank1")
     * @param subWorkspace optional sub-workspace name (e.g., "eXOReaction", "Quadim")
     */
    public Document createDocument(FileMetadata metadata, AnalysisResult analysis,
                                    String repository, String organization, String client,
                                    String subWorkspace) {
        Document doc = createDocument(metadata, analysis, repository, organization, client);

        // Sub-workspace field
        if (subWorkspace != null && !subWorkspace.isEmpty()) {
            doc.add(new StringField(DocumentFields.SUB_WORKSPACE, subWorkspace, Field.Store.YES));
        }

        return doc;
    }

    /**
     * Creates a Lucene Document for a virtual file (e.g., a Notion page synced as Markdown).
     *
     * <p>Virtual files have no physical path on disk. The {@code virtualPath} is used
     * as both the path and relative path, and the content is indexed directly.
     * The {@link DocumentFields#SOURCE} field is set to "notion" to distinguish
     * these from filesystem-backed documents.
     *
     * @param virtualPath    the virtual filesystem path (e.g., "notion://Engineering/Architecture.md")
     * @param content        the Markdown content to index
     * @param lastModifiedMs last modified time in epoch milliseconds
     * @return a Lucene Document representing the virtual file
     */
    public Document indexVirtualFile(String virtualPath, String content, long lastModifiedMs) {
        Document doc = new Document();

        // Use "notion://" prefix to clearly distinguish from filesystem paths
        String prefixedPath = "notion://" + virtualPath;

        // Identity fields
        doc.add(new StoredField(DocumentFields.PATH, prefixedPath));
        doc.add(new StringField(DocumentFields.RELATIVE_PATH, prefixedPath, Field.Store.YES));

        // Extract filename from virtual path
        String fileName = virtualPath.contains("/")
                ? virtualPath.substring(virtualPath.lastIndexOf('/') + 1)
                : virtualPath;
        doc.add(new TextField(DocumentFields.FILENAME, fileName, Field.Store.YES));
        doc.add(new StringField(DocumentFields.EXTENSION, ".md", Field.Store.YES));

        // Classification
        doc.add(new StringField(DocumentFields.FILE_TYPE, "MARKDOWN", Field.Store.YES));
        doc.add(new StringField(DocumentFields.SOURCE, "notion", Field.Store.YES));

        // Content (tokenized, not stored — content can be re-fetched from Notion)
        if (content != null && !content.isEmpty()) {
            doc.add(new TextField(DocumentFields.CONTENT, content, Field.Store.NO));

            // Extract a summary from the first 200 chars of content
            String summary = content.length() > 200 ? content.substring(0, 200) + "..." : content;
            doc.add(new TextField(DocumentFields.SUMMARY, summary, Field.Store.YES));
        }

        // Metadata
        doc.add(new StoredField(DocumentFields.SIZE, Long.toString(content != null ? content.length() : 0)));
        doc.add(new LongPoint(DocumentFields.LAST_MODIFIED, lastModifiedMs));
        doc.add(new StoredField(DocumentFields.LAST_MODIFIED, Long.toString(lastModifiedMs)));

        return doc;
    }
}
