package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Organization management commands for multi-company workspaces.
 *
 * <p>Provides auto-discovery, listing, and visualization of organizational
 * structure including companies, clients, products, and codebases.
 *
 * <p>Usage:
 * <pre>
 *   synthesis org scan    # Auto-discover organizations
 *   synthesis org list    # Show organizational hierarchy
 * </pre>
 */
@Command(
        name = "org",
        description = "Manage organizational structure (companies, clients, products)",
        mixinStandardHelpOptions = true,
        subcommands = {
                OrgCommand.ScanSubcommand.class,
                OrgCommand.ListSubcommand.class,
                OrgCommand.ClassifySubcommand.class
        }
)
public class OrgCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    /**
     * Scans the workspace to auto-discover organizations.
     */
    @Command(
            name = "scan",
            description = "Auto-discover organizational structure from workspace",
            mixinStandardHelpOptions = true
    )
    public static class ScanSubcommand implements Callable<Integer> {

        @ParentCommand
        private OrgCommand parent;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();

                AnsiOutput.printHeader("Synthesis - Organizational Scan");
                AnsiOutput.printInfo("Scanning: " + workspaceRoot);
                System.out.println();

                OrganizationScanner scanner = new OrganizationScanner(workspaceRoot);
                OrganizationRegistry registry = scanner.scan();

                if (!registry.hasOrganizations()) {
                    AnsiOutput.printWarning("No organizations detected in " + workspaceRoot);
                    AnsiOutput.printInfo("Organizations need directories with clients/, products/, business/ or README.md");
                    return 0;
                }

                // Display results
                System.out.printf("  Discovered %s organizations:%n%n",
                        AnsiOutput.bold(String.valueOf(registry.getOrganizations().size())));

                for (Organization org : registry.getOrganizations()) {
                    printOrganization(org);
                }

                // Save to disk
                registry.save();
                System.out.println();
                AnsiOutput.printSuccess("Saved to " + registry.getOrgsFilePath());
                System.out.println();

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Org scan failed: " + e.getMessage());
                return 1;
            }
        }

        private void printOrganization(Organization org) {
            String typeBadge = switch (org.getType()) {
                case COMPANY -> AnsiOutput.blue("[company]");
                case FOUNDATION -> AnsiOutput.green("[foundation]");
                case HOLDING -> AnsiOutput.magenta("[holding]");
                case CONCEPT -> AnsiOutput.cyan("[concept]");
                case OTHER -> AnsiOutput.dim("[other]");
            };

            System.out.printf("  %s %s %s%n",
                    AnsiOutput.bold(org.getName()),
                    typeBadge,
                    AnsiOutput.dim(org.getBasePath()));

            if (org.getDescription() != null) {
                System.out.printf("    %s%n", AnsiOutput.dim(org.getDescription()));
            }

            // Clients summary
            if (!org.getClients().isEmpty()) {
                long active = org.getClients().stream().filter(c -> c.getStatus() == ClientStatus.ACTIVE).count();
                long past = org.getClients().stream().filter(c -> c.getStatus() == ClientStatus.PAST).count();
                long opportunity = org.getClients().stream().filter(c -> c.getStatus() == ClientStatus.OPPORTUNITY).count();
                long signed = org.getClients().stream().filter(c -> c.getStatus() == ClientStatus.SIGNED).count();

                StringBuilder sb = new StringBuilder();
                sb.append("    Clients: ").append(org.getClients().size());
                sb.append(" (");
                List<String> parts = new java.util.ArrayList<>();
                if (active > 0) parts.add(active + " active");
                if (signed > 0) parts.add(signed + " signed");
                if (opportunity > 0) parts.add(opportunity + " opportunity");
                if (past > 0) parts.add(past + " past");
                sb.append(String.join(", ", parts));
                sb.append(")");
                System.out.println(sb);
            }

            // Products summary
            if (!org.getProducts().isEmpty()) {
                System.out.printf("    Products: %d (%s)%n",
                        org.getProducts().size(),
                        String.join(", ", org.getProducts().stream()
                                .map(Product::getName).toList()));
            }

            // Codebases summary
            if (!org.getCodebasePaths().isEmpty()) {
                System.out.printf("    Codebases: %s%n",
                        String.join(", ", org.getCodebasePaths()));
            }

            System.out.println();
        }
    }

    /**
     * Lists the current organizational hierarchy.
     */
    @Command(
            name = "list",
            description = "Show organizational hierarchy",
            mixinStandardHelpOptions = true
    )
    public static class ListSubcommand implements Callable<Integer> {

        @ParentCommand
        private OrgCommand parent;

        @picocli.CommandLine.Option(
                names = {"--show-clients"},
                description = "Show detailed client listing",
                defaultValue = "false"
        )
        private boolean showClients;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);
                registry.load();

                if (!registry.hasOrganizations()) {
                    AnsiOutput.printWarning("No organizations registered. Run 'synthesis org scan' first.");
                    return 0;
                }

                AnsiOutput.printHeader("Synthesis - Organizations");
                System.out.println();

                for (Organization org : registry.getOrganizations()) {
                    System.out.printf("  %s (%s) - %s%n",
                            AnsiOutput.bold(org.getName()),
                            org.getType().name().toLowerCase(),
                            AnsiOutput.dim(org.getBasePath()));

                    if (showClients && !org.getClients().isEmpty()) {
                        System.out.println("    " + AnsiOutput.bold("Clients:"));
                        for (Client client : org.getClients()) {
                            String statusColor = switch (client.getStatus()) {
                                case ACTIVE -> AnsiOutput.green("ACTIVE");
                                case SIGNED -> AnsiOutput.blue("SIGNED");
                                case OPPORTUNITY -> AnsiOutput.yellow("PROSPECT");
                                case PAST -> AnsiOutput.dim("PAST");
                            };
                            System.out.printf("      %-10s %s%n", statusColor, client.getName());
                        }
                    } else if (!org.getClients().isEmpty()) {
                        System.out.printf("    Clients: %d%n", org.getClients().size());
                    }

                    if (!org.getProducts().isEmpty()) {
                        System.out.printf("    Products: %s%n",
                                String.join(", ", org.getProducts().stream()
                                        .map(Product::getName).toList()));
                    }

                    System.out.println();
                }

                if (registry.getLastScanTime() != null) {
                    AnsiOutput.printInfo("Last scan: " + registry.getLastScanTime());
                }
                System.out.println();

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Failed to list organizations: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Classifies files in the Downloads directory.
     */
    @Command(
            name = "classify",
            description = "Classify files in Downloads directory by organization",
            mixinStandardHelpOptions = true
    )
    public static class ClassifySubcommand implements Callable<Integer> {

        @ParentCommand
        private OrgCommand parent;

        @picocli.CommandLine.Parameters(
                index = "0",
                description = "Directory to classify (default: ~/Downloads)",
                defaultValue = ""
        )
        private String directory;

        @picocli.CommandLine.Option(
                names = {"--threshold"},
                description = "Confidence threshold for classification (0.0-1.0, default: 0.6)",
                defaultValue = "0.6"
        )
        private double threshold;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);
                registry.load();

                if (!registry.hasOrganizations()) {
                    AnsiOutput.printWarning("No organizations registered. Run 'synthesis org scan' first.");
                    return 0;
                }

                // Determine which directory to classify
                Path downloadsDir;
                if (directory.isEmpty()) {
                    downloadsDir = Path.of(System.getProperty("user.home"), "Downloads");
                } else {
                    downloadsDir = Path.of(directory);
                }

                if (!java.nio.file.Files.isDirectory(downloadsDir)) {
                    AnsiOutput.printError("Directory not found: " + downloadsDir);
                    return 1;
                }

                AnsiOutput.printHeader("Synthesis - Downloads Classification");
                AnsiOutput.printInfo("Scanning: " + downloadsDir);
                AnsiOutput.printInfo("Threshold: " + String.format("%.0f%%", threshold * 100));
                System.out.println();

                DownloadsClassifier classifier = new DownloadsClassifier(registry);

                int classified = 0;
                int uncertain = 0;
                int skipped = 0;

                try (var stream = java.nio.file.Files.list(downloadsDir)) {
                    List<Path> files = stream
                            .filter(java.nio.file.Files::isRegularFile)
                            .sorted()
                            .toList();

                    for (Path file : files) {
                        DownloadsClassifier.ClassificationResult result = classifier.classify(file);

                        if (result.shouldSkip()) {
                            skipped++;
                            continue;
                        }

                        if (result.isConfident(threshold)) {
                            classified++;
                            System.out.printf("  %s %-40s -> %s (%s)%n",
                                    AnsiOutput.green("OK"),
                                    truncate(file.getFileName().toString(), 40),
                                    AnsiOutput.bold(result.organization()),
                                    String.format("%.0f%%", result.confidence() * 100));
                        } else if (result.organization() != null) {
                            uncertain++;
                            System.out.printf("  %s %-40s -> %s? (%s)%n",
                                    AnsiOutput.yellow("??"),
                                    truncate(file.getFileName().toString(), 40),
                                    result.organization(),
                                    String.format("%.0f%%", result.confidence() * 100));
                        } else {
                            uncertain++;
                            System.out.printf("  %s %-40s -> %s%n",
                                    AnsiOutput.red("--"),
                                    truncate(file.getFileName().toString(), 40),
                                    AnsiOutput.dim("unknown"));
                        }
                    }
                }

                System.out.println();
                System.out.printf("  Classified: %s | Uncertain: %s | Skipped: %s%n",
                        AnsiOutput.bold(String.valueOf(classified)),
                        AnsiOutput.yellow(String.valueOf(uncertain)),
                        AnsiOutput.dim(String.valueOf(skipped)));
                System.out.println();

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Classification failed: " + e.getMessage());
                return 1;
            }
        }

        private String truncate(String s, int maxLen) {
            if (s.length() <= maxLen) return s;
            return s.substring(0, maxLen - 3) + "...";
        }
    }
}
