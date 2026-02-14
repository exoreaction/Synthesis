package io.exoreaction.synthesis;

import io.exoreaction.synthesis.cli.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Synthesis: AI operations partner for knowledge infrastructure.
 *
 * <p>A CLI tool that scans, indexes, and searches workspace file systems,
 * providing rapid discovery of documents, code, and knowledge artifacts.
 *
 * <p>Usage:
 * <pre>
 *   synthesis init [directory]     Initialize a workspace
 *   synthesis scan                 Scan and index files
 *   synthesis search <query>       Search the index
 *   synthesis maintain             Detect changes and update index
 *   synthesis export               Export index as JSON or Markdown
 *   synthesis status               Show workspace health
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@Command(
        name = "synthesis",
        description = "AI operations partner for knowledge infrastructure",
        version = "Synthesis 1.0.0-SNAPSHOT",
        mixinStandardHelpOptions = true,
        subcommands = {
                InitCommand.class,
                ScanCommand.class,
                SearchCommand.class,
                MaintainCommand.class,
                ExportCommand.class,
                StatusCommand.class
        }
)
public class SynthesisApp implements Callable<Integer> {

    @Option(
            names = {"-d", "--directory"},
            description = "Workspace root directory (default: current directory)",
            defaultValue = ".",
            scope = CommandLine.ScopeType.INHERIT
    )
    private Path workspaceRoot;

    /**
     * Returns the resolved workspace root directory.
     * Used by subcommands via @ParentCommand injection.
     */
    public Path getWorkspaceRoot() {
        return workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public Integer call() {
        // No subcommand specified -- print usage
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SynthesisApp())
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    System.err.println("Error: " + ex.getMessage());
                    return 1;
                })
                .execute(args);
        System.exit(exitCode);
    }
}
