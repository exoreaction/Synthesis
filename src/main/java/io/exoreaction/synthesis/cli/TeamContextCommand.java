package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.agents.TeamContextBuilder;
import io.exoreaction.synthesis.agents.TeamContextBuilder.TeamBriefing;
import io.exoreaction.synthesis.agents.TeamReader;
import io.exoreaction.synthesis.agents.TeamReader.TeamContext;
import io.exoreaction.synthesis.agents.TeamReader.TeamNotFoundException;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI command for generating a codebase-aware briefing for the active Claude Code agent team.
 *
 * <p>Usage:
 * <pre>
 *   synthesis team-context                    # auto-detect team, verbose output
 *   synthesis team-context --team my-team     # explicit team name
 *   synthesis team-context --compact          # single paragraph for Agent prompt injection
 *   synthesis team-context --json             # machine-readable JSON
 *   synthesis team-context --list             # list all teams in ~/.claude/teams/
 * </pre>
 *
 * <p>For each task the briefing includes:
 * <ul>
 *   <li>Top-3 related files from the Synthesis workspace index</li>
 *   <li>Top-3 recommended skill names from ~/.claude/skills/</li>
 *   <li>Conflict warnings when two tasks share related files</li>
 * </ul>
 *
 * <p>The compact output is designed for injection into an agent's spawn prompt,
 * letting it skip the 40-60% blind retrieval phase at session start.
 */
@Command(
        name = "team-context",
        description = "Generate codebase-aware briefing for the active Claude Code agent team",
        mixinStandardHelpOptions = true
)
public class TeamContextCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--team"}, description = "Team name (default: auto-detect from ~/.claude/teams/)")
    private String teamName;

    @Option(names = {"--compact"}, description = "Single-paragraph output for Agent prompt injection",
            defaultValue = "false")
    private boolean compact;

    @Option(names = {"--json"}, description = "Machine-readable JSON output", defaultValue = "false")
    private boolean json;

    @Option(names = {"--list"}, description = "List all teams in ~/.claude/teams/ and exit",
            defaultValue = "false")
    private boolean list;

    @Option(names = {"--skills-dir"}, description = "Skills directory (default: ~/.claude/skills/)")
    private Path skillsDir;

    @Override
    public Integer call() {
        // --list mode: no workspace needed
        if (list) {
            return listTeams();
        }

        // Resolve workspace + index
        Path workspace = parent.getWorkspaceRoot();
        WorkspaceManager wm = new WorkspaceManager(workspace);
        Optional<String> validation = wm.validate();
        if (validation.isPresent()) {
            AnsiOutput.printError(validation.get());
            return 1;
        }

        // Load team context
        TeamContext context;
        try {
            if (teamName != null && !teamName.isBlank()) {
                context = TeamReader.read(teamName);
            } else {
                context = TeamReader.readAutoDetect();
            }
        } catch (TeamNotFoundException e) {
            AnsiOutput.printError(e.getMessage());
            AnsiOutput.printInfo("Use --list to see available teams, or create one with TeamCreate.");
            return 1;
        }

        // Build briefing with index
        TeamBriefing briefing;
        Path resolvedSkillsDir = skillsDir != null ? skillsDir
                : Path.of(System.getProperty("user.home"), ".claude", "skills");

        try (SearchIndex index = SearchIndex.openReadOnly(wm.getIndexPath())) {
            briefing = TeamContextBuilder.build(context, index, resolvedSkillsDir);
        } catch (Exception e) {
            // Index unavailable — build without file enrichment
            briefing = TeamContextBuilder.build(context, null, resolvedSkillsDir);
        }

        // Render
        if (compact) {
            System.out.println(briefing.toCompact());
        } else if (json) {
            System.out.println(toJson(briefing));
        } else {
            AnsiOutput.printHeader("Synthesis - Team Context");
            System.out.print(briefing.toVerbose());
        }

        return 0;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private int listTeams() {
        List<String> teams = TeamReader.listTeams();
        if (teams.isEmpty()) {
            System.out.println("No teams found in ~/.claude/teams/");
            System.out.println("Create a team with the TeamCreate tool in a multi-agent session.");
            return 0;
        }
        AnsiOutput.printHeader("Synthesis - Agent Teams");
        System.out.printf("Found %d team(s):%n%n", teams.size());
        teams.forEach(t -> System.out.println("  " + t));
        return 0;
    }

    private String toJson(TeamBriefing briefing) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"team\": \"").append(escape(briefing.context().teamName())).append("\",\n");
        sb.append("  \"description\": \"").append(escape(briefing.context().description())).append("\",\n");
        sb.append("  \"taskCount\": ").append(briefing.context().tasks().size()).append(",\n");
        sb.append("  \"tasks\": [\n");

        List<TeamContextBuilder.TaskBriefing> tbs = briefing.taskBriefings();
        for (int i = 0; i < tbs.size(); i++) {
            TeamContextBuilder.TaskBriefing tb = tbs.get(i);
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(escape(tb.task().id())).append("\",\n");
            sb.append("      \"subject\": \"").append(escape(tb.task().subject())).append("\",\n");
            sb.append("      \"status\": \"").append(escape(tb.task().status())).append("\",\n");
            sb.append("      \"owner\": \"").append(escape(tb.task().owner())).append("\",\n");
            sb.append("      \"relatedFiles\": ").append(jsonArray(tb.relatedFiles())).append(",\n");
            sb.append("      \"recommendedSkills\": ").append(jsonArray(tb.recommendedSkills())).append(",\n");
            sb.append("      \"conflictsWith\": ").append(jsonArray(tb.conflictsWith())).append("\n");
            sb.append("    }").append(i < tbs.size() - 1 ? "," : "").append("\n");
        }

        sb.append("  ],\n");
        sb.append("  \"conflicts\": ").append(jsonArray(briefing.globalConflicts())).append("\n");
        sb.append("}");
        return sb.toString();
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
