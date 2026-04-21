package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.cli.NotionAuthCommand;
import io.exoreaction.synthesis.cli.NotionCommand;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Notion OAuth command hierarchy ({@link NotionCommand} + {@link NotionAuthCommand}).
 *
 * <p>Tests focus on Picocli wiring and URL builder logic. Full integration
 * (browser + callback server) is not tested here.
 */
class NotionAuthCommandTest {

    // -----------------------------------------------------------------------
    // 1. NotionCommand is registered as a subcommand of SynthesisApp
    // -----------------------------------------------------------------------

    @Test
    void notionCommand_isRegisteredInSynthesisApp() {
        var cmd = new CommandLine(new SynthesisApp());
        assertTrue(cmd.getSubcommands().containsKey("notion"),
                "SynthesisApp should have a 'notion' subcommand");
    }

    // -----------------------------------------------------------------------
    // 2. NotionAuthCommand is registered as a subcommand of NotionCommand
    // -----------------------------------------------------------------------

    @Test
    void authCommand_isRegisteredInNotionCommand() {
        var cmd = new CommandLine(new NotionCommand());
        assertTrue(cmd.getSubcommands().containsKey("auth"),
                "NotionCommand should have an 'auth' subcommand");
    }

    // -----------------------------------------------------------------------
    // 3. Auth URL builder includes required parameters
    // -----------------------------------------------------------------------

    @Test
    void buildAuthUrl_includesRequiredParameters() {
        String state = "test-state-123";
        String url = NotionAuthCommand.buildAuthUrl(state);

        assertTrue(url.startsWith("https://api.notion.com/v1/oauth/authorize?"),
                "URL should start with Notion OAuth endpoint");
        assertTrue(url.contains("client_id=" + NotionOAuthClient.CLIENT_ID),
                "URL should contain client_id");
        assertTrue(url.contains("response_type=code"),
                "URL should contain response_type=code");
        assertTrue(url.contains("owner=user"),
                "URL should contain owner=user");
        assertTrue(url.contains("redirect_uri="),
                "URL should contain redirect_uri");
        assertTrue(url.contains("state=" + state),
                "URL should contain the state parameter");
    }

    // -----------------------------------------------------------------------
    // 4. Auth URL builder uses different state each time
    // -----------------------------------------------------------------------

    @Test
    void buildAuthUrl_differentStateProducesDifferentUrl() {
        String url1 = NotionAuthCommand.buildAuthUrl("state-aaa");
        String url2 = NotionAuthCommand.buildAuthUrl("state-bbb");

        assertNotEquals(url1, url2, "Different states should produce different URLs");
        assertTrue(url1.contains("state=state-aaa"));
        assertTrue(url2.contains("state=state-bbb"));
    }

    // -----------------------------------------------------------------------
    // 5. NotionCommand name is "notion"
    // -----------------------------------------------------------------------

    @Test
    void notionCommand_hasCorrectName() {
        var cmd = new CommandLine(new NotionCommand());
        assertEquals("notion", cmd.getCommandName());
    }

    // -----------------------------------------------------------------------
    // 6. NotionAuthCommand name is "auth"
    // -----------------------------------------------------------------------

    @Test
    void authCommand_hasCorrectName() {
        var cmd = new CommandLine(new NotionAuthCommand());
        assertEquals("auth", cmd.getCommandName());
    }
}
