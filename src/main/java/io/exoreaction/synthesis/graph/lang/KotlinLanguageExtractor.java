package io.exoreaction.synthesis.graph.lang;

import io.exoreaction.synthesis.graph.CodeGraphExtractor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Kotlin extraction behind the {@link LanguageExtractor} seam (ADR-0001). All logic
 * is lifted verbatim from {@code CodeGraphExtractor}'s former inline Kotlin path.
 *
 * <p>Kotlin shares Java's FQN resolution machinery (imports are FQN-based) but adds
 * two wrinkles kept entirely inside this class: a file may declare several top-level
 * types (so the file's import edges are attributed to a chosen primary class), and a
 * pure top-level-function/utility file has no type to key on — its imports resolve
 * via the package-function fallback ({@link #packageFallbackFiles}). Supertype edges
 * use dependency type {@code "supertype"} (Kotlin's colon syntax does not distinguish
 * Java's {@code extends}/{@code implements}).
 */
public class KotlinLanguageExtractor implements LanguageExtractor {

    /**
     * Kotlin import. Unlike Java, the trailing {@code ;} is optional and imports may carry
     * an {@code as} alias ({@code import com.foo.Bar as Baz} -- alias is ignored, only the
     * FQN is captured) or a wildcard suffix ({@code import com.foo.*} -- caller drops these,
     * see {@link #extractKotlinImports}).
     */
    private static final Pattern KOTLIN_IMPORT = Pattern.compile(
            "^import\\s+([\\w.]+(?:\\.\\*)?)(?:\\s+as\\s+\\w+)?\\s*(?:;|$)", Pattern.MULTILINE);

    private static final Pattern KOTLIN_PACKAGE = Pattern.compile(
            "^package\\s+([\\w.]+)\\s*(?:;|$)", Pattern.MULTILINE);

    /**
     * Matches a top-level Kotlin type declaration ({@code class}/{@code interface}/{@code object},
     * optionally prefixed with modifiers -- {@code data}, {@code sealed}, {@code enum}, {@code value},
     * {@code annotation}, visibility, etc. -- which Kotlin allows in front of the bare keyword rather
     * than as compound keywords). No leading {@code \s*} before the anchor: nested/inner declarations
     * are indented in idiomatic Kotlin (ktlint/detekt-enforced in this codebase, verified against
     * real tvimenning-template source), so requiring column-0 is a cheap, effective filter against
     * matching non-top-level classes -- a real parser would use scope tracking instead.
     *
     * <p>Group 1: type name. Group 2 (optional): raw supertype list text after {@code :}, up to
     * {@code {} or end of line -- fed to {@link #splitKotlinSupertypes} for cleanup. Constructor-arg
     * parens and generic angle-brackets are matched non-greedily and are assumed non-nested (no
     * default-value calls like {@code = foo()} inside the primary constructor); this mirrors the
     * existing Java {@code implements} pattern's equally naive comma-split, not a regression.
     *
     * <p>{@code fun} appears in the modifier list for {@code fun interface} (SAM) declarations.
     * This cannot mis-match a top-level function: the regex still requires a following
     * {@code class}/{@code interface}/{@code object} keyword.
     */
    private static final Pattern KOTLIN_TOPLEVEL_DECL = Pattern.compile(
            "^(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:public|private|protected|internal|open|sealed|abstract|final|inner|data|enum|value|annotation|fun)\\s+)*"
                    + "(?:class|interface|object)\\s+"
                    + "([A-Z]\\w*)"
                    + "(?:\\s*<[^<>]*>)?"
                    + "(?:\\s*\\([^()]*\\))?"
                    + "(?:\\s*:\\s*([^{\\n]+))?",
            Pattern.MULTILINE);

    /** A top-level Kotlin declaration found via {@link #KOTLIN_TOPLEVEL_DECL}. */
    record KotlinDecl(String name, List<String> supertypes) {}

    @Override
    public String languageId() {
        return "kotlin";
    }

    @Override
    public Set<Ext> extensions() {
        return Set.of(new Ext(".kt"));
    }

    @Override
    public Set<EdgeKind> supportedEdgeKinds() {
        return Set.of(EdgeKind.IMPORT, EdgeKind.SUPERTYPE);
    }

    /**
     * Walks the workspace for {@code .kt} files, applying the same exclusion rules as the
     * other languages (build artifacts, archive directories, hidden dirs) -- no
     * {@code identifyNonJavaRepos}-style repo-skip logic is needed here. {@code .kts} script
     * files (Gradle Kotlin DSL, build scripts) are excluded -- they aren't application source.
     */
    @Override
    public List<Path> findFiles(Path root, ExclusionRules excl) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Stream<Path> filtered = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".kt"))
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

    /**
     * Declared identities for a Kotlin file: one {@link ResolutionKey.FqnKey} per top-level
     * type declaration, or a single filename-derived fallback key for a pure
     * function/utility file with no top-level type (mirrors Java's filename-based identity so
     * the file still has a stable identity for edge attribution).
     */
    @Override
    public List<Declaration> declarations(Path file, String content) {
        String pkg = extractKotlinPackage(content);
        List<KotlinDecl> decls = findKotlinTopLevelDecls(content);
        List<Declaration> out = new ArrayList<>();
        if (decls.isEmpty()) {
            String fallback = extractKotlinFileClassName(file);
            out.add(new Declaration(new ResolutionKey.FqnKey(fqn(pkg, fallback)), file));
        } else {
            for (KotlinDecl decl : decls) {
                out.add(new Declaration(new ResolutionKey.FqnKey(fqn(pkg, decl.name())), file));
            }
        }
        return out;
    }

    /**
     * A pure top-level-function/property file (no top-level type) contributes a
     * package-function fallback entry so imports naming its functions directly
     * ({@code import pkg.doThing}) can resolve to it -- the compiler-synthesized
     * {@code <FileName>Kt} facade is never named in source, so the FQN index can't
     * key such imports. Files with a top-level type contribute nothing.
     */
    @Override
    public Map<String, List<Path>> packageFallbackFiles(Path file, String content, List<Declaration> decls) {
        if (!findKotlinTopLevelDecls(content).isEmpty()) {
            return Map.of();
        }
        String pkg = extractKotlinPackage(content);
        return Map.of(pkg != null ? pkg : "", List.of(file));
    }

    /**
     * Import + supertype edges for a Kotlin file. Import edges are attributed to the
     * file's primary class ({@link #choosePrimaryClass}); each supertype edge is
     * attributed to its own declaring type. Imports carry the package-function fallback
     * (resolved via {@link ResolutionRef.FqnRef} with the fallback flag); supertypes use
     * simple-name resolution. Re-parses the file's declarations for supertypes -- the
     * same double-parse the former inline path performed.
     */
    @Override
    public List<RawEdge> edges(Path file, String content, List<Declaration> decls) {
        String packageName = extractKotlinPackage(content);
        String pkg = packageName != null ? packageName : "";
        List<KotlinDecl> topLevel = findKotlinTopLevelDecls(content);
        String primaryClass = choosePrimaryClass(topLevel, file);

        List<RawEdge> edges = new ArrayList<>();

        for (String imp : extractKotlinImports(content)) {
            String targetClass = Resolver.getSimpleClassName(imp);
            String targetPackage = Resolver.getPackageFromImport(imp);
            edges.add(new RawEdge(primaryClass, pkg,
                    new ResolutionRef.FqnRef(imp, true),
                    targetClass, targetPackage, EdgeKind.IMPORT, "import"));
        }

        for (KotlinDecl decl : topLevel) {
            for (String supertype : decl.supertypes()) {
                if (supertype.equals(decl.name())) continue; // guard against a malformed capture
                edges.add(new RawEdge(decl.name(), pkg,
                        new ResolutionRef.SimpleNameRef(supertype, pkg),
                        supertype, "", EdgeKind.SUPERTYPE, "supertype"));
            }
        }

        return edges;
    }

    // -----------------------------------------------------------------------
    // Kotlin parsing (verbatim from CodeGraphExtractor)
    // -----------------------------------------------------------------------

    private static String fqn(String pkg, String name) {
        return pkg != null ? pkg + "." + name : name;
    }

    /**
     * Extracts non-wildcard import FQNs from Kotlin source. Wildcard imports
     * ({@code import x.*}) are dropped -- they don't name a specific class to resolve.
     */
    List<String> extractKotlinImports(String content) {
        List<String> imports = new ArrayList<>();
        Matcher m = KOTLIN_IMPORT.matcher(content);
        while (m.find()) {
            String imp = m.group(1);
            if (imp != null && !imp.endsWith(".*")) {
                imports.add(imp);
            }
        }
        return imports;
    }

    String extractKotlinPackage(String content) {
        Matcher m = KOTLIN_PACKAGE.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Fallback identity for a Kotlin file with no top-level type declaration (e.g. an
     * extension-function-only utility file like {@code StringExt.kt}).
     */
    String extractKotlinFileClassName(Path ktFile) {
        String name = ktFile.getFileName().toString();
        return name.endsWith(".kt") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * Picks which of a Kotlin file's top-level declarations owns the file's import edges.
     * Prefer the declaration whose name matches the filename (Kotlin's strong convention);
     * fall back to the first declaration only when nothing matches.
     */
    String choosePrimaryClass(List<KotlinDecl> decls, Path ktFile) {
        String fileBasedName = extractKotlinFileClassName(ktFile);
        if (decls.isEmpty()) return fileBasedName;
        return decls.stream()
                .filter(d -> d.name().equals(fileBasedName))
                .findFirst()
                .map(KotlinDecl::name)
                .orElse(decls.get(0).name());
    }

    /**
     * Finds every top-level type declaration in a Kotlin file. Unlike Java, one file may
     * declare zero (a pure extension-function/utility file), one, or several top-level
     * classes/interfaces/objects.
     */
    List<KotlinDecl> findKotlinTopLevelDecls(String content) {
        List<KotlinDecl> decls = new ArrayList<>();
        Matcher m = KOTLIN_TOPLEVEL_DECL.matcher(content);
        while (m.find()) {
            decls.add(new KotlinDecl(m.group(1), splitKotlinSupertypes(m.group(2))));
        }
        return decls;
    }

    /**
     * Cleans a raw {@code : A(), B<T>} supertype-list capture into simple type names.
     * Strips constructor-call parens and generic angle-brackets (both assumed non-nested)
     * before splitting on top-level commas.
     */
    List<String> splitKotlinSupertypes(String raw) {
        if (raw == null) return List.of();
        String cleaned = raw
                .replaceAll("<[^<>]*>", "")
                .replaceAll("\\([^()]*\\)", "");
        List<String> names = new ArrayList<>();
        for (String part : cleaned.split(",")) {
            String name = part.trim();
            if (name.matches("[A-Za-z_][\\w.]*")) {
                names.add(Resolver.getSimpleClassName(name));
            }
        }
        return names;
    }
}
