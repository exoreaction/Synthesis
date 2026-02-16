package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.search.WorkspaceDiscoveryConfig;
import io.exoreaction.synthesis.workspace.WorkspaceMetadata;
import io.exoreaction.synthesis.workspace.WorkspaceType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Lists all Synthesis workspaces with optional filtering by type, language,
 * and company.
 *
 * <p>Usage:
 * <pre>
 *   synthesis list                           # List all workspaces
 *   synthesis list --type source-code        # Filter by category
 *   synthesis list --type documents          # Show document workspaces
 *   synthesis list --language java           # Filter by primary language
 *   synthesis list --company eXOReaction     # Filter by company
 *   synthesis list --format json             # JSON output
 * </pre>
 */
@Command(name = "list", description = "List all Synthesis workspaces")
public class ListWorkspacesCommand implements Callable<Integer> {

    @Option(names = {"-v", "--verbose"}, description = "Show detailed information")
    private boolean verbose;

    @Option(names = {"--format"}, description = "Output format: table (default) or json", defaultValue = "table")
    private String format;

    @Option(names = {"--type"}, description = "Filter by workspace type: source-code, documents, mixed")
    private String typeFilter;

    @Option(names = {"--language"}, description = "Filter by primary programming language (e.g., java, javascript)")
    private String languageFilter;

    @Option(names = {"--company"}, description = "Filter by company/organization")
    private String companyFilter;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Override
    public Integer call() {
        try {
            List<WorkspaceInfo> workspaces = discoverWorkspaces();

            // Apply filters
            if (typeFilter != null) {
                WorkspaceType filterType = WorkspaceType.fromConfigValue(typeFilter);
                workspaces = workspaces.stream()
                        .filter(ws -> ws.workspaceType == filterType)
                        .toList();
            }
            if (languageFilter != null) {
                workspaces = workspaces.stream()
                        .filter(ws -> ws.primaryLanguage != null &&
                                ws.primaryLanguage.equalsIgnoreCase(languageFilter))
                        .toList();
            }
            if (companyFilter != null) {
                workspaces = workspaces.stream()
                        .filter(ws -> ws.company != null &&
                                ws.company.toLowerCase().contains(companyFilter.toLowerCase()))
                        .toList();
            }

            if (workspaces.isEmpty()) {
                if (typeFilter != null || languageFilter != null || companyFilter != null) {
                    System.out.println("No workspaces match the given filters.");
                    StringBuilder filters = new StringBuilder("  Filters: ");
                    if (typeFilter != null) filters.append("type=").append(typeFilter).append(" ");
                    if (languageFilter != null) filters.append("language=").append(languageFilter).append(" ");
                    if (companyFilter != null) filters.append("company=").append(companyFilter);
                    System.out.println(filters.toString().trim());
                } else {
                    System.out.println("No Synthesis workspaces found.");
                    System.out.println("Run 'synthesis init' to initialize a workspace.");
                }
                return 0;
            }

            if ("json".equalsIgnoreCase(format)) {
                printJson(workspaces);
            } else {
                printTable(workspaces);
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error listing workspaces: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private List<WorkspaceInfo> discoverWorkspaces() throws IOException {
        List<WorkspaceInfo> workspaces = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        // Load workspace discovery configuration
        WorkspaceDiscoveryConfig config = WorkspaceDiscoveryConfig.load();
        List<Path> searchPaths = new ArrayList<>(config.getSearchPaths());

        // Also check current directory
        searchPaths.add(Paths.get(".").toAbsolutePath().normalize());

        // Check for workspaces in these locations
        for (Path searchPath : searchPaths) {
            if (!Files.exists(searchPath)) {
                continue;
            }

            // Direct .synthesis directory
            Path synthDir = searchPath.resolve(".synthesis");
            if (Files.isDirectory(synthDir)) {
                Path abs = searchPath.toAbsolutePath().normalize();
                if (seen.add(abs)) {
                    workspaces.add(createWorkspaceInfo(abs, abs.resolve(".synthesis")));
                }
            }

            // Search one level deep (e.g., /src/*/.synthesis)
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

        // Sort by path
        workspaces.sort(Comparator.comparing(w -> w.path.toString()));

        return workspaces;
    }

    private WorkspaceInfo createWorkspaceInfo(Path workspacePath, Path synthDir) throws IOException {
        WorkspaceInfo info = new WorkspaceInfo();
        info.path = workspacePath.toAbsolutePath().normalize();
        info.name = workspacePath.getFileName() != null
                ? workspacePath.getFileName().toString() : workspacePath.toString();

        // Read config using ConfigLoader for proper YAML parsing
        try {
            SynthesisConfig config = ConfigLoader.load(workspacePath);
            if (config.getWorkspace() != null) {
                if (config.getWorkspace().getName() != null && !config.getWorkspace().getName().isBlank()) {
                    // Strip quotes from name
                    info.name = config.getWorkspace().getName().replace("\"", "");
                }
                info.workspaceType = config.getWorkspace().getWorkspaceType();

                WorkspaceMetadata metadata = config.getWorkspace().getMetadata();
                if (metadata != null) {
                    info.category = metadata.getCategory();
                    info.primaryLanguage = metadata.getPrimaryLanguage();
                    info.repoCount = metadata.getRepoCount();
                    info.company = metadata.getCompany();
                }
            }
            if (config.getSubWorkspaces() != null && !config.getSubWorkspaces().isEmpty()) {
                info.subWorkspaces = config.getSubWorkspaces();
            }
        } catch (Exception e) {
            // Fall back to simple parsing if YAML parsing fails
            Path configFile = synthDir.resolve("config.yaml");
            if (Files.exists(configFile)) {
                String configContent = Files.readString(configFile);
                if (configContent.contains("name:")) {
                    String[] lines = configContent.split("\n");
                    for (String line : lines) {
                        if (line.trim().startsWith("name:")) {
                            info.name = line.substring(line.indexOf(":") + 1).trim()
                                    .replace("\"", "");
                            break;
                        }
                    }
                }
            }
        }

        // Check index status
        Path indexDir = synthDir.resolve("index");
        if (Files.isDirectory(indexDir)) {
            info.indexed = true;

            // Get index size
            try (Stream<Path> files = Files.walk(indexDir)) {
                info.indexSize = files
                        .filter(Files::isRegularFile)
                        .mapToLong(f -> {
                            try {
                                return Files.size(f);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
            }

            // Get file count from scan state
            Path scanStateFile = synthDir.resolve("scan-state.json");
            if (Files.exists(scanStateFile)) {
                String scanState = Files.readString(scanStateFile);
                // Simple JSON parsing for "fileCount"
                if (scanState.contains("\"fileCount\"")) {
                    try {
                        String fileCountStr = scanState.substring(scanState.indexOf("\"fileCount\""));
                        fileCountStr = fileCountStr.substring(fileCountStr.indexOf(":") + 1);
                        fileCountStr = fileCountStr.substring(0, fileCountStr.indexOf(",")).trim();
                        info.fileCount = Integer.parseInt(fileCountStr);
                    } catch (Exception e) {
                        // Ignore parse errors
                    }
                }

                // Get last scan time
                if (scanState.contains("\"lastScanTime\"")) {
                    try {
                        String lastScanStr = scanState.substring(scanState.indexOf("\"lastScanTime\""));
                        lastScanStr = lastScanStr.substring(lastScanStr.indexOf(":") + 1);
                        lastScanStr = lastScanStr.substring(0, lastScanStr.indexOf(",")).trim();
                        long epochMilli = Long.parseLong(lastScanStr);
                        info.lastScan = Instant.ofEpochMilli(epochMilli);
                    } catch (Exception e) {
                        // Ignore parse errors
                    }
                }
            }
        }

        // Check if watch daemon is running
        info.watching = isWatchDaemonRunning(workspacePath);

        return info;
    }

    /**
     * Checks if watch daemon is running for this workspace.
     * Derives service name from workspace path basename.
     */
    private boolean isWatchDaemonRunning(Path workspacePath) {
        try {
            // Derive service name from workspace path basename
            String basename = workspacePath.getFileName() != null
                    ? workspacePath.getFileName().toString().toLowerCase()
                    : "unknown";

            // Try service name patterns
            String[] servicePatterns = {
                "synthesis-watch-" + basename + ".service",
                "synthesis-watch-" + basename.replaceAll("[^a-z0-9]", "") + ".service"
            };

            for (String serviceName : servicePatterns) {
                Process process = new ProcessBuilder("systemctl", "--user", "is-active", serviceName)
                        .redirectErrorStream(true)
                        .start();

                process.waitFor();
                if (process.exitValue() == 0) {
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void printTable(List<WorkspaceInfo> workspaces) {
        System.out.println("\033[1m\033[34m========================================\033[0m");
        System.out.println("\033[1m\033[34m  Synthesis Workspaces\033[0m");
        System.out.println("\033[1m\033[34m========================================\033[0m");
        System.out.println();

        // Show active filters
        if (typeFilter != null || languageFilter != null || companyFilter != null) {
            StringBuilder filters = new StringBuilder("  \033[2mFilters:");
            if (typeFilter != null) filters.append(" type=").append(typeFilter);
            if (languageFilter != null) filters.append(" language=").append(languageFilter);
            if (companyFilter != null) filters.append(" company=").append(companyFilter);
            filters.append("\033[0m");
            System.out.println(filters.toString());
            System.out.println();
        }

        for (WorkspaceInfo ws : workspaces) {
            // Type badge
            String typeBadge = switch (ws.workspaceType) {
                case SOURCE_CODE -> "\033[34m[source]\033[0m";
                case DOCUMENTS -> "\033[32m[docs]  \033[0m";
                case STAGING -> "\033[35m[stage] \033[0m";
                case MIXED -> "\033[33m[mixed] \033[0m";
            };

            System.out.println("  " + typeBadge + " \033[1m" + ws.name + "\033[0m");
            System.out.println("  Path:        " + ws.path);

            // Show metadata if available
            if (ws.primaryLanguage != null) {
                System.out.println("  Language:    \033[36m" + ws.primaryLanguage + "\033[0m");
            }
            if (ws.company != null) {
                System.out.println("  Company:     " + ws.company);
            }
            if (ws.repoCount > 0) {
                System.out.println("  Repos:       " + ws.repoCount);
            }

            // Show sub-workspaces as tree view
            if (!ws.subWorkspaces.isEmpty()) {
                System.out.println("  Sub-spaces:  " + ws.subWorkspaces.size());
                for (int i = 0; i < ws.subWorkspaces.size(); i++) {
                    SubWorkspaceConfig sw = ws.subWorkspaces.get(i);
                    boolean isLast = (i == ws.subWorkspaces.size() - 1);
                    String connector = isLast ? "\u2514\u2500\u2500" : "\u251C\u2500\u2500";
                    String desc = sw.getDescription() != null && !sw.getDescription().isEmpty()
                            ? " \033[2m(" + sw.getDescription() + ")\033[0m" : "";
                    String type = sw.getType() != null && !sw.getType().isEmpty()
                            ? " \033[36m[" + sw.getType() + "]\033[0m" : "";
                    System.out.println("               " + connector + " \033[1m" + sw.getName() + "\033[0m"
                            + type + desc);
                    if (verbose) {
                        String prefix = isLast ? "               " : "               \u2502  ";
                        System.out.println(prefix + "   path: " + sw.getPath());
                        if (sw.getTags() != null && !sw.getTags().isEmpty()) {
                            System.out.println(prefix + "   tags: " + String.join(", ", sw.getTags()));
                        }
                    }
                }
            }

            System.out.println("  Indexed:     " + (ws.indexed ? "\033[32m+\033[0m" : "\033[33m-\033[0m"));

            if (ws.indexed) {
                System.out.println("  Files:       " + (ws.fileCount > 0 ? String.format("\033[1m%,d\033[0m", ws.fileCount) : "unknown"));
                System.out.println("  Index size:  " + formatBytes(ws.indexSize));
                if (ws.lastScan != null) {
                    System.out.println("  Last scan:   " + TIME_FORMATTER.format(ws.lastScan) + " (" + formatTimeAgo(ws.lastScan) + ")");
                }
            }

            System.out.println("  Watching:    " + (ws.watching ? "\033[32m+ Active\033[0m" : "\033[2m- Not running\033[0m"));
            System.out.println();
        }

        // Summary
        long totalFiles = workspaces.stream().mapToLong(w -> w.fileCount).sum();
        long totalIndexSize = workspaces.stream().mapToLong(w -> w.indexSize).sum();
        long indexed = workspaces.stream().filter(w -> w.indexed).count();
        long watching = workspaces.stream().filter(w -> w.watching).count();
        long sourceCount = workspaces.stream().filter(w -> w.workspaceType == WorkspaceType.SOURCE_CODE).count();
        long docsCount = workspaces.stream().filter(w -> w.workspaceType == WorkspaceType.DOCUMENTS).count();
        long mixedCount = workspaces.stream().filter(w -> w.workspaceType == WorkspaceType.MIXED).count();

        System.out.println("  \033[1mSummary:\033[0m");
        System.out.println("  Workspaces:  " + workspaces.size()
                + " (" + sourceCount + " source, " + docsCount + " docs, " + mixedCount + " mixed)");
        System.out.println("  Indexed:     " + indexed + "/" + workspaces.size());
        System.out.println("  Watching:    " + watching + "/" + workspaces.size());
        if (totalFiles > 0) {
            System.out.println("  Total files: " + String.format("\033[1m%,d\033[0m", totalFiles));
            System.out.println("  Total index: " + formatBytes(totalIndexSize));
        }
        System.out.println();
    }

    private void printJson(List<WorkspaceInfo> workspaces) {
        System.out.println("{");
        System.out.println("  \"workspaces\": [");

        for (int i = 0; i < workspaces.size(); i++) {
            WorkspaceInfo ws = workspaces.get(i);
            System.out.println("    {");
            System.out.println("      \"name\": \"" + ws.name + "\",");
            System.out.println("      \"path\": \"" + ws.path + "\",");
            System.out.println("      \"type\": \"" + ws.workspaceType.getConfigValue() + "\",");
            System.out.println("      \"category\": \"" + (ws.category != null ? ws.category : "mixed") + "\",");
            if (ws.primaryLanguage != null) {
                System.out.println("      \"primaryLanguage\": \"" + ws.primaryLanguage + "\",");
            }
            if (ws.company != null) {
                System.out.println("      \"company\": \"" + ws.company + "\",");
            }
            if (ws.repoCount > 0) {
                System.out.println("      \"repoCount\": " + ws.repoCount + ",");
            }
            System.out.println("      \"indexed\": " + ws.indexed + ",");
            System.out.println("      \"fileCount\": " + ws.fileCount + ",");
            System.out.println("      \"indexSize\": " + ws.indexSize + ",");
            System.out.println("      \"lastScan\": " + (ws.lastScan != null ? "\"" + ws.lastScan + "\"" : "null") + ",");
            System.out.println("      \"watching\": " + ws.watching + ",");
            // Sub-workspaces
            System.out.println("      \"subWorkspaces\": [");
            for (int j = 0; j < ws.subWorkspaces.size(); j++) {
                SubWorkspaceConfig sw = ws.subWorkspaces.get(j);
                System.out.println("        {");
                System.out.println("          \"name\": \"" + sw.getName() + "\",");
                System.out.println("          \"path\": \"" + sw.getPath() + "\",");
                if (sw.getType() != null) {
                    System.out.println("          \"type\": \"" + sw.getType() + "\",");
                }
                if (sw.getDescription() != null) {
                    System.out.println("          \"description\": \"" + sw.getDescription() + "\",");
                }
                if (sw.getTags() != null && !sw.getTags().isEmpty()) {
                    System.out.print("          \"tags\": [");
                    for (int t = 0; t < sw.getTags().size(); t++) {
                        System.out.print("\"" + sw.getTags().get(t) + "\"");
                        if (t < sw.getTags().size() - 1) System.out.print(", ");
                    }
                    System.out.println("]");
                }
                System.out.print("        }");
                System.out.println(j < ws.subWorkspaces.size() - 1 ? "," : "");
            }
            System.out.println("      ]");
            System.out.print("    }");
            System.out.println(i < workspaces.size() - 1 ? "," : "");
        }

        System.out.println("  ]");
        System.out.println("}");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatTimeAgo(Instant instant) {
        long seconds = Instant.now().getEpochSecond() - instant.getEpochSecond();

        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    private static class WorkspaceInfo {
        Path path;
        String name;
        WorkspaceType workspaceType = WorkspaceType.MIXED;
        String category;
        String primaryLanguage;
        String company;
        int repoCount;
        boolean indexed;
        int fileCount;
        long indexSize;
        Instant lastScan;
        boolean watching;
        List<SubWorkspaceConfig> subWorkspaces = new ArrayList<>();
    }
}
