package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.util.AnsiOutput;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages multiple repositories within a Synthesis workspace.
 *
 * <p>Tracks repository metadata in {@code .synthesis/repos.json}, including
 * name, path, and last scan time. Supports adding, removing, and listing repos.
 *
 * <p>When a workspace has multiple repos, each file in the index is tagged
 * with its repository name for scoped querying.
 *
 * @deprecated Since v1.4.0. Use {@link SubWorkspaceResolver} with sub-workspace
 * configuration in {@code config.yaml} instead. Repository-based partitioning
 * is superseded by the sub-workspace architecture, which provides richer
 * features including scoped search, staging workflows, and organizational
 * hierarchy. Existing repos can be migrated using {@code synthesis migrate-repos}.
 * This class will be removed in v2.0.0.
 *
 * @see SubWorkspaceResolver
 * @see io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig
 */
@Deprecated(since = "1.4.0", forRemoval = true)
public class RepositoryManager {

    private static final String REPOS_FILE = "repos.json";

    private final Path workspaceRoot;
    private final List<RepoEntry> repositories;

    public RepositoryManager(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.repositories = new ArrayList<>();
    }

    /**
     * A tracked repository entry.
     */
    public record RepoEntry(
            String name,
            String path,
            Instant lastScanTime
    ) {
        public Path resolvedPath(Path workspaceRoot) {
            Path p = Path.of(path);
            if (p.isAbsolute()) return p.normalize();
            return workspaceRoot.resolve(p).normalize();
        }
    }

    /**
     * Loads repository configuration from .synthesis/repos.json.
     */
    public void load() throws IOException {
        Path reposFile = getReposFilePath();
        if (!Files.exists(reposFile)) {
            return;
        }
        String json = Files.readString(reposFile);
        parseRepos(json);
    }

    /**
     * Saves repository configuration to .synthesis/repos.json.
     */
    public void save() throws IOException {
        Path reposFile = getReposFilePath();
        Files.createDirectories(reposFile.getParent());
        try (Writer writer = Files.newBufferedWriter(reposFile)) {
            writer.write("{\n");
            writer.write("  \"version\": 1,\n");
            writer.write("  \"repositories\": [\n");

            var it = repositories.iterator();
            while (it.hasNext()) {
                RepoEntry entry = it.next();
                writer.write("    {\n");
                writer.write("      \"name\": \"" + escapeJson(entry.name()) + "\",\n");
                writer.write("      \"path\": \"" + escapeJson(entry.path()) + "\",\n");
                writer.write("      \"lastScanTime\": " +
                        (entry.lastScanTime() != null ? "\"" + entry.lastScanTime() + "\"" : "null") + "\n");
                writer.write("    }");
                if (it.hasNext()) writer.write(",");
                writer.write("\n");
            }

            writer.write("  ]\n");
            writer.write("}\n");
        }
    }

    /**
     * Adds a repository to the workspace. Supports both absolute and relative paths.
     *
     * @param repoPath the path to the repository
     * @param name optional name override (null = derived from directory name)
     * @return true if added, false if already exists
     */
    public boolean addRepository(Path repoPath, String name) {
        Path resolved = repoPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Repository path does not exist: " + resolved);
        }

        // Check for duplicates
        for (RepoEntry entry : repositories) {
            if (entry.resolvedPath(workspaceRoot).equals(resolved)) {
                return false;
            }
        }

        String repoName = name;
        if (repoName == null || repoName.isBlank()) {
            repoName = resolved.getFileName().toString();
        }

        // Ensure unique name
        repoName = ensureUniqueName(repoName);

        repositories.add(new RepoEntry(repoName, resolved.toString(), null));
        return true;
    }

    /**
     * Removes a repository by name.
     *
     * @return true if removed
     */
    public boolean removeRepository(String name) {
        return repositories.removeIf(e -> e.name().equals(name));
    }

    /**
     * Updates the last scan time for a repository.
     */
    public void updateScanTime(String repoName, Instant scanTime) {
        for (int i = 0; i < repositories.size(); i++) {
            RepoEntry entry = repositories.get(i);
            if (entry.name().equals(repoName)) {
                repositories.set(i, new RepoEntry(entry.name(), entry.path(), scanTime));
                return;
            }
        }
    }

    /**
     * Returns all tracked repositories.
     */
    public List<RepoEntry> getRepositories() {
        return Collections.unmodifiableList(repositories);
    }

    /**
     * Finds a repository by name (case-insensitive).
     */
    public Optional<RepoEntry> findByName(String name) {
        return repositories.stream()
                .filter(e -> e.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Returns whether this is a multi-repo workspace.
     */
    public boolean isMultiRepo() {
        return repositories.size() > 1;
    }

    /**
     * Returns whether any repos are configured.
     */
    public boolean hasRepos() {
        return !repositories.isEmpty();
    }

    /**
     * Returns the repo names as a list.
     */
    public List<String> getRepoNames() {
        return repositories.stream().map(RepoEntry::name).collect(Collectors.toList());
    }

    /**
     * Resolves the path to the repos.json file.
     */
    public Path getReposFilePath() {
        return workspaceRoot.resolve(WorkspaceManager.SYNTHESIS_DIR).resolve(REPOS_FILE);
    }

    private String ensureUniqueName(String name) {
        Set<String> existingNames = repositories.stream()
                .map(RepoEntry::name)
                .collect(Collectors.toSet());

        if (!existingNames.contains(name)) return name;

        for (int i = 2; i < 100; i++) {
            String candidate = name + "-" + i;
            if (!existingNames.contains(candidate)) return candidate;
        }
        return name + "-" + System.currentTimeMillis();
    }

    // --- JSON parsing (minimal, dependency-free) ---

    private void parseRepos(String json) {
        repositories.clear();
        try {
            int reposStart = json.indexOf("\"repositories\"");
            if (reposStart < 0) return;

            int arrayStart = json.indexOf('[', reposStart);
            if (arrayStart < 0) return;

            int arrayEnd = findMatchingBracket(json, arrayStart);
            if (arrayEnd < 0) return;

            String arrayContent = json.substring(arrayStart + 1, arrayEnd);

            // Parse each object in the array
            int pos = 0;
            while (pos < arrayContent.length()) {
                int objStart = arrayContent.indexOf('{', pos);
                if (objStart < 0) break;
                int objEnd = arrayContent.indexOf('}', objStart);
                if (objEnd < 0) break;

                String obj = arrayContent.substring(objStart + 1, objEnd);

                String name = extractJsonStringValue(obj, "name");
                String path = extractJsonStringValue(obj, "path");
                String timeStr = extractJsonStringValue(obj, "lastScanTime");

                if (name != null && path != null) {
                    Instant lastScan = timeStr != null ? Instant.parse(timeStr) : null;
                    repositories.add(new RepoEntry(name, path, lastScan));
                }

                pos = objEnd + 1;
            }
        } catch (Exception e) {
            // If parsing fails, start fresh
            repositories.clear();
        }
    }

    private static int findMatchingBracket(String json, int openBracket) {
        int depth = 1;
        boolean inString = false;
        for (int i = openBracket + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String extractJsonStringValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;

        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == 'n') return null; // null value

        if (json.charAt(start) != '"') return null;

        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;

        return json.substring(start + 1, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
