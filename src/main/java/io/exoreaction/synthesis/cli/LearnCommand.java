package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.org.OrganizationRegistry;
import io.exoreaction.synthesis.skills.SkillGenerator;
import io.exoreaction.synthesis.skills.SkillInstaller;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Generates Claude Code skills from workspace organizational knowledge.
 *
 * <p>Reads organizational data from {@code .synthesis/organizations.json}
 * and generates YAML skill files that teach Claude Code about the workspace:
 * <ul>
 *   <li>Workspace context (organizations, statistics, key files)</li>
 *   <li>Organization-specific context (clients, products, codebases)</li>
 *   <li>Client navigation shortcuts</li>
 *   <li>Pipeline tracking awareness</li>
 *   <li>Technical proof points</li>
 * </ul>
 *
 * <p>Skills are generated in {@code .synthesis/skills/} (workspace-scoped).
 * Use {@code --install} to copy them to {@code ~/.claude/skills/} for global availability.
 *
 * <p>Usage:
 * <pre>
 *   synthesis learn                # Generate skills in .synthesis/skills/
 *   synthesis learn --install      # Also install to ~/.claude/skills/
 *   synthesis learn --uninstall    # Remove installed skills from global dir
 * </pre>
 */
@Command(
        name = "learn",
        description = "Generate Claude Code skills from workspace knowledge",
        mixinStandardHelpOptions = true
)
public class LearnCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--install"},
            description = "Install skills to ~/.claude/skills/ for global availability",
            defaultValue = "false"
    )
    private boolean install;

    @Option(
            names = {"--uninstall"},
            description = "Remove this workspace's skills from ~/.claude/skills/",
            defaultValue = "false"
    )
    private boolean uninstall;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Handle --uninstall
            if (uninstall) {
                return handleUninstall(workspaceRoot);
            }

            AnsiOutput.printHeader("Synthesis - Learn");

            // Load organizational data
            OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);
            registry.load();

            if (!registry.hasOrganizations()) {
                AnsiOutput.printWarning("No organizations found. Run 'synthesis org scan' or 'synthesis init' first.");
                return 0;
            }

            System.out.println("  Reading organizational data from .synthesis/organizations.json...");
            System.out.printf("    %d organization%s%n",
                    registry.getOrganizations().size(),
                    registry.getOrganizations().size() != 1 ? "s" : "");

            int totalClients = registry.getAllClients().size();
            if (totalClients > 0) {
                System.out.printf("    %d client%s%n", totalClients,
                        totalClients != 1 ? "s" : "");
            }

            int totalProducts = registry.getOrganizations().stream()
                    .mapToInt(o -> o.getProducts().size()).sum();
            if (totalProducts > 0) {
                System.out.printf("    %d product%s%n", totalProducts,
                        totalProducts != 1 ? "s" : "");
            }
            System.out.println();

            // Generate skills
            SkillGenerator generator = new SkillGenerator(workspaceRoot, registry);
            System.out.println("  Generating Claude Code skills...");

            SkillGenerator.GenerationResult result = generator.generateAll();

            for (var entry : result.skills().entrySet()) {
                System.out.printf("    %s %s (%d lines)%n",
                        AnsiOutput.green("OK"),
                        entry.getKey(),
                        entry.getValue());
            }

            System.out.println();
            AnsiOutput.printSuccess("Generated " + result.totalFiles() + " skills ("
                    + result.totalLines() + " total lines)");
            AnsiOutput.printInfo("Skills saved to: " + result.skillsDir());

            // Handle --install
            if (install) {
                System.out.println();
                return handleInstall(workspaceRoot, result.skillsDir());
            }

            // Print integration hint
            System.out.println();
            System.out.println("  Claude Code integration:");
            System.out.println("    Skills are workspace-scoped. To make globally available:");
            System.out.println("      " + AnsiOutput.cyan("synthesis learn --install"));
            System.out.println();

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Learn failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Handles installing generated skills to the global skills directory.
     */
    private int handleInstall(Path workspaceRoot, Path skillsDir) {
        try {
            String prefix = workspaceRoot.getFileName().toString();
            SkillInstaller installer = new SkillInstaller(skillsDir, prefix);

            System.out.println("  Installing to " + installer.getGlobalSkillsDir() + "...");

            SkillInstaller.InstallResult installResult = installer.installAll();

            for (var entry : installResult.installed().entrySet()) {
                System.out.printf("    %s %s -> %s%n",
                        AnsiOutput.green("OK"),
                        entry.getKey(),
                        entry.getValue());
            }

            System.out.println();
            AnsiOutput.printSuccess(installResult.count() + " skills installed to "
                    + installResult.targetDir());
            AnsiOutput.printInfo("Claude Code will load these skills in all sessions.");
            System.out.println();

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Installation failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Handles uninstalling skills from the global skills directory.
     */
    private int handleUninstall(Path workspaceRoot) {
        try {
            String prefix = workspaceRoot.getFileName().toString();
            Path skillsDir = workspaceRoot.resolve(".synthesis").resolve("skills");
            SkillInstaller installer = new SkillInstaller(skillsDir, prefix);

            System.out.println();
            int removed = installer.uninstallAll();

            if (removed > 0) {
                AnsiOutput.printSuccess("Removed " + removed + " skill"
                        + (removed != 1 ? "s" : "") + " from "
                        + installer.getGlobalSkillsDir());
            } else {
                AnsiOutput.printInfo("No installed skills found for this workspace.");
            }
            System.out.println();

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Uninstall failed: " + e.getMessage());
            return 1;
        }
    }
}
