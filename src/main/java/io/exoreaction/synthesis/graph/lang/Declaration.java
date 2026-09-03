package io.exoreaction.synthesis.graph.lang;

import java.nio.file.Path;

/**
 * A declared identity discovered by a language extractor's {@code declarations()}
 * pass, registered with the shared resolver so later edges can resolve to
 * {@code file}. Uses {@link Path} to match the rest of the code graph.
 */
public record Declaration(ResolutionKey key, Path file) {}
