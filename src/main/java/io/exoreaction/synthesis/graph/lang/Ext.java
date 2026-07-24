package io.exoreaction.synthesis.graph.lang;

/**
 * A source-file extension a {@link LanguageExtractor} claims (e.g. {@code ".java"},
 * {@code ".kt"}, {@code ".ts"}). Declarative metadata for {@code extensions()}; the
 * concrete file-matching still lives inside each extractor's {@code findFiles}.
 */
public record Ext(String suffix) {}
