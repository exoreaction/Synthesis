package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                OrgCommand.ClassifySubcommand.class,
                OrgCommand.EnrichSubcommand.class
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

    /**
     * Enriches organizations with auto-discovered codebase paths.
     *
     * <p>Uses a 4-layer resolution strategy to discover codebase paths
     * for clients and products that don't yet have them:
     * <ol>
     *   <li>CODEBASE-INDEX.md parsing (confidence 0.5)</li>
     *   <li>Organization inheritance (confidence 0.4)</li>
     *   <li>Git repo discovery (confidence 0.35)</li>
     *   <li>Path name heuristics (confidence 0.3)</li>
     * </ol>
     *
     * <p>Additive only: never overwrites existing non-empty codebase lists.
     * Dry-run by default: shows proposals without writing.
     */
    @Command(
            name = "enrich",
            description = "Auto-discover codebase paths for organizations, clients, and products",
            mixinStandardHelpOptions = true
    )
    public static class EnrichSubcommand implements Callable<Integer> {

        @ParentCommand
        private OrgCommand parent;

        @picocli.CommandLine.Option(
                names = {"--apply"},
                description = "Write discovered paths to organizations.json (default: dry-run)",
                defaultValue = "false"
        )
        private boolean apply;

        @picocli.CommandLine.Option(
                names = {"--force"},
                description = "Re-evaluate even orgs/clients that already have codebase entries",
                defaultValue = "false"
        )
        private boolean force;

        @picocli.CommandLine.Option(
                names = {"--confidence"},
                description = "Minimum confidence threshold (0.0-1.0, default: 0.4)",
                defaultValue = "0.4"
        )
        private double confidence;

        /** Pattern to match Location field in CODEBASE-INDEX.md */
        private static final Pattern LOCATION_PATTERN = Pattern.compile(
                "\\*\\*Location:\\*\\*\\s*`?([^`\\n]+)`?"
        );

        /** Pattern to match client sections in CODEBASE-INDEX.md */
        private static final Pattern CLIENT_SECTION_PATTERN = Pattern.compile(
                "###\\s+([^\\(]+)\\s*\\([^)]+\\)\\s*-\\s*([^\\n]+)"
        );

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

                if (!apply) {
                    AnsiOutput.printHeader("Synthesis - Org Enrich (DRY RUN)");
                } else {
                    AnsiOutput.printHeader("Synthesis - Org Enrich");
                }
                AnsiOutput.printInfo("Confidence threshold: " + String.format("%.0f%%", confidence * 100));
                if (force) {
                    AnsiOutput.printInfo("Force mode: re-evaluating all entries");
                }
                System.out.println();

                List<EnrichProposal> allProposals = new ArrayList<>();

                for (Organization org : registry.getOrganizations()) {
                    // Discover for clients
                    for (Client client : org.getClients()) {
                        if (!force && !client.getCodebases().isEmpty()) {
                            continue;
                        }
                        List<EnrichProposal> proposals = discoverForEntity(
                                client.getName(), client.getDirectoryName(), org);
                        allProposals.addAll(proposals);
                    }

                    // Discover for products
                    for (Product product : org.getProducts()) {
                        List<EnrichProposal> proposals = discoverForEntity(
                                product.getName(), product.getName(), org);
                        allProposals.addAll(proposals);
                    }
                }

                // Filter by confidence threshold
                List<EnrichProposal> accepted = allProposals.stream()
                        .filter(p -> p.confidence >= confidence)
                        .toList();

                if (accepted.isEmpty()) {
                    System.out.println("  No new codebase paths discovered above confidence threshold.");
                    System.out.println();
                    return 0;
                }

                // Display proposals
                for (EnrichProposal proposal : accepted) {
                    if (!apply) {
                        System.out.printf("  [DRY RUN] Would add to %s (confidence %.1f, source: %s):%n",
                                AnsiOutput.bold(proposal.entityName),
                                proposal.confidence,
                                proposal.source);
                    } else {
                        System.out.printf("  Adding to %s (confidence %.1f, source: %s):%n",
                                AnsiOutput.bold(proposal.entityName),
                                proposal.confidence,
                                proposal.source);
                    }
                    for (String path : proposal.paths) {
                        System.out.printf("    + %s%n", AnsiOutput.green(path));
                    }
                    System.out.println();
                }

                if (apply) {
                    // Apply proposals to the registry
                    for (EnrichProposal proposal : accepted) {
                        applyProposal(registry, proposal);
                    }
                    registry.save();
                    AnsiOutput.printSuccess("Updated " + accepted.size() + " entries in " + registry.getOrgsFilePath());
                } else {
                    System.out.printf("  Run with %s to write changes.%n", AnsiOutput.bold("--apply"));
                }
                System.out.println();

                return 0;
            } catch (Exception e) {
                AnsiOutput.printError("Org enrich failed: " + e.getMessage());
                return 1;
            }
        }

        /**
         * Discovers codebase paths for a named entity (client or product) using 4-layer resolution.
         */
        List<EnrichProposal> discoverForEntity(String entityName, String directoryName,
                                                       Organization org) {
            List<EnrichProposal> proposals = new ArrayList<>();
            String normalizedName = normalizeName(entityName);

            // Layer 1: CODEBASE-INDEX.md parsing (confidence 0.5)
            List<String> indexPaths = discoverFromCodebaseIndex(entityName, org);
            if (!indexPaths.isEmpty()) {
                proposals.add(new EnrichProposal(entityName, org.getName(), indexPaths,
                        0.5, "CODEBASE-INDEX.md"));
                return proposals; // Highest confidence, no need for lower layers
            }

            // Layer 2: Organization inheritance (confidence 0.4)
            List<String> inheritedPaths = discoverFromOrganization(normalizedName, directoryName, org);
            if (!inheritedPaths.isEmpty()) {
                proposals.add(new EnrichProposal(entityName, org.getName(), inheritedPaths,
                        0.4, "organization-inheritance"));
                return proposals;
            }

            // Layer 3: Git repo discovery (confidence 0.35)
            List<String> gitPaths = discoverFromGitRepos(normalizedName, org);
            if (!gitPaths.isEmpty()) {
                proposals.add(new EnrichProposal(entityName, org.getName(), gitPaths,
                        0.35, "git-repo-discovery"));
                return proposals;
            }

            // Layer 4: Path name heuristics (confidence 0.3)
            List<String> heuristicPaths = discoverFromPathHeuristics(normalizedName);
            if (!heuristicPaths.isEmpty()) {
                proposals.add(new EnrichProposal(entityName, org.getName(), heuristicPaths,
                        0.3, "path-heuristic"));
            }

            return proposals;
        }

        /**
         * Layer 1: Parse CODEBASE-INDEX.md files in the org's codebasePaths.
         */
        private List<String> discoverFromCodebaseIndex(String entityName, Organization org) {
            List<String> discovered = new ArrayList<>();

            // Check the org's base path for CODEBASE-INDEX.md
            Path orgIndex = Path.of(org.getBasePath()).resolve("CODEBASE-INDEX.md");
            if (Files.exists(orgIndex)) {
                discovered.addAll(parseCodebaseIndexForEntity(orgIndex, entityName));
            }

            // Also check in each codebasePath
            for (String codebasePath : org.getCodebasePaths()) {
                Path cbPath = Path.of(codebasePath);
                Path indexFile = cbPath.resolve("CODEBASE-INDEX.md");
                if (Files.exists(indexFile)) {
                    discovered.addAll(parseCodebaseIndexForEntity(indexFile, entityName));
                }
            }

            return discovered;
        }

        /**
         * Parses a CODEBASE-INDEX.md file for Location entries matching the entity name.
         */
        private List<String> parseCodebaseIndexForEntity(Path indexFile, String entityName) {
            List<String> paths = new ArrayList<>();
            try {
                String content = Files.readString(indexFile);
                String[] sections = content.split("(?m)(?=^###\\s)");

                for (String section : sections) {
                    Matcher sectionMatcher = CLIENT_SECTION_PATTERN.matcher(section);
                    if (!sectionMatcher.find()) continue;

                    String sectionName = sectionMatcher.group(1).trim();
                    if (!sectionName.equalsIgnoreCase(entityName)) continue;

                    Matcher locationMatcher = LOCATION_PATTERN.matcher(section);
                    if (!locationMatcher.find()) continue;

                    String location = locationMatcher.group(1).trim();
                    String resolved = ClientCodebaseResolver.resolveCodebasePath(location);
                    if (resolved != null && Files.exists(Path.of(resolved))) {
                        paths.add(resolved);
                    }
                }
            } catch (IOException e) {
                // Ignore parse failures
            }
            return paths;
        }

        /**
         * Layer 2: Check if org's codebase directory names match the entity name.
         */
        private List<String> discoverFromOrganization(String normalizedName, String directoryName,
                                                       Organization org) {
            List<String> matched = new ArrayList<>();
            String cleanDir = directoryName.toLowerCase()
                    .replaceFirst("^opportunity-", "")
                    .replaceFirst("-past$", "")
                    .replaceFirst("-active$", "");
            String normalizedDir = cleanDir.replaceAll("[^a-z0-9]", "");

            for (String codebasePath : org.getCodebasePaths()) {
                Path path = Path.of(codebasePath);
                if (!Files.isDirectory(path)) continue;

                String codebaseName = path.getFileName().toString().toLowerCase()
                        .replaceAll("[^a-z0-9]", "");

                if (codebaseName.equals(normalizedName) || codebaseName.equals(normalizedDir)
                        || codebaseName.contains(normalizedName) || normalizedName.contains(codebaseName)) {
                    matched.add(codebasePath);
                }
            }
            return matched;
        }

        /**
         * Layer 3: Discover git repos within org's codebase paths that match the entity name.
         */
        private List<String> discoverFromGitRepos(String normalizedName, Organization org) {
            List<String> discovered = new ArrayList<>();

            for (String codebasePath : org.getCodebasePaths()) {
                Path cbPath = Path.of(codebasePath);
                if (!Files.isDirectory(cbPath)) continue;

                try (var stream = Files.list(cbPath)) {
                    stream.filter(Files::isDirectory)
                            .filter(dir -> Files.isDirectory(dir.resolve(".git")))
                            .forEach(dir -> {
                                String dirNormalized = dir.getFileName().toString()
                                        .toLowerCase().replaceAll("[^a-z0-9]", "");

                                if (dirNormalized.equals(normalizedName)
                                        || dirNormalized.contains(normalizedName)
                                        || normalizedName.contains(dirNormalized)) {
                                    discovered.add(dir.toString());
                                } else {
                                    // Check pom.xml artifactId or package.json name
                                    String pomName = readArtifactId(dir);
                                    if (pomName != null && normalizeName(pomName).equals(normalizedName)) {
                                        discovered.add(dir.toString());
                                        return;
                                    }
                                    String pkgName = readPackageJsonName(dir);
                                    if (pkgName != null && normalizeName(pkgName).equals(normalizedName)) {
                                        discovered.add(dir.toString());
                                    }
                                }
                            });
                } catch (IOException e) {
                    // Ignore errors listing directories
                }
            }
            return discovered;
        }

        /**
         * Layer 4: Check if /src/{normalizedName}/ exists on the filesystem.
         */
        private List<String> discoverFromPathHeuristics(String normalizedName) {
            Path candidate = Path.of("/src", normalizedName);
            if (Files.isDirectory(candidate)) {
                return List.of(candidate.toString());
            }
            return List.of();
        }

        /**
         * Reads the artifactId from a pom.xml file using simple string search.
         */
        static String readArtifactId(Path dir) {
            Path pomFile = dir.resolve("pom.xml");
            if (!Files.exists(pomFile)) return null;
            try {
                String content = Files.readString(pomFile);
                // Find first <artifactId> that is NOT inside a <parent> block
                int parentEnd = content.indexOf("</parent>");
                int searchStart = parentEnd > 0 ? parentEnd : 0;
                int idx = content.indexOf("<artifactId>", searchStart);
                if (idx < 0) return null;
                int start = idx + "<artifactId>".length();
                int end = content.indexOf("</artifactId>", start);
                if (end < 0) return null;
                return content.substring(start, end).trim();
            } catch (IOException e) {
                return null;
            }
        }

        /**
         * Reads the "name" field from a package.json file using simple string search.
         */
        static String readPackageJsonName(Path dir) {
            Path pkgFile = dir.resolve("package.json");
            if (!Files.exists(pkgFile)) return null;
            try {
                String content = Files.readString(pkgFile);
                int idx = content.indexOf("\"name\"");
                if (idx < 0) return null;
                int colonIdx = content.indexOf(':', idx);
                if (colonIdx < 0) return null;
                int quoteStart = content.indexOf('"', colonIdx + 1);
                if (quoteStart < 0) return null;
                int quoteEnd = content.indexOf('"', quoteStart + 1);
                if (quoteEnd < 0) return null;
                String name = content.substring(quoteStart + 1, quoteEnd);
                // Strip scope prefix (e.g., @org/name -> name)
                if (name.contains("/")) {
                    name = name.substring(name.lastIndexOf('/') + 1);
                }
                return name;
            } catch (IOException e) {
                return null;
            }
        }

        /**
         * Normalizes a name for fuzzy matching.
         */
        static String normalizeName(String name) {
            return name.toLowerCase().replaceAll("[^a-z0-9]", "");
        }

        /**
         * Applies a proposal to the registry by updating the client or product codebases.
         */
        private void applyProposal(OrganizationRegistry registry, EnrichProposal proposal) {
            for (Organization org : registry.getOrganizations()) {
                if (!org.getName().equals(proposal.orgName)) continue;

                // Try to apply to client
                for (Client client : org.getClients()) {
                    if (client.getName().equals(proposal.entityName)) {
                        List<String> existing = new ArrayList<>(client.getCodebases());
                        for (String path : proposal.paths) {
                            if (!existing.contains(path)) {
                                existing.add(path);
                            }
                        }
                        client.setCodebases(existing);
                        return;
                    }
                }

                // Try to apply to org's codebasePaths (for products)
                for (Product product : org.getProducts()) {
                    if (product.getName().equals(proposal.entityName)) {
                        for (String path : proposal.paths) {
                            org.addCodebasePath(path);
                        }
                        return;
                    }
                }
            }
        }

        /**
         * A proposal to add codebase paths to an entity.
         */
        static class EnrichProposal {
            final String entityName;
            final String orgName;
            final List<String> paths;
            final double confidence;
            final String source;

            EnrichProposal(String entityName, String orgName, List<String> paths,
                          double confidence, String source) {
                this.entityName = entityName;
                this.orgName = orgName;
                this.paths = List.copyOf(paths);
                this.confidence = confidence;
                this.source = source;
            }
        }
    }
}
