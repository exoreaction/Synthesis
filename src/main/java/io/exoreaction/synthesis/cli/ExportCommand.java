package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.ai.PromptTemplates;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import io.exoreaction.synthesis.util.Version;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Exports the workspace index as JSON or Markdown.
 *
 * <p>Useful for:
 * <ul>
 *   <li>Sharing a workspace overview with team members</li>
 *   <li>Generating AI context (inject into Claude Code prompts)</li>
 *   <li>Creating documentation from indexed content</li>
 *   <li>Auditing what's in the index</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   synthesis export                                    # Export as Markdown to stdout
 *   synthesis export --format json                      # Export as JSON to stdout
 *   synthesis export --output overview.md               # Export to file
 *   synthesis export --type CODE                        # Export only code files
 *   synthesis export --format architecture-doc          # AI-generated architecture doc
 *   synthesis export --format onboarding-guide          # AI-generated onboarding guide
 *   synthesis export --format kcp --output knowledge.yaml  # Knowledge Context Protocol export
 * </pre>
 */
@Command(
        name = "export",
        description = "Export workspace index as JSON or Markdown",
        mixinStandardHelpOptions = true
)
public class ExportCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"-f", "--format"},
            description = "Output format: markdown, json, architecture-doc, onboarding-guide, kcp (default: markdown)",
            defaultValue = "markdown"
    )
    private String format;

    @Option(
            names = {"-o", "--output"},
            description = "Output file (default: stdout)"
    )
    private Path output;

    @Option(
            names = {"--type"},
            description = "Filter by file type (e.g., CODE, MARKDOWN, YAML, PDF)"
    )
    private String typeFilter;

    @Option(
            names = {"--limit"},
            description = "Maximum number of entries to export",
            defaultValue = "1000"
    )
    private int limit;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            // Query the index for all documents
            List<SearchResult> results;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                results = index.listAll(typeFilter, limit);
            }

            if (results.isEmpty()) {
                AnsiOutput.printWarning("No documents found in index. Run 'synthesis scan' first.");
                return 1;
            }

            // Generate output
            String content = switch (format.toLowerCase()) {
                case "json" -> exportAsJson(config, results);
                case "markdown", "md" -> exportAsMarkdown(config, results, workspaceRoot);
                case "architecture-doc", "architecture" -> exportAsArchitectureDoc(config, results, workspaceRoot);
                case "onboarding-guide", "onboarding" -> exportAsOnboardingGuide(config, results, workspaceRoot);
                case "kcp", "knowledge-context-protocol" -> exportAsKcp(config, results, workspaceRoot);
                default -> {
                    AnsiOutput.printError("Unknown format: " + format +
                            ". Use 'markdown', 'json', 'architecture-doc', 'onboarding-guide', or 'kcp'.");
                    yield null;
                }
            };

            if (content == null) return 1;

            // Write output
            if (output != null) {
                Files.writeString(output, content);
                AnsiOutput.printSuccess("Exported " + results.size() + " entries to " + output);
            } else {
                System.out.print(content);
            }

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Export failed: " + e.getMessage());
            return 1;
        }
    }

    private String exportAsJson(SynthesisConfig config, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"workspace\": \"").append(escapeJson(config.getWorkspace().getName())).append("\",\n");
        sb.append("  \"type\": \"").append(escapeJson(config.getWorkspace().getType())).append("\",\n");
        sb.append("  \"totalFiles\": ").append(results.size()).append(",\n");
        sb.append("  \"files\": [\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("    {\n");
            sb.append("      \"path\": \"").append(escapeJson(r.relativePath())).append("\",\n");
            sb.append("      \"fileName\": \"").append(escapeJson(r.fileName())).append("\",\n");
            sb.append("      \"fileType\": \"").append(r.fileType() != null ? escapeJson(r.fileType()) : "").append("\",\n");
            sb.append("      \"language\": ").append(r.language() != null ? "\"" + escapeJson(r.language()) + "\"" : "null").append(",\n");
            sb.append("      \"sizeBytes\": ").append(r.sizeBytes()).append(",\n");
            sb.append("      \"summary\": \"").append(escapeJson(r.summary())).append("\"\n");
            sb.append("    }");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String exportAsMarkdown(SynthesisConfig config, List<SearchResult> results, Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(config.getWorkspace().getName()).append(" - Workspace Index\n\n");
        sb.append("**Type:** ").append(config.getWorkspace().getType()).append("\n");
        sb.append("**Root:** ").append(workspaceRoot).append("\n");
        sb.append("**Total files:** ").append(results.size()).append("\n\n");

        // Group by file type
        Map<String, List<SearchResult>> byType = results.stream()
                .collect(Collectors.groupingBy(
                        r -> r.fileType() != null ? r.fileType() : "OTHER",
                        TreeMap::new,
                        Collectors.toList()
                ));

        // Type summary table
        sb.append("## File Types\n\n");
        sb.append("| Type | Count |\n");
        sb.append("|------|-------|\n");
        for (var entry : byType.entrySet()) {
            sb.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue().size()).append(" |\n");
        }
        sb.append("\n");

        // Files grouped by type
        for (var entry : byType.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n\n");

            for (SearchResult r : entry.getValue()) {
                sb.append("- **").append(r.relativePath()).append("**");
                if (r.language() != null) {
                    sb.append(" (").append(r.language()).append(")");
                }
                sb.append(" - ").append(FileUtils.formatSize(r.sizeBytes()));
                if (!r.summary().isEmpty()) {
                    sb.append("\n  > ").append(truncate(r.summary(), 120));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Exports as AI-generated architecture documentation.
     * Falls back to basic markdown if AI is unavailable.
     */
    private String exportAsArchitectureDoc(SynthesisConfig config, List<SearchResult> results, Path workspaceRoot) {
        String fileIndex = buildFileIndex(results);

        Optional<AiClient> clientOpt = AiClient.create(config.getAi());
        if (clientOpt.isEmpty()) {
            AnsiOutput.printWarning("AI not configured. Generating basic architecture overview.");
            return exportAsBasicArchitecture(config, results, workspaceRoot, fileIndex);
        }

        AnsiOutput.printInfo("Generating architecture documentation with AI...");

        try {
            String prompt = PromptTemplates.buildArchitectureDocPrompt(
                    config.getWorkspace().getName(),
                    config.getWorkspace().getType(),
                    workspaceRoot.toString(),
                    fileIndex
            );
            return clientOpt.get().generate(prompt, 4000);
        } catch (Exception e) {
            AnsiOutput.printWarning("AI generation failed: " + e.getMessage() + ". Using basic format.");
            return exportAsBasicArchitecture(config, results, workspaceRoot, fileIndex);
        }
    }

    /**
     * Exports as AI-generated onboarding guide.
     * Falls back to basic markdown if AI is unavailable.
     */
    private String exportAsOnboardingGuide(SynthesisConfig config, List<SearchResult> results, Path workspaceRoot) {
        String fileIndex = buildFileIndex(results);

        Optional<AiClient> clientOpt = AiClient.create(config.getAi());
        if (clientOpt.isEmpty()) {
            AnsiOutput.printWarning("AI not configured. Generating basic onboarding guide.");
            return exportAsBasicOnboarding(config, results, workspaceRoot, fileIndex);
        }

        AnsiOutput.printInfo("Generating onboarding guide with AI...");

        try {
            String prompt = PromptTemplates.buildOnboardingGuidePrompt(
                    config.getWorkspace().getName(),
                    config.getWorkspace().getType(),
                    workspaceRoot.toString(),
                    fileIndex
            );
            return clientOpt.get().generate(prompt, 4000);
        } catch (Exception e) {
            AnsiOutput.printWarning("AI generation failed: " + e.getMessage() + ". Using basic format.");
            return exportAsBasicOnboarding(config, results, workspaceRoot, fileIndex);
        }
    }

    /**
     * Builds a compact file index string for AI prompts.
     */
    String buildFileIndex(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (SearchResult r : results) {
            sb.append(r.relativePath());
            if (r.fileType() != null) sb.append(" [").append(r.fileType()).append("]");
            if (r.language() != null) sb.append(" (").append(r.language()).append(")");
            sb.append(" ").append(FileUtils.formatSize(r.sizeBytes()));
            if (!r.summary().isEmpty()) {
                sb.append(" - ").append(truncate(r.summary(), 80));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Basic architecture doc fallback (no AI).
     */
    private String exportAsBasicArchitecture(SynthesisConfig config, List<SearchResult> results,
                                              Path workspaceRoot, String fileIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Architecture: ").append(config.getWorkspace().getName()).append("\n\n");
        sb.append("**Type:** ").append(config.getWorkspace().getType()).append("\n");
        sb.append("**Root:** ").append(workspaceRoot).append("\n");
        sb.append("**Total files:** ").append(results.size()).append("\n\n");

        // Technology stack
        Map<String, Long> languages = results.stream()
                .filter(r -> r.language() != null)
                .collect(Collectors.groupingBy(SearchResult::language, Collectors.counting()));
        if (!languages.isEmpty()) {
            sb.append("## Technology Stack\n\n");
            languages.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> sb.append("- **").append(e.getKey()).append("**: ")
                            .append(e.getValue()).append(" files\n"));
            sb.append("\n");
        }

        // Directory structure
        Map<String, Long> directories = results.stream()
                .collect(Collectors.groupingBy(r -> {
                    String path = r.relativePath();
                    int sep = path.indexOf('/');
                    if (sep < 0) sep = path.indexOf('\\');
                    return sep > 0 ? path.substring(0, sep) : "(root)";
                }, Collectors.counting()));

        sb.append("## Directory Structure\n\n");
        directories.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("- **").append(e.getKey()).append("/**: ")
                        .append(e.getValue()).append(" files\n"));
        sb.append("\n");

        // Key files
        sb.append("## Key Files\n\n");
        results.stream()
                .filter(r -> r.fileName().equalsIgnoreCase("README.md") ||
                        r.fileName().equalsIgnoreCase("pom.xml") ||
                        r.fileName().equalsIgnoreCase("package.json") ||
                        r.fileName().equalsIgnoreCase("build.gradle") ||
                        r.fileName().equalsIgnoreCase("Makefile") ||
                        r.fileName().equalsIgnoreCase("Dockerfile"))
                .forEach(r -> {
                    sb.append("- `").append(r.relativePath()).append("`");
                    if (!r.summary().isEmpty()) {
                        sb.append(" - ").append(truncate(r.summary(), 80));
                    }
                    sb.append("\n");
                });
        sb.append("\n");

        sb.append("---\n");
        sb.append("*Note: This is a basic architecture overview. Enable AI (set ai.enabled=true " +
                "and the provider's API key) for a detailed narrative document.*\n");

        return sb.toString();
    }

    /**
     * Exports as Knowledge Context Protocol (KCP) YAML.
     *
     * <p>Without {@code --type}, only MARKDOWN files are included (the KCP-natural audience).
     * Pass {@code --type CODE} (or any type) to override.
     *
     * <p>Files with no summary AND no headings in the index are skipped; a trailing comment
     * reports the count so users know to run {@code synthesis maintain} to enrich them.
     */
    private String exportAsKcp(SynthesisConfig config, List<SearchResult> results, Path workspaceRoot) {
        // Step 1 — Filter: default to MARKDOWN only; respect explicit --type if given.
        // Always exclude .synthesis.md companion files (Synthesis internals, not knowledge units).
        List<SearchResult> filtered;
        if (typeFilter == null) {
            filtered = results.stream()
                    .filter(r -> "MARKDOWN".equals(r.fileType()))
                    .filter(r -> !r.relativePath().endsWith(".synthesis.md"))
                    .toList();
        } else {
            filtered = results.stream()
                    .filter(r -> !r.relativePath().endsWith(".synthesis.md"))
                    .toList();
        }

        StringBuilder sb = new StringBuilder();
        String version = Version.getVersion();
        String today = LocalDate.now().toString();
        String workspaceName = config.getWorkspace().getName();

        // Pre-count non-skipped units for manifest hints
        int totalUnits = (int) filtered.stream().filter(r -> !isBlankSummaryAndHeadings(r)).count();

        // Step 2 — Header
        sb.append("# Generated by Synthesis ").append(version)
                .append(" \u2014 Knowledge Context Protocol (KCP) v0.5\n");
        sb.append("# Spec: github.com/cantara/knowledge-context-protocol\n");
        sb.append("kcp_version: \"0.5\"\n");
        sb.append("project: ").append(workspaceName).append("\n");
        sb.append("version: 1.0.0\n");
        sb.append("updated: \"").append(today).append("\"\n");
        sb.append("language: en\n");
        sb.append("indexing: open\n");
        sb.append("hints:\n");
        sb.append("  unit_count: ").append(totalUnits).append("\n");
        sb.append("\n");
        sb.append("units:\n");

        // Step 3 — Emit units, tracking skipped count
        int[] skipped = {0};

        // Step 4 — Multi-repo grouping: group with comment headers when repos present
        boolean hasRepos = filtered.stream().anyMatch(r -> r.repository() != null);
        if (hasRepos) {
            Map<String, List<SearchResult>> byRepo = filtered.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.repository() != null ? r.repository() : "",
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            for (var entry : byRepo.entrySet()) {
                if (!entry.getKey().isEmpty()) {
                    sb.append("  # --- Repository: ").append(entry.getKey()).append(" ---\n");
                }
                for (SearchResult r : entry.getValue()) {
                    if (isBlankSummaryAndHeadings(r)) { skipped[0]++; continue; }
                    appendKcpUnit(sb, r);
                }
            }
        } else {
            for (SearchResult r : filtered) {
                if (isBlankSummaryAndHeadings(r)) { skipped[0]++; continue; }
                appendKcpUnit(sb, r);
            }
        }

        if (skipped[0] > 0) {
            sb.append("\n# ").append(skipped[0])
                    .append(" file(s) skipped \u2014 no summary in index. Run 'synthesis maintain' to enrich.\n");
        }

        return sb.toString();
    }

    /**
     * Test-only accessor: runs the KCP export without requiring a real workspace config.
     * Uses a default {@link io.exoreaction.synthesis.config.SynthesisConfig} (empty workspace name).
     */
    String exportAsKcpForTest(List<SearchResult> results, java.nio.file.Path workspaceRoot) {
        return exportAsKcp(new io.exoreaction.synthesis.config.SynthesisConfig(), results, workspaceRoot);
    }

    private boolean isBlankSummaryAndHeadings(SearchResult r) {
        boolean noSummary = r.summary() == null || r.summary().isBlank();
        boolean noHeadings = r.headings() == null || r.headings().isBlank();
        return noSummary && noHeadings;
    }

    private void appendKcpUnit(StringBuilder sb, SearchResult r) {
        String intent = toKcpIntent(r.summary());
        List<String> triggers = toKcpTriggers(r.headings(), r.structure());
        String format = toKcpFormat(r.relativePath());
        String kind   = toKcpKind(r.relativePath());

        sb.append("  - id: ").append(toKcpId(r.relativePath())).append("\n");
        sb.append("    path: ").append(r.relativePath()).append("\n");
        if (!intent.isEmpty()) {
            sb.append("    intent: \"").append(intent).append("\"\n");
        }
        sb.append("    scope: ").append(toKcpScope(r.relativePath())).append("\n");
        sb.append("    audience: ").append(toKcpAudience(r.fileType())).append("\n");
        if (format != null) {
            sb.append("    format: ").append(format).append("\n");
        }
        if (kind != null) {
            sb.append("    kind: ").append(kind).append("\n");
        }
        sb.append("    validated: \"").append(toKcpValidated(r.path())).append("\"\n");
        if (!triggers.isEmpty()) {
            sb.append("    triggers: [").append(String.join(", ", triggers)).append("]\n");
        }
        sb.append("\n");
    }

    /** Converts a relative file path to a KCP slug id. */
    private String toKcpId(String relativePath) {
        // Strip extension
        int dot = relativePath.lastIndexOf('.');
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        String noExt = (dot > slash) ? relativePath.substring(0, dot) : relativePath;
        // Replace path separators, spaces and non-slug chars with dashes, lowercase, collapse
        String slug = noExt.toLowerCase()
                .replaceAll("[/\\\\\\s]+", "-")
                .replaceAll("[^a-z0-9\\-.]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug;
    }

    /** Returns the first sentence of summary (up to first '.' or 120 chars), YAML-safe.
     *  A '.' is only treated as a sentence boundary when followed by whitespace or end-of-string,
     *  not when embedded in a word (e.g. "llms.txt", "v0.1.0"). */
    private String toKcpIntent(String summary) {
        if (summary == null || summary.isBlank()) return "";
        int dotPos = -1;
        int limit = Math.min(summary.length(), 121);
        for (int i = 0; i < limit; i++) {
            if (summary.charAt(i) == '.') {
                int next = i + 1;
                if (next >= summary.length() || summary.charAt(next) == ' ' || summary.charAt(next) == '\n') {
                    dotPos = i;
                    break;
                }
            }
        }
        String sentence = (dotPos > 0 && dotPos <= 120)
                ? summary.substring(0, dotPos + 1).trim()
                : summary.substring(0, Math.min(summary.length(), 120)).trim();
        // Escape double-quotes for inline YAML
        return sentence.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Maps path depth to KCP scope: 0-1 separators → global, 2 → project, 3+ → module. */
    private String toKcpScope(String relativePath) {
        int depth = 0;
        for (char c : relativePath.toCharArray()) {
            if (c == '/' || c == '\\') depth++;
        }
        if (depth <= 1) return "global";
        if (depth == 2) return "project";
        return "module";
    }

    /** Maps Synthesis file type to KCP audience array. */
    private String toKcpAudience(String fileType) {
        if (fileType == null) return "[agent]";
        return switch (fileType.toUpperCase()) {
            case "MARKDOWN" -> "[human, agent]";
            case "CODE" -> "[developer, agent]";
            case "YAML", "JSON", "CONFIG" -> "[developer, devops, agent]";
            default -> "[agent]";
        };
    }

    /** Returns the file's last-modified date as an ISO-8601 date string. */
    private String toKcpValidated(Path absolutePath) {
        try {
            FileTime ft = Files.getLastModifiedTime(absolutePath);
            return LocalDate.ofInstant(ft.toInstant(), java.time.ZoneId.systemDefault()).toString();
        } catch (IOException e) {
            return LocalDate.now().toString();
        }
    }

    /**
     * Extracts up to 8 lowercase slug triggers from headings and structure text.
     * Each heading line is converted to a hyphenated slug; empty results are omitted.
     */
    List<String> toKcpTriggers(String headings, String structure) {
        List<String> triggers = new ArrayList<>();
        for (String source : new String[]{headings, structure}) {
            if (source == null || source.isBlank()) continue;
            for (String line : source.split("[\\n|,;]+")) {
                // Strip leading Markdown heading markers and whitespace
                String cleaned = line.replaceAll("^#+\\s*", "").trim();
                if (cleaned.isBlank()) continue;
                // Convert to slug: lowercase, spaces/special chars to dashes
                String slug = cleaned.toLowerCase()
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("-{2,}", "-")
                        .replaceAll("(^-+|-+$)", "");
                if (!slug.isBlank() && slug.length() <= 40 && !triggers.contains(slug)) {
                    triggers.add(slug);
                }
                if (triggers.size() >= 8) break;
            }
            if (triggers.size() >= 8) break;
        }
        return triggers;
    }

    /**
     * Infers the KCP {@code format} value from the file extension.
     * Returns {@code null} when the format cannot be meaningfully determined
     * (e.g. plain code files where the extension is self-evident from the path).
     */
    String toKcpFormat(String relativePath) {
        if (relativePath == null) return null;
        String lower = relativePath.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".markdown")) {
            return "markdown";
        }
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".txt")) return "text";
        if (lower.endsWith(".rst")) return "restructuredtext";
        if (lower.endsWith(".adoc") || lower.endsWith(".asciidoc")) return "asciidoc";
        // YAML/JSON: check for OpenAPI/AsyncAPI signal in the filename
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            if (lower.contains("openapi") || lower.contains("swagger") || lower.contains("asyncapi")) {
                return "openapi";
            }
            return "yaml";
        }
        if (lower.endsWith(".json")) {
            if (lower.contains("schema") || lower.contains("openapi") || lower.contains("swagger")) {
                return "json-schema";
            }
            return "json";
        }
        return null; // omit for code files — extension is self-evident in the path
    }

    /**
     * Infers the KCP {@code kind} value from the filename.
     * Returns {@code null} when the kind is the default ({@code knowledge}) so the
     * field is omitted and the manifest stays concise.
     *
     * <p>Override rules:
     * <ul>
     *   <li>{@code policy} — files named SECURITY, LICENSE, POLICY, TERMS, PRIVACY, NOTICE</li>
     *   <li>{@code schema} — files with openapi/swagger/asyncapi/schema in their name</li>
     * </ul>
     */
    String toKcpKind(String relativePath) {
        if (relativePath == null) return null;
        String name = relativePath.toLowerCase();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String filename = name.substring(slash + 1);
        // Strip extension for name-based matching
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        if (stem.equals("security") || stem.startsWith("license") || stem.startsWith("licence")
                || stem.equals("policy") || stem.startsWith("terms") || stem.startsWith("privacy")
                || stem.equals("notice") || stem.equals("contributing")) {
            return "policy";
        }
        if (filename.contains("openapi") || filename.contains("swagger")
                || filename.contains("asyncapi") || stem.endsWith("-schema")
                || stem.endsWith(".schema")) {
            return "schema";
        }
        return null; // omit — default is "knowledge"
    }

    /**
     * Basic onboarding guide fallback (no AI).
     */
    private String exportAsBasicOnboarding(SynthesisConfig config, List<SearchResult> results,
                                            Path workspaceRoot, String fileIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Onboarding Guide: ").append(config.getWorkspace().getName()).append("\n\n");
        sb.append("Welcome to the ").append(config.getWorkspace().getName()).append(" workspace.\n\n");

        // Getting started
        sb.append("## Getting Started\n\n");
        sb.append("1. Explore the project root: `").append(workspaceRoot).append("`\n");
        sb.append("2. Read key documentation files listed below\n");
        sb.append("3. Run `synthesis search <topic>` to find relevant files\n\n");

        // README files
        List<SearchResult> readmes = results.stream()
                .filter(r -> r.fileName().equalsIgnoreCase("README.md"))
                .toList();
        if (!readmes.isEmpty()) {
            sb.append("## Start Reading Here\n\n");
            for (SearchResult r : readmes) {
                sb.append("- `").append(r.relativePath()).append("`");
                if (!r.summary().isEmpty()) {
                    sb.append(" - ").append(truncate(r.summary(), 80));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Project layout
        sb.append("## Project Layout\n\n");
        sb.append("**Total:** ").append(results.size()).append(" files\n\n");

        Map<String, Long> byType = results.stream()
                .filter(r -> r.fileType() != null)
                .collect(Collectors.groupingBy(SearchResult::fileType, Collectors.counting()));
        byType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                        .append(e.getValue()).append(" files\n"));
        sb.append("\n");

        // Configuration files
        List<SearchResult> configs = results.stream()
                .filter(r -> "CONFIG".equals(r.fileType()) || "YAML".equals(r.fileType()) || "JSON".equals(r.fileType()))
                .limit(10)
                .toList();
        if (!configs.isEmpty()) {
            sb.append("## Configuration Files\n\n");
            for (SearchResult r : configs) {
                sb.append("- `").append(r.relativePath()).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("*Note: This is a basic onboarding guide. Enable AI (set ai.enabled=true " +
                "and the provider's API key) for a detailed narrative guide.*\n");

        return sb.toString();
    }

    /**
     * Static facade for MCP tool integration.
     * Delegates to the instance export methods for the given format.
     *
     * @param format        output format (markdown, json, kcp, architecture-doc, onboarding-guide)
     * @param config        workspace configuration
     * @param results       search results to export
     * @param workspaceRoot workspace root path
     * @param typeFilter    optional file type filter (may be null)
     * @return exported content as a string, or null if format is unknown
     */
    public static String exportContent(String format, SynthesisConfig config,
                                        List<SearchResult> results, Path workspaceRoot,
                                        String typeFilter) {
        ExportCommand cmd = new ExportCommand();
        cmd.typeFilter = typeFilter;
        return switch (format.toLowerCase()) {
            case "json" -> cmd.exportAsJson(config, results);
            case "markdown", "md" -> cmd.exportAsMarkdown(config, results, workspaceRoot);
            case "architecture-doc", "architecture" -> cmd.exportAsArchitectureDoc(config, results, workspaceRoot);
            case "onboarding-guide", "onboarding" -> cmd.exportAsOnboardingGuide(config, results, workspaceRoot);
            case "kcp", "knowledge-context-protocol" -> cmd.exportAsKcp(config, results, workspaceRoot);
            default -> null;
        };
    }
}
