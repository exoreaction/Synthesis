package io.exoreaction.synthesis.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Parent command for Notion-related subcommands.
 *
 * <p>Usage: {@code synthesis notion auth}
 */
@Command(
        name = "notion",
        description = "Notion workspace integration",
        mixinStandardHelpOptions = true,
        subcommands = {
                NotionAuthCommand.class
        }
)
public class NotionCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // No subcommand specified — print usage
        CommandLine.usage(this, System.out);
        return 0;
    }
}
