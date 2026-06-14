package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.ai.AiProvider;
import io.exoreaction.synthesis.changelog.*;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.core.ScanState;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.search.WorkspaceDiscoveryConfig;
import io.exoreaction.synthesis.summary.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import io.exoreaction.synthesis.workspace.WorkspaceMetadata;
import io.exoreaction.synthesis.workspace.WorkspaceType;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Interactive workspace navigator dashboard with organization-based navigation.
 *
 * <p>Provides a tree-based navigation UI organized around the WBS structure
 * (organizations, clients, products) loaded from OrganizationRegistry, with
 * fallback to raw workspace navigation when no org data is available.
 *
 * <p>Navigation model:
 * <pre>
 *   [Top Level]        Organizations + non-staging workspaces
 *     -> [Org Level]   Clients (by status) + Products
 *       -> [Client]    Client detail + context-aware actions
 *       -> [Product]   Product detail + context-aware actions
 *     -> [Workspace]   Single workspace detail + sub-workspaces
 *       -> [Sub-ws]    Drilled into a sub-workspace
 * </pre>
 *
 * <p>Usage:
 * <pre>
 *   synthesis dashboard       # Launch interactive navigator
 *   exo                       # Same (via wrapper script)
 * </pre>
 *
 * @since v1.7.1
 */
@Command(
        name = "dashboard",
        description = "Interactive workspace navigator with context-aware actions",
        mixinStandardHelpOptions = true
)
public class DashboardCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    /** Discovered workspace info for navigation. */
    private static class WorkspaceInfo {
        Path path;
        String name;
        WorkspaceType workspaceType = WorkspaceType.MIXED;
        String primaryLanguage;
        String company;
        int repoCount;
        boolean indexed;
        int fileCount;
        long indexSize;
        String lastScanFormatted;
        List<SubWorkspaceConfig> subWorkspaces = new ArrayList<>();
        Map<String, Long> subWorkspaceCounts = new HashMap<>();
    }

    /** Navigation states for the main loop. */
    private enum NavState {
        ORG_TOP_LEVEL,
        WORKSPACE_TOP_LEVEL,
        ORG_LEVEL,
        CLIENT_LEVEL,
        PRODUCT_LEVEL,
        WORKSPACE_LEVEL,
        SUB_WORKSPACE_LEVEL
    }

    @Override
    public Integer call() {
        try {
            List<WorkspaceInfo> allWorkspaces = discoverWorkspaces();

            if (allWorkspaces.isEmpty()) {
                AnsiOutput.printError("No Synthesis workspaces found.");
                AnsiOutput.printInfo("Run 'synthesis init' in a directory to create a workspace.");
                return 1;
            }

            // Filter out staging workspaces for display
            List<WorkspaceInfo> displayWorkspaces = allWorkspaces.stream()
                    .filter(ws -> ws.workspaceType != WorkspaceType.STAGING)
                    .toList();

            // Try to load organization registry
            OrganizationRegistry registry = loadOrgRegistry(allWorkspaces);
            boolean hasOrgs = registry != null && registry.hasOrganizations();

            // Navigation state
            NavState navState = hasOrgs ? NavState.ORG_TOP_LEVEL : NavState.WORKSPACE_TOP_LEVEL;
            WorkspaceInfo selectedWorkspace = null;
            SubWorkspaceConfig selectedSubWorkspace = null;
            Organization selectedOrg = null;
            Client selectedClient = null;
            Product selectedProduct = null;
            Scanner scanner = new Scanner(System.in);

            // Main interaction loop
            while (true) {
                switch (navState) {
                    case ORG_TOP_LEVEL -> {
                        List<Organization> orgs = registry.getOrganizations().stream()
                                .filter(o -> o.getType() != OrganizationType.HOLDING)
                                .toList();
                        printOrgTopLevel(orgs, displayWorkspaces);
                        String input = readInput(scanner);
                        if (input == null || "q".equals(input)) return 0;

                        switch (input) {
                            case "1" -> runQuickStatusAll(displayWorkspaces);
                            case "2" -> runWhatChangedAll(displayWorkspaces);
                            case "r" -> runSynthesisReport();
                            case "d" -> runSynthesisReport("--topic", "decisions");
                            case "p" -> runSynthesisReport("--topic", "pipeline");
                            case "w" -> navState = NavState.WORKSPACE_TOP_LEVEL;
                            default -> {
                                // Try org selection (numbered starting at 3)
                                int idx = parseIndex(input, 3, orgs.size());
                                if (idx >= 0) {
                                    selectedOrg = orgs.get(idx);
                                    navState = NavState.ORG_LEVEL;
                                } else {
                                    AnsiOutput.printWarning("Invalid choice. Enter a number, 'w' for workspaces, or 'q' to quit.");
                                }
                            }
                        }
                    }

                    case WORKSPACE_TOP_LEVEL -> {
                        printTopLevel(displayWorkspaces, hasOrgs);
                        String input = readInput(scanner);
                        if (input == null || "q".equals(input)) return 0;

                        if (hasOrgs && "b".equals(input)) {
                            navState = NavState.ORG_TOP_LEVEL;
                        } else if ("1".equals(input)) {
                            runQuickStatusAll(displayWorkspaces);
                        } else if ("2".equals(input)) {
                            runWhatChangedAll(displayWorkspaces);
                        } else {
                            int idx = parseIndex(input, 3, displayWorkspaces.size());
                            if (idx >= 0) {
                                selectedWorkspace = displayWorkspaces.get(idx);
                                navState = NavState.WORKSPACE_LEVEL;
                            } else {
                                AnsiOutput.printWarning("Invalid choice. Enter a number or 'q' to quit.");
                            }
                        }
                    }

                    case ORG_LEVEL -> {
                        printOrgLevel(selectedOrg, allWorkspaces);
                        String input = readInput(scanner);
                        if (input == null || "q".equals(input)) return 0;

                        if ("b".equals(input)) {
                            selectedOrg = null;
                            navState = NavState.ORG_TOP_LEVEL;
                        } else if ("s".equals(input)) {
                            // Summary for org workspace
                            WorkspaceInfo orgWs = findWorkspaceForPath(Path.of(selectedOrg.getBasePath()), allWorkspaces);
                            if (orgWs != null) {
                                runSummary(orgWs);
                            } else {
                                AnsiOutput.printWarning("No Synthesis index found for " + selectedOrg.getName() + ".");
                                pressEnterToContinue();
                            }
                        } else if ("d".equals(input)) {
                            // Business summary for org
                            runOrgBusinessSummary(selectedOrg, scanner);
                        } else if ("c".equals(input)) {
                            // What changed for org workspace
                            WorkspaceInfo orgWs = findWorkspaceForPath(Path.of(selectedOrg.getBasePath()), allWorkspaces);
                            if (orgWs != null) {
                                runWhatChanged(orgWs);
                            } else {
                                AnsiOutput.printWarning("No Synthesis index found for " + selectedOrg.getName() + ".");
                                pressEnterToContinue();
                            }
                        } else {
                            // Parse client or product selection
                            // Clients are numbered starting at 1, products prefixed with 'p'
                            if (input.startsWith("p")) {
                                // Product selection
                                try {
                                    int pIdx = Integer.parseInt(input.substring(1)) - 1;
                                    if (pIdx >= 0 && pIdx < selectedOrg.getProducts().size()) {
                                        selectedProduct = selectedOrg.getProducts().get(pIdx);
                                        navState = NavState.PRODUCT_LEVEL;
                                    } else {
                                        AnsiOutput.printWarning("Invalid product number.");
                                    }
                                } catch (NumberFormatException e) {
                                    AnsiOutput.printWarning("Invalid input. Use p1, p2, etc. for products.");
                                }
                            } else {
                                // Client selection by number
                                List<Client> allClients = buildOrderedClientList(selectedOrg);
                                int cIdx = parseIndex(input, 1, allClients.size());
                                if (cIdx >= 0) {
                                    selectedClient = allClients.get(cIdx);
                                    navState = NavState.CLIENT_LEVEL;
                                } else {
                                    AnsiOutput.printWarning("Invalid choice. Enter a client number, p# for products, 's'/'c' for actions, or 'b'.");
                                }
                            }
                        }
                    }

                    case CLIENT_LEVEL -> {
                        printClientLevel(selectedOrg, selectedClient, allWorkspaces);
                        String input = readInput(scanner);
                        if (input == null || "q".equals(input)) return 0;

                        if ("b".equals(input)) {
                            selectedClient = null;
                            navState = NavState.ORG_LEVEL;
                        } else if ("o".equals(input)) {
                            openFolder(selectedClient.resolvedPath());
                        } else if ("s".equals(input)) {
                            runClientSummary(selectedClient, allWorkspaces, scanner);
                        } else if ("4".equals(input)) {
                            runSynthesisReport("--client", selectedClient.getName());
                        } else {
                            WorkspaceInfo clientWs = findWorkspaceForPath(selectedClient.resolvedPath(), allWorkspaces);
                            if (clientWs != null && clientWs.indexed) {
                                switch (input) {
                                    case "1" -> runSearchInWorkspace(clientWs, scanner);
                                    case "2" -> runSummary(clientWs);
                                    case "3" -> runWhatChanged(clientWs);
                                    default -> AnsiOutput.printWarning("Invalid choice.");
                                }
                            } else {
                                AnsiOutput.printWarning("Invalid choice. Enter 's' for summary, 'o' to open, 'b' for back, or 'q' to quit.");
                            }
                        }
                    }

                    case PRODUCT_LEVEL -> {
                        printProductLevel(selectedOrg, selectedProduct, allWorkspaces);
                        String input = readInput(scanner);
                        if (input == null || "q".equals(input)) return 0;

                        if ("b".equals(input)) {
                            selectedProduct = null;
                            navState = NavState.ORG_LEVEL;
                        } else if ("o".equals(input)) {
                            openFolder(selectedProduct.resolvedPath());
                        } else if ("s".equals(input)) {
                            runProductSummary(selectedProduct, scanner);
                        } else if ("4".equals(input)) {
                            runSynthesisReport("--product", selectedProduct.getName());
                        } else {
                            WorkspaceInfo prodWs = findWorkspaceForPath(selectedProduct.resolvedPath(), allWorkspaces);
                            if (prodWs != null && prodWs.indexed) {
                                switch (input) {
                                    case "1" -> runSearchInWorkspace(prodWs, scanner);
                                    case "2" -> runSummary(prodWs);
                                    case "3" -> runWhatChanged(prodWs);
                                    default -> AnsiOutput.printWarning("Invalid choice.");
                                }
                            } else {
                                AnsiOutput.printWarning("Invalid choice. Enter 's' for summary, 'o' to open, 'b' for back, or 'q' to quit.");
                            }
                        }
                    }

                    case WORKSPACE_LEVEL -> {
                        printWorkspaceLevel(selectedWorkspace);
                        String input = readInput(scanner);
                        if (input == null || "q".equals(input)) return 0;

                        switch (input) {
                            case "b" -> {
                                selectedWorkspace = null;
                                navState = hasOrgs ? NavState.ORG_TOP_LEVEL : NavState.WORKSPACE_TOP_LEVEL;
                            }
                            case "1" -> runSummary(selectedWorkspace);
                            case "2" -> runWhatChanged(selectedWorkspace);
                            case "3" -> runFullStatus(selectedWorkspace);
                            case "4" -> runChangesLastMonth(selectedWorkspace);
                            case "5" -> {
                                if (selectedWorkspace.subWorkspaces.isEmpty()) {
                                    AnsiOutput.printWarning("No sub-workspaces configured for this workspace.");
                                } else {
                                    printSubWorkspaceList(selectedWorkspace);
                                    String subInput = readInput(scanner);
                                    if (subInput != null && !"b".equals(subInput) && !"q".equals(subInput)) {
                                        int idx = parseIndex(subInput, 1, selectedWorkspace.subWorkspaces.size());
                                        if (idx >= 0) {
                                            selectedSubWorkspace = selectedWorkspace.subWorkspaces.get(idx);
                                            navState = NavState.SUB_WORKSPACE_LEVEL;
                                        } else {
                                            AnsiOutput.printWarning("Invalid sub-workspace number.");
                                        }
                                    }
                                    if ("q".equals(subInput)) return 0;
                                }
                            }
                            case "6" -> runArchitectureOverview(selectedWorkspace);
                            case "s" -> runSaveReport(selectedWorkspace);
                            default -> AnsiOutput.printWarning(
                                    "Invalid choice. Enter a number, 'b' for back, or 'q' to quit.");
                        }
                    }

                    case SUB_WORKSPACE_LEVEL -> {
                        printSubWorkspaceLevel(selectedWorkspace, selectedSubWorkspace);
                        String input = readInput(scanner);
                        if (input == null || "q".equals(input)) return 0;

                        switch (input) {
                            case "b" -> {
                                selectedSubWorkspace = null;
                                navState = NavState.WORKSPACE_LEVEL;
                            }
                            case "1" -> runSearchInSubWorkspace(selectedWorkspace, selectedSubWorkspace, scanner);
                            case "2" -> runSummary(selectedWorkspace);
                            case "3" -> runWhatChanged(selectedWorkspace);
                            default -> AnsiOutput.printWarning(
                                    "Invalid choice. Enter a number, 'b' for back, or 'q' to quit.");
                        }
                    }
                }
            }

        } catch (Exception e) {
            AnsiOutput.printError("Dashboard error: " + e.getMessage());
            return 1;
        }
    }

    // ===============================================================
    //  Organization Registry Loading
    // ===============================================================

    /**
     * Loads the OrganizationRegistry by searching discovered workspaces
     * and common locations for organizations.json.
     */
    private OrganizationRegistry loadOrgRegistry(List<WorkspaceInfo> workspaces) {
        // Try each discovered workspace root for organizations.json
        for (WorkspaceInfo ws : workspaces) {
            Path orgsFile = ws.path.resolve(".synthesis").resolve("organizations.json");
            if (Files.exists(orgsFile)) {
                try {
                    OrganizationRegistry registry = new OrganizationRegistry(ws.path);
                    registry.load();
                    if (registry.hasOrganizations()) return registry;
                } catch (Exception ignored) {}
            }
        }
        // Also try ~/Documents directly
        Path docsPath = Path.of(System.getProperty("user.home"), "Documents");
        Path orgsFile = docsPath.resolve(".synthesis").resolve("organizations.json");
        if (Files.exists(orgsFile)) {
            try {
                OrganizationRegistry registry = new OrganizationRegistry(docsPath);
                registry.load();
                if (registry.hasOrganizations()) return registry;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ===============================================================
    //  Screen Rendering
    // ===============================================================

    private void printBanner(String... breadcrumbs) {
        System.out.println();
        String line = "════════════════════════════════════════════";
        System.out.println("  " + AnsiOutput.bold(AnsiOutput.blue(line)));
        System.out.println("  " + AnsiOutput.bold(AnsiOutput.blue("  Synthesis Dashboard")));
        if (breadcrumbs.length > 0) {
            System.out.println("  " + AnsiOutput.bold(AnsiOutput.blue("  > "))
                    + AnsiOutput.cyan(String.join(" > ", breadcrumbs)));
        }
        System.out.println("  " + AnsiOutput.bold(AnsiOutput.blue(line)));
        System.out.println();
    }

    /**
     * Organization-based top-level screen: shows orgs first, workspaces as secondary.
     */
    private void printOrgTopLevel(List<Organization> orgs, List<WorkspaceInfo> workspaces) {
        printBanner();

        // Aggregate stats from workspaces
        long totalFiles = 0;
        long totalIndexSize = 0;
        int indexedCount = 0;
        for (WorkspaceInfo ws : workspaces) {
            if (ws.indexed) {
                indexedCount++;
                totalFiles += ws.fileCount;
                totalIndexSize += ws.indexSize;
            }
        }

        System.out.println("  " + AnsiOutput.bold("System Overview"));
        System.out.printf("  %-20s %s organizations, %s workspaces (%d indexed)%n",
                "Scope:",
                AnsiOutput.bold(String.valueOf(orgs.size())),
                AnsiOutput.bold(String.valueOf(workspaces.size())),
                indexedCount);
        System.out.printf("  %-20s %s%n",
                "Total Files:", AnsiOutput.bold(String.format("%,d", totalFiles)));
        System.out.printf("  %-20s %s%n",
                "Total Index:", FileUtils.formatSize(totalIndexSize));
        System.out.println();

        // List organizations
        System.out.println("  " + AnsiOutput.bold("Organizations:"));
        for (int i = 0; i < orgs.size(); i++) {
            Organization org = orgs.get(i);
            String orgTypeBadge = orgTypeBadge(org.getType());
            int clientCount = org.getClients().size();
            int productCount = org.getProducts().size();

            List<String> details = new ArrayList<>();
            if (clientCount > 0) {
                long activeCount = org.getClientsByStatus(ClientStatus.ACTIVE).size();
                long opportunityCount = org.getClientsByStatus(ClientStatus.OPPORTUNITY).size();
                List<String> parts = new ArrayList<>();
                if (activeCount > 0) parts.add(activeCount + " active");
                if (opportunityCount > 0) parts.add(opportunityCount + " prospects");
                details.add(clientCount + " clients" + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")"));
            }
            if (productCount > 0) {
                details.add(productCount + " products");
            }
            String detailStr = details.isEmpty() ? "" : AnsiOutput.dim("  " + String.join(", ", details));

            System.out.printf("  %s) %s %s%s%n",
                    AnsiOutput.bold(String.valueOf(i + 3)),
                    orgTypeBadge,
                    AnsiOutput.bold(org.getName()),
                    detailStr);
        }

        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Actions:"));
        System.out.println("    " + AnsiOutput.bold("1") + ") Quick Status (all workspaces)");
        System.out.println("    " + AnsiOutput.bold("2") + ") What Changed (all workspaces, last 7d)");
        for (int i = 0; i < orgs.size(); i++) {
            System.out.println("    " + AnsiOutput.bold(String.valueOf(i + 3))
                    + ") Navigate into " + AnsiOutput.cyan(orgs.get(i).getName()));
        }
        System.out.println();
        System.out.println("  " + AnsiOutput.cyan("  ── AI Reports ─────────────────────────"));
        System.out.println("    " + AnsiOutput.bold("r") + ") " + AnsiOutput.cyan("[AI]") + " Weekly CEO briefing");
        System.out.println("    " + AnsiOutput.bold("d") + ") " + AnsiOutput.cyan("[AI]") + " Critical decisions");
        System.out.println("    " + AnsiOutput.bold("p") + ") " + AnsiOutput.cyan("[AI]") + " Pipeline status");
        System.out.println();
        System.out.println("    " + AnsiOutput.bold("w") + ") Raw workspace view");
        System.out.println("    " + AnsiOutput.bold("q") + ") Quit");
        System.out.println();
    }

    /**
     * Workspace-based top-level screen (fallback or when 'w' pressed from org top).
     * Filters out staging workspaces.
     */
    private void printTopLevel(List<WorkspaceInfo> workspaces, boolean hasOrgView) {
        printBanner("Workspaces");

        // Aggregate stats
        long totalFiles = 0;
        long totalIndexSize = 0;
        int indexedCount = 0;
        for (WorkspaceInfo ws : workspaces) {
            if (ws.indexed) {
                indexedCount++;
                totalFiles += ws.fileCount;
                totalIndexSize += ws.indexSize;
            }
        }

        System.out.println("  " + AnsiOutput.bold("System Overview"));
        System.out.printf("  %-20s %s workspaces (%d indexed)%n",
                "Workspaces:", AnsiOutput.bold(String.valueOf(workspaces.size())), indexedCount);
        System.out.printf("  %-20s %s%n",
                "Total Files:", AnsiOutput.bold(String.format("%,d", totalFiles)));
        System.out.printf("  %-20s %s%n",
                "Total Index:", FileUtils.formatSize(totalIndexSize));
        System.out.println();

        // List workspaces
        System.out.println("  " + AnsiOutput.bold("Workspaces:"));
        for (int i = 0; i < workspaces.size(); i++) {
            WorkspaceInfo ws = workspaces.get(i);
            String typeBadge = typeBadge(ws.workspaceType);
            String fileInfo = ws.indexed
                    ? String.format("%,d files", ws.fileCount)
                    : AnsiOutput.dim("not indexed");

            List<String> tags = new ArrayList<>();
            if (ws.primaryLanguage != null) tags.add(AnsiOutput.cyan(ws.primaryLanguage));
            if (ws.company != null) tags.add(ws.company);
            String tagStr = tags.isEmpty() ? "" : AnsiOutput.dim(" (" + String.join(", ", tags) + ")");

            System.out.printf("  %s) %s %s%s  %s  %s%n",
                    AnsiOutput.bold(String.valueOf(i + 3)),
                    typeBadge,
                    AnsiOutput.bold(ws.name),
                    tagStr,
                    AnsiOutput.dim("-"),
                    fileInfo);
        }

        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Actions:"));
        System.out.println("    " + AnsiOutput.bold("1") + ") Quick Status (all workspaces)");
        System.out.println("    " + AnsiOutput.bold("2") + ") What Changed (all workspaces, last 7d)");
        for (int i = 0; i < workspaces.size(); i++) {
            System.out.println("    " + AnsiOutput.bold(String.valueOf(i + 3))
                    + ") Navigate into " + AnsiOutput.cyan(workspaces.get(i).name));
        }
        if (hasOrgView) {
            System.out.println("    " + AnsiOutput.bold("b") + ") Back to organizations");
        }
        System.out.println("    " + AnsiOutput.bold("q") + ") Quit");
        System.out.println();
    }

    /**
     * Organization-level screen: shows clients grouped by status + products.
     */
    private void printOrgLevel(Organization org, List<WorkspaceInfo> allWorkspaces) {
        printBanner(org.getName());

        String orgTypeBadge = orgTypeBadge(org.getType());
        System.out.println("  " + orgTypeBadge + " " + AnsiOutput.bold(org.getName()));
        System.out.printf("  %-20s %s%n", "Path:", AnsiOutput.dim(org.getBasePath()));
        if (org.getDescription() != null && !org.getDescription().isBlank()) {
            System.out.printf("  %-20s %s%n", "Description:", org.getDescription());
        }

        // Check if this org has a synthesis index
        WorkspaceInfo orgWs = findWorkspaceForPath(Path.of(org.getBasePath()), allWorkspaces);
        if (orgWs != null && orgWs.indexed) {
            System.out.printf("  %-20s %s (%s files)%n",
                    "Index:", AnsiOutput.success("Active"),
                    String.format("%,d", orgWs.fileCount));
        }

        // Client counts
        int totalClients = org.getClients().size();
        int totalProducts = org.getProducts().size();
        System.out.printf("  %-20s %d clients, %d products%n", "Contents:", totalClients, totalProducts);
        System.out.println();

        // Build ordered client list for numbering
        List<Client> orderedClients = buildOrderedClientList(org);

        // Show clients grouped by status
        if (!orderedClients.isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("Clients:"));

            ClientStatus currentStatus = null;
            int clientNum = 1;
            for (Client client : orderedClients) {
                if (client.getStatus() != currentStatus) {
                    currentStatus = client.getStatus();
                    System.out.println("    " + clientStatusHeader(currentStatus));
                }
                System.out.printf("    %s) %s %s%n",
                        AnsiOutput.bold(String.valueOf(clientNum)),
                        clientStatusBadge(client.getStatus()),
                        client.getName());
                clientNum++;
            }
            System.out.println();
        }

        // Show products
        if (!org.getProducts().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("Products:"));
            for (int i = 0; i < org.getProducts().size(); i++) {
                Product prod = org.getProducts().get(i);
                System.out.printf("    %s) %s%n",
                        AnsiOutput.bold("p" + (i + 1)),
                        AnsiOutput.cyan(prod.getName()));
            }
            System.out.println();
        }

        // Actions
        System.out.println("  " + AnsiOutput.bold("Actions:"));
        if (!orderedClients.isEmpty()) {
            System.out.println("    " + AnsiOutput.bold("1-" + orderedClients.size())
                    + ") Navigate to client");
        }
        if (!org.getProducts().isEmpty()) {
            System.out.println("    " + AnsiOutput.bold("p1-p" + org.getProducts().size())
                    + ") Navigate to product");
        }
        if (orgWs != null && orgWs.indexed) {
            System.out.println("    " + AnsiOutput.bold("s") + ") Summary (org workspace)");
            System.out.println("    " + AnsiOutput.bold("c") + ") What Changed (org workspace)");
        }
        System.out.println("    " + AnsiOutput.bold("d") + ") Business summary -- " + AnsiOutput.cyan(org.getName()));
        System.out.println("    " + AnsiOutput.bold("b") + ") Back");
        System.out.println("    " + AnsiOutput.bold("q") + ") Quit");
        System.out.println();
    }

    /**
     * Client-level detail screen with context-aware actions.
     */
    private void printClientLevel(Organization org, Client client, List<WorkspaceInfo> allWorkspaces) {
        printBanner(org.getName(), client.getName());

        System.out.println("  " + clientStatusBadge(client.getStatus()) + " " + AnsiOutput.bold(client.getName()));
        System.out.printf("  %-20s %s%n", "Organization:", org.getName());
        System.out.printf("  %-20s %s%n", "Status:", clientStatusLabel(client.getStatus()));
        System.out.printf("  %-20s %s%n", "Path:", AnsiOutput.dim(client.getBasePath()));

        // Check for codebases
        if (!client.getCodebases().isEmpty()) {
            System.out.printf("  %-20s %s%n", "Codebases:",
                    AnsiOutput.dim(String.join(", ", client.getCodebases())));
        }

        // Check if client path has a synthesis index
        WorkspaceInfo clientWs = findWorkspaceForPath(client.resolvedPath(), allWorkspaces);
        boolean hasIndex = clientWs != null && clientWs.indexed;

        if (hasIndex) {
            System.out.printf("  %-20s %s (%s files)%n",
                    "Index:", AnsiOutput.success("Active"),
                    String.format("%,d", clientWs.fileCount));
        }

        // Check if directory exists and has content
        Path clientPath = client.resolvedPath();
        if (Files.isDirectory(clientPath)) {
            try (Stream<Path> entries = Files.list(clientPath)) {
                long fileCount = entries.count();
                System.out.printf("  %-20s %d items%n", "Directory:", fileCount);
            } catch (IOException ignored) {}
        } else {
            System.out.printf("  %-20s %s%n", "Directory:", AnsiOutput.warning("Not found"));
        }

        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Actions:"));
        System.out.println("    " + AnsiOutput.bold("s") + ") Client Summary (business context + code activity)");
        if (hasIndex) {
            System.out.println("    " + AnsiOutput.bold("1") + ") Search here");
            System.out.println("    " + AnsiOutput.bold("2") + ") Codebase Profile");
            System.out.println("    " + AnsiOutput.bold("3") + ") What Changed");
        }
        System.out.println("    " + AnsiOutput.bold("4") + ") " + AnsiOutput.cyan("[AI]") + " Client Report");
        System.out.println("    " + AnsiOutput.bold("o") + ") Open folder");
        System.out.println("    " + AnsiOutput.bold("b") + ") Back");
        System.out.println("    " + AnsiOutput.bold("q") + ") Quit");
        System.out.println();
    }

    /**
     * Product-level detail screen with context-aware actions.
     */
    private void printProductLevel(Organization org, Product product, List<WorkspaceInfo> allWorkspaces) {
        printBanner(org.getName(), product.getName());

        System.out.println("  " + AnsiOutput.cyan("[product]") + " " + AnsiOutput.bold(product.getName()));
        System.out.printf("  %-20s %s%n", "Organization:", org.getName());
        System.out.printf("  %-20s %s%n", "Path:", AnsiOutput.dim(product.getBasePath()));

        // Check if product path has a synthesis index
        WorkspaceInfo prodWs = findWorkspaceForPath(product.resolvedPath(), allWorkspaces);
        boolean hasIndex = prodWs != null && prodWs.indexed;

        if (hasIndex) {
            System.out.printf("  %-20s %s (%s files)%n",
                    "Index:", AnsiOutput.success("Active"),
                    String.format("%,d", prodWs.fileCount));
        }

        // Check if directory exists and has content
        Path prodPath = product.resolvedPath();
        if (Files.isDirectory(prodPath)) {
            try (Stream<Path> entries = Files.list(prodPath)) {
                long fileCount = entries.count();
                System.out.printf("  %-20s %d items%n", "Directory:", fileCount);
            } catch (IOException ignored) {}
        } else {
            System.out.printf("  %-20s %s%n", "Directory:", AnsiOutput.warning("Not found"));
        }

        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Actions:"));
        System.out.println("    " + AnsiOutput.bold("s") + ") Product Summary (overview + code activity)");
        if (hasIndex) {
            System.out.println("    " + AnsiOutput.bold("1") + ") Search here");
            System.out.println("    " + AnsiOutput.bold("2") + ") Codebase Profile");
            System.out.println("    " + AnsiOutput.bold("3") + ") What Changed");
        }
        System.out.println("    " + AnsiOutput.bold("4") + ") " + AnsiOutput.cyan("[AI]") + " Product Report");
        System.out.println("    " + AnsiOutput.bold("o") + ") Open folder");
        System.out.println("    " + AnsiOutput.bold("b") + ") Back");
        System.out.println("    " + AnsiOutput.bold("q") + ") Quit");
        System.out.println();
    }

    /**
     * Workspace-level screen: detail view for a single workspace.
     */
    private void printWorkspaceLevel(WorkspaceInfo ws) {
        printBanner(ws.name);

        String typeBadge = typeBadge(ws.workspaceType);
        System.out.println("  " + typeBadge + " " + AnsiOutput.bold(ws.name));
        System.out.printf("  %-20s %s%n", "Path:", AnsiOutput.dim(ws.path.toString()));
        if (ws.company != null) {
            System.out.printf("  %-20s %s%n", "Company:", ws.company);
        }
        if (ws.primaryLanguage != null) {
            System.out.printf("  %-20s %s%n", "Language:", AnsiOutput.cyan(ws.primaryLanguage));
        }
        if (ws.repoCount > 0) {
            System.out.printf("  %-20s %d repositories%n", "Scope:", ws.repoCount);
        }

        // Index status
        if (ws.indexed) {
            System.out.printf("  %-20s %s (%s files, %s)%n",
                    "Index:",
                    AnsiOutput.success("Active"),
                    String.format("%,d", ws.fileCount),
                    FileUtils.formatSize(ws.indexSize));
        } else {
            System.out.printf("  %-20s %s%n", "Index:", AnsiOutput.warning("Not built"));
        }

        if (ws.lastScanFormatted != null) {
            System.out.printf("  %-20s %s%n", "Last scan:", ws.lastScanFormatted);
        }

        // Sub-workspaces summary
        if (!ws.subWorkspaces.isEmpty()) {
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Sub-workspaces: ") + ws.subWorkspaces.size());
            int shown = 0;
            for (SubWorkspaceConfig sub : ws.subWorkspaces) {
                if (shown >= 6) {
                    System.out.println("    " + AnsiOutput.dim("+" + (ws.subWorkspaces.size() - shown) + " more..."));
                    break;
                }
                long count = ws.subWorkspaceCounts.getOrDefault(sub.getName(), 0L);
                String countStr = count > 0 ? String.format(" (%,d files)", count) : "";
                System.out.println("    " + AnsiOutput.cyan(sub.getName()) + countStr);
                shown++;
            }
        }

        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Actions:"));
        System.out.println("    " + AnsiOutput.bold("1") + ") Summary (executive overview)");
        System.out.println("    " + AnsiOutput.bold("2") + ") What Changed (last 7 days)");
        System.out.println("    " + AnsiOutput.bold("3") + ") Full Status");
        System.out.println("    " + AnsiOutput.bold("4") + ") Changes Last Month");
        if (!ws.subWorkspaces.isEmpty()) {
            System.out.println("    " + AnsiOutput.bold("5") + ") Navigate sub-workspaces");
        }
        System.out.println("    " + AnsiOutput.bold("6") + ") Architecture Overview");
        System.out.println("    " + AnsiOutput.bold("s") + ") Save Report to file");
        System.out.println("    " + AnsiOutput.bold("b") + ") Back");
        System.out.println("    " + AnsiOutput.bold("q") + ") Quit");
        System.out.println();
    }

    /**
     * Sub-workspace navigation: lists available sub-workspaces for selection.
     */
    private void printSubWorkspaceList(WorkspaceInfo ws) {
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Sub-workspaces in " + ws.name + ":"));
        for (int i = 0; i < ws.subWorkspaces.size(); i++) {
            SubWorkspaceConfig sub = ws.subWorkspaces.get(i);
            long count = ws.subWorkspaceCounts.getOrDefault(sub.getName(), 0L);
            String countStr = count > 0 ? String.format(" (%,d files)", count) : "";
            String desc = (sub.getDescription() != null && !sub.getDescription().isBlank())
                    ? AnsiOutput.dim(" - " + sub.getDescription()) : "";
            System.out.printf("    %s) %s%s%s%n",
                    AnsiOutput.bold(String.valueOf(i + 1)),
                    AnsiOutput.cyan(sub.getName()),
                    countStr,
                    desc);
        }
        System.out.println("    " + AnsiOutput.bold("b") + ") Back");
        System.out.println();
        System.out.print("  Choose sub-workspace: ");
        System.out.flush();
    }

    /**
     * Sub-workspace detail screen.
     */
    private void printSubWorkspaceLevel(WorkspaceInfo ws, SubWorkspaceConfig sub) {
        printBanner(ws.name, sub.getName());

        long count = ws.subWorkspaceCounts.getOrDefault(sub.getName(), 0L);

        System.out.println("  " + AnsiOutput.cyan(sub.getName()));
        System.out.printf("  %-20s %s%n", "Path:", AnsiOutput.dim(sub.getPath()));
        if (sub.getDescription() != null && !sub.getDescription().isBlank()) {
            System.out.printf("  %-20s %s%n", "Description:", sub.getDescription());
        }
        if (count > 0) {
            System.out.printf("  %-20s %s files%n", "Files:", String.format("%,d", count));
        }
        if (!sub.getTags().isEmpty()) {
            System.out.printf("  %-20s %s%n", "Tags:", String.join(", ", sub.getTags()));
        }

        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Actions:"));
        System.out.println("    " + AnsiOutput.bold("1") + ") Search in " + sub.getName());
        System.out.println("    " + AnsiOutput.bold("2") + ") Summary (workspace)");
        System.out.println("    " + AnsiOutput.bold("3") + ") What Changed (workspace)");
        System.out.println("    " + AnsiOutput.bold("b") + ") Back");
        System.out.println("    " + AnsiOutput.bold("q") + ") Quit");
        System.out.println();
    }

    // ===============================================================
    //  Actions
    // ===============================================================

    /**
     * Quick status across all workspaces.
     */
    private void runQuickStatusAll(List<WorkspaceInfo> workspaces) {
        System.out.println();
        AnsiOutput.printHeader("Quick Status - All Workspaces");

        for (WorkspaceInfo ws : workspaces) {
            String typeBadge = typeBadge(ws.workspaceType);
            String status = ws.indexed
                    ? AnsiOutput.success("OK") + " " + String.format("%,d files", ws.fileCount)
                    : AnsiOutput.warning("Not indexed");
            String scanInfo = ws.lastScanFormatted != null
                    ? AnsiOutput.dim(" (scanned " + ws.lastScanFormatted + ")") : "";
            System.out.printf("  %s %-25s %s%s%n", typeBadge, AnsiOutput.bold(ws.name), status, scanInfo);
        }
        System.out.println();
        pressEnterToContinue();
    }

    /**
     * What changed across all workspaces (last 7 days).
     */
    private void runWhatChangedAll(List<WorkspaceInfo> workspaces) {
        System.out.println();
        AnsiOutput.printHeader("Changes - All Workspaces (Last 7 Days)");

        boolean anyChanges = false;
        for (WorkspaceInfo ws : workspaces) {
            try {
                List<ChangeEvent> events = getChanges(ws.path, 7);
                if (!events.isEmpty()) {
                    anyChanges = true;
                    ChangeReportGenerator generator = new ChangeReportGenerator();
                    String summary = generator.generateSummary(events);
                    System.out.println("  " + AnsiOutput.bold(ws.name) + ": " + summary);
                }
            } catch (Exception e) {
                System.out.println("  " + AnsiOutput.bold(ws.name) + ": "
                        + AnsiOutput.dim("No snapshots available"));
            }
        }

        if (!anyChanges) {
            AnsiOutput.printInfo("No changes detected across any workspace in the last 7 days.");
            AnsiOutput.printInfo("Run 'synthesis changelog --snapshot' in each workspace to take snapshots.");
        }

        System.out.println();
        pressEnterToContinue();
    }

    /**
     * Executive summary for a workspace using CodebaseProfile and SummaryRenderer.
     */
    private void runSummary(WorkspaceInfo ws) {
        System.out.println();
        AnsiOutput.printHeader("Summary - " + ws.name);

        if (!ws.indexed) {
            AnsiOutput.printWarning("Workspace not indexed. Run 'synthesis scan -d " + ws.path + "' first.");
            pressEnterToContinue();
            return;
        }

        try {
            Path indexPath = ws.path.resolve(".synthesis").resolve("index");
            CodebaseProfile profiler = new CodebaseProfile();
            CodebaseProfile.Profile profile;
            try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                profile = profiler.generate(index, ws.path);
            }

            SummaryResult result = SummaryResult.fromProfile(
                    profile, SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, 0);

            SummaryRenderer renderer = new SummaryRenderer();
            String output = renderer.renderTerminal(result);
            System.out.println(output);

        } catch (Exception e) {
            AnsiOutput.printError("Summary generation failed: " + e.getMessage());
        }

        pressEnterToContinue();
    }

    /**
     * What changed in a workspace (last 7 days).
     */
    private void runWhatChanged(WorkspaceInfo ws) {
        System.out.println();
        AnsiOutput.printHeader("Changes - " + ws.name + " (Last 7 Days)");
        showChanges(ws, 7);
        pressEnterToContinue();
    }

    /**
     * Full status of a workspace (delegates to StatusCommand-style output).
     */
    private void runFullStatus(WorkspaceInfo ws) {
        System.out.println();
        AnsiOutput.printHeader("Full Status - " + ws.name);

        String typeBadge = typeBadge(ws.workspaceType);
        System.out.println("  " + typeBadge + " " + AnsiOutput.bold(ws.name));
        System.out.printf("  %-20s %s%n", "Root:", ws.path);
        if (ws.company != null) {
            System.out.printf("  %-20s %s%n", "Company:", ws.company);
        }
        if (ws.primaryLanguage != null) {
            System.out.printf("  %-20s %s%n", "Language:", AnsiOutput.cyan(ws.primaryLanguage));
        }
        if (ws.repoCount > 0) {
            System.out.printf("  %-20s %d repositories%n", "Scope:", ws.repoCount);
        }
        System.out.println();

        if (ws.indexed) {
            System.out.printf("  %-20s %s%n", "Index status:", AnsiOutput.success("Active"));
            System.out.printf("  %-20s %s%n", "Documents indexed:",
                    AnsiOutput.bold(String.format("%,d", ws.fileCount)));
            System.out.printf("  %-20s %s%n", "Index size:", FileUtils.formatSize(ws.indexSize));
        } else {
            System.out.printf("  %-20s %s%n", "Index status:", AnsiOutput.warning("Not built"));
            System.out.println("  Run " + AnsiOutput.cyan("synthesis scan -d " + ws.path) + " to build the index.");
        }

        if (ws.lastScanFormatted != null) {
            System.out.printf("  %-20s %s%n", "Last scan:", ws.lastScanFormatted);
        }

        // Sub-workspace tree
        if (!ws.subWorkspaces.isEmpty() && !ws.subWorkspaceCounts.isEmpty()) {
            long total = ws.subWorkspaceCounts.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                SubWorkspaceTreeRenderer.render(ws.subWorkspaces, ws.subWorkspaceCounts, total);
            }
        }

        // Media stats
        if (ws.indexed) {
            try {
                Path indexPath = ws.path.resolve(".synthesis").resolve("index");
                try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                    showMediaStats(index);
                }
            } catch (Exception ignored) {
                // Media stats are informational
            }
        }

        System.out.println();
        System.out.println("  " + AnsiOutput.dim("For full details: synthesis status -d " + ws.path));
        System.out.println();
        pressEnterToContinue();
    }

    /**
     * Changes in the last 30 days.
     */
    private void runChangesLastMonth(WorkspaceInfo ws) {
        System.out.println();
        AnsiOutput.printHeader("Changes - " + ws.name + " (Last 30 Days)");
        showChanges(ws, 30);
        pressEnterToContinue();
    }

    /**
     * Architecture overview using CodebaseProfile metrics.
     */
    private void runArchitectureOverview(WorkspaceInfo ws) {
        System.out.println();
        AnsiOutput.printHeader("Architecture Overview - " + ws.name);

        if (!ws.indexed) {
            AnsiOutput.printWarning("Workspace not indexed. Run 'synthesis scan -d " + ws.path + "' first.");
            pressEnterToContinue();
            return;
        }

        try {
            Path indexPath = ws.path.resolve(".synthesis").resolve("index");
            CodebaseProfile profiler = new CodebaseProfile();
            CodebaseProfile.Profile profile;
            try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                profile = profiler.generate(index, ws.path);
            }

            SummaryResult result = SummaryResult.fromProfile(
                    profile, SummaryLevel.DEVELOPER, SummaryPerspective.ARCHITECT, 0);

            SummaryRenderer renderer = new SummaryRenderer();
            String output = renderer.renderTerminal(result);
            System.out.println(output);

        } catch (Exception e) {
            AnsiOutput.printError("Architecture overview failed: " + e.getMessage());
        }

        pressEnterToContinue();
    }

    /**
     * Save a summary report to file.
     */
    private void runSaveReport(WorkspaceInfo ws) {
        if (!ws.indexed) {
            AnsiOutput.printWarning("Workspace not indexed. Cannot generate report.");
            pressEnterToContinue();
            return;
        }

        try {
            Path indexPath = ws.path.resolve(".synthesis").resolve("index");
            CodebaseProfile profiler = new CodebaseProfile();
            CodebaseProfile.Profile profile;
            try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                profile = profiler.generate(index, ws.path);
            }

            SummaryResult result = SummaryResult.fromProfile(
                    profile, SummaryLevel.MANAGER, SummaryPerspective.GENERAL, 0);

            SummaryRenderer renderer = new SummaryRenderer();
            String markdown = renderer.renderMarkdown(result);

            String safeName = ws.name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
            String fileName = "synthesis-report-" + safeName + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".md";
            Path reportPath = ws.path.resolve(".synthesis").resolve("reports").resolve(fileName);
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, markdown);

            AnsiOutput.printSuccess("Report saved: " + reportPath);

        } catch (Exception e) {
            AnsiOutput.printError("Failed to save report: " + e.getMessage());
        }

        pressEnterToContinue();
    }

    // ===============================================================
    //  AI Report (delegates to `synthesis report` command)
    // ===============================================================

    /**
     * Runs the {@code synthesis report} command as a subprocess with inherited I/O,
     * so color output streams directly to the terminal.
     *
     * <p>The workspace root is resolved from the parent command's workspace root
     * (typically ~/Documents, where business docs like PIPELINE-STATUS.md live).
     *
     * @param extraArgs additional arguments to pass after {@code synthesis report -d <root>}
     */
    private void runSynthesisReport(String... extraArgs) {
        System.out.println();

        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            List<String> command = new ArrayList<>();
            command.add("synthesis");
            command.add("report");
            command.add("-d");
            command.add(workspaceRoot.toString());
            command.add("-v");
            for (String arg : extraArgs) {
                command.add(arg);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                AnsiOutput.printWarning("Report exited with code " + exitCode);
            }
        } catch (Exception e) {
            AnsiOutput.printError("Failed to run report: " + e.getMessage());
        }

        System.out.println();
        pressEnterToContinue();
    }

    // ===============================================================
    //  Two-Tier Summaries (Client / Product / Org Business)
    // ===============================================================

    /**
     * Two-tier client summary: fast tier (README + git log) shown immediately,
     * then optional AI digest.
     */
    private void runClientSummary(Client client, List<WorkspaceInfo> allWorkspaces, Scanner scanner) {
        System.out.println();
        String statusLabel = clientStatusLabel(client.getStatus());
        AnsiOutput.printHeader(client.getName() + " (" + client.getStatus().name() + ")");

        // --- Fast Tier: README ---
        Path basePath = client.resolvedPath();
        Path readmePath = basePath.resolve("README.md");
        System.out.println("  " + AnsiOutput.bold("[Business Context]"));
        printReadmePreview(readmePath, basePath, 40);
        System.out.println();

        // --- Fast Tier: Code Activity (last 7 days) ---
        if (!client.getCodebases().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("[Code Activity -- Last 7 Days]"));
            for (String codebasePath : client.getCodebases()) {
                Path cbPath = Path.of(codebasePath);
                if (!Files.isDirectory(cbPath)) {
                    System.out.println("  " + AnsiOutput.dim(codebasePath)
                            + "  " + AnsiOutput.warning("(codebase not found)"));
                    continue;
                }
                System.out.println("  " + AnsiOutput.dim(codebasePath));
                List<String> commits = runGitLog(cbPath, 7, 10);
                if (commits.isEmpty()) {
                    System.out.println("    " + AnsiOutput.dim("(no changes in last 7 days)"));
                } else {
                    for (String commit : commits) {
                        System.out.println("    " + commit);
                    }
                }
            }
            System.out.println();
        }

        // --- AI Tier Prompt ---
        System.out.println("  " + AnsiOutput.bold("a") + ") Generate AI digest  "
                + AnsiOutput.dim("(requires API key)"));
        System.out.println("  " + AnsiOutput.dim("[Enter] Back"));
        System.out.println();
        System.out.print("  Choose: ");
        System.out.flush();
        if (scanner.hasNextLine()) {
            String choice = scanner.nextLine().trim().toLowerCase();
            if ("a".equals(choice)) {
                runClientAiDigest(client);
            }
        }
    }

    /**
     * Two-tier product summary: fast tier (README + git log) shown immediately,
     * then optional AI digest.
     */
    private void runProductSummary(Product product, Scanner scanner) {
        System.out.println();
        AnsiOutput.printHeader(product.getName());

        // --- Fast Tier: README ---
        Path basePath = product.resolvedPath();
        Path readmePath = basePath.resolve("README.md");
        System.out.println("  " + AnsiOutput.bold("[Product Overview]"));
        printReadmePreview(readmePath, basePath, 40);
        System.out.println();

        // --- AI Tier Prompt ---
        System.out.println("  " + AnsiOutput.bold("a") + ") Generate AI digest  "
                + AnsiOutput.dim("(requires API key)"));
        System.out.println("  " + AnsiOutput.dim("[Enter] Back"));
        System.out.println();
        System.out.print("  Choose: ");
        System.out.flush();
        if (scanner.hasNextLine()) {
            String choice = scanner.nextLine().trim().toLowerCase();
            if ("a".equals(choice)) {
                runProductAiDigest(product);
            }
        }
    }

    /**
     * Org-level business summary: fast tier showing clients by status with
     * last activity and next actions, then optional AI digest.
     */
    private void runOrgBusinessSummary(Organization org, Scanner scanner) {
        System.out.println();
        AnsiOutput.printHeader(org.getName() + " -- Business Overview");

        List<Client> orderedClients = buildOrderedClientList(org);

        // Group clients by status
        Map<ClientStatus, List<Client>> grouped = new LinkedHashMap<>();
        for (ClientStatus status : List.of(ClientStatus.ACTIVE, ClientStatus.SIGNED,
                ClientStatus.OPPORTUNITY, ClientStatus.PAST)) {
            List<Client> byStatus = org.getClientsByStatus(status);
            if (!byStatus.isEmpty()) {
                grouped.put(status, byStatus);
            }
        }

        // Show each group
        for (Map.Entry<ClientStatus, List<Client>> entry : grouped.entrySet()) {
            ClientStatus status = entry.getKey();
            List<Client> clients = entry.getValue();
            String header = switch (status) {
                case ACTIVE -> "ACTIVE CLIENTS (" + clients.size() + ")";
                case SIGNED -> "SIGNED (" + clients.size() + ")";
                case OPPORTUNITY -> "HOT PIPELINE (" + clients.size() + ")";
                case PAST -> "PAST CLIENTS (" + clients.size() + ")";
            };
            System.out.println("  " + AnsiOutput.bold(header));

            for (Client client : clients) {
                String lastActivity = getLastActivityLabel(client);
                String nextAction = getFirstNextAction(client);
                String statusBadge = switch (status) {
                    case ACTIVE -> "";
                    case SIGNED -> AnsiOutput.blue("(SIGNED)") + "  ";
                    case OPPORTUNITY -> AnsiOutput.yellow("(OPPORTUNITY)") + "  ";
                    case PAST -> AnsiOutput.dim("(PAST)") + "  ";
                };
                System.out.printf("    %-18s %s-- last activity: %s%n",
                        AnsiOutput.bold(client.getName()),
                        statusBadge,
                        lastActivity);
                if (nextAction != null) {
                    System.out.println("                     " + AnsiOutput.dim("next: " + nextAction));
                }
            }
            System.out.println();
        }

        // Show products
        if (!org.getProducts().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("PRODUCTS"));
            for (Product product : org.getProducts()) {
                System.out.println("    " + AnsiOutput.cyan(product.getName()));
            }
            System.out.println();
        }

        // --- AI Tier Prompt ---
        System.out.println("  " + AnsiOutput.bold("a") + ") Generate AI digest for CEO"
                + "  " + AnsiOutput.dim("(requires API key)"));
        System.out.println("  " + AnsiOutput.dim("[Enter] Back"));
        System.out.println();
        System.out.print("  Choose: ");
        System.out.flush();
        if (scanner.hasNextLine()) {
            String choice = scanner.nextLine().trim().toLowerCase();
            if ("a".equals(choice)) {
                runOrgAiDigest(org);
            }
        }
    }

    // ===============================================================
    //  AI Digest Helpers
    // ===============================================================

    /**
     * Generates an AI digest for a client using the configured AiClient.
     */
    private void runClientAiDigest(Client client) {
        System.out.println();
        AnsiOutput.printInfo("Generating AI digest...");

        try {
            Optional<AiClient> clientOpt = createAiClient();
            if (clientOpt.isEmpty()) {
                AnsiOutput.printWarning("AI not available. Set " + configuredApiKeyName()
                        + " environment variable and enable AI in a workspace config (ai.enabled: true).");
                pressEnterToContinue();
                return;
            }

            // Read README
            Path readmePath = client.resolvedPath().resolve("README.md");
            String readmeContent = "";
            if (Files.exists(readmePath)) {
                readmeContent = readFileSafe(readmePath, 8000);
            }

            // Gather git logs (14 days, max 20 per repo)
            StringBuilder gitContext = new StringBuilder();
            for (String codebasePath : client.getCodebases()) {
                Path cbPath = Path.of(codebasePath);
                if (!Files.isDirectory(cbPath)) continue;
                gitContext.append("\nRepository: ").append(codebasePath).append("\n");
                List<String> commits = runGitLogWithAuthor(cbPath, 14, 20);
                if (commits.isEmpty()) {
                    gitContext.append("  (no commits in last 14 days)\n");
                } else {
                    for (String c : commits) {
                        gitContext.append("  ").append(c).append("\n");
                    }
                }
            }

            String prompt = "You are a business assistant for a software consultancy.\n\n"
                    + "Client: " + client.getName() + " (Status: " + client.getStatus().name() + ")\n\n"
                    + "Business context from README:\n"
                    + (readmeContent.isEmpty() ? "(no README available)" : readmeContent) + "\n\n"
                    + "Recent code activity (last 14 days):\n"
                    + (gitContext.isEmpty() ? "(no codebases linked)" : gitContext.toString()) + "\n\n"
                    + "Generate a concise executive summary (max 200 words) covering:\n"
                    + "1. Current relationship status and business context\n"
                    + "2. What the team has been working on recently\n"
                    + "3. Key next actions or open items\n\n"
                    + "Write for a CEO/CTO audience. Be specific, not generic.";

            String answer = clientOpt.get().generate(prompt, 1024);

            System.out.println();
            System.out.println("  " + AnsiOutput.bold("AI Digest -- " + client.getName()));
            System.out.println();
            for (String line : answer.split("\n")) {
                System.out.println("  " + line);
            }
            System.out.println();

        } catch (Exception e) {
            AnsiOutput.printError("AI digest failed: " + e.getMessage());
        }

        pressEnterToContinue();
    }

    /**
     * Generates an AI digest for a product using the configured AiClient.
     */
    private void runProductAiDigest(Product product) {
        System.out.println();
        AnsiOutput.printInfo("Generating AI digest...");

        try {
            Optional<AiClient> clientOpt = createAiClient();
            if (clientOpt.isEmpty()) {
                AnsiOutput.printWarning("AI not available. Set " + configuredApiKeyName()
                        + " environment variable and enable AI in a workspace config (ai.enabled: true).");
                pressEnterToContinue();
                return;
            }

            // Read README
            Path readmePath = product.resolvedPath().resolve("README.md");
            String readmeContent = "";
            if (Files.exists(readmePath)) {
                readmeContent = readFileSafe(readmePath, 8000);
            }

            String prompt = "You are a business assistant for a software consultancy.\n\n"
                    + "Product: " + product.getName() + "\n\n"
                    + "Product documentation from README:\n"
                    + (readmeContent.isEmpty() ? "(no README available)" : readmeContent) + "\n\n"
                    + "Generate a concise product summary (max 200 words) covering:\n"
                    + "1. What the product does and its target market\n"
                    + "2. Current status and maturity\n"
                    + "3. Key next steps or opportunities\n\n"
                    + "Write for a CEO/CTO audience. Be specific, not generic.";

            String answer = clientOpt.get().generate(prompt, 1024);

            System.out.println();
            System.out.println("  " + AnsiOutput.bold("AI Digest -- " + product.getName()));
            System.out.println();
            for (String line : answer.split("\n")) {
                System.out.println("  " + line);
            }
            System.out.println();

        } catch (Exception e) {
            AnsiOutput.printError("AI digest failed: " + e.getMessage());
        }

        pressEnterToContinue();
    }

    /**
     * Generates an AI CEO briefing for the entire organization.
     */
    private void runOrgAiDigest(Organization org) {
        System.out.println();
        AnsiOutput.printInfo("Generating CEO briefing...");

        try {
            Optional<AiClient> clientOpt = createAiClient();
            if (clientOpt.isEmpty()) {
                AnsiOutput.printWarning("AI not available. Set " + configuredApiKeyName()
                        + " environment variable and enable AI in a workspace config (ai.enabled: true).");
                pressEnterToContinue();
                return;
            }

            // Build context from all clients
            StringBuilder context = new StringBuilder();
            for (ClientStatus status : List.of(ClientStatus.ACTIVE, ClientStatus.SIGNED,
                    ClientStatus.OPPORTUNITY, ClientStatus.PAST)) {
                List<Client> clients = org.getClientsByStatus(status);
                if (clients.isEmpty()) continue;
                context.append("\n").append(status.name()).append(" CLIENTS:\n");
                for (Client client : clients) {
                    context.append("- ").append(client.getName())
                            .append(" (").append(status.name()).append(")");
                    String lastAct = getLastActivityLabel(client);
                    context.append(", last activity: ").append(lastAct);
                    String nextAction = getFirstNextAction(client);
                    if (nextAction != null) {
                        context.append(", next: ").append(nextAction);
                    }
                    context.append("\n");
                }
            }

            // Add product info
            if (!org.getProducts().isEmpty()) {
                context.append("\nPRODUCTS:\n");
                for (Product product : org.getProducts()) {
                    context.append("- ").append(product.getName()).append("\n");
                }
            }

            String prompt = "You are a business assistant. Generate a weekly CEO briefing"
                    + " (max 300 words) for " + org.getName() + " based on:\n\n"
                    + context + "\n\n"
                    + "Write as a CEO briefing: what's going well, what needs attention,"
                    + " key actions for this week.";

            String answer = clientOpt.get().generate(prompt, 1024);

            System.out.println();
            System.out.println("  " + AnsiOutput.bold("CEO Briefing -- " + org.getName()));
            System.out.println();
            for (String line : answer.split("\n")) {
                System.out.println("  " + line);
            }
            System.out.println();

        } catch (Exception e) {
            AnsiOutput.printError("CEO briefing failed: " + e.getMessage());
        }

        pressEnterToContinue();
    }

    // ===============================================================
    //  Summary Utility Methods
    // ===============================================================

    /**
     * Prints first N lines of a README.md file, or a "not found" message.
     */
    private void printReadmePreview(Path readmePath, Path basePath, int maxLines) {
        if (!Files.exists(readmePath)) {
            System.out.println("    " + AnsiOutput.dim("No README.md found -- add one at "
                    + basePath.resolve("README.md")));
            return;
        }
        try {
            List<String> lines = Files.readAllLines(readmePath);
            int limit = Math.min(lines.size(), maxLines);
            for (int i = 0; i < limit; i++) {
                System.out.println("    " + lines.get(i));
            }
            if (lines.size() > maxLines) {
                System.out.println("    " + AnsiOutput.dim("... (" + (lines.size() - maxLines) + " more lines)"));
            }
        } catch (IOException e) {
            System.out.println("    " + AnsiOutput.dim("(could not read README.md: " + e.getMessage() + ")"));
        }
    }

    /**
     * Runs git log for a codebase directory, returning formatted commit lines.
     *
     * @param codebaseDir directory to run git log in
     * @param sinceDays   number of days to look back
     * @param maxCommits  maximum commits to return
     * @return list of formatted commit strings (e.g., "3d  fix: Update invoice parser")
     */
    private List<String> runGitLog(Path codebaseDir, int sinceDays, int maxCommits) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--oneline",
                    "--since=" + sinceDays + ".days",
                    "--format=%ar  %s",
                    "-" + maxCommits);
            pb.directory(codebaseDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
            process.waitFor();
            return lines;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Runs git log with author info for AI context.
     */
    private List<String> runGitLogWithAuthor(Path codebaseDir, int sinceDays, int maxCommits) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--oneline",
                    "--since=" + sinceDays + ".days",
                    "--format=%ar %an: %s",
                    "-" + maxCommits);
            pb.directory(codebaseDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
            process.waitFor();
            return lines;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Gets a human-readable label for the last git activity in a client's codebases.
     */
    private String getLastActivityLabel(Client client) {
        if (client.getCodebases().isEmpty()) {
            return "unknown";
        }
        String mostRecent = null;
        for (String codebasePath : client.getCodebases()) {
            Path cbPath = Path.of(codebasePath);
            if (!Files.isDirectory(cbPath)) continue;
            try {
                // Read .synthesis-last-activity file if present
                Path lastActivityFile = cbPath.resolve(".synthesis-last-activity");
                if (Files.exists(lastActivityFile)) {
                    String content = Files.readString(lastActivityFile).trim();
                    if (!content.isEmpty()) {
                        if (mostRecent == null || content.compareTo(mostRecent) > 0) {
                            mostRecent = content;
                        }
                    }
                    continue;
                }
                // Fallback: run git log to find most recent commit
                ProcessBuilder pb = new ProcessBuilder(
                        "git", "log", "-1", "--format=%ar");
                pb.directory(cbPath.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        return line.trim();
                    }
                }
                process.waitFor();
            } catch (Exception ignored) {}
        }
        if (mostRecent != null) {
            // Parse ISO date and compute relative time
            try {
                java.time.LocalDate date = java.time.LocalDate.parse(mostRecent);
                long daysAgo = java.time.temporal.ChronoUnit.DAYS.between(date, java.time.LocalDate.now());
                if (daysAgo == 0) return "today";
                if (daysAgo == 1) return "1d ago";
                return daysAgo + "d ago";
            } catch (Exception e) {
                return mostRecent;
            }
        }
        return "unknown";
    }

    /**
     * Reads the first "## Next Actions" item from a client's README.md.
     *
     * @return first next action line, or null if not found
     */
    private String getFirstNextAction(Client client) {
        Path readmePath = client.resolvedPath().resolve("README.md");
        if (!Files.exists(readmePath)) return null;
        try {
            List<String> lines = Files.readAllLines(readmePath);
            boolean inNextActions = false;
            for (String line : lines) {
                if (line.matches("(?i)^#{1,3}\\s+Next\\s+Actions?.*")) {
                    inNextActions = true;
                    continue;
                }
                if (inNextActions) {
                    // Found a non-empty line after the heading
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.startsWith("#")) break; // next heading
                    // Strip leading bullet/dash
                    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                        trimmed = trimmed.substring(2).trim();
                    }
                    // Truncate if too long
                    if (trimmed.length() > 80) {
                        trimmed = trimmed.substring(0, 77) + "...";
                    }
                    return trimmed;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Reads a file safely, returning at most maxChars characters.
     */
    private String readFileSafe(Path path, int maxChars) {
        try {
            String content = Files.readString(path);
            if (content.length() > maxChars) {
                return content.substring(0, maxChars) + "\n...(truncated)";
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Creates an AiClient by trying to find an AI-enabled config across workspaces.
     * Falls back to a default-enabled config when only the provider's API key is present.
     */
    private Optional<AiClient> createAiClient() {
        SynthesisConfig.AiConfig aiConfig = resolveAiConfig();
        if (AiProvider.fromId(aiConfig.getProvider()).resolveApiKey().isEmpty()) {
            return Optional.empty();
        }
        return AiClient.create(aiConfig);
    }

    private String configuredApiKeyName() {
        return AiProvider.forConfig(resolveAiConfig()).apiKeyName();
    }

    private SynthesisConfig.AiConfig resolveAiConfig() {
        try {
            SynthesisConfig config = ConfigLoader.load(parent.getWorkspaceRoot());
            if (config.getAi().isEnabled()) {
                return config.getAi();
            }
        } catch (Exception ignored) {}

        SynthesisConfig.AiConfig fallback = new SynthesisConfig.AiConfig();
        fallback.setEnabled(true);
        return fallback;
    }

    /**
     * Search within a sub-workspace.
     */
    private void runSearchInSubWorkspace(WorkspaceInfo ws, SubWorkspaceConfig sub, Scanner scanner) {
        if (!ws.indexed) {
            AnsiOutput.printWarning("Workspace not indexed.");
            pressEnterToContinue();
            return;
        }

        System.out.print("  Search query: ");
        System.out.flush();
        String query = scanner.nextLine().trim();
        if (query.isEmpty()) {
            return;
        }

        System.out.println();

        try {
            Path indexPath = ws.path.resolve(".synthesis").resolve("index");
            try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                List<SearchResult> results = index.searchWithSubWorkspace(
                                query, null, null, null, null, sub.getName(), 20);

                if (results.isEmpty()) {
                    AnsiOutput.printInfo("No results found for '" + query + "' in " + sub.getName());
                } else {
                    System.out.println("  " + AnsiOutput.bold("Results for '") + AnsiOutput.cyan(query)
                            + AnsiOutput.bold("' in ") + AnsiOutput.cyan(sub.getName())
                            + AnsiOutput.bold(":"));
                    System.out.println();
                    printSearchResults(results);
                }
            }
        } catch (Exception e) {
            AnsiOutput.printError("Search failed: " + e.getMessage());
        }

        System.out.println();
        pressEnterToContinue();
    }

    /**
     * Search within a workspace (used from client/product level).
     */
    private void runSearchInWorkspace(WorkspaceInfo ws, Scanner scanner) {
        if (!ws.indexed) {
            AnsiOutput.printWarning("Workspace not indexed.");
            pressEnterToContinue();
            return;
        }

        System.out.print("  Search query: ");
        System.out.flush();
        String query = scanner.nextLine().trim();
        if (query.isEmpty()) {
            return;
        }

        System.out.println();

        try {
            Path indexPath = ws.path.resolve(".synthesis").resolve("index");
            try (SearchIndex index = SearchIndex.openReadOnly(indexPath)) {
                List<SearchResult> results = index.search(query, 20);

                if (results.isEmpty()) {
                    AnsiOutput.printInfo("No results found for '" + query + "'.");
                } else {
                    System.out.println("  " + AnsiOutput.bold("Results for '") + AnsiOutput.cyan(query)
                            + AnsiOutput.bold("':"));
                    System.out.println();
                    printSearchResults(results);
                }
            }
        } catch (Exception e) {
            AnsiOutput.printError("Search failed: " + e.getMessage());
        }

        System.out.println();
        pressEnterToContinue();
    }

    // ===============================================================
    //  Shared Helpers
    // ===============================================================

    /**
     * Prints search results with truncation for long lists.
     */
    private void printSearchResults(List<SearchResult> results) {
        int shown = 0;
        for (SearchResult result : results) {
            if (shown >= 15) {
                System.out.println("  " + AnsiOutput.dim("... and "
                        + (results.size() - shown) + " more results"));
                break;
            }
            System.out.println("  " + AnsiOutput.bold(result.relativePath()));
            if (result.summary() != null && !result.summary().isBlank()) {
                String preview = result.summary().length() > 120
                        ? result.summary().substring(0, 120) + "..."
                        : result.summary();
                System.out.println("    " + AnsiOutput.dim(preview));
            }
            shown++;
        }
    }

    /**
     * Displays change events for a workspace.
     */
    private void showChanges(WorkspaceInfo ws, int days) {
        try {
            List<ChangeEvent> events = getChanges(ws.path, days);

            if (events.isEmpty()) {
                AnsiOutput.printInfo("No changes detected in the last " + days + " days.");
                AnsiOutput.printInfo("Run 'synthesis changelog --snapshot -d " + ws.path
                        + "' to take a snapshot.");
                return;
            }

            // Filter to at least NORMAL significance
            List<ChangeEvent> filtered = events.stream()
                    .filter(e -> e.significance().isAtLeast(ChangeSignificance.NORMAL))
                    .toList();

            ChangeReportGenerator generator = new ChangeReportGenerator();
            String summary = generator.generateSummary(events);
            System.out.println("  " + summary);
            System.out.println();

            // Show changes grouped by type
            for (ChangeEvent e : filtered) {
                String icon = switch (e.changeType()) {
                    case ADDED -> AnsiOutput.green("+");
                    case MODIFIED -> AnsiOutput.yellow("~");
                    case DELETED -> AnsiOutput.red("-");
                    case MOVED -> AnsiOutput.blue(">");
                };
                String sigLabel = e.significance() == ChangeSignificance.CRITICAL
                        ? " " + AnsiOutput.red("[CRITICAL]") : "";
                System.out.println("  " + icon + " " + e.relativePath() + sigLabel);
            }

            int noiseCount = events.size() - filtered.size();
            if (noiseCount > 0) {
                System.out.println();
                AnsiOutput.printInfo(noiseCount + " noise events filtered.");
            }

        } catch (Exception e) {
            AnsiOutput.printInfo("No snapshot data available for this workspace.");
            AnsiOutput.printInfo("Run 'synthesis changelog --snapshot -d " + ws.path
                    + "' to take a snapshot.");
        }
    }

    /**
     * Retrieves change events for a workspace.
     */
    private List<ChangeEvent> getChanges(Path workspacePath, int days) throws Exception {
        SynthesisDatabase db = SynthesisDatabase.getDefault();
        SnapshotManager snapshots = new SnapshotManager(db);
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return snapshots.getChangesForWorkspace(workspacePath.toString(), since);
    }

    /**
     * Shows media file statistics from the index.
     */
    private void showMediaStats(SearchIndex index) throws IOException {
        long imageCount = index.listAll("IMAGE", 50000).size();
        long videoCount = index.listAll("VIDEO", 50000).size();
        long audioCount = index.listAll("AUDIO", 50000).size();
        long pdfCount = index.listAll("PDF", 50000).size();
        long mediaTotal = imageCount + videoCount + audioCount;

        if (mediaTotal > 0 || pdfCount > 0) {
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Media & Documents:"));
            if (imageCount > 0) System.out.printf("    %-15s %d files%n", "Images:", imageCount);
            if (videoCount > 0) System.out.printf("    %-15s %d files%n", "Videos:", videoCount);
            if (audioCount > 0) System.out.printf("    %-15s %d files%n", "Audio:", audioCount);
            if (pdfCount > 0) System.out.printf("    %-15s %d files%n", "PDFs:", pdfCount);
        }
    }

    /**
     * Opens a folder using the system file manager.
     */
    private void openFolder(Path path) {
        try {
            if (Files.isDirectory(path)) {
                ProcessBuilder pb = new ProcessBuilder("xdg-open", path.toString());
                pb.start();
                AnsiOutput.printSuccess("Opening " + path);
            } else {
                AnsiOutput.printWarning("Directory does not exist: " + path);
            }
        } catch (IOException e) {
            AnsiOutput.printError("Could not open folder: " + e.getMessage());
        }
        pressEnterToContinue();
    }

    /**
     * Builds an ordered list of clients: ACTIVE, SIGNED, OPPORTUNITY, PAST.
     */
    private List<Client> buildOrderedClientList(Organization org) {
        List<Client> ordered = new ArrayList<>();
        for (ClientStatus status : List.of(ClientStatus.ACTIVE, ClientStatus.SIGNED,
                ClientStatus.OPPORTUNITY, ClientStatus.PAST)) {
            List<Client> byStatus = org.getClientsByStatus(status);
            ordered.addAll(byStatus);
        }
        return ordered;
    }

    /**
     * Finds a WorkspaceInfo whose path contains or equals the given target path.
     * Checks if the target path is under any workspace, or if any workspace is under
     * the target path. Also checks for .synthesis/index directly at the target path.
     */
    private WorkspaceInfo findWorkspaceForPath(Path target, List<WorkspaceInfo> allWorkspaces) {
        Path normalized = target.toAbsolutePath().normalize();

        // First: exact match
        for (WorkspaceInfo ws : allWorkspaces) {
            if (ws.path.equals(normalized)) {
                return ws;
            }
        }

        // Second: target is under a workspace (the org/client path is inside a workspace)
        WorkspaceInfo best = null;
        for (WorkspaceInfo ws : allWorkspaces) {
            if (normalized.startsWith(ws.path)) {
                // This workspace contains the target — prefer the longest (most specific) match
                if (best == null || ws.path.getNameCount() > best.path.getNameCount()) {
                    best = ws;
                }
            }
        }
        return best;
    }

    // ===============================================================
    //  Workspace Discovery
    // ===============================================================

    /**
     * Discovers all Synthesis workspaces using the same logic as StatusCommand.
     */
    private List<WorkspaceInfo> discoverWorkspaces() throws IOException {
        List<WorkspaceInfo> workspaces = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        WorkspaceDiscoveryConfig config = WorkspaceDiscoveryConfig.load();
        List<Path> searchPaths = config.getSearchPaths();

        for (Path searchPath : searchPaths) {
            if (!Files.exists(searchPath)) continue;

            // Direct .synthesis directory
            Path synthDir = searchPath.resolve(".synthesis");
            if (Files.isDirectory(synthDir)) {
                Path abs = searchPath.toAbsolutePath().normalize();
                if (seen.add(abs)) {
                    workspaces.add(createWorkspaceInfo(abs, synthDir));
                }
            }

            // Search one level deep
            if (Files.isDirectory(searchPath)) {
                try (Stream<Path> entries = Files.list(searchPath)) {
                    entries.filter(Files::isDirectory)
                            .forEach(subDir -> {
                                Path subSynthDir = subDir.resolve(".synthesis");
                                if (Files.isDirectory(subSynthDir)) {
                                    Path abs = subDir.toAbsolutePath().normalize();
                                    if (seen.add(abs)) {
                                        try {
                                            workspaces.add(createWorkspaceInfo(abs, subSynthDir));
                                        } catch (IOException e) {
                                            // Skip this workspace
                                        }
                                    }
                                }
                            });
                } catch (IOException e) {
                    // Skip this search path
                }
            }
        }

        // Also ensure the current workspace root is included
        try {
            Path currentRoot = parent.getWorkspaceRoot();
            Path currentSynth = currentRoot.resolve(".synthesis");
            if (Files.isDirectory(currentSynth)) {
                Path abs = currentRoot.toAbsolutePath().normalize();
                if (seen.add(abs)) {
                    workspaces.add(createWorkspaceInfo(abs, currentSynth));
                }
            }
        } catch (Exception ignored) {
            // Current workspace may not be valid
        }

        workspaces.sort(Comparator.comparing(w -> w.path.toString()));
        return workspaces;
    }

    /**
     * Creates a WorkspaceInfo from a workspace path and its .synthesis directory.
     */
    private WorkspaceInfo createWorkspaceInfo(Path workspacePath, Path synthDir) throws IOException {
        WorkspaceInfo info = new WorkspaceInfo();
        info.path = workspacePath;
        info.name = workspacePath.getFileName() != null
                ? workspacePath.getFileName().toString() : workspacePath.toString();

        // Read config
        try {
            SynthesisConfig config = ConfigLoader.load(workspacePath);
            if (config.getWorkspace() != null) {
                if (config.getWorkspace().getName() != null && !config.getWorkspace().getName().isBlank()) {
                    info.name = config.getWorkspace().getName().replace("\"", "");
                }
                info.workspaceType = config.getWorkspace().getWorkspaceType();

                WorkspaceMetadata metadata = config.getWorkspace().getMetadata();
                if (metadata != null) {
                    info.primaryLanguage = metadata.getPrimaryLanguage();
                    info.repoCount = metadata.getRepoCount();
                    info.company = metadata.getCompany();
                }
            }

            // Sub-workspaces
            if (config.getSubWorkspaces() != null) {
                info.subWorkspaces = config.getSubWorkspaces();
            }
        } catch (Exception e) {
            // Fallback if config reading fails
        }

        // Check index status
        Path indexDir = synthDir.resolve("index");
        if (Files.isDirectory(indexDir)) {
            try (Stream<Path> indexFiles = Files.list(indexDir)) {
                if (indexFiles.findAny().isPresent()) {
                    info.indexed = true;

                    // Get index size
                    try (Stream<Path> files = Files.walk(indexDir)) {
                        info.indexSize = files
                                .filter(Files::isRegularFile)
                                .mapToLong(f -> {
                                    try { return Files.size(f); }
                                    catch (IOException e) { return 0; }
                                })
                                .sum();
                    }

                    // Get file count from scan state
                    Path scanStateFile = synthDir.resolve("scan-state.json");
                    if (Files.exists(scanStateFile)) {
                        try {
                            ScanState scanState = ScanState.load(scanStateFile);
                            info.fileCount = scanState.getFileCount();
                            if (scanState.getLastScanTime() != null) {
                                String time = LocalDateTime.ofInstant(
                                                scanState.getLastScanTime(), ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                                Duration elapsed = Duration.between(scanState.getLastScanTime(), Instant.now());
                                info.lastScanFormatted = time + " (" + formatDuration(elapsed) + " ago)";
                            }
                        } catch (Exception e) {
                            // Fallback: parse manually
                            String scanState = Files.readString(scanStateFile);
                            if (scanState.contains("\"fileCount\"")) {
                                try {
                                    String fileCountStr = scanState.substring(scanState.indexOf("\"fileCount\""));
                                    fileCountStr = fileCountStr.substring(fileCountStr.indexOf(":") + 1);
                                    fileCountStr = fileCountStr.substring(0, fileCountStr.indexOf(",")).trim();
                                    info.fileCount = Integer.parseInt(fileCountStr);
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Get sub-workspace counts
                    try (SearchIndex index = SearchIndex.openReadOnly(indexDir)) {
                        Map<String, Long> counts = index.getSubWorkspaceCounts();
                        if (counts != null && !counts.isEmpty()) {
                            info.subWorkspaceCounts = counts;
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        }

        return info;
    }

    // ===============================================================
    //  Utility Methods
    // ===============================================================

    /**
     * Reads a trimmed, lowercased line from the scanner.
     * Returns null if no input available (e.g., piped input exhausted).
     */
    private String readInput(Scanner scanner) {
        System.out.print("  Choose: ");
        System.out.flush();
        if (scanner.hasNextLine()) {
            return scanner.nextLine().trim().toLowerCase();
        }
        return null;
    }

    /**
     * Parses user input as an index selection.
     * The first selectable item starts at number {@code offset}.
     *
     * @param input     user input string
     * @param offset    the number shown for the first item (e.g. 3 for top-level)
     * @param listSize  number of items in the list
     * @return 0-based index into the list, or -1 if invalid
     */
    private int parseIndex(String input, int offset, int listSize) {
        try {
            int num = Integer.parseInt(input);
            int idx = num - offset;
            if (idx >= 0 && idx < listSize) {
                return idx;
            }
        } catch (NumberFormatException e) {
            // Not a number
        }
        return -1;
    }

    /**
     * Formats a Duration in human-readable form.
     */
    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = duration.toMinutes();
        if (minutes < 60) return minutes + " min";
        long hours = duration.toHours();
        if (hours < 24) return hours + "h " + (minutes % 60) + "m";
        long days = hours / 24;
        return days + " day" + (days > 1 ? "s" : "");
    }

    /**
     * Returns a colored workspace type badge.
     */
    private String typeBadge(WorkspaceType type) {
        return switch (type) {
            case SOURCE_CODE -> AnsiOutput.blue("[source]");
            case DOCUMENTS -> AnsiOutput.green("[docs]  ");
            case STAGING -> AnsiOutput.magenta("[stage] ");
            case MIXED -> AnsiOutput.yellow("[mixed] ");
        };
    }

    /**
     * Returns a colored organization type badge.
     */
    private String orgTypeBadge(OrganizationType type) {
        return switch (type) {
            case COMPANY -> AnsiOutput.blue("[company]   ");
            case FOUNDATION -> AnsiOutput.green("[foundation]");
            case HOLDING -> AnsiOutput.dim("[holding]   ");
            case CONCEPT -> AnsiOutput.yellow("[concept]   ");
            case OTHER -> AnsiOutput.dim("[other]     ");
        };
    }

    /**
     * Returns a colored client status badge.
     */
    private String clientStatusBadge(ClientStatus status) {
        return switch (status) {
            case ACTIVE -> AnsiOutput.green("[active]  ");
            case SIGNED -> AnsiOutput.blue("[signed]  ");
            case OPPORTUNITY -> AnsiOutput.yellow("[prospect]");
            case PAST -> AnsiOutput.dim("[past]    ");
        };
    }

    /**
     * Returns a human-readable client status label.
     */
    private String clientStatusLabel(ClientStatus status) {
        return switch (status) {
            case ACTIVE -> AnsiOutput.green("Active");
            case SIGNED -> AnsiOutput.blue("Signed");
            case OPPORTUNITY -> AnsiOutput.yellow("Prospect / Opportunity");
            case PAST -> AnsiOutput.dim("Past");
        };
    }

    /**
     * Returns a section header for a client status group.
     */
    private String clientStatusHeader(ClientStatus status) {
        return switch (status) {
            case ACTIVE -> AnsiOutput.green("-- Active --");
            case SIGNED -> AnsiOutput.blue("-- Signed --");
            case OPPORTUNITY -> AnsiOutput.yellow("-- Prospects --");
            case PAST -> AnsiOutput.dim("-- Past --");
        };
    }

    /**
     * Pauses until the user presses Enter.
     */
    private void pressEnterToContinue() {
        System.out.print("  " + AnsiOutput.dim("Press Enter to continue..."));
        System.out.flush();
        try {
            System.in.read();
            // Consume remaining newline bytes
            while (System.in.available() > 0) {
                System.in.read();
            }
        } catch (IOException ignored) {}
    }
}
