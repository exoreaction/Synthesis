package io.exoreaction.synthesis.agents;

import io.exoreaction.synthesis.agents.TeamReader.TeamContext;
import io.exoreaction.synthesis.agents.TeamReader.TeamNotFoundException;
import io.exoreaction.synthesis.agents.TeamReader.TeamTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamReaderTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path teamsRoot() { return tempDir.resolve("teams"); }
    private Path tasksRoot() { return tempDir.resolve("tasks"); }

    private void writeTeamConfig(String teamName, String configJson) throws IOException {
        Path teamDir = teamsRoot().resolve(teamName);
        Files.createDirectories(teamDir);
        Files.writeString(teamDir.resolve("config.json"), configJson);
    }

    private void writeTaskFile(String teamName, String taskFileName, String taskJson) throws IOException {
        Path taskDir = tasksRoot().resolve(teamName);
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve(taskFileName), taskJson);
    }

    // -----------------------------------------------------------------------
    // listTeams()
    // -----------------------------------------------------------------------

    @Test
    void testListTeams_multipleTeams() throws IOException {
        Files.createDirectories(teamsRoot().resolve("alpha-team"));
        Files.createDirectories(teamsRoot().resolve("beta-team"));
        Files.createDirectories(teamsRoot().resolve("gamma-team"));

        List<String> teams = TeamReader.listTeams(teamsRoot());

        assertEquals(3, teams.size());
        assertTrue(teams.contains("alpha-team"));
        assertTrue(teams.contains("beta-team"));
        assertTrue(teams.contains("gamma-team"));
    }

    @Test
    void testListTeams_missingDirectory() {
        List<String> teams = TeamReader.listTeams(tempDir.resolve("nonexistent"));
        assertTrue(teams.isEmpty(), "Missing directory should return empty list");
    }

    @Test
    void testListTeams_emptyDirectory() throws IOException {
        Files.createDirectories(teamsRoot());
        List<String> teams = TeamReader.listTeams(teamsRoot());
        assertTrue(teams.isEmpty(), "Empty directory should return empty list");
    }

    // -----------------------------------------------------------------------
    // read()
    // -----------------------------------------------------------------------

    @Test
    void testReadTeamConfig() throws Exception {
        writeTeamConfig("feature-build", """
                {
                  "description": "PCB format feature team",
                  "agents": [
                    {"name": "agent-1", "role": "parser"},
                    {"name": "agent-2", "role": "validator"}
                  ]
                }
                """);

        writeTaskFile("feature-build", "task-001.json", """
                {
                  "id": "TASK-001",
                  "subject": "Implement Gerber parser",
                  "description": "Parse RS-274X Gerber files",
                  "status": "pending",
                  "owner": "",
                  "blockedBy": []
                }
                """);

        writeTaskFile("feature-build", "task-002.json", """
                {
                  "id": "TASK-002",
                  "subject": "Add validation rules",
                  "description": "Validate PCB design rules",
                  "status": "in_progress",
                  "owner": "agent-2",
                  "blockedBy": ["TASK-001"]
                }
                """);

        TeamContext ctx = TeamReader.read("feature-build", teamsRoot(), tasksRoot());

        assertEquals("feature-build", ctx.teamName());
        assertEquals("PCB format feature team", ctx.description());
        assertEquals(2, ctx.agents().size());
        assertEquals("agent-1", ctx.agents().get(0).name());
        assertEquals("parser", ctx.agents().get(0).role());

        assertEquals(2, ctx.tasks().size());
        TeamTask t1 = ctx.tasks().get(0);
        assertEquals("TASK-001", t1.id());
        assertEquals("Implement Gerber parser", t1.subject());
        assertEquals("pending", t1.status());
        assertTrue(t1.blockedBy().isEmpty());

        TeamTask t2 = ctx.tasks().get(1);
        assertEquals("TASK-002", t2.id());
        assertEquals("in_progress", t2.status());
        assertEquals("agent-2", t2.owner());
        assertEquals(List.of("TASK-001"), t2.blockedBy());
    }

    @Test
    void testReadTeamConfig_noConfigFile() throws IOException {
        // Team dir exists but no config.json
        Files.createDirectories(teamsRoot().resolve("bare-team"));

        assertDoesNotThrow(() -> {
            TeamContext ctx = TeamReader.read("bare-team", teamsRoot(), tasksRoot());
            assertEquals("bare-team", ctx.teamName());
            assertTrue(ctx.description().isBlank());
            assertTrue(ctx.agents().isEmpty());
            assertTrue(ctx.tasks().isEmpty());
        });
    }

    @Test
    void testHandlesMissingTeamDir() {
        assertThrows(TeamNotFoundException.class, () ->
                TeamReader.read("nonexistent-team", teamsRoot(), tasksRoot()));
    }

    @Test
    void testReadAutoDetect_singleTeam() throws Exception {
        writeTeamConfig("only-team", """
                {"description": "The only team", "agents": []}
                """);

        TeamContext ctx = TeamReader.readAutoDetect(teamsRoot(), tasksRoot());
        assertEquals("only-team", ctx.teamName());
    }

    @Test
    void testReadAutoDetect_noTeams() throws IOException {
        Files.createDirectories(teamsRoot());
        assertThrows(TeamNotFoundException.class, () ->
                TeamReader.readAutoDetect(teamsRoot(), tasksRoot()));
    }

    @Test
    void testReadAutoDetect_multipleTeams() throws IOException {
        Files.createDirectories(teamsRoot().resolve("team-a"));
        Files.createDirectories(teamsRoot().resolve("team-b"));
        assertThrows(TeamNotFoundException.class, () ->
                TeamReader.readAutoDetect(teamsRoot(), tasksRoot()));
    }

    @Test
    void testTaskIdDerivedFromFilename() throws Exception {
        writeTeamConfig("test-team", """
                {"description": "", "agents": []}
                """);
        // Task JSON with no id field — should fall back to filename
        writeTaskFile("test-team", "my-task-123.json", """
                {
                  "subject": "Do something",
                  "description": "Details",
                  "status": "pending",
                  "owner": ""
                }
                """);

        TeamContext ctx = TeamReader.read("test-team", teamsRoot(), tasksRoot());
        assertEquals(1, ctx.tasks().size());
        assertEquals("my-task-123", ctx.tasks().get(0).id());
    }

    @Test
    void testMalformedTaskSkipped() throws Exception {
        writeTeamConfig("test-team", """
                {"description": "", "agents": []}
                """);
        // One valid task + one broken JSON
        writeTaskFile("test-team", "valid.json", """
                {"id": "T1", "subject": "Valid task", "status": "pending"}
                """);
        writeTaskFile("test-team", "broken.json", "{ not valid json [[[");

        TeamContext ctx = TeamReader.read("test-team", teamsRoot(), tasksRoot());
        // Only the valid task should appear
        assertEquals(1, ctx.tasks().size());
        assertEquals("T1", ctx.tasks().get(0).id());
    }
}
