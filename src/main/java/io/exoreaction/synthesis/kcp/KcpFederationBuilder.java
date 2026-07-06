package io.exoreaction.synthesis.kcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds KCP federation artifacts from an estate of child git repositories,
 * each carrying a {@code knowledge.yaml} (issue #358, Phase 5 of the v0.25
 * alignment epic).
 *
 * <p>Two standard outputs, both consumable by kcp-agent:
 * <ul>
 *   <li>a {@code catalog.yaml} (catalog spec v0.1) — one cartridge entry per
 *       repo with git {@code source}, {@code version}, {@code source_commit},
 *       and cycle-safe {@code depends_on}</li>
 *   <li>a root {@code knowledge.yaml} whose {@code manifests[]} block federates
 *       every repo manifest, plus {@code external_relationships} from the
 *       cross-repo dependency edges</li>
 * </ul>
 *
 * <p>Cross-repo dependency edges are derived deterministically from two
 * signals: Maven {@code <dependency><artifactId>} matches against other repos'
 * ids, and each manifest's own root {@code manifests[]} federation
 * declarations. Cycles are detected and dropped from {@code depends_on} (and
 * reported) so emitted artifacts stay acyclic per the catalog spec.
 */
public final class KcpFederationBuilder {

    /** kcp-agent federation-follower ceiling: a root manifest lists at most 50 sub-manifests. */
    public static final int MAX_MANIFESTS_PER_ROOT = 50;

    private static final Pattern ARTIFACT_ID =
            Pattern.compile("<artifactId>\\s*([^<]+?)\\s*</artifactId>");

    private KcpFederationBuilder() {
    }

    /** One repository cartridge in the estate. */
    public record RepoEntry(String name, Path repoDir, String projectId, String gitSource,
                            String httpsUrl, String version, String commit, String generated,
                            String orgGroup, List<String> dependsOn) {}

    /** A federation output file (path relative to the estate root + its YAML body). */
    public record FederationFile(String relativePath, String yaml) {}

    // -----------------------------------------------------------------------
    // Estate scan
    // -----------------------------------------------------------------------

    /**
     * Scans {@code estateRoot} for direct child git repos carrying a
     * {@code knowledge.yaml}, resolving git metadata and cross-repo deps.
     *
     * @param orgResolver maps a repo dir to an org/group label (may return null)
     * @param today       ISO date stamped as {@code generated}
     */
    public static List<RepoEntry> scanEstate(Path estateRoot,
                                             java.util.function.Function<Path, String> orgResolver,
                                             String today) {
        List<Path> repoDirs = new ArrayList<>();
        try (var children = Files.list(estateRoot)) {
            children.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("knowledge.yaml")))
                    .sorted()
                    .forEach(repoDirs::add);
        } catch (Exception e) {
            return List.of();
        }

        // Pass 1: base metadata + id → name index
        Map<String, String> idToName = new HashMap<>();   // projectId AND slug → entry name
        List<RepoEntry> entries = new ArrayList<>();
        Map<String, List<String>> rawDeps = new LinkedHashMap<>();
        for (Path repoDir : repoDirs) {
            String name = KcpScaffolder.slug(repoDir.getFileName().toString());
            String manifest = readOrNull(repoDir.resolve("knowledge.yaml"));
            String projectId = extractProject(manifest);
            String version = firstNonBlank(extractVersion(manifest),
                    latestGitTag(repoDir), "0.0.0");
            String commit = gitHead(repoDir);
            String remote = gitRemote(repoDir);
            String ref = version != null && !version.equals("0.0.0") ? "v" + version
                    : (commit != null && commit.length() == 40 ? commit : null);
            String gitSource = toGitSource(remote, ref, repoDir);
            String httpsUrl = toHttpsRawUrl(remote, ref);
            String org = orgResolver != null ? orgResolver.apply(repoDir) : null;

            idToName.put(name, name);
            if (projectId != null) idToName.put(KcpScaffolder.slug(projectId), name);

            entries.add(new RepoEntry(name, repoDir, projectId, gitSource, httpsUrl, version,
                    commit, today, org, new ArrayList<>()));
            rawDeps.put(name, deriveDependencyCandidates(repoDir, manifest));
        }

        // Pass 2: resolve dep candidates to entry names, drop self + unknowns
        for (RepoEntry entry : entries) {
            Set<String> resolved = new LinkedHashSet<>();
            for (String candidate : rawDeps.getOrDefault(entry.name(), List.of())) {
                String slug = KcpScaffolder.slug(candidate);
                String depName = idToName.get(slug);
                if (depName != null && !depName.equals(entry.name())) {
                    resolved.add(depName);
                }
            }
            entry.dependsOn().addAll(resolved);
        }

        return entries;
    }

    // -----------------------------------------------------------------------
    // Cycle detection
    // -----------------------------------------------------------------------

    /**
     * Returns a copy of {@code entries} with {@code depends_on} edges that would
     * introduce a cycle removed. Removed edges are appended to
     * {@code reportedCycles} as {@code "from -> to"} strings.
     */
    public static List<RepoEntry> withAcyclicDeps(List<RepoEntry> entries,
                                                  List<String> reportedCycles) {
        Map<String, RepoEntry> byName = new LinkedHashMap<>();
        for (RepoEntry e : entries) byName.put(e.name(), e);

        Map<String, Set<String>> accepted = new HashMap<>();
        for (RepoEntry e : entries) accepted.put(e.name(), new LinkedHashSet<>());

        for (RepoEntry e : entries) {
            for (String dep : e.dependsOn()) {
                // Adding edge e -> dep is safe unless dep can already reach e
                if (canReach(dep, e.name(), accepted)) {
                    reportedCycles.add(e.name() + " -> " + dep);
                } else {
                    accepted.get(e.name()).add(dep);
                }
            }
        }

        List<RepoEntry> result = new ArrayList<>();
        for (RepoEntry e : entries) {
            result.add(new RepoEntry(e.name(), e.repoDir(), e.projectId(), e.gitSource(),
                    e.httpsUrl(), e.version(), e.commit(), e.generated(), e.orgGroup(),
                    new ArrayList<>(accepted.get(e.name()))));
        }
        return result;
    }

    private static boolean canReach(String from, String target, Map<String, Set<String>> graph) {
        Set<String> seen = new HashSet<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (node.equals(target)) return true;
            if (!seen.add(node)) continue;
            for (String next : graph.getOrDefault(node, Set.of())) stack.push(next);
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // catalog.yaml
    // -----------------------------------------------------------------------

    public static String toCatalogYaml(List<RepoEntry> entries, String catalogName,
                                       String maintainer) {
        List<String> cycles = new ArrayList<>();
        List<RepoEntry> acyclic = withAcyclicDeps(entries, cycles);

        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by Synthesis — KCP catalog (catalog spec v0.1)\n");
        sb.append("catalog_version: \"0.1\"\n\n");
        if (catalogName != null || maintainer != null) {
            sb.append("catalog:\n");
            if (catalogName != null) sb.append("  name: ").append(catalogName).append("\n");
            sb.append("  description: \"KCP manifests for the ").append(
                    catalogName != null ? catalogName : "workspace").append(" estate\"\n");
            if (maintainer != null) sb.append("  maintainer: ").append(maintainer).append("\n");
            sb.append("\n");
        }
        sb.append("entries:\n");
        for (RepoEntry e : acyclic) {
            sb.append("  - name: ").append(e.name()).append("\n");
            sb.append("    source: ").append(e.gitSource()).append("\n");
            sb.append("    version: ").append(e.version()).append("\n");
            sb.append("    generated: \"").append(e.generated()).append("\"\n");
            if (e.commit() != null && e.commit().length() == 40) {
                sb.append("    source_commit: ").append(e.commit()).append("\n");
            }
            if (!e.dependsOn().isEmpty()) {
                sb.append("    depends_on:\n");
                for (String dep : e.dependsOn()) {
                    sb.append("      - ").append(dep).append("\n");
                }
            }
        }
        if (!cycles.isEmpty()) {
            sb.append("\n# Dropped ").append(cycles.size())
                    .append(" dependency edge(s) that would form a cycle: ")
                    .append(String.join(", ", cycles)).append("\n");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Federation root manifest(s)
    // -----------------------------------------------------------------------

    /**
     * Builds one or more federation root manifests. When the estate has at most
     * {@link #MAX_MANIFESTS_PER_ROOT} repos a single {@code knowledge.yaml} is
     * returned; otherwise repos are chunked into shard files
     * ({@code knowledge.shard-N.yaml}) referenced from a top {@code knowledge.yaml},
     * so no single manifest exceeds the follower limit.
     */
    public static List<FederationFile> toFederationManifests(List<RepoEntry> entries,
                                                             String project, String today) {
        List<String> cycles = new ArrayList<>();
        List<RepoEntry> acyclic = withAcyclicDeps(entries, cycles);
        List<FederationFile> files = new ArrayList<>();

        if (acyclic.size() <= MAX_MANIFESTS_PER_ROOT) {
            files.add(new FederationFile("knowledge.yaml",
                    renderRoot(project, today, acyclic, true)));
            return files;
        }

        // Chunk into shards; the top root federates the shard files themselves.
        List<List<RepoEntry>> shards = new ArrayList<>();
        for (int i = 0; i < acyclic.size(); i += MAX_MANIFESTS_PER_ROOT) {
            shards.add(acyclic.subList(i, Math.min(i + MAX_MANIFESTS_PER_ROOT, acyclic.size())));
        }
        StringBuilder top = new StringBuilder();
        top.append("# Generated by Synthesis — KCP federation root (sharded)\n");
        top.append("kcp_version: \"0.25\"\n");
        top.append("project: ").append(project).append("\n");
        top.append("updated: \"").append(today).append("\"\n");
        top.append("language: en\n");
        top.append("indexing: open\n");
        top.append("manifests:\n");
        for (int i = 0; i < shards.size(); i++) {
            String shardFile = "knowledge.shard-" + (i + 1) + ".yaml";
            top.append("  - id: shard-").append(i + 1).append("\n");
            top.append("    url: ./").append(shardFile).append("\n");
            top.append("    relationship: extends\n");
            top.append("    local_mirror: ./").append(shardFile).append("\n");
            files.add(new FederationFile(shardFile,
                    renderRoot(project + "-shard-" + (i + 1), today, shards.get(i), true)));
        }
        files.add(0, new FederationFile("knowledge.yaml", top.toString()));
        return files;
    }

    private static String renderRoot(String project, String today, List<RepoEntry> entries,
                                     boolean includeExternalRels) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by Synthesis — KCP federation root manifest (KCP v0.25)\n");
        sb.append("kcp_version: \"0.25\"\n");
        sb.append("project: ").append(project).append("\n");
        sb.append("version: 1.0.0\n");
        sb.append("updated: \"").append(today).append("\"\n");
        sb.append("language: en\n");
        sb.append("indexing: open\n");
        sb.append("manifests:\n");
        for (RepoEntry e : entries) {
            sb.append("  - id: ").append(e.name()).append("\n");
            // Federation url should be a TLS-fetchable manifest URL; fall back to the
            // local mirror path when the repo has no https remote.
            String url = e.httpsUrl() != null ? e.httpsUrl()
                    : "./" + e.name() + "/knowledge.yaml";
            sb.append("    url: ").append(url).append("\n");
            sb.append("    label: \"").append(e.name()).append("\"\n");
            sb.append("    relationship: references\n");
            sb.append("    local_mirror: ./").append(e.name()).append("/knowledge.yaml\n");
            if (e.orgGroup() != null && !e.orgGroup().isBlank()) {
                sb.append("    context: ").append(KcpScaffolder.slug(e.orgGroup())).append("\n");
            }
        }
        if (includeExternalRels) {
            List<String[]> edges = new ArrayList<>();
            for (RepoEntry e : entries) {
                for (String dep : e.dependsOn()) {
                    edges.add(new String[]{e.name(), dep});
                }
            }
            if (!edges.isEmpty()) {
                sb.append("external_relationships:\n");
                for (String[] edge : edges) {
                    sb.append("  - from: ").append(edge[0]).append("\n");
                    sb.append("    to: ").append(edge[1]).append("\n");
                    sb.append("    type: depends_on\n");
                }
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Dependency derivation
    // -----------------------------------------------------------------------

    /** Candidate dependency names from pom.xml artifacts + manifest federation declarations. */
    static List<String> deriveDependencyCandidates(Path repoDir, String manifest) {
        Set<String> candidates = new LinkedHashSet<>();

        // Maven: <dependency> artifactIds (skip the project's own first artifactId)
        String pom = readOrNull(repoDir.resolve("pom.xml"));
        if (pom != null) {
            pom = pom.replaceAll("(?s)<!--.*?-->", "");
            Matcher m = ARTIFACT_ID.matcher(pom);
            boolean first = true;
            while (m.find()) {
                if (first) { first = false; continue; }  // project's own artifactId
                candidates.add(m.group(1).trim());
            }
        }

        // Manifest federation declarations: manifests[].id / manifests[].url basenames
        if (manifest != null) {
            Matcher idm = Pattern.compile("(?m)^\\s*-?\\s*id:\\s*([\\w.-]+)").matcher(manifest);
            // manifests[] ids appear after a "manifests:" key; a light scan suffices for
            // fixture-scale estates — we intersect with known repo names later anyway.
            boolean inManifests = false;
            for (String line : manifest.split("\n")) {
                String s = line.strip();
                if (s.startsWith("manifests:")) { inManifests = true; continue; }
                if (inManifests && !line.startsWith(" ") && !s.isEmpty()) inManifests = false;
                if (inManifests) {
                    Matcher im = Pattern.compile("id:\\s*([\\w.-]+)").matcher(s);
                    if (im.find()) candidates.add(im.group(1));
                    Matcher um = Pattern.compile("url:.*/([\\w.-]+?)(?:\\.git)?/").matcher(s);
                    if (um.find()) candidates.add(um.group(1));
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    // -----------------------------------------------------------------------
    // Git + manifest helpers
    // -----------------------------------------------------------------------

    /** Catalog {@code source} form: {@code git+https://host/org/repo.git//knowledge.yaml@ref}. */
    static String toGitSource(String remote, String ref, Path repoDir) {
        String https = normalizeRemoteHttps(remote);
        if (https != null) {
            if (!https.endsWith(".git")) https = https + ".git";
            return "git+" + https + "//knowledge.yaml" + (ref != null ? "@" + ref : "");
        }
        return "./" + KcpScaffolder.slug(repoDir.getFileName().toString()) + "/knowledge.yaml";
    }

    /**
     * Federation {@code url} form: a TLS-fetchable manifest URL. For GitHub remotes
     * this is the {@code raw.githubusercontent.com} path; returns null when no https
     * remote is available (caller falls back to the local mirror).
     */
    static String toHttpsRawUrl(String remote, String ref) {
        String https = normalizeRemoteHttps(remote);
        if (https == null) return null;
        String noGit = https.replaceFirst("\\.git$", "");
        String r = ref != null ? ref : "HEAD";
        var m = Pattern.compile("^https://github\\.com/([^/]+)/([^/]+)$").matcher(noGit);
        if (m.matches()) {
            return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2)
                    + "/" + r + "/knowledge.yaml";
        }
        return noGit + "/knowledge.yaml";
    }

    /** Normalises a git remote (scp-style, ssh, http) to {@code https://host/path}, or null. */
    static String normalizeRemoteHttps(String remote) {
        if (remote == null || remote.isBlank()) return null;
        return remote
                .replaceFirst("^git@([^:]+):", "https://$1/")
                .replaceFirst("^ssh://git@", "https://")
                .replaceFirst("^http://", "https://");
    }

    private static String gitRemote(Path repoDir) {
        return firstLine(runGit(repoDir, "config", "--get", "remote.origin.url"));
    }

    private static String gitHead(Path repoDir) {
        return firstLine(runGit(repoDir, "rev-parse", "HEAD"));
    }

    private static String latestGitTag(Path repoDir) {
        String tag = firstLine(runGit(repoDir, "describe", "--tags", "--abbrev=0"));
        return tag != null ? tag.replaceFirst("^v", "") : null;
    }

    private static String runGit(Path repoDir, String... args) {
        if (!Files.exists(repoDir.resolve(".git"))) return null;
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repoDir.toString()));
        cmd.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                out = sb.toString();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            return process.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    static String extractProject(String manifest) {
        return manifestScalar(manifest, "project");
    }

    static String extractVersion(String manifest) {
        String v = manifestScalar(manifest, "version");
        // ignore kcp_version; only a top-level 'version:' counts
        return v;
    }

    private static String manifestScalar(String manifest, String key) {
        if (manifest == null) return null;
        for (String line : manifest.split("\n")) {
            if (line.startsWith(key + ":")) {
                String v = line.substring(key.length() + 1).trim().replaceAll("^\"|\"$", "");
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    private static String readOrNull(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        int nl = s.indexOf('\n');
        String line = (nl >= 0 ? s.substring(0, nl) : s).trim();
        return line.isBlank() ? null : line;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
