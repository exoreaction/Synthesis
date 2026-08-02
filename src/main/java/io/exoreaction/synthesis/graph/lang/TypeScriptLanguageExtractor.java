package io.exoreaction.synthesis.graph.lang;

import io.exoreaction.synthesis.graph.CodeGraphExtractor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * TypeScript / TSX extraction behind the {@link LanguageExtractor} seam (#323, ADR-0001).
 * All logic is lifted verbatim from {@code CodeGraphExtractor}'s former inline TS path.
 *
 * <p>Unlike Java/Kotlin, TypeScript resolution is <em>path-based</em>, not FQN-based: a
 * relative import is resolved against the source file's directory and a module-path index.
 * That index is contributed to the shared resolver via {@link #pathIndex} (which needs the
 * workspace root to compute relative stems); {@link #declarations} therefore contributes
 * nothing to the FQN index. The path resolution algorithm itself lives in {@link Resolver}.
 */
public class TypeScriptLanguageExtractor implements LanguageExtractor {

    /**
     * Matches ES6 import / require references and captures the module specifier.
     *
     * <p>Covers four TypeScript/JavaScript import forms:
     * <ul>
     *   <li>{@code import X from 'specifier'} — default import</li>
     *   <li>{@code import { X } from 'specifier'} — named import</li>
     *   <li>{@code import 'specifier'} — side-effect import</li>
     *   <li>{@code require('specifier')} — CommonJS</li>
     *   <li>{@code export ... from 'specifier'} — re-export</li>
     * </ul>
     * The key insight: for named / default imports the specifier follows {@code from},
     * not {@code import} directly. Using {@code (?:from|import)\s+} as the prefix
     * captures both cases with a single group.
     */
    private static final Pattern JS_TS_IMPORT = Pattern.compile(
            "(?:\\b(?:from|import)\\s+|require\\s*\\()['\"]([^'\"]+)['\"]",
            Pattern.MULTILINE);

    @Override
    public String languageId() {
        return "typescript";
    }

    @Override
    public String displayName() {
        return "TypeScript"; // not the default "Typescript"
    }

    @Override
    public Set<Ext> extensions() {
        return Set.of(new Ext(".ts"), new Ext(".tsx"));
    }

    @Override
    public Set<EdgeKind> supportedEdgeKinds() {
        return Set.of(EdgeKind.IMPORT);
    }

    /**
     * Walks the workspace for {@code .ts} and {@code .tsx} files, applying the same
     * exclusion rules used for Java (build artifacts, archive directories, hidden dirs).
     * Declaration files ({@code .d.ts}) are excluded — they describe ambient types and
     * would inflate the graph with synthetic edges.
     */
    @Override
    public List<Path> findFiles(Path root, ExclusionRules excl) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Stream<Path> filtered = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return (s.endsWith(".ts") || s.endsWith(".tsx")) && !s.endsWith(".d.ts");
                    })
                    .filter(p -> !p.toString().contains("/."))
                    .filter(p -> !CodeGraphExtractor.isBuildArtifact(root, p));
            if (!excl.includeArchives()) {
                filtered = filtered.filter(p -> !CodeGraphExtractor.isArchiveDirectory(root, p));
            }
            filtered.forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** TypeScript declares no FQN identities; its module paths are contributed via {@link #pathIndex}. */
    @Override
    public List<Declaration> declarations(Path file, String content) {
        return List.of();
    }

    /**
     * Contributes this file's module-path index entries: its relative stem (extension
     * stripped) -> its relative path, plus a directory-style {@code <dir>} -> file entry
     * when the file is an {@code index.ts(x)}. Mirrors the former {@code buildTsPathIndex}.
     */
    @Override
    public Map<String, String> pathIndex(Path root, Path file, String content) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        String stem = Resolver.stripTsExtension(rel);
        Map<String, String> index = new HashMap<>();
        index.put(stem, rel);
        if (stem.endsWith("/index")) {
            index.put(stem.substring(0, stem.length() - "/index".length()), rel);
        }
        return index;
    }

    /**
     * Import edges for a TypeScript file. Bare-module specifiers (e.g. {@code 'react'}) stay
     * external; relative specifiers resolve against the source directory and the TS path index
     * (in {@link Resolver}). Specifiers are de-duplicated (a file importing the same module
     * twice yields one edge), matching the former inline behaviour.
     */
    @Override
    public List<RawEdge> edges(Path file, String content, List<Declaration> decls) {
        String sourceModule = Resolver.stripTsExtension(file.getFileName().toString());

        Set<String> seenSpecifiers = new LinkedHashSet<>();
        Matcher m = JS_TS_IMPORT.matcher(content);
        while (m.find()) {
            String spec = m.group(1);
            if (spec != null && !spec.isBlank()) seenSpecifiers.add(spec);
        }

        List<RawEdge> edges = new ArrayList<>();
        for (String spec : seenSpecifiers) {
            String targetClass = simpleSpecifierName(spec);
            edges.add(new RawEdge(sourceModule, "",
                    new ResolutionRef.ModulePathRef(spec),
                    targetClass, "", EdgeKind.IMPORT, "import"));
        }
        return edges;
    }

    /** Extracts the trailing identifier from a module specifier (best-effort). */
    private static String simpleSpecifierName(String spec) {
        String trimmed = spec.replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        String last = (slash >= 0) ? trimmed.substring(slash + 1) : trimmed;
        // Drop common extensions for a cleaner display name.
        if (last.endsWith(".tsx")) last = last.substring(0, last.length() - 4);
        else if (last.endsWith(".ts")) last = last.substring(0, last.length() - 3);
        else if (last.endsWith(".jsx")) last = last.substring(0, last.length() - 4);
        else if (last.endsWith(".js")) last = last.substring(0, last.length() - 3);
        return last.isBlank() ? spec : last;
    }
}
