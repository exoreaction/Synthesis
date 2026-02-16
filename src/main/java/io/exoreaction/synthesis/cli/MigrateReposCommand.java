package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.core.RepositoryManager;
import io.exoreaction.synthesis.core.RepositoryManager.RepoEntry;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.org.OrganizationScanner;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Migrates existing repository and organization configurations to sub-workspaces.
 *
 * <p>This command converts the legacy partitioning mechanisms (repos.json and
 * organizations.json) to the new sub-workspace configuration in config.yaml.
 *
 * <p>Migration is non-destructive: existing configurations are preserved as backups
 * and can be restored if needed.
 *
 * <p>Usage:
 * <pre>
 *   synthesis migrate-repos                  # Migrate repos and orgs to sub-workspaces
 *   synthesis migrate-repos --dry-run        # Show what would be migrated
 *   synthesis migrate-repos --repos-only     # Only migrate repos, not orgs
 *   synthesis migrate-repos --orgs-only      # Only migrate orgs, not repos
 * </pre>
 *
 * @since v1.4.0
 */
@Command(
        name = "migrate-repos",
        description = "Migrate repos and organizations to sub-workspaces",
        mixinStandardHelpOptions = true
)
public class MigrateReposCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--dry-run"}, description = "Show what would be migrated without making changes",
            defaultValue = "false")
    private boolean dryRun;

    @Option(names = {"--repos-only"}, description = "Only migrate repositories",
            defaultValue = "false")
    private boolean reposOnly;

    @Option(names = {"--orgs-only"}, description = "Only migrate organizations",
            defaultValue = "false")
    private boolean orgsOnly;

    @Option(names = {"-v", "--verbose"}, description = "Show detailed output",
            defaultValue = "false")
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Migrate to Sub-Workspaces");
            System.out.println();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            // Check if already has sub-workspaces
            if (!config.getSubWorkspaces().isEmpty()) {
                AnsiOutput.printWarning("This workspace already has " + config.getSubWorkspaces().size()
                        + " sub-workspace(s) configured.");
                if (!dryRun) {
                    AnsiOutput.printInfo("New sub-workspaces will be appended to existing ones.");
                }
                System.out.println();
            }

            List<SubWorkspaceConfig> newSubWorkspaces = new ArrayList<>();

            // Migrate repositories
            if (!orgsOnly) {
                List<SubWorkspaceConfig> repoConfigs = migrateRepositories(workspaceRoot);
                if (!repoConfigs.isEmpty()) {
                    newSubWorkspaces.addAll(repoConfigs);
                }
            }

            // Migrate organizations
            if (!reposOnly) {
                List<SubWorkspaceConfig> orgConfigs = migrateOrganizations(workspaceRoot);
                if (!orgConfigs.isEmpty()) {
                    newSubWorkspaces.addAll(orgConfigs);
                }
            }

            if (newSubWorkspaces.isEmpty()) {
                System.out.println("  No repositories or organizations found to migrate.");
                System.out.println();
                return 0;
            }

            // Deduplicate against existing sub-workspaces
            List<SubWorkspaceConfig> existingNames = config.getSubWorkspaces();
            List<SubWorkspaceConfig> toAdd = new ArrayList<>();
            for (SubWorkspaceConfig swc : newSubWorkspaces) {
                boolean exists = false;
                for (SubWorkspaceConfig existing : existingNames) {
                    if (existing.getName().equals(swc.getName())) {
                        exists = true;
                        if (verbose) {
                            System.out.println("  Skipping '" + swc.getName()
                                    + "' (already exists as sub-workspace)");
                        }
                        break;
                    }
                }
                if (!exists) {
                    toAdd.add(swc);
                }
            }

            if (toAdd.isEmpty()) {
                System.out.println("  All discovered entries are already configured as sub-workspaces.");
                System.out.println();
                return 0;
            }

            // Print summary
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Migration plan:"));
            for (SubWorkspaceConfig swc : toAdd) {
                String tags = swc.getTags() != null && !swc.getTags().isEmpty()
                        ? " [" + String.join(", ", swc.getTags()) + "]" : "";
                System.out.printf("    + %s (%s)%s%n",
                        AnsiOutput.bold(swc.getName()),
                        swc.getPath(),
                        AnsiOutput.dim(tags));
                if (swc.getDescription() != null && !swc.getDescription().isEmpty()) {
                    System.out.printf("      %s%n", AnsiOutput.dim(swc.getDescription()));
                }
            }
            System.out.println();

            if (dryRun) {
                AnsiOutput.printInfo("Dry run -- no changes made. Run without --dry-run to apply.");
                return 0;
            }

            // Apply migration
            appendSubWorkspacesToConfig(workspaceRoot, toAdd);
            AnsiOutput.printSuccess("Added " + toAdd.size() + " sub-workspace(s) to config.yaml");

            // Backup legacy files
            backupLegacyFiles(workspaceRoot);

            System.out.println();
            AnsiOutput.printInfo("Next steps:");
            System.out.println("    1. Run " + AnsiOutput.cyan("synthesis scan")
                    + " to re-index with sub-workspace tags");
            System.out.println("    2. Use " + AnsiOutput.cyan("synthesis search --scope <name>")
                    + " to search within a sub-workspace");
            System.out.println("    3. Use " + AnsiOutput.cyan("synthesis search --aggregate")
                    + " to see results grouped by sub-workspace");
            System.out.println();

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Migration failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    /**
     * Migrates repos.json entries to sub-workspace configurations.
     */
    private List<SubWorkspaceConfig> migrateRepositories(Path workspaceRoot) throws IOException {
        List<SubWorkspaceConfig> configs = new ArrayList<>();

        RepositoryManager repoManager = new RepositoryManager(workspaceRoot);
        repoManager.load();

        if (!repoManager.hasRepos()) {
            if (verbose) {
                System.out.println("  No repositories found in repos.json");
            }
            return configs;
        }

        AnsiOutput.printInfo("Found " + repoManager.getRepositories().size()
                + " repository(ies) to migrate");

        for (RepoEntry repo : repoManager.getRepositories()) {
            // Compute the relative path from workspace root
            Path repoPath = repo.resolvedPath(workspaceRoot);
            String relativePath;
            try {
                relativePath = workspaceRoot.relativize(repoPath).toString();
            } catch (IllegalArgumentException e) {
                // Repository is outside workspace root -- use absolute path as name
                relativePath = repo.path();
                if (verbose) {
                    System.out.println("  Warning: " + repo.name()
                            + " is outside workspace root, using absolute path");
                }
            }

            SubWorkspaceConfig swc = new SubWorkspaceConfig(repo.name(), relativePath);
            swc.setDescription("Migrated from repos.json");
            swc.setType("general");
            swc.setTags(List.of("migrated-repo"));

            configs.add(swc);
        }

        return configs;
    }

    /**
     * Migrates organization scan results to sub-workspace configurations.
     */
    private List<SubWorkspaceConfig> migrateOrganizations(Path workspaceRoot) throws IOException {
        OrganizationScanner scanner = new OrganizationScanner(workspaceRoot);

        try {
            List<SubWorkspaceConfig> configs = scanner.toSubWorkspaceConfigs();
            if (!configs.isEmpty()) {
                AnsiOutput.printInfo("Found " + configs.size()
                        + " organization(s) to migrate");
            } else if (verbose) {
                System.out.println("  No organizations found above confidence threshold");
            }
            return configs;
        } catch (IOException e) {
            if (verbose) {
                System.err.println("  Warning: Organization scan failed: " + e.getMessage());
            }
            return List.of();
        }
    }

    /**
     * Appends sub-workspace configurations to the existing config.yaml.
     */
    private void appendSubWorkspacesToConfig(Path workspaceRoot,
                                               List<SubWorkspaceConfig> newSubWorkspaces)
            throws IOException {
        Path configPath = workspaceRoot.resolve(".synthesis").resolve("config.yaml");

        if (!Files.exists(configPath)) {
            AnsiOutput.printError("Config file not found: " + configPath);
            return;
        }

        // Read existing config
        String existingContent = Files.readString(configPath);

        // Build YAML block for sub-workspaces
        StringBuilder yamlBlock = new StringBuilder();
        yamlBlock.append("\n# Sub-workspaces (migrated from repos/organizations)\n");

        // Check if subWorkspaces key already exists
        if (existingContent.contains("subWorkspaces:")) {
            // Append to existing list
            for (SubWorkspaceConfig sw : newSubWorkspaces) {
                yamlBlock.append("  - name: \"").append(sw.getName()).append("\"\n");
                yamlBlock.append("    path: \"").append(sw.getPath()).append("\"\n");
                if (sw.getDescription() != null && !sw.getDescription().isEmpty()) {
                    yamlBlock.append("    description: \"").append(sw.getDescription()).append("\"\n");
                }
                if (sw.getType() != null) {
                    yamlBlock.append("    type: \"").append(sw.getType()).append("\"\n");
                }
                if (sw.getTags() != null && !sw.getTags().isEmpty()) {
                    yamlBlock.append("    tags:\n");
                    for (String tag : sw.getTags()) {
                        yamlBlock.append("      - \"").append(tag).append("\"\n");
                    }
                }
            }

            // Append after the last entry in subWorkspaces
            // Find the end of the subWorkspaces block
            Files.writeString(configPath, existingContent + yamlBlock.toString());
        } else {
            // Add new subWorkspaces section
            yamlBlock = new StringBuilder();
            yamlBlock.append("\nsubWorkspaces:\n");
            for (SubWorkspaceConfig sw : newSubWorkspaces) {
                yamlBlock.append("  - name: \"").append(sw.getName()).append("\"\n");
                yamlBlock.append("    path: \"").append(sw.getPath()).append("\"\n");
                if (sw.getDescription() != null && !sw.getDescription().isEmpty()) {
                    yamlBlock.append("    description: \"").append(sw.getDescription()).append("\"\n");
                }
                if (sw.getType() != null) {
                    yamlBlock.append("    type: \"").append(sw.getType()).append("\"\n");
                }
                if (sw.getTags() != null && !sw.getTags().isEmpty()) {
                    yamlBlock.append("    tags:\n");
                    for (String tag : sw.getTags()) {
                        yamlBlock.append("      - \"").append(tag).append("\"\n");
                    }
                }
            }
            Files.writeString(configPath, existingContent + yamlBlock.toString());
        }
    }

    /**
     * Creates backups of legacy configuration files.
     */
    private void backupLegacyFiles(Path workspaceRoot) {
        Path synthDir = workspaceRoot.resolve(".synthesis");

        // Backup repos.json
        Path reposFile = synthDir.resolve("repos.json");
        if (Files.exists(reposFile)) {
            try {
                Path backup = synthDir.resolve("repos.json.pre-migration");
                Files.copy(reposFile, backup);
                AnsiOutput.printInfo("Backed up repos.json -> repos.json.pre-migration");
            } catch (IOException e) {
                if (verbose) {
                    System.err.println("  Warning: Could not backup repos.json: " + e.getMessage());
                }
            }
        }

        // Backup organizations.json
        Path orgsFile = synthDir.resolve("organizations.json");
        if (Files.exists(orgsFile)) {
            try {
                Path backup = synthDir.resolve("organizations.json.pre-migration");
                Files.copy(orgsFile, backup);
                AnsiOutput.printInfo("Backed up organizations.json -> organizations.json.pre-migration");
            } catch (IOException e) {
                if (verbose) {
                    System.err.println("  Warning: Could not backup organizations.json: " + e.getMessage());
                }
            }
        }
    }
}
