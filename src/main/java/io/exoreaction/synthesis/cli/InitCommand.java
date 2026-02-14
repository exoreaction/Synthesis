package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.RepositoryManager;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Initializes a new Synthesis workspace with optional multi-repository support.
 *
 * <p>Usage:
 * <pre>
 *   synthesis init [directory] [--name NAME] [--type TYPE]
 *   synthesis init --repos ~/project-a,~/project-b --name "My Workspace"
 *   synthesis init --add ~/project-d   # Add repo to existing workspace
 * </pre>
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

    @Option(
            names = {"--repos"},
            description = "Comma-separated list of repository paths to index",
            split = ","
    )
    private List<String> repos;

    @Option(
            names = {"--add"},
            description = "Add a repository to an existing multi-repo workspace"
    )
    private String addRepo;

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

            // Handle --add (add repo to existing workspace)
            if (addRepo != null) {
                return handleAddRepo(workspace, targetDir);
            }

            // Standard init
            SynthesisConfig config = workspace.init(name, type);

            // Handle --repos (multi-repo init)
            if (repos != null && !repos.isEmpty()) {
                RepositoryManager repoManager = new RepositoryManager(targetDir);
                for (String repoPath : repos) {
                    Path resolved = Path.of(repoPath.trim()).toAbsolutePath().normalize();
                    boolean added = repoManager.addRepository(resolved, null);
                    if (added) {
                        AnsiOutput.printSuccess("Added repository: " + resolved.getFileName()
                                + " (" + resolved + ")");
                    } else {
                        AnsiOutput.printWarning("Already tracked: " + resolved);
                    }
                }
                repoManager.save();
                System.out.println();
                AnsiOutput.printInfo("Multi-repo workspace with " + repoManager.getRepositories().size()
                        + " repositories");
            }

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

    private int handleAddRepo(WorkspaceManager workspace, Path targetDir) throws Exception {
        var validation = workspace.validate();
        if (validation.isPresent()) {
            AnsiOutput.printError(validation.get());
            return 1;
        }

        RepositoryManager repoManager = new RepositoryManager(targetDir);
        repoManager.load();

        Path repoPath = Path.of(addRepo.trim()).toAbsolutePath().normalize();
        boolean added = repoManager.addRepository(repoPath, null);
        if (added) {
            repoManager.save();
            AnsiOutput.printSuccess("Added repository: " + repoPath.getFileName()
                    + " (" + repoPath + ")");
            AnsiOutput.printInfo("Run " + AnsiOutput.cyan("synthesis scan") + " to index the new repository.");
        } else {
            AnsiOutput.printWarning("Repository already tracked: " + repoPath);
        }

        System.out.println();
        AnsiOutput.printInfo("Repositories in workspace:");
        for (RepositoryManager.RepoEntry entry : repoManager.getRepositories()) {
            System.out.println("    " + AnsiOutput.bold(entry.name()) + " -> " + entry.path());
        }
        System.out.println();

        return 0;
    }
}
