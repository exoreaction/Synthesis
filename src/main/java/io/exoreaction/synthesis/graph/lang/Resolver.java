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
 * <p>They are stateless pure functions over the caller-supplied index maps; a
 * later step adds the instance-level {@code resolve(ResolutionRef)} dispatch that
 * owns those maps and routes each {@link ResolutionRef} subtype here.
 * TypeScript path resolution joins this class alongside the TypeScript extractor.
 */
public class Resolver {

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
