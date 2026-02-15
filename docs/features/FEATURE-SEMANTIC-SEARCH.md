# Feature: Semantic Code Search

**Status:** Implemented | **Priority:** 2 | **Version:** 1.0.4-SNAPSHOT

## Overview

Semantic search enables meaning-based file discovery using vector embeddings. Instead of matching keywords, it understands the *intent* behind queries and finds files with similar semantic content.

## Problem

Keyword search works well for exact terms but fails when:
- You search for "how errors are handled" but the code uses `catch`, `exception`, or `fallback`
- You search for "database connection management" but the code uses `DataSource`, `pool`, or `ConnectionFactory`
- You want files about a concept (e.g., "retry logic") rather than a specific term

## Solution

The `--semantic` flag on `synthesis search` switches from Lucene keyword search to embedding-based similarity search:

```bash
synthesis search "how authentication tokens are refreshed" --semantic
```

## Architecture

### EmbeddingService

Located at: `src/main/java/io/exoreaction/synthesis/ai/EmbeddingService.java`

The `EmbeddingService` is the core component that:
1. Generates 256-dimension vector embeddings for text
2. Supports two providers: OpenAI (`text-embedding-3-small`) and local TF-IDF
3. Computes cosine similarity between query and document embeddings
4. Caches embeddings by content hash for performance

### Embedding Providers

| Provider | When Used | Dimensions | Quality |
|----------|-----------|-----------|---------|
| **OpenAI** | `OPENAI_API_KEY` is set | 256 | High (semantic understanding) |
| **Local TF-IDF** | No API key (fallback) | 256 | Medium (term frequency based) |

The service automatically falls back to local TF-IDF when no OpenAI API key is available, ensuring the feature works offline.

### Local TF-IDF Implementation

The local provider uses hash-based feature extraction:
1. Tokenizes input text into words
2. Applies bi-gram generation for phrase-level features
3. Maps tokens to fixed dimensions via hash functions
4. L2-normalizes the resulting vector

This approach requires no external dependencies and produces deterministic embeddings.

## CLI Integration

### Search Command

```bash
# Basic semantic search
synthesis search "error handling patterns" --semantic

# With custom similarity threshold
synthesis search "retry logic" --semantic --similarity-threshold 0.5

# Combined with type filter
synthesis search "authentication" --semantic --type CODE --limit 10
```

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `--semantic` | boolean | `false` | Enable semantic search mode |
| `--similarity-threshold` | float | `0.3` | Minimum cosine similarity (0.0-1.0) |

## How It Works

1. **Query embedding:** The search query is converted to a 256-dimension vector
2. **Document retrieval:** All indexed files are retrieved (up to the limit)
3. **Content embedding:** Each file's content preview is embedded
4. **Similarity scoring:** Cosine similarity is computed between query and each document
5. **Ranking:** Results are sorted by similarity score (highest first)
6. **Filtering:** Results below the similarity threshold are excluded

## Performance

| Operation | Time | Notes |
|-----------|------|-------|
| Local embedding (single) | <1ms | Hash-based, very fast |
| OpenAI embedding (single) | 100-500ms | API call, network latency |
| Batch embedding (100 files) | 1-5s (local) / 5-15s (OpenAI) | Parallelizable |
| Similarity computation | O(d) per pair | d = 256 dimensions |

## Testing

Tests located at: `src/test/java/io/exoreaction/synthesis/ai/EmbeddingServiceTest.java`

21 tests covering:
- Dimension correctness (256-d output)
- Empty and null text handling
- Similarity comparisons (related > unrelated)
- Vector normalization (unit length)
- Deterministic output
- File embedding
- Batch processing
- Cosine similarity mathematics
- Caching behavior
- Provider detection

## Configuration

### OpenAI Provider

```bash
export OPENAI_API_KEY="sk-..."
synthesis search "concept" --semantic
```

### Local Provider (default)

No configuration needed. Works offline with deterministic results.

## Limitations

- Local TF-IDF embeddings are term-frequency based, not truly semantic
- OpenAI provider requires network access and API key
- Large workspaces may be slow without caching
- Similarity threshold needs tuning per workspace (start with 0.3)

## Future Enhancements

- Pre-computed embedding index stored in Lucene for faster search
- Support for additional embedding providers (Cohere, local models)
- MCP tool integration (`search` with `semantic: true` parameter)
- Incremental embedding updates during `synthesis scan`
