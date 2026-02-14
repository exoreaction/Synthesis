package io.exoreaction.synthesis.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Lucene search index wrapper providing a simple API for Synthesis.
 *
 * <p>Encapsulates all Lucene complexity behind three operations:
 * <ul>
 *   <li>{@link #addDocument} -- add or update a document in the index</li>
 *   <li>{@link #search} -- query the index with a search string</li>
 *   <li>{@link #deleteAll} -- clear the index for full rebuild</li>
 * </ul>
 *
 * <p>Thread safety: This class is NOT thread-safe. Use from a single thread
 * or synchronize externally.
 */
public class SearchIndex implements Closeable {

    private final Path indexPath;
    private final Analyzer analyzer;
    private final Directory directory;
    private IndexWriter writer;

    /** Fields searched by default, with relevance boost weights. */
    private static final String[] SEARCH_FIELDS = {
            DocumentFields.FILENAME,
            DocumentFields.HEADINGS,
            DocumentFields.KEYWORDS,
            DocumentFields.SUMMARY,
            DocumentFields.CONTENT,
            DocumentFields.RELATIVE_PATH
    };

    /** Boost factors: headings and keywords rank higher than raw content. */
    private static final Map<String, Float> FIELD_BOOSTS = Map.of(
            DocumentFields.FILENAME, 3.0f,
            DocumentFields.HEADINGS, 2.5f,
            DocumentFields.KEYWORDS, 2.0f,
            DocumentFields.SUMMARY, 1.5f,
            DocumentFields.CONTENT, 1.0f,
            DocumentFields.RELATIVE_PATH, 1.0f
    );

    /**
     * Opens or creates a search index at the given path.
     *
     * @param indexPath directory where the Lucene index files are stored
     */
    public SearchIndex(Path indexPath) throws IOException {
        this.indexPath = indexPath;
        this.analyzer = new StandardAnalyzer();

        Files.createDirectories(indexPath);
        this.directory = FSDirectory.open(indexPath);
        openWriter();
    }

    private void openWriter() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
    }

    /**
     * Adds a document to the index. If a document with the same path
     * already exists, it is replaced (update semantics).
     */
    public void addDocument(Document doc) throws IOException {
        String path = doc.get(DocumentFields.PATH);
        if (path != null) {
            // Delete existing document with same path (upsert)
            writer.updateDocument(new Term(DocumentFields.RELATIVE_PATH,
                    doc.get(DocumentFields.RELATIVE_PATH)), doc);
        } else {
            writer.addDocument(doc);
        }
    }

    /**
     * Commits all pending changes to the index.
     * Must be called after adding documents to make them searchable.
     */
    public void commit() throws IOException {
        writer.commit();
    }

    /**
     * Deletes all documents from the index.
     * Used for full rebuild.
     */
    public void deleteAll() throws IOException {
        writer.deleteAll();
        writer.commit();
    }

    /**
     * Deletes a single document by its relative path.
     * Used by incremental maintenance to remove deleted files.
     *
     * @param relativePath the relative path of the file to remove
     */
    public void deleteByRelativePath(String relativePath) throws IOException {
        writer.deleteDocuments(new Term(DocumentFields.RELATIVE_PATH, relativePath));
    }

    /**
     * Returns the number of documents in the index.
     */
    public int documentCount() {
        return writer.getDocStats().numDocs;
    }

    /**
     * Searches the index with a query string.
     *
     * <p>Searches across filename, headings, keywords, summary, and content
     * with relevance boosting. Supports Lucene query syntax:
     * <ul>
     *   <li>Simple terms: {@code "testing strategy"}</li>
     *   <li>Exact phrases: {@code "\"NCI Protocol\""}</li>
     *   <li>Boolean: {@code "testing AND strategy"}</li>
     *   <li>Wildcards: {@code "test*"}</li>
     *   <li>Field-specific: {@code "language:Java"}</li>
     * </ul>
     *
     * @param queryString the search query
     * @param maxResults  maximum number of results to return
     * @return ranked list of search results
     */
    public List<SearchResult> search(String queryString, int maxResults) throws IOException {
        if (queryString == null || queryString.isBlank()) {
            return List.of();
        }

        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            Query query = buildQuery(queryString);
            TopDocs topDocs = searcher.search(query, maxResults);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(toSearchResult(doc, scoreDoc.score));
            }

            return results;
        } catch (ParseException e) {
            // If query parsing fails, try as a simple term query
            return searchSimple(queryString, maxResults);
        }
    }

    /**
     * Searches the index with an optional file type filter.
     */
    public List<SearchResult> search(String queryString, String fileTypeFilter, int maxResults) throws IOException {
        if (fileTypeFilter == null || fileTypeFilter.isBlank()) {
            return search(queryString, maxResults);
        }

        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

            // Content query
            Query contentQuery = buildQuery(queryString);
            booleanQuery.add(contentQuery, BooleanClause.Occur.MUST);

            // File type filter
            String filterValue = fileTypeFilter.toUpperCase();
            booleanQuery.add(new TermQuery(new Term(DocumentFields.FILE_TYPE, filterValue)),
                    BooleanClause.Occur.FILTER);

            TopDocs topDocs = searcher.search(booleanQuery.build(), maxResults);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(toSearchResult(doc, scoreDoc.score));
            }

            return results;
        } catch (ParseException e) {
            return List.of();
        }
    }

    /**
     * Lists all documents in the index, optionally filtered by file type.
     * Used by the export command to enumerate the entire index.
     *
     * @param fileTypeFilter optional file type filter (e.g., "CODE"), or null for all
     * @param maxResults     maximum results to return
     * @return list of all matching documents
     */
    public List<SearchResult> listAll(String fileTypeFilter, int maxResults) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            Query query;
            if (fileTypeFilter != null && !fileTypeFilter.isBlank()) {
                query = new TermQuery(new Term(DocumentFields.FILE_TYPE, fileTypeFilter.toUpperCase()));
            } else {
                query = new MatchAllDocsQuery();
            }

            TopDocs topDocs = searcher.search(query, maxResults);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(toSearchResult(doc, scoreDoc.score));
            }

            return results;
        }
    }

    /**
     * Fallback simple search when query parsing fails.
     */
    private List<SearchResult> searchSimple(String queryString, int maxResults) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
            for (String field : SEARCH_FIELDS) {
                Term term = new Term(field, queryString.toLowerCase());
                float boost = FIELD_BOOSTS.getOrDefault(field, 1.0f);
                booleanQuery.add(new BoostQuery(new FuzzyQuery(term, 2), boost),
                        BooleanClause.Occur.SHOULD);
            }

            TopDocs topDocs = searcher.search(booleanQuery.build(), maxResults);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(toSearchResult(doc, scoreDoc.score));
            }

            return results;
        }
    }

    private Query buildQuery(String queryString) throws ParseException {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        parser.setAllowLeadingWildcard(true);
        return parser.parse(queryString);
    }

    private SearchResult toSearchResult(Document doc, float score) {
        String sizeStr = doc.get(DocumentFields.SIZE);
        long sizeBytes = sizeStr != null ? Long.parseLong(sizeStr) : 0;

        return new SearchResult(
                Path.of(doc.get(DocumentFields.PATH)),
                doc.get(DocumentFields.RELATIVE_PATH),
                score,
                doc.get(DocumentFields.FILENAME),
                doc.get(DocumentFields.FILE_TYPE),
                doc.get(DocumentFields.LANGUAGE),
                doc.get(DocumentFields.SUMMARY) != null ? doc.get(DocumentFields.SUMMARY) : "",
                doc.get(DocumentFields.HEADINGS) != null ? doc.get(DocumentFields.HEADINGS) : "",
                doc.get(DocumentFields.STRUCTURE) != null ? doc.get(DocumentFields.STRUCTURE) : "",
                sizeBytes
        );
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (directory != null) {
            directory.close();
        }
    }
}
