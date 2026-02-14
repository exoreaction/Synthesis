package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Initializes a new Synthesis workspace.
 *
 * <p>Creates the .synthesis/ directory structure with default configuration,
 * Lucene index directory, and reports directory.
 *
 * <p>Usage: {@code synthesis init [directory] [--name NAME] [--type TYPE]}
 *
 * <p>The directory can be specified either as a positional argument or via
 * the parent command's {@code -d/--directory} option. The positional argument
 * takes precedence when explicitly provided.
 */
@Command(
        name = "init",
        description = "Initialize a new Synthesis workspace",
        mixinStandardHelpOptions = true
)
public class InitCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "Directory to initialize (default: uses -d option or current directory)",
            defaultValue = "",
            arity = "0..1"
    )
    private String directory;

    @Option(
            names = {"-n", "--name"},
            description = "Workspace name (default: directory name)"
    )
    private String name;

    @Option(
            names = {"-t", "--type"},
            description = "Workspace type: general, plugin-ecosystem, monorepo, multi-project",
            defaultValue = "general"
    )
    private String type;

    @Override
    public Integer call() {
        try {
            AnsiOutput.printHeader("Synthesis - Initialize Workspace");

            // Resolve directory: positional arg > parent -d option > current directory
            Path targetDir;
            if (directory != null && !directory.isEmpty()) {
                targetDir = Path.of(directory).toAbsolutePath().normalize();
            } else {
                targetDir = parent.getWorkspaceRoot();
            }
            WorkspaceManager workspace = new WorkspaceManager(targetDir);
            SynthesisConfig config = workspace.init(name, type);

            System.out.println();
            AnsiOutput.printInfo("Workspace: " + config.getWorkspace().getName());
            AnsiOutput.printInfo("Type:      " + config.getWorkspace().getType());
            System.out.println();
            System.out.println("  Next steps:");
            System.out.println("    1. Edit " + AnsiOutput.cyan(".synthesis/config.yaml") + " to customize scan patterns");
            System.out.println("    2. Run " + AnsiOutput.cyan("synthesis scan") + " to index your workspace");
            System.out.println("    3. Run " + AnsiOutput.cyan("synthesis search <query>") + " to find files");
            System.out.println();

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Failed to initialize workspace: " + e.getMessage());
            return 1;
        }
    }
}
