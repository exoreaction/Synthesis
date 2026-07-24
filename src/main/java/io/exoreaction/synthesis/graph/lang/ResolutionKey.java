package io.exoreaction.synthesis.graph.lang;

/**
 * The declared identity of a source file — what a language extractor's
 * {@code declarations()} pass registers so edges can later resolve to it.
 *
 * <p>Sealed so the resolver switches exhaustively as languages are added
 * (ADR-0001 sub-decision 1): a new key kind forces a compile error at every
 * dispatch site rather than silently no-resolving.
 *
 * <ul>
 *   <li>{@link FqnKey} — fully-qualified name; Java and Kotlin (file-level).</li>
 *   <li>{@link PathKey} — workspace-relative module stem; TypeScript (file-level).</li>
 * </ul>
 */
public sealed interface ResolutionKey permits ResolutionKey.FqnKey, ResolutionKey.PathKey {

    /** Fully-qualified name key (Java, Kotlin), e.g. {@code com.example.UserService}. */
    record FqnKey(String fqn) implements ResolutionKey {}

    /** Module-path key (TypeScript): workspace-relative path with the extension stripped. */
    record PathKey(String modulePath) implements ResolutionKey {}
}
