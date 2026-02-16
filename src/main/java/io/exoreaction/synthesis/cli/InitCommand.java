package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.RepositoryManager;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.telemetry.ClientUUID;
import io.exoreaction.synthesis.telemetry.TelemetryConfig;
import io.exoreaction.synthesis.telemetry.TelemetryService;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.workspace.WorkspaceType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 *   <li>Register installation for pilot program (mandatory telemetry)</li>
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
            names = {"--category"},
            description = "Workspace category: source-code, documents, mixed"
    )
    private String category;

    @Option(
            names = {"--language"},
            description = "Primary programming language (e.g., java, javascript, python)"
    )
    private String primaryLanguage;

    @Option(
            names = {"--company"},
            description = "Company or organization that owns this workspace"
    )
    private String company;

    @Option(
            names = {"--repo-count"},
            description = "Number of repositories in this workspace",
            defaultValue = "0"
    )
    private int repoCount;

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

    @Option(
            names = {"--auto-discover"},
            description = "Auto-discover sub-workspaces from directory structure and generate config",
            defaultValue = "false"
    )
    private boolean autoDiscover;

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

            // Prompt for workspace category if not provided and not in non-interactive mode
            String resolvedCategory = category;
            if (resolvedCategory == null && !noInteractive) {
                resolvedCategory = promptWorkspaceCategory(targetDir);
            } else if (resolvedCategory == null) {
                resolvedCategory = detectWorkspaceCategory(targetDir);
            }

            // Standard init with metadata
            SynthesisConfig config = workspace.initWithMetadata(
                    name, type, resolvedCategory, primaryLanguage, repoCount, company);

            // Handle --repos (multi-repo init)
            if (repos != null && !repos.isEmpty()) {
                handleMultiRepo(targetDir);
            }

            System.out.println();
            AnsiOutput.printInfo("Workspace: " + config.getWorkspace().getName());
            AnsiOutput.printInfo("Type:      " + config.getWorkspace().getType());
            if (resolvedCategory != null) {
                AnsiOutput.printInfo("Category:  " + resolvedCategory);
            }
            if (primaryLanguage != null) {
                AnsiOutput.printInfo("Language:  " + primaryLanguage);
            }
            if (company != null) {
                AnsiOutput.printInfo("Company:   " + company);
            }

            // Organization discovery (unless skipped)
            if (!skipOrgScan) {
                System.out.println();
                int orgResult = handleOrgDiscovery(targetDir);
                if (orgResult != 0) {
                    AnsiOutput.printWarning("Organization discovery had issues, but workspace is initialized.");
                }
            }

            // Auto-discover sub-workspaces from directory structure
            if (autoDiscover) {
                System.out.println();
                handleSubWorkspaceDiscovery(targetDir);
            }

            // Register installation for pilot program (mandatory)
            handlePilotRegistration();

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
     * Registers this installation for the pilot program.
     *
     * <p>Generates the client UUID, saves the telemetry config with installation
     * timestamp, sends an install event to the telemetry channel, and shows the
     * UUID to the user so they can request pilot approval.
     */
    void handlePilotRegistration() {
        try {
            // Generate UUID (or use existing)
            String uuid = ClientUUID.getOrCreate();

            // Save telemetry config with installation timestamp
            TelemetryConfig config = TelemetryConfig.load();
            config.markInstalled();
            config.save();

            // Report installation
            TelemetryService service = TelemetryService.create();
            service.reportInstall();
            service.shutdown();

            // Show UUID to user
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Pilot Program Registration"));
            System.out.println();
            System.out.println("  Your Synthesis UUID: " + AnsiOutput.cyan(uuid));
            System.out.println("  " + AnsiOutput.dim("Provide this UUID to the maintainer for pilot approval."));
            System.out.println();
            System.out.println("  " + AnsiOutput.dim("Telemetry: Active (mandatory for pilot program)"));
            System.out.println("  " + AnsiOutput.dim("Run 'synthesis telemetry --show' to see what data is sent."));
        } catch (Exception e) {
            // Registration failure should never prevent workspace init
            AnsiOutput.printWarning("Could not register for pilot program: " + e.getMessage());
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

    /**
     * Auto-discovers sub-workspaces from the directory structure.
     *
     * <p>Scans top-level directories and uses the OrganizationScanner's confidence
     * scoring to identify likely sub-workspaces. Generates sub-workspace entries
     * in the config and prints a summary.
     */
    void handleSubWorkspaceDiscovery(Path targetDir) {
        try {
            OrganizationScanner scanner = new OrganizationScanner(targetDir);
            java.util.List<SynthesisConfig.SubWorkspaceConfig> discovered = new java.util.ArrayList<>();

            AnsiOutput.printInfo("Discovering sub-workspaces...");

            try (java.nio.file.DirectoryStream<Path> dirs = java.nio.file.Files.newDirectoryStream(targetDir)) {
                for (Path dir : dirs) {
                    if (!java.nio.file.Files.isDirectory(dir)) continue;
                    String dirName = dir.getFileName().toString();
                    if (dirName.startsWith(".") || dirName.equals("archive") || dirName.equals("personal")) continue;

                    int confidence = scanner.computeConfidence(dir);
                    if (confidence >= 2) {
                        SynthesisConfig.SubWorkspaceConfig sw = new SynthesisConfig.SubWorkspaceConfig(dirName, dirName);
                        sw.setDescription("Auto-discovered from " + dirName + "/ (confidence: " + confidence + ")");
                        // Classify type based on content
                        String detectedCategory = detectWorkspaceCategory(dir);
                        sw.setType(detectedCategory);
                        discovered.add(sw);
                    }
                }
            }

            if (discovered.isEmpty()) {
                AnsiOutput.printInfo("No sub-workspaces discovered. You can add them manually in synthesis-config.yaml.");
                return;
            }

            // Print discovered sub-workspaces
            System.out.println();
            AnsiOutput.printSuccess("Discovered " + discovered.size() + " sub-workspace(s):");
            for (SynthesisConfig.SubWorkspaceConfig sw : discovered) {
                System.out.printf("    %s -> %s/ (%s)%n",
                        AnsiOutput.bold(sw.getName()), sw.getPath(), sw.getType());
            }

            // Update config with discovered sub-workspaces
            SynthesisConfig config = ConfigLoader.load(targetDir);
            config.setSubWorkspaces(discovered);

            // Append sub-workspace config to the config file
            Path configFile = targetDir.resolve("synthesis-config.yaml");
            if (!java.nio.file.Files.exists(configFile)) {
                configFile = targetDir.resolve(".synthesis/config.yaml");
            }
            if (java.nio.file.Files.exists(configFile)) {
                StringBuilder sb = new StringBuilder();
                sb.append("\n# Sub-workspaces (auto-discovered)\nsubWorkspaces:\n");
                for (SynthesisConfig.SubWorkspaceConfig sw : discovered) {
                    sb.append("  - name: \"").append(sw.getName()).append("\"\n");
                    sb.append("    path: \"").append(sw.getPath()).append("\"\n");
                    sb.append("    description: \"").append(sw.getDescription()).append("\"\n");
                    sb.append("    type: \"").append(sw.getType()).append("\"\n");
                }
                java.nio.file.Files.writeString(configFile,
                        java.nio.file.Files.readString(configFile) + sb.toString());
                AnsiOutput.printInfo("Updated config: " + configFile);
            }

        } catch (Exception e) {
            AnsiOutput.printWarning("Sub-workspace discovery failed: " + e.getMessage());
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

    /**
     * Prompts the user interactively for workspace category.
     * Falls back to auto-detection if user enters empty input.
     */
    String promptWorkspaceCategory(Path targetDir) {
        String detected = detectWorkspaceCategory(targetDir);
        try {
            BufferedReader reader = customInput != null ? customInput
                    : new BufferedReader(new InputStreamReader(System.in));
            PrintStream out = customOutput != null ? customOutput : System.out;

            out.println();
            out.println("  " + AnsiOutput.bold("Workspace Category"));
            out.println("  Choose a category for this workspace:");
            out.println("    1. " + AnsiOutput.cyan("source-code") + " - Source code repositories");
            out.println("    2. " + AnsiOutput.cyan("documents")   + " - Documents and knowledge bases");
            out.println("    3. " + AnsiOutput.cyan("mixed")       + " - Both code and documents");
            out.println();
            out.print("  Category [" + detected + "]: ");
            out.flush();

            String input = reader.readLine();
            if (input == null || input.isBlank()) {
                return detected;
            }

            input = input.trim();
            return switch (input) {
                case "1", "source-code", "source_code" -> "source-code";
                case "2", "documents", "docs" -> "documents";
                case "3", "mixed" -> "mixed";
                default -> detected;
            };
        } catch (IOException e) {
            return detected;
        }
    }

    /**
     * Auto-detects the workspace category by examining directory contents.
     */
    static String detectWorkspaceCategory(Path targetDir) {
        boolean hasCode = false;
        boolean hasDocs = false;

        // Check for common source code indicators
        String[] codeIndicators = {"pom.xml", "build.gradle", "package.json",
                "Cargo.toml", "go.mod", "requirements.txt", "setup.py",
                "CMakeLists.txt", "Makefile", ".git"};
        for (String indicator : codeIndicators) {
            if (java.nio.file.Files.exists(targetDir.resolve(indicator))) {
                hasCode = true;
                break;
            }
        }

        // Check for src/ directory as a strong code indicator
        if (java.nio.file.Files.isDirectory(targetDir.resolve("src"))) {
            hasCode = true;
        }

        // Check if this looks like a Documents/Downloads folder
        String dirName = targetDir.getFileName() != null ? targetDir.getFileName().toString().toLowerCase() : "";
        if (dirName.equals("documents") || dirName.equals("downloads") ||
            dirName.equals("docs") || dirName.equals("desktop")) {
            hasDocs = true;
        }

        // Check for common document patterns
        String[] docIndicators = {"CLAUDE.md", "README.md", "CHANGELOG.md"};
        int docCount = 0;
        for (String indicator : docIndicators) {
            if (java.nio.file.Files.exists(targetDir.resolve(indicator))) {
                docCount++;
            }
        }
        if (docCount >= 2) hasDocs = true;

        if (hasCode && hasDocs) return "mixed";
        if (hasCode) return "source-code";
        if (hasDocs) return "documents";
        return "mixed"; // default
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
