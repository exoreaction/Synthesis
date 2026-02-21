package io.exoreaction.synthesis.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builder DSL for creating realistic Synthesis workspaces in a {@code @TempDir}
 * for TDD / integration testing.
 *
 * <p>Usage example:
 * <pre>{@code
 * WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
 *     .workspaceName("my-test-workspace")
 *     .routingRule("Shell scripts", List.of("*.sh"), List.of(), "automation")
 *     .directory("automation")
 *         .withIdentity(types("automation"), formats("sh"), confidence(0.8))
 *         .end()
 *     .rootFile("start.sh", "#!/bin/bash", ageDays(60))
 *     .build();
 *
 * fixture.assertFileExists("automation/start.sh");
 * fixture.assertFileAbsent("start.sh");
 * }</pre>
 *
 * @since v1.9.9 (issue #184)
 */
public class WorkspaceFixture {

    private final Path root;

    private WorkspaceFixture(Path root) {
        this.root = root;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static Builder builder(Path tempDir) {
        return new Builder(tempDir);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the workspace root directory. */
    public Path getRoot() {
        return root;
    }

    /** Resolves a path relative to the workspace root. */
    public Path resolve(String relative) {
        return root.resolve(relative);
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    /** Asserts that the given workspace-relative path exists. */
    public void assertFileExists(String relative) {
        Path path = root.resolve(relative);
        assertTrue(Files.exists(path),
                "Expected file to exist: " + path + " (relative: " + relative + ")");
    }

    /** Asserts that the given workspace-relative path does NOT exist. */
    public void assertFileAbsent(String relative) {
        Path path = root.resolve(relative);
        assertFalse(Files.exists(path),
                "Expected file to be absent: " + path + " (relative: " + relative + ")");
    }

    /**
     * Asserts that the given workspace-relative path exists somewhere under
     * the {@code archive/} subdirectory of the workspace.
     */
    public void assertFileInArchive(String relative) {
        Path archiveDir = root.resolve("archive");
        assertTrue(Files.exists(archiveDir), "archive/ directory does not exist under " + root);

        // Walk the archive tree looking for the filename
        String filename = Path.of(relative).getFileName().toString();
        boolean found;
        try {
            found = Files.walk(archiveDir)
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().equals(filename));
        } catch (IOException e) {
            throw new AssertionError("Could not walk archive directory: " + e.getMessage(), e);
        }
        assertTrue(found,
                "Expected file '" + filename + "' to be somewhere under archive/ in " + root);
    }

    /**
     * Safety assertion: verifies that no workspace files were written outside the workspace root.
     *
     * <p>Specifically, checks that the files created inside this workspace's {@code root}
     * do NOT appear as copies under the given {@code absolutePath}. This is useful to
     * confirm that a command (e.g. sweep) did not accidentally write to a real filesystem
     * path outside the test sandbox.
     *
     * @param absolutePath absolute path that should not contain copies of workspace files
     */
    public void assertNoFilesUnder(String absolutePath) {
        Path forbidden = Path.of(absolutePath);
        if (!Files.exists(forbidden)) return; // forbidden path doesn't exist — trivially safe
        try {
            // Collect filenames that exist inside our workspace root
            java.util.Set<String> workspaceFilenames = new java.util.HashSet<>();
            if (Files.exists(root)) {
                Files.walk(root)
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .forEach(workspaceFilenames::add);
            }
            // Check if any of those filenames also appear in the forbidden path
            boolean leaked = Files.walk(forbidden)
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(root)) // ignore files inside our workspace
                    .anyMatch(p -> workspaceFilenames.contains(p.getFileName().toString()));
            assertFalse(leaked,
                    "A workspace file appears to have been written to protected path: " + absolutePath);
        } catch (IOException e) {
            // Best effort — don't fail the test on I/O errors in the safety check
        }
    }

    // -------------------------------------------------------------------------
    // Static convenience factories
    // -------------------------------------------------------------------------

    public static List<String> types(String... t)    { return List.of(t); }
    public static List<String> formats(String... f)   { return List.of(f); }
    public static List<String> patterns(String... p)  { return List.of(p); }
    public static double confidence(double c)          { return c; }
    public static int ageDays(int d)                   { return d; }

    // =========================================================================
    // Internal spec classes
    // =========================================================================

    /** Specification for a routing rule entry in {@code .synthesis/config.yaml}. */
    private record RoutingRuleSpec(
            String name,
            List<String> includeGlobs,
            List<String> excludeGlobs,
            String destination) {}

    /** Specification for a directory to create inside the workspace. */
    private record DirectorySpec(
            String relativePath,
            DirectoryIdentitySpec identity,
            List<FileSpec> files) {}

    /** Specification for a {@code .synthesis.md} identity file in a directory. */
    private record DirectoryIdentitySpec(
            List<String> types,
            List<String> formats,
            List<String> patterns,
            double confidence) {}

    /** Specification for a single file inside a directory. */
    private record FileSpec(String name, String content) {}

    /** Specification for a file at workspace root level. */
    private record RootFileSpec(String name, String content, int ageDays) {}

    /** Specification for a TTL rule entry in {@code .synthesis/ttl-rules.yaml}. */
    private record TtlRuleSpec(String name, List<String> patterns, int days) {}

    // =========================================================================
    // Builder
    // =========================================================================

    public static class Builder {

        private final Path root;
        private final List<RoutingRuleSpec> routingRules = new ArrayList<>();
        private final List<DirectorySpec> directories = new ArrayList<>();
        private final List<RootFileSpec> rootFiles = new ArrayList<>();
        private final List<TtlRuleSpec> ttlRules = new ArrayList<>();
        private String workspaceName = "test-workspace";

        public Builder(Path tempDir) {
            this.root = tempDir;
        }

        /** Sets the workspace name written into {@code .synthesis/config.yaml}. */
        public Builder workspaceName(String name) {
            this.workspaceName = name;
            return this;
        }

        /**
         * Adds a routing rule to the generated {@code .synthesis/config.yaml}.
         *
         * @param name         human-readable rule name
         * @param includeGlobs glob patterns that match filenames (e.g. {@code "*.sh"})
         * @param excludeGlobs glob patterns to exclude (may be empty)
         * @param destination  relative or absolute destination path
         */
        public Builder routingRule(String name, List<String> includeGlobs,
                                   List<String> excludeGlobs, String destination) {
            routingRules.add(new RoutingRuleSpec(name, includeGlobs, excludeGlobs, destination));
            return this;
        }

        /**
         * Adds a TTL rule to the generated {@code .synthesis/ttl-rules.yaml}.
         *
         * <p>The {@code createdAt} is set to {@code days + 1} days ago so the rule
         * is also considered expired by {@link io.exoreaction.synthesis.cli.TtlCommand}'s
         * own {@code isExpired()} check.
         *
         * @param name     human-readable rule name (not persisted in YAML, for test clarity)
         * @param patterns glob patterns that match file names (e.g. {@code "TONIGHT-*.md"})
         * @param days     files older than this many days are considered expired
         */
        public Builder withTtlRule(String name, List<String> patterns, int days) {
            ttlRules.add(new TtlRuleSpec(name, patterns, days));
            return this;
        }

        /**
         * Starts a {@link DirectoryBuilder} for configuring a subdirectory.
         *
         * @param relativePath path relative to workspace root (e.g. {@code "automation"})
         */
        public DirectoryBuilder directory(String relativePath) {
            return new DirectoryBuilder(this, relativePath);
        }

        /**
         * Adds a root-level file with fresh modification time (age = 0).
         *
         * @param name    filename
         * @param content file content
         */
        public Builder rootFile(String name, String content) {
            return rootFile(name, content, 0);
        }

        /**
         * Adds a root-level file with a configurable age.
         *
         * @param name    filename
         * @param content file content
         * @param ageDays how many days old the file's last-modified timestamp should be
         */
        public Builder rootFile(String name, String content, int ageDays) {
            rootFiles.add(new RootFileSpec(name, content, ageDays));
            return this;
        }

        /** Called by {@link DirectoryBuilder#end()} to register a completed directory spec. */
        void addDirectory(DirectorySpec spec) {
            directories.add(spec);
        }

        // ------------------------------------------------------------------
        // build()
        // ------------------------------------------------------------------

        /**
         * Materializes the workspace on disk.
         *
         * <ol>
         *   <li>Creates {@code .synthesis/} directory</li>
         *   <li>Writes {@code .synthesis/config.yaml} with routing rules</li>
         *   <li>Creates configured sub-directories (with {@code .synthesis.md} if requested)</li>
         *   <li>Creates root-level files; sets last-modified time for aged files</li>
         * </ol>
         *
         * @return a {@link WorkspaceFixture} pointing at the created workspace
         * @throws IOException if any file I/O fails
         */
        public WorkspaceFixture build() throws IOException {
            // 1. Create .synthesis/ directory
            Path synthDir = root.resolve(".synthesis");
            Files.createDirectories(synthDir);

            // 2. Write config.yaml
            writeConfigYaml(synthDir);

            // 2b. Write ttl-rules.yaml if rules are present
            if (!ttlRules.isEmpty()) {
                writeTtlRules(synthDir, ttlRules);
            }

            // 3. Create sub-directories (and their identity files)
            for (DirectorySpec dirSpec : directories) {
                Path dir = root.resolve(dirSpec.relativePath());
                Files.createDirectories(dir);

                if (dirSpec.identity() != null) {
                    writeIdentityFile(dir.resolve(".synthesis.md"), dirSpec.identity());
                }

                for (FileSpec fileSpec : dirSpec.files()) {
                    Files.writeString(dir.resolve(fileSpec.name()), fileSpec.content());
                }
            }

            // 4. Create root-level files
            for (RootFileSpec rootFile : rootFiles) {
                Path filePath = root.resolve(rootFile.name());
                Files.writeString(filePath, rootFile.content());
                if (rootFile.ageDays() > 0) {
                    Instant old = Instant.now().minus(rootFile.ageDays(), ChronoUnit.DAYS);
                    Files.setLastModifiedTime(filePath, FileTime.from(old));
                }
            }

            return new WorkspaceFixture(root);
        }

        // ------------------------------------------------------------------
        // TTL rules YAML writer
        // ------------------------------------------------------------------

        /**
         * Writes {@code .synthesis/ttl-rules.yaml} with the configured TTL rules.
         *
         * <p>Each pattern in each {@link TtlRuleSpec} becomes a separate rule entry.
         * The {@code createdAt} is set far enough in the past that the rule is also
         * considered expired by the old {@code TtlRule.isExpired()} check.
         */
        private void writeTtlRules(Path synthDir, List<TtlRuleSpec> rules) throws IOException {
            Path rulesFile = synthDir.resolve("ttl-rules.yaml");
            StringBuilder sb = new StringBuilder("rules:\n");
            for (TtlRuleSpec rule : rules) {
                for (String pattern : rule.patterns()) {
                    sb.append("- pattern: \"").append(pattern).append("\"\n");
                    sb.append("  days: ").append(rule.days()).append("\n");
                    // createdAt far in past so TtlRule.isExpired() also considers it expired
                    sb.append("  createdAt: \"")
                      .append(LocalDate.now().minusDays((long) rule.days() + 1))
                      .append("\"\n");
                }
            }
            Files.writeString(rulesFile, sb.toString());
        }

        // ------------------------------------------------------------------
        // Config YAML writer
        // ------------------------------------------------------------------

        /**
         * Writes a minimal {@code config.yaml} matching the structure expected by
         * {@link io.exoreaction.synthesis.config.SynthesisConfig} / SnakeYAML.
         *
         * <p>The routing section uses {@code patterns} (not {@code include}) to match
         * the actual {@link io.exoreaction.synthesis.config.SynthesisConfig.RoutingRule} fields.
         */
        private void writeConfigYaml(Path synthDir) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("workspace:\n");
            sb.append("  name: \"").append(workspaceName).append("\"\n");
            sb.append("\n");

            if (!routingRules.isEmpty()) {
                sb.append("routing:\n");
                sb.append("  copyCompanions: true\n");
                sb.append("  rules:\n");
                for (RoutingRuleSpec rule : routingRules) {
                    sb.append("    - name: \"").append(rule.name()).append("\"\n");
                    sb.append("      patterns:\n");
                    for (String glob : rule.includeGlobs()) {
                        sb.append("        - \"").append(glob).append("\"\n");
                    }
                    if (!rule.excludeGlobs().isEmpty()) {
                        // excludeGlobs are not a first-class field in RoutingRule,
                        // stored as comment for documentation only
                        sb.append("      # excludeGlobs: ").append(rule.excludeGlobs()).append("\n");
                    }
                    sb.append("      destination: \"").append(rule.destination()).append("\"\n");
                }
            }

            Files.writeString(synthDir.resolve("config.yaml"), sb.toString());
        }

        // ------------------------------------------------------------------
        // Identity file writer
        // ------------------------------------------------------------------

        /**
         * Writes a {@code .synthesis.md} file with YAML front matter declaring the
         * directory's accepted content types and formats.
         *
         * <p>Format matches {@link io.exoreaction.synthesis.org.DirectoryIdentityParser}:
         * <pre>
         * ---
         * synthesis:
         *   accepts:
         *     types:
         *       - "guide"
         *     formats:
         *       - "md"
         *   scope:
         *     level: "WORKSPACE"
         *     organization: null
         *     entity: null
         *   confidence: 0.85
         *   source: "manual"
         * ---
         * </pre>
         */
        private void writeIdentityFile(Path path, DirectoryIdentitySpec spec) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("synthesis:\n");
            sb.append("  accepts:\n");
            sb.append("    types:\n");
            for (String type : spec.types()) {
                sb.append("      - \"").append(type).append("\"\n");
            }
            sb.append("    formats:\n");
            for (String format : spec.formats()) {
                sb.append("      - \"").append(format).append("\"\n");
            }
            if (spec.patterns() != null && !spec.patterns().isEmpty()) {
                sb.append("    patterns:\n");
                for (String pattern : spec.patterns()) {
                    sb.append("      - \"").append(pattern).append("\"\n");
                }
            }
            sb.append("  scope:\n");
            sb.append("    level: \"WORKSPACE\"\n");
            sb.append("    organization: null\n");
            sb.append("    entity: null\n");
            sb.append("  confidence: ").append(spec.confidence()).append("\n");
            sb.append("  source: \"manual\"\n");
            sb.append("---\n");
            Files.writeString(path, sb.toString());
        }
    }

    // =========================================================================
    // DirectoryBuilder
    // =========================================================================

    public static class DirectoryBuilder {

        private final Builder parent;
        private final String relativePath;
        private DirectoryIdentitySpec identitySpec;
        private final List<FileSpec> files = new ArrayList<>();

        DirectoryBuilder(Builder parent, String relativePath) {
            this.parent = parent;
            this.relativePath = relativePath;
        }

        /**
         * Adds a {@code .synthesis.md} identity declaration to this directory.
         *
         * @param types      accepted content types (e.g. {@code "automation"}, {@code "guide"})
         * @param formats    accepted file extensions (e.g. {@code "sh"}, {@code "md"})
         * @param confidence routing confidence score (0.0-1.0)
         */
        public DirectoryBuilder withIdentity(List<String> types, List<String> formats,
                                             double confidence) {
            this.identitySpec = new DirectoryIdentitySpec(types, formats, List.of(), confidence);
            return this;
        }

        /**
         * Adds a {@code .synthesis.md} identity declaration to this directory with patterns.
         *
         * @param types      accepted content types (e.g. {@code "automation"}, {@code "guide"})
         * @param formats    accepted file extensions (e.g. {@code "sh"}, {@code "md"})
         * @param patterns   glob patterns accepted (e.g. {@code "*.sh"}, {@code "*deploy*"})
         * @param confidence routing confidence score (0.0-1.0)
         */
        public DirectoryBuilder withIdentity(List<String> types, List<String> formats,
                                             List<String> patterns, double confidence) {
            this.identitySpec = new DirectoryIdentitySpec(types, formats, patterns, confidence);
            return this;
        }

        /**
         * Adds a file to be created inside this directory.
         *
         * @param name    filename
         * @param content file content
         */
        public DirectoryBuilder withFile(String name, String content) {
            files.add(new FileSpec(name, content));
            return this;
        }

        /**
         * Returns to the parent {@link Builder}, registering this directory spec.
         */
        public Builder end() {
            parent.addDirectory(new DirectorySpec(relativePath, identitySpec, List.copyOf(files)));
            return parent;
        }
    }
}
