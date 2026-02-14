package io.exoreaction.synthesis.index;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import org.apache.lucene.document.*;

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

        return doc;
    }
}
