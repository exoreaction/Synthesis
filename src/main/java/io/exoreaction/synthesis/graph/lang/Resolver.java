package io.exoreaction.synthesis.graph.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared resolution algorithms for the per-language extraction seam (ADR-0001
 * sub-decision 2). These are the FQN / simple-name resolution routines lifted
 * verbatim from {@code CodeGraphExtractor}, unified in one place so every
 * language dispatches to the same algorithms rather than re-implementing them.
 *
 * <p>The static methods are stateless pure functions over caller-supplied index
 * maps. An instance owns the built indexes and exposes {@link #resolve} — the
 * single dispatch point routing each {@link ResolutionRef} subtype to the matching
 * algorithm (ADR-0001 sub-decision 2). TypeScript path resolution joins in step 6.
 */
public class Resolver {

    private final Map<String, String> classToFile;
    private final Map<String, List<String>> simpleNameIndex;
    private final Map<String, List<String>> packageFunctionFiles;

    /**
     * @param classToFile          FQN (or bare simple name when unpackaged) to workspace-relative path
     * @param simpleNameIndex      simple class name to the FQN keys carrying it
     * @param packageFunctionFiles package to Kotlin function-only files (for the Kotlin import fallback)
     */
    public Resolver(Map<String, String> classToFile,
                    Map<String, List<String>> simpleNameIndex,
                    Map<String, List<String>> packageFunctionFiles) {
        this.classToFile = classToFile;
        this.simpleNameIndex = simpleNameIndex;
        this.packageFunctionFiles = packageFunctionFiles;
    }

    /**
     * Resolves a use-site reference to a workspace-relative target file, or
     * {@code null} when unresolved (external). Dispatches each subtype to the
     * existing algorithm verbatim.
     */
    public String resolve(ResolutionRef ref) {
        return switch (ref) {
            case ResolutionRef.FqnRef f -> resolveFqn(f);
            case ResolutionRef.SimpleNameRef s ->
                    lookupBySimpleName(s.simpleName(), s.sourcePackage(), classToFile, simpleNameIndex);
            case ResolutionRef.ModulePathRef m ->
                    throw new UnsupportedOperationException(
                            "TypeScript path resolution is wired into Resolver in step 6");
        };
    }

    /**
     * FQN import resolution: exact lookup, then — only when
     * {@link ResolutionRef.FqnRef#packageFunctionFallback()} (Kotlin) — the
     * single-candidate function-only-file fallback in the imported symbol's package.
     * Java passes {@code false}, exactly as it never applied this fallback.
     */
    private String resolveFqn(ResolutionRef.FqnRef ref) {
        String targetFile = classToFile.get(ref.fqn());
        if (targetFile == null && ref.packageFunctionFallback()) {
            List<String> candidates = packageFunctionFiles.get(getPackageFromImport(ref.fqn()));
            if (candidates != null && candidates.size() == 1) {
                targetFile = candidates.get(0);
            }
        }
        return targetFile;
    }

    /**
     * Builds a reverse index from simple class name to set of FQN keys present
     * in the classToFile map. Used for extends/implements resolution where only
     * simple names are available.
     */
    public static Map<String, List<String>> buildSimpleNameIndex(Map<String, String> classToFileMap) {
        Map<String, List<String>> index = new HashMap<>();
        for (String fqn : classToFileMap.keySet()) {
            String simpleName = getSimpleClassName(fqn);
            index.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqn);
        }
        return index;
    }

    /**
     * Looks up a simple class name in the FQN map using the simple name index.
     * If exactly one project class has that simple name, returns its file path.
     * If multiple classes share the name, tries to match by source package proximity.
     * Returns null if no match (external class).
     */
    public static String lookupBySimpleName(String simpleName, String sourcePackage,
                               Map<String, String> classToFileMap,
                               Map<String, List<String>> simpleNameIndex) {
        List<String> fqns = simpleNameIndex.get(simpleName);
        if (fqns == null || fqns.isEmpty()) {
            return null; // external
        }
        if (fqns.size() == 1) {
            return classToFileMap.get(fqns.get(0));
        }
        // Multiple matches: prefer same package
        for (String fqn : fqns) {
            String pkg = getPackageFromImport(fqn);
            if (pkg.equals(sourcePackage)) {
                return classToFileMap.get(fqn);
            }
        }
        // No exact package match — return first (project-internal either way)
        return classToFileMap.get(fqns.get(0));
    }

    public static String getSimpleClassName(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualified.substring(lastDot + 1) : fullyQualified;
    }

    public static String getPackageFromImport(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualified.substring(0, lastDot) : "";
    }
}
