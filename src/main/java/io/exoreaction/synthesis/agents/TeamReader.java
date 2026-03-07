package io.exoreaction.synthesis.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads Claude Code multi-agent team coordination files.
 *
 * <p>Reads from:
 * <ul>
 *   <li>{@code ~/.claude/teams/{name}/config.json} — team metadata and agent list</li>
 *   <li>{@code ~/.claude/tasks/{name}/*.json} — individual task files</li>
 * </ul>
 *
 * <p>These files are created by the Claude Code multi-agent coordination system
 * ({@code TeamCreate} / {@code TaskCreate} tools). All methods are read-only and
 * handle missing directories gracefully.
 */
public class TeamReader {

    /** Metadata about a single team member agent. */
    public record AgentInfo(String name, String role) {}

    /** A single task in the team's task list. */
    public record TeamTask(
            String id,
            String subject,
            String description,
            String status,
            String owner,
            List<String> blockedBy
    ) {}

    /** Full team context: config + all tasks. */
    public record TeamContext(
            String teamName,
            String description,
            List<AgentInfo> agents,
            List<TeamTask> tasks,
            Path teamsRoot,
            Path tasksRoot
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Lists all team names found in {@code ~/.claude/teams/}.
     *
     * @return team names (directory names), empty list if directory absent
     */
    public static List<String> listTeams() {
        return listTeams(defaultTeamsRoot());
    }

    /**
     * Lists all team names found in the given teams root.
     *
     * @param teamsRoot path to the teams root directory
     * @return team names (directory names), empty list if directory absent
     */
    public static List<String> listTeams(Path teamsRoot) {
        if (!Files.isDirectory(teamsRoot)) return List.of();
        try (Stream<Path> stream = Files.list(teamsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Reads config and tasks for the named team from default locations.
     *
     * @param teamName the team name (directory under {@code ~/.claude/teams/})
     * @return populated TeamContext (tasks may be empty if none found)
     * @throws TeamNotFoundException if the team directory does not exist
     */
    public static TeamContext read(String teamName) throws TeamNotFoundException {
        return read(teamName, defaultTeamsRoot(), defaultTasksRoot());
    }

    /**
     * Reads config and tasks for the named team from the given roots.
     *
     * @param teamName  the team name
     * @param teamsRoot root directory containing team config directories
     * @param tasksRoot root directory containing task directories
     * @return populated TeamContext
     * @throws TeamNotFoundException if the team directory does not exist
     */
    public static TeamContext read(String teamName, Path teamsRoot, Path tasksRoot)
            throws TeamNotFoundException {
        Path teamDir = teamsRoot.resolve(teamName);
        if (!Files.isDirectory(teamDir)) {
            throw new TeamNotFoundException("Team not found: " + teamName + " (looked in " + teamsRoot + ")");
        }

        // Read config.json
        String teamDescription = "";
        List<AgentInfo> agents = new ArrayList<>();
        Path configFile = teamDir.resolve("config.json");
        if (Files.exists(configFile)) {
            try {
                JsonNode config = MAPPER.readTree(configFile.toFile());
                if (config.has("description")) {
                    teamDescription = config.get("description").asText("");
                }
                if (config.has("agents") && config.get("agents").isArray()) {
                    for (JsonNode agent : config.get("agents")) {
                        String name = agent.has("name") ? agent.get("name").asText("") : "";
                        String role = agent.has("role") ? agent.get("role").asText("") : "";
                        if (!name.isBlank()) agents.add(new AgentInfo(name, role));
                    }
                }
            } catch (IOException e) {
                // Best-effort: keep defaults
            }
        }

        // Read tasks/{teamName}/*.json
        List<TeamTask> tasks = readTasks(teamName, tasksRoot);

        return new TeamContext(teamName, teamDescription, agents, tasks, teamsRoot, tasksRoot);
    }

    /**
     * Auto-detects and reads the only team that exists.
     *
     * @return TeamContext for the single available team
     * @throws TeamNotFoundException if zero or more than one team exists
     */
    public static TeamContext readAutoDetect() throws TeamNotFoundException {
        return readAutoDetect(defaultTeamsRoot(), defaultTasksRoot());
    }

    /**
     * Auto-detects and reads the only team that exists in the given roots.
     */
    public static TeamContext readAutoDetect(Path teamsRoot, Path tasksRoot)
            throws TeamNotFoundException {
        List<String> teams = listTeams(teamsRoot);
        if (teams.isEmpty()) {
            throw new TeamNotFoundException("No teams found in " + teamsRoot);
        }
        if (teams.size() > 1) {
            throw new TeamNotFoundException(
                    "Multiple teams found: " + String.join(", ", teams) +
                    ". Specify --team <name>.");
        }
        return read(teams.get(0), teamsRoot, tasksRoot);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static List<TeamTask> readTasks(String teamName, Path tasksRoot) {
        Path taskDir = tasksRoot.resolve(teamName);
        if (!Files.isDirectory(taskDir)) return List.of();

        List<TeamTask> tasks = new ArrayList<>();
        try (Stream<Path> stream = Files.list(taskDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(file -> {
                        TeamTask t = parseTask(file);
                        if (t != null) tasks.add(t);
                    });
        } catch (IOException e) {
            // Best-effort
        }
        return tasks;
    }

    private static TeamTask parseTask(Path file) {
        try {
            JsonNode node = MAPPER.readTree(file.toFile());
            String id = node.has("id") ? node.get("id").asText("") : "";
            String subject = node.has("subject") ? node.get("subject").asText("") : "";
            String description = node.has("description") ? node.get("description").asText("") : "";
            String status = node.has("status") ? node.get("status").asText("pending") : "pending";
            String owner = node.has("owner") ? node.get("owner").asText("") : "";

            List<String> blockedBy = new ArrayList<>();
            if (node.has("blockedBy") && node.get("blockedBy").isArray()) {
                node.get("blockedBy").forEach(b -> blockedBy.add(b.asText()));
            }

            // Derive id from filename if not in JSON
            if (id.isBlank()) {
                id = file.getFileName().toString().replaceAll("\\.json$", "");
            }

            return new TeamTask(id, subject, description, status, owner, blockedBy);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path defaultTeamsRoot() {
        return Path.of(System.getProperty("user.home"), ".claude", "teams");
    }

    private static Path defaultTasksRoot() {
        return Path.of(System.getProperty("user.home"), ".claude", "tasks");
    }

    // -----------------------------------------------------------------------
    // Exceptions
    // -----------------------------------------------------------------------

    /** Thrown when a requested team directory does not exist. */
    public static class TeamNotFoundException extends Exception {
        public TeamNotFoundException(String message) {
            super(message);
        }
    }
}
