package io.exoreaction.synthesis.agents;

import io.exoreaction.synthesis.agents.TeamReader.TeamContext;
import io.exoreaction.synthesis.agents.TeamReader.TeamTask;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.skills.SkillMatcher;
import io.exoreaction.synthesis.skills.SkillMatcher.SkillMatch;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a codebase-aware briefing for a Claude Code agent team.
 *
 * <p>For each task, enriches it with:
 * <ul>
 *   <li>Top-3 related files from the Synthesis index (by impact score)</li>
 *   <li>Top-3 recommended skills from the skill directory</li>
 *   <li>Conflict warnings: tasks that share related files</li>
 * </ul>
 *
 * <p>The resulting {@link TeamBriefing} can be rendered as verbose text (for
 * terminal use) or as a compact single-paragraph string for injection into an
 * agent's prompt at spawn time.
 */
public class TeamContextBuilder {

    /** Per-task enrichment: related files, skills, conflicts. */
    public record TaskBriefing(
            TeamTask task,
            List<String> relatedFiles,
            List<String> recommendedSkills,
            List<String> conflictsWith
    ) {}

    /** Full briefing for the team. */
    public record TeamBriefing(
            TeamContext context,
            List<TaskBriefing> taskBriefings,
            List<String> globalConflicts
    ) {
        /** Renders a compact single-paragraph summary for agent prompt injection. */
        public String toCompact() {
            StringBuilder sb = new StringBuilder();
            sb.append("Team: ").append(context.teamName());
            if (!context.description().isBlank()) {
                sb.append(" — ").append(context.description());
            }
            sb.append(". ");

            long pending = context.tasks().stream()
                    .filter(t -> "pending".equalsIgnoreCase(t.status())).count();
            long inProgress = context.tasks().stream()
                    .filter(t -> "in_progress".equalsIgnoreCase(t.status())).count();
            sb.append("Tasks: ").append(context.tasks().size())
              .append(" (").append(pending).append(" pending, ")
              .append(inProgress).append(" in-progress). ");

            if (!taskBriefings.isEmpty()) {
                TaskBriefing first = taskBriefings.get(0);
                sb.append("Next: [").append(first.task().id()).append("] ")
                  .append(first.task().subject()).append(". ");
                if (!first.relatedFiles().isEmpty()) {
                    sb.append("Related files: ")
                      .append(String.join(", ", first.relatedFiles().subList(
                              0, Math.min(2, first.relatedFiles().size()))))
                      .append(". ");
                }
                if (!first.recommendedSkills().isEmpty()) {
                    sb.append("Skills: ")
                      .append(String.join(", ", first.recommendedSkills()))
                      .append(".");
                }
            }

            if (!globalConflicts.isEmpty()) {
                sb.append(" ⚠ Conflicts: ").append(String.join("; ", globalConflicts)).append(".");
            }

            return sb.toString();
        }

        /** Renders verbose multi-line text for terminal display. */
        public String toVerbose() {
            StringBuilder sb = new StringBuilder();
            sb.append("Team: ").append(context.teamName());
            if (!context.description().isBlank()) {
                sb.append(" — ").append(context.description());
            }
            sb.append(System.lineSeparator());

            if (!context.agents().isEmpty()) {
                sb.append("Agents: ").append(context.agents().size())
                  .append(" (").append(context.agents().stream()
                          .map(TeamReader.AgentInfo::name).collect(Collectors.joining(", ")))
                  .append(")").append(System.lineSeparator());
            }

            long pending = context.tasks().stream()
                    .filter(t -> "pending".equalsIgnoreCase(t.status())).count();
            long inProgress = context.tasks().stream()
                    .filter(t -> "in_progress".equalsIgnoreCase(t.status())).count();
            long completed = context.tasks().stream()
                    .filter(t -> "completed".equalsIgnoreCase(t.status())).count();
            sb.append("Tasks: ").append(context.tasks().size())
              .append(" (").append(pending).append(" pending, ")
              .append(inProgress).append(" in-progress, ")
              .append(completed).append(" completed)")
              .append(System.lineSeparator());

            if (!taskBriefings.isEmpty()) {
                sb.append(System.lineSeparator());
                for (TaskBriefing tb : taskBriefings) {
                    TeamTask t = tb.task();
                    sb.append("[").append(t.id()).append("] ")
                      .append(t.subject()).append(" — ")
                      .append(t.status().toUpperCase());
                    if (!t.owner().isBlank()) {
                        sb.append(" (").append(t.owner()).append(")");
                    }
                    sb.append(System.lineSeparator());

                    if (!tb.relatedFiles().isEmpty()) {
                        sb.append("  Related files: ")
                          .append(String.join(", ", tb.relatedFiles()))
                          .append(System.lineSeparator());
                    }
                    if (!tb.recommendedSkills().isEmpty()) {
                        sb.append("  Skills: ")
                          .append(String.join(", ", tb.recommendedSkills()))
                          .append(System.lineSeparator());
                    }
                    if (!t.blockedBy().isEmpty()) {
                        sb.append("  Blocked by: ")
                          .append(String.join(", ", t.blockedBy()))
                          .append(System.lineSeparator());
                    }
                    if (!tb.conflictsWith().isEmpty()) {
                        sb.append("  ⚠ Conflict with: ")
                          .append(String.join(", ", tb.conflictsWith()))
                          .append(" (shared files)")
                          .append(System.lineSeparator());
                    }
                    sb.append(System.lineSeparator());
                }
            } else if (context.tasks().isEmpty()) {
                sb.append(System.lineSeparator()).append("  (no tasks)").append(System.lineSeparator());
            }

            if (!globalConflicts.isEmpty()) {
                sb.append("⚠ File conflicts: ").append(System.lineSeparator());
                globalConflicts.forEach(c -> sb.append("  ").append(c).append(System.lineSeparator()));
            }

            return sb.toString();
        }
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /**
     * Builds a briefing for the team by enriching each task with index lookups
     * and skill recommendations.
     *
     * @param context   team context from {@link TeamReader}
     * @param index     open read-only Synthesis index (may be null — gracefully skipped)
     * @param skillsDir skills directory for {@link SkillMatcher} (may be null)
     * @return enriched briefing
     */
    public static TeamBriefing build(TeamContext context, SearchIndex index, Path skillsDir) {
        // Build per-task file sets for conflict detection
        Map<String, Set<String>> taskFiles = new LinkedHashMap<>();

        List<TaskBriefing> briefings = new ArrayList<>();
        for (TeamTask task : context.tasks()) {
            List<String> relatedFiles = findRelatedFiles(task, index);
            List<String> skills = recommendSkills(task, skillsDir);
            taskFiles.put(task.id(), new HashSet<>(relatedFiles));
            // conflicts populated below
            briefings.add(new TaskBriefing(task, relatedFiles, skills, new ArrayList<>()));
        }

        // Detect conflicts: tasks sharing ≥1 file
        List<String> globalConflicts = new ArrayList<>();
        List<String> taskIds = new ArrayList<>(taskFiles.keySet());
        for (int i = 0; i < taskIds.size(); i++) {
            for (int j = i + 1; j < taskIds.size(); j++) {
                String idA = taskIds.get(i);
                String idB = taskIds.get(j);
                Set<String> filesA = taskFiles.get(idA);
                Set<String> filesB = taskFiles.get(idB);
                Set<String> shared = new HashSet<>(filesA);
                shared.retainAll(filesB);
                if (!shared.isEmpty()) {
                    String conflict = "[" + idA + "] ↔ [" + idB + "]: " +
                            shared.stream().map(f -> {
                                // Show just the filename for brevity
                                int slash = f.lastIndexOf('/');
                                return slash >= 0 ? f.substring(slash + 1) : f;
                            }).collect(Collectors.joining(", "));
                    globalConflicts.add(conflict);

                    // Add back-references to both task briefings
                    addConflict(briefings, idA, idB);
                    addConflict(briefings, idB, idA);
                }
            }
        }

        return new TeamBriefing(context, briefings, globalConflicts);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static List<String> findRelatedFiles(TeamTask task, SearchIndex index) {
        if (index == null) return List.of();
        String query = task.subject() + " " + task.description();
        if (query.isBlank()) return List.of();
        try {
            List<SearchResult> results = index.search(query, null, 3);
            return results.stream()
                    .map(SearchResult::relativePath)
                    .filter(Objects::nonNull)
                    .distinct()
                    .limit(3)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> recommendSkills(TeamTask task, Path skillsDir) {
        if (skillsDir == null) return List.of();
        String query = task.subject() + " " + task.description();
        if (query.isBlank()) return List.of();
        return SkillMatcher.match(skillsDir, query, 3).stream()
                .map(SkillMatch::skillName)
                .collect(Collectors.toList());
    }

    private static void addConflict(List<TaskBriefing> briefings, String forId, String withId) {
        for (int i = 0; i < briefings.size(); i++) {
            if (briefings.get(i).task().id().equals(forId)) {
                List<String> existing = new ArrayList<>(briefings.get(i).conflictsWith());
                existing.add(withId);
                TaskBriefing old = briefings.get(i);
                briefings.set(i, new TaskBriefing(old.task(), old.relatedFiles(),
                        old.recommendedSkills(), existing));
                break;
            }
        }
    }
}
