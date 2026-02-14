package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.RepositoryManager;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Initializes a new Synthesis workspace with optional multi-repository support
 * and interactive organization discovery.
 *
 * <p>The init process now includes smart organizational discovery:
 * <ol>
 *   <li>Initialize workspace directory structure</li>
 *   <li>Scan for organizations (companies, foundations, etc.)</li>
 *   <li>Present findings interactively for user confirmation</li>
 *   <li>Save confirmed organizations to .synthesis/organizations.json</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   synthesis init [directory] [--name NAME] [--type TYPE]
 *   synthesis init --repos ~/project-a,~/project-b --name "My Workspace"
 *   synthesis init --add ~/project-d        # Add repo to existing workspace
 *   synthesis init . --skip-org-scan        # Skip organization discovery
 *   synthesis init . --no-interactive       # Non-interactive (auto-accept all)
 * </pre>
 */
@Command(
        name = "init",
        description = "Initialize a new Synthesis workspace with smart organization discovery",
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

    @Option(
            names = {"--skip-org-scan"},
            description = "Skip automatic organization scanning",
            defaultValue = "false"
    )
    private boolean skipOrgScan;

    @Option(
            names = {"--no-interactive"},
            description = "Non-interactive mode (auto-accept all discovered organizations)",
            defaultValue = "false"
    )
    private boolean noInteractive;

    // Visible for testing: custom I/O for interactive confirmation
    private BufferedReader customInput;
    private PrintStream customOutput;

    /**
     * Sets custom I/O streams for testing interactive confirmation.
     */
    public void setInteractiveIO(BufferedReader input, PrintStream output) {
        this.customInput = input;
        this.customOutput = output;
    }

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
                handleMultiRepo(targetDir);
            }

            System.out.println();
            AnsiOutput.printInfo("Workspace: " + config.getWorkspace().getName());
            AnsiOutput.printInfo("Type:      " + config.getWorkspace().getType());

            // Organization discovery (unless skipped)
            if (!skipOrgScan) {
                System.out.println();
                int orgResult = handleOrgDiscovery(targetDir);
                if (orgResult != 0) {
                    AnsiOutput.printWarning("Organization discovery had issues, but workspace is initialized.");
                }
            }

            // Print next steps
            System.out.println();
            System.out.println("  Next steps:");
            if (!skipOrgScan) {
                System.out.println("    1. Run " + AnsiOutput.cyan("synthesis learn")
                        + " to generate Claude Code skills");
            }
            System.out.println("    " + (skipOrgScan ? "1" : "2") + ". Run "
                    + AnsiOutput.cyan("synthesis scan") + " to index your workspace");
            System.out.println("    " + (skipOrgScan ? "2" : "3") + ". Run "
                    + AnsiOutput.cyan("synthesis search <query>") + " to find files");
            if (skipOrgScan) {
                System.out.println("    3. Run " + AnsiOutput.cyan("synthesis org scan")
                        + " to discover organizations");
            }
            System.out.println();

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Failed to initialize workspace: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Handles interactive organization discovery during init.
     */
    int handleOrgDiscovery(Path targetDir) {
        try {
            OrganizationScanner scanner = new OrganizationScanner(targetDir);

            System.out.println("  Discovering organizations...");
            List<DiscoveredOrganization> discoveries = scanner.discoverWithConfidence();

            if (discoveries.isEmpty()) {
                AnsiOutput.printInfo("No organizations detected in " + targetDir);
                AnsiOutput.printInfo("You can run 'synthesis org scan' later to discover organizations.");
                return 0;
            }

            List<Organization> accepted;

            if (noInteractive) {
                // Non-interactive: accept all
                accepted = discoveries.stream()
                        .map(DiscoveredOrganization::organization)
                        .toList();

                // Display what was found
                System.out.printf("%n  Auto-accepted %s organization%s:%n",
                        AnsiOutput.bold(String.valueOf(accepted.size())),
                        accepted.size() != 1 ? "s" : "");
                for (Organization org : accepted) {
                    System.out.printf("    %s (%s)%n",
                            AnsiOutput.bold(org.getName()),
                            org.getType().name().toLowerCase());
                }
            } else {
                // Interactive: prompt user
                InteractiveConfirmation confirmation;
                if (customInput != null && customOutput != null) {
                    confirmation = new InteractiveConfirmation(customInput, customOutput);
                } else {
                    confirmation = new InteractiveConfirmation();
                }

                InteractiveConfirmation.ConfirmationResult result = confirmation.confirm(discoveries);
                accepted = result.accepted();

                if (!result.hasAccepted()) {
                    AnsiOutput.printWarning("No organizations accepted. You can run 'synthesis org scan' later.");
                    return 0;
                }
            }

            // Save accepted organizations
            OrganizationRegistry registry = new OrganizationRegistry(targetDir);
            for (Organization org : accepted) {
                registry.addOrganization(org);
            }
            registry.setLastScanTime(java.time.Instant.now());
            registry.save();

            System.out.println();
            AnsiOutput.printSuccess("Organizations confirmed and saved (" + accepted.size() + ")");
            AnsiOutput.printInfo("Saved to " + registry.getOrgsFilePath());

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Organization discovery failed: " + e.getMessage());
            return 1;
        }
    }

    private void handleMultiRepo(Path targetDir) throws Exception {
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
