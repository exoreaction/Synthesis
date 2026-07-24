package io.exoreaction.synthesis.graph.lang;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-language extraction strategy (ADR-0001). Each supported language implements
 * the three capabilities the shared orchestrator drives — find files, declare
 * identities, emit edges — while the orchestrator keeps every shared concern
 * (resolution, persistence, cross-format, incremental scoping).
 *
 * <p>The two-pass contract: {@link #findFiles} + {@link #declarations} always run
 * over the full workspace so the resolution index is never stale; only
 * {@link #edges} is scoped to changed files on an incremental update
 * (ADR-0001 sub-decision 3).
 *
 * <p>A new language slots in as one more implementation registered with the
 * orchestrator — no orchestrator edits beyond the registry entry (ADR-0001
 * acceptance criterion).
 */
public interface LanguageExtractor {

    /** Stable identifier for this language (e.g. {@code "java"}), for logging/diagnostics. */
    String languageId();

    /** The file extensions this language claims. */
    Set<Ext> extensions();

    /**
     * Discovers this language's source files under {@code root}, applying the
     * shared {@code excl} plus this language's own fixed exclusions. Returns a
     * {@link List} (not a set) to preserve deterministic discovery order.
     */
    List<Path> findFiles(Path root, ExclusionRules excl);

    /**
     * Declared identities in {@code file} — registered with the shared resolver so
     * later edges can resolve to this file. Runs in the always-full pass 1.
     */
    List<Declaration> declarations(Path file, String content);

    /** The edge kinds this language emits (opt-in). */
    Set<EdgeKind> supportedEdgeKinds();

    /**
     * Dependency edges originating in {@code file}, targets still unresolved. The
     * file's own {@code decls} are passed in so no re-parse is needed
     * (ADR-0001 Q6=A). Runs in pass 2, scoped to changed files when incremental.
     */
    List<RawEdge> edges(Path file, String content, List<Declaration> decls);

    /**
     * Optional per-package fallback entries ({@code package -> declaring files}) this
     * language contributes to the shared resolver, for imports that name a symbol the
     * FQN index cannot key directly (Kotlin top-level functions resolve to the single
     * function-only file in the package). Computed in the same declaration pass from
     * the already-read {@code content}.
     *
     * <p>Default: no contribution. The orchestrator merges whatever is returned without
     * knowing the language, so a language that needs no such fallback (Java, TypeScript,
     * a future Go) leaves this empty and stays entirely self-contained.
     */
    default Map<String, List<Path>> packageFallbackFiles(Path file, String content, List<Declaration> decls) {
        return Map.of();
    }

    /**
     * Optional module-path index entries ({@code module-stem -> workspace-relative file})
     * this language contributes to the shared resolver, for path-based (not FQN-based)
     * resolution — TypeScript relative imports. Needs {@code root} to compute the
     * workspace-relative stem, so it is passed here (unlike {@link #packageFallbackFiles},
     * whose keys come from file content). Default: no contribution; FQN-resolved languages
     * (Java, Kotlin) and a future Go leave this empty.
     */
    default Map<String, String> pathIndex(Path root, Path file, String content) {
        return Map.of();
    }
}
