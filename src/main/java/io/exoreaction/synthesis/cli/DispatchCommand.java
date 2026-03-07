package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.agents.TeamContextBuilder;
import io.exoreaction.synthesis.agents.TeamReader;
import io.exoreaction.synthesis.agents.TeamReader.TeamNotFoundException;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.skills.SkillMatcher;
import io.exoreaction.synthesis.skills.SkillMatcher.SkillMatch;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI command that composes SkillMatcher + index search + team conflict check
 * into a single agent dispatch plan.
 *
 * <p>Usage:
 * <pre>
 *   synthesis dispatch "fix OAuth2 token refresh"        # full table output
 *   synthesis dispatch "query" --compact                 # single line for Agent prompt injection
 *   synthesis dispatch "query" --top-skills 5            # skill count (default 3)
 *   synthesis dispatch "query" --top-files 5             # file count (default 5)
 *   synthesis dispatch "query" --skills-dir /path        # override skills dir
 *   synthesis dispatch "query" --json                    # machine-readable JSON
 *   synthesis dispatch "query" --no-team                 # skip team conflict check
 * </pre>
 *
 * <p>Enables the Supervisor Router and Magentic One multi-agent patterns
 * by returning a complete agent configuration before the agent is spawned.
 * The compact output is designed for injection into an agent's spawn prompt,
 * letting it skip the 40-60% blind retrieval phase at session start.
 */
@Command(
        name = "dispatch",
        description = "Plan an agent dispatch: skills, related files, team conflicts, and token estimate",
        mixinStandardHelpOptions = true
)
public class DispatchCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(index = "0", description = "Task description for the agent to work on")
    private String query;

    @Option(names = {"--compact"}, description = "Single-line output for Agent prompt injection",
            defaultValue = "false")
    private boolean compact;

    @Option(names = {"--json"}, description = "Machine-readable JSON output", defaultValue = "false")
    private boolean json;

    @Option(names = {"--top-skills"}, description = "Number of skills to recommend (default: 3)",
            defaultValue = "3")
    private int topSkills;

    @Option(names = {"--top-files"}, description = "Number of related files to include (default: 5)",
            defaultValue = "5")
    private int topFiles;

    @Option(names = {"--skills-dir"}, description = "Skills directory (default: ~/.claude/skills/)")
    private Path skillsDir;

    @Option(names = {"--no-team"}, description = "Skip team conflict check", defaultValue = "false")
    private boolean noTeam;

    @Override
    public Integer call() {
        Path workspace = parent.getWorkspaceRoot();
        WorkspaceManager wm = new WorkspaceManager(workspace);
        Optional<String> validation = wm.validate();
        if (validation.isPresent()) {
            AnsiOutput.printError(validation.get());
            return 1;
        }

        Path resolvedSkillsDir = skillsDir != null ? skillsDir.toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home"), ".claude", "skills");

        // Step 1: Skill matching
        List<SkillMatch> skills = SkillMatcher.match(resolvedSkillsDir, query, topSkills);

        // Step 2: Related files from index
        List<SearchResult> files = searchFiles(wm, query, topFiles);

        // Step 3: Team conflict check (graceful if no team)
        List<String> conflicts = noTeam ? List.of() : checkTeamConflicts();

        // Step 4: Estimated tokens (sum of file sizes / 4)
        long estimatedTokens = files.stream().mapToLong(SearchResult::sizeBytes).sum() / 4;

        // Render
        if (compact) {
            System.out.println(toCompact(skills, files, conflicts, estimatedTokens));
        } else if (json) {
            System.out.println(toJson(query, skills, files, conflicts, estimatedTokens, workspace.toString()));
        } else {
            renderVerbose(query, skills, files, conflicts, estimatedTokens);
        }

        return 0;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<SearchResult> searchFiles(WorkspaceManager wm, String q, int topN) {
        try (SearchIndex index = SearchIndex.openReadOnly(wm.getIndexPath())) {
            return index.search(q, null, topN);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> checkTeamConflicts() {
        try {
            TeamReader.TeamContext context = TeamReader.readAutoDetect();
            TeamContextBuilder.TeamBriefing briefing = TeamContextBuilder.build(context, null, null);
            return briefing.globalConflicts();
        } catch (TeamNotFoundException e) {
            // No team found — skip gracefully
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toCompact(List<SkillMatch> skills, List<SearchResult> files,
                              List<String> conflicts, long estimatedTokens) {
        String skillNames = skills.isEmpty() ? "none"
                : String.join(",", skills.stream().map(SkillMatch::skillName).toList());
        String fileNames = files.isEmpty() ? "none"
                : String.join(",", files.stream().map(SearchResult::fileName).toList());
        String conflictStr = conflicts.isEmpty() ? "none" : String.join("; ", conflicts);
        return "skills:" + skillNames + " | files:" + fileNames
                + " | conflicts:" + conflictStr + " | ~" + estimatedTokens + " tokens";
    }

    private String toJson(String q, List<SkillMatch> skills, List<SearchResult> files,
                           List<String> conflicts, long estimatedTokens, String workspace) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"query\": \"").append(escape(q)).append("\",\n");

        sb.append("  \"skills\": [\n");
        for (int i = 0; i < skills.size(); i++) {
            SkillMatch m = skills.get(i);
            sb.append("    {");
            sb.append("\"name\": \"").append(escape(m.skillName())).append("\", ");
            sb.append("\"score\": ").append(m.score()).append(", ");
            sb.append("\"preview\": \"").append(escape(m.firstLine())).append("\"");
            sb.append("}").append(i < skills.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"relatedFiles\": [\n");
        for (int i = 0; i < files.size(); i++) {
            SearchResult r = files.get(i);
            String path = r.relativePath() != null ? r.relativePath() : r.path().toString();
            sb.append("    {");
            sb.append("\"path\": \"").append(escape(path)).append("\", ");
            sb.append("\"score\": ").append(String.format("%.1f", (double) r.score())).append(", ");
            sb.append("\"type\": \"").append(escape(r.fileType() != null ? r.fileType() : "")).append("\", ");
            sb.append("\"sizeBytes\": ").append(r.sizeBytes());
            sb.append("}").append(i < files.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"conflicts\": ").append(jsonArray(conflicts)).append(",\n");
        sb.append("  \"estimatedTokens\": ").append(estimatedTokens).append(",\n");
        sb.append("  \"workspace\": \"").append(escape(workspace)).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    private void renderVerbose(String q, List<SkillMatch> skills, List<SearchResult> files,
                                List<String> conflicts, long estimatedTokens) {
        AnsiOutput.printHeader("Synthesis - Dispatch Plan");
        System.out.printf("Query: %s%n%n", AnsiOutput.highlight(q));

        System.out.printf("Skills (top %d):%n", topSkills);
        if (skills.isEmpty()) {
            System.out.println("  (none found)");
        } else {
            for (SkillMatch m : skills) {
                String desc = m.firstLine();
                if (desc.length() > 50) desc = desc.substring(0, 49) + "\u2026";
                System.out.printf("  %-25s score:%-6.1f %s%n", m.skillName(), m.score(), desc);
            }
        }

        System.out.printf("%nRelated Files (top %d):%n", topFiles);
        if (files.isEmpty()) {
            System.out.println("  (none found)");
        } else {
            for (SearchResult r : files) {
                String path = r.relativePath() != null ? r.relativePath() : r.path().toString();
                String type = r.fileType() != null ? r.fileType() : "";
                System.out.printf("  %-50s %-6s %.1f  %s%n",
                        path, type, (double) r.score(), formatSize(r.sizeBytes()));
            }
        }

        System.out.printf("%nTeam Conflicts: %s%n",
                conflicts.isEmpty() ? "none" : String.join(", ", conflicts));
        System.out.printf("Estimated tokens: ~%,d%n", estimatedTokens);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }

    private static String jsonArray(List<String> items) {
        if (items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\"").append(escape(items.get(i))).append("\"");
            if (i < items.size() - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
