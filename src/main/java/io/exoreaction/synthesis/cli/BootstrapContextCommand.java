package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.changelog.ChangeEvent;
import io.exoreaction.synthesis.changelog.SnapshotManager;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.skills.SkillMatcher;
import io.exoreaction.synthesis.skills.SkillMatcher.SkillMatch;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Returns a harness-neutral startup context packet for the current workspace.
 *
 * <p>Composes workspace freshness, session summary, skill recommendations, and key document
 * hints into one startup packet suitable for injection into any LLM harness session prompt.
 * Read-only: never writes hooks, skills, or instruction files.
 *
 * <p>Usage:
 * <pre>
 *   synthesis bootstrap-context                        # Compact text (default)
 *   synthesis bootstrap-context --task "add OAuth"    # Include skill recommendations
 *   synthesis bootstrap-context --json                # Full JSON output
 * </pre>
 */
@Command(
        name = "bootstrap-context",
        description = "Return a harness-neutral startup context packet for the current workspace",
        mixinStandardHelpOptions = true
)
public class BootstrapContextCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--task"}, description = "Optional task description to tailor skill recommendations")
    private String task;

    @Option(names = {"--skills-dir"}, description = "Skills directory (default: ~/.claude/skills/)")
    private String skillsDir;

    @Option(names = {"--json"}, description = "Output full JSON response", defaultValue = "false")
    private boolean json;

    @Option(names = {"--top-skills"}, description = "Number of skills to recommend (default: 5)", defaultValue = "5")
    private int topSkills;

    @Option(names = {"--top-kcp-units"}, description = "Number of key docs to include (default: 5)", defaultValue = "5")
    private int topKcpUnits;

    void setParent(SynthesisApp parent) { this.parent = parent; }
    void setTask(String task) { this.task = task; }
    void setSkillsDir(String skillsDir) { this.skillsDir = skillsDir; }
    void setJson(boolean json) { this.json = json; }

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            List<String> warnings = new ArrayList<>();

            // 1. Validate workspace (tolerant: warn, continue)
            boolean valid = workspace.validate().isEmpty();
            if (!valid) {
                warnings.add("Workspace not initialized: run 'synthesis init' and 'synthesis scan'");
            }

            // 2. Freshness from index + snapshot data
            int fileCount = 0;
            long indexSize = 0;
            int changeCount = 0;
            if (valid) {
                Path indexPath = workspace.getIndexPath();
                if (Files.exists(indexPath)) {
                    try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                        fileCount = index.documentCount();
                    }
                    try (Stream<Path> stream = Files.walk(indexPath)) {
                        indexSize = stream.filter(Files::isRegularFile)
                                .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } })
                                .sum();
                    }
                }
                try {
                    SynthesisDatabase db = SynthesisDatabase.getDefault();
                    SnapshotManager snapshots = new SnapshotManager(db);
                    Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
                    List<ChangeEvent> changes = snapshots.getChangesForWorkspace(workspaceRoot.toString(), since);
                    changeCount = changes.size();
                } catch (Exception ignored) {}
            }

            String sessionSummary = valid
                    ? "workspace:" + fileCount + "files"
                        + (indexSize > 0 ? "·" + FileUtils.formatSize(indexSize).replace(" ", "") : "")
                        + " | changed:" + changeCount + "files(24h)"
                    : "";

            // 3. Skill matching (only when task is provided)
            Path skills = skillsDir != null
                    ? Path.of(skillsDir).toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.home"), ".claude", "skills");
            List<SkillMatch> skillMatches = List.of();
            if (task != null && !task.isBlank()) {
                try {
                    skillMatches = SkillMatcher.match(skills, task, topSkills);
                } catch (Exception ignored) {}
            }

            // 4. Key docs from workspace root
            String[] candidates = {"knowledge.yaml", "README.md", "AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md"};
            List<String[]> keyDocs = new ArrayList<>();
            for (String candidate : candidates) {
                if (keyDocs.size() >= topKcpUnits) break;
                if (Files.exists(workspaceRoot.resolve(candidate))) {
                    keyDocs.add(new String[]{candidate, docIntent(candidate)});
                }
            }

            if (json) {
                printJson(workspaceRoot, sessionSummary, valid, warnings, skillMatches, keyDocs);
            } else {
                printCompact(workspaceRoot, sessionSummary, warnings, skillMatches, keyDocs);
            }
            return 0;

        } catch (Exception e) {
            System.err.println("bootstrap-context failed: " + e.getMessage());
            return 1;
        }
    }

    private void printCompact(Path workspaceRoot, String sessionSummary, List<String> warnings,
                               List<SkillMatch> skillMatches, List<String[]> keyDocs) {
        List<String> parts = new ArrayList<>();
        parts.add("workspace:" + workspaceRoot.getFileName());
        if (!sessionSummary.isBlank()) parts.add(sessionSummary);
        if (!skillMatches.isEmpty()) {
            String names = skillMatches.stream().limit(3)
                    .map(SkillMatch::skillName).collect(Collectors.joining(", "));
            parts.add("skills:" + names);
        }
        if (!keyDocs.isEmpty()) {
            String docs = keyDocs.stream().limit(2).map(d -> d[0]).collect(Collectors.joining(", "));
            parts.add("docs:" + docs);
        }
        System.out.println(String.join(" | ", parts));
        warnings.forEach(w -> System.err.println("WARN: " + w));
    }

    private void printJson(Path workspaceRoot, String sessionSummary, boolean valid,
                            List<String> warnings, List<SkillMatch> skillMatches, List<String[]> keyDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"workspace\": \"").append(workspaceRoot).append("\",\n");
        sb.append("  \"freshness\": {\n");
        sb.append("    \"indexed\": ").append(valid).append(",\n");
        sb.append("    \"stale\": ").append(!valid).append(",\n");
        sb.append("    \"summary\": \"").append(escape(sessionSummary)).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"session_summary\": \"").append(escape(sessionSummary)).append("\",\n");
        sb.append("  \"warnings\": [");
        sb.append(warnings.stream().map(w -> "\"" + escape(w) + "\"").collect(Collectors.joining(", ")));
        sb.append("],\n");
        sb.append("  \"recommended_skills\": [");
        sb.append(skillMatches.stream().map(m ->
                "{\"name\":\"" + escape(m.skillName()) + "\","
                + "\"score\":" + m.score() + ","
                + "\"preview\":\"" + escape(m.firstLine()) + "\"}"
        ).collect(Collectors.joining(", ")));
        sb.append("],\n");
        sb.append("  \"kcp_units\": [");
        sb.append(keyDocs.stream().map(d ->
                "{\"path\":\"" + escape(d[0]) + "\","
                + "\"intent\":\"" + escape(d[1]) + "\"}"
        ).collect(Collectors.joining(", ")));
        sb.append("],\n");
        sb.append("  \"suggested_tools\": [\"search\", \"relate\"");
        if (System.getenv("ANTHROPIC_API_KEY") != null) sb.append(", \"ask\"");
        sb.append("]\n");
        sb.append("}");
        System.out.println(sb);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String docIntent(String filename) {
        return switch (filename) {
            case "knowledge.yaml" -> "KCP manifest: structured reading order and unit descriptions";
            case "README.md" -> "Project overview and entry point";
            case "AGENTS.md" -> "Instructions for AI agents working in this repo";
            case "CLAUDE.md" -> "Claude Code session context and contribution policy";
            case "CONTRIBUTING.md" -> "How to contribute to this project";
            default -> "Key project document";
        };
    }
}
