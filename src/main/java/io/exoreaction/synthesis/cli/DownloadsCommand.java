package io.exoreaction.synthesis.cli;

import picocli.CommandLine.Command;

/**
 * User-friendly alias for {@link StagingCommand} — "downloads" is more intuitive
 * than "staging" for end users who think of incoming files as coming from Downloads.
 *
 * <p>Extends {@link StagingCommand} and inherits all its subcommands.
 * The command name is overridden to {@code downloads} via this class's {@code @Command}
 * annotation, so {@code synthesis downloads list|route|ingest|...} all work correctly.
 *
 * <p>By extending {@link StagingCommand}, the {@code @ParentCommand} field in each
 * inner subcommand (typed as {@code StagingCommand}) is satisfied at runtime, since
 * a {@code DownloadsCommand} instance is assignable to {@code StagingCommand}.
 */
@Command(
        name = "downloads",
        description = "Process incoming files from your Downloads folder (alias for 'staging')"
)
public class DownloadsCommand extends StagingCommand {

    @Override
    public Integer call() {
        System.out.println("  Use 'synthesis downloads <subcommand>' for Downloads processing.");
        System.out.println("  Available: list, route, hints, resolve, stats, ingest");
        System.out.println("  Tip: 'synthesis staging' is the same command with more options.");
        return 0;
    }
}
