package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI command to show planned future events, pipeline actions, and content deadlines.
 *
 * <p>Combines a user-maintained {@code UPCOMING.md} file at the workspace root with
 * auto-scanned "Next Actions" / "Next Steps" sections from indexed opportunity documents.
 *
 * <p>Usage:
 * <pre>
 *   synthesis upcoming                     # Show items in the next 30 days
 *   synthesis upcoming --days 14           # Show items in the next 14 days
 *   synthesis upcoming --all               # Show all items (no date filter)
 *   synthesis upcoming --actions           # Include scanned actions from indexed docs
 *   synthesis upcoming --overdue           # Show past-due items
 *   synthesis upcoming --format markdown   # Output as markdown
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction (with Claude Code)
 */
@Command(
        name = "upcoming",
        description = "Show planned events, actions, and deadlines",
        mixinStandardHelpOptions = true
)
public class UpcomingCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--days"}, description = "Look-ahead window in days (default: 30)", defaultValue = "30")
    private int days;

    @Option(names = {"--all"}, description = "Show all items, no date filter", defaultValue = "false")
    private boolean showAll;

    @Option(names = {"--actions"}, description = "Include scanned Next Actions from indexed docs", defaultValue = "false")
    private boolean includeScannedActions;

    @Option(names = {"--overdue"}, description = "Show past-due items (past dates not yet marked done)", defaultValue = "false")
    private boolean showOverdue;

    @Option(names = {"--format"}, description = "Output format: terminal (default), markdown", defaultValue = "terminal")
    private String format;

    /** Name of the upcoming file in the workspace root. */
    private static final String UPCOMING_FILE = "UPCOMING.md";

    /** Pattern for dated lines: - YYYY-MM-DD  description  [tag] */
    private static final Pattern DATED_LINE = Pattern.compile(
            "^-\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(.+?)(?:\\s+\\[([^\\]]+)\\])?\\s*$");

    /** Pattern for action lines: - [ ] owner: description */
    private static final Pattern ACTION_LINE = Pattern.compile(
            "^-\\s+\\[\\s]\\s+(.+?):\\s+(.+)$");

    /** Pattern for done lines: - [x] ... */
    private static final Pattern DONE_LINE = Pattern.compile(
            "^-\\s+\\[[xX]\\]\\s+.*$");

    /** Pattern for done dated lines: - YYYY-MM-DD ... [done] */
    private static final Pattern DONE_DATED_LINE = Pattern.compile(
            "^-\\s+\\d{4}-\\d{2}-\\d{2}\\s+.+\\[done\\]\\s*$", Pattern.CASE_INSENSITIVE);

    /** Pattern for TBD lines: - TBD  description */
    private static final Pattern TBD_LINE = Pattern.compile(
            "^-\\s+TBD\\s+(.+)$");

    /** Pattern for section headers: ## Section Name */
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^##\\s+(.+)$");

    // --- Data model ---

    enum ItemType { EVENT, ACTION, CONTENT, TBD }

    record UpcomingItem(
            ItemType type,
            String section,
            LocalDate date,       // null for undated actions and TBD items
            String description,
            String tag,           // [confirmed], [recommended], etc.
            String owner,         // for action items: "Mynder", "SpareBank 1", etc.
            boolean done
    ) {}

    record ScannedAction(
            String sourcePath,     // relative path of the source document
            String sourceLabel,    // formatted label like "eXOReaction > opportunity-Mynder"
            String actionText      // the action item text
    ) {}

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            LocalDate today = LocalDate.now();
            LocalDate cutoff = today.plusDays(days);

            boolean isMarkdown = "markdown".equalsIgnoreCase(format) || "md".equalsIgnoreCase(format);

            // Parse UPCOMING.md
            Path upcomingFile = workspaceRoot.resolve(UPCOMING_FILE);
            List<UpcomingItem> allItems = new ArrayList<>();
            boolean fileExists = Files.exists(upcomingFile);

            if (fileExists) {
                allItems = parseUpcomingFile(upcomingFile);
            }

            // Scan indexed docs for Next Actions (if requested)
            List<ScannedAction> scannedActions = new ArrayList<>();
            int scannedDocCount = 0;
            if (includeScannedActions) {
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                var validation = workspace.validate();
                if (validation.isEmpty()) {
                    var scanResult = scanIndexedActions(workspace);
                    scannedActions = scanResult.actions;
                    scannedDocCount = scanResult.docCount;
                }
            }

            // Filter items
            List<UpcomingItem> events = new ArrayList<>();
            List<UpcomingItem> overdueEvents = new ArrayList<>();
            List<UpcomingItem> actions = new ArrayList<>();
            List<UpcomingItem> content = new ArrayList<>();
            List<UpcomingItem> overdueContent = new ArrayList<>();
            List<UpcomingItem> tbdItems = new ArrayList<>();

            for (UpcomingItem item : allItems) {
                // Skip done items unless --all
                if (item.done() && !showAll) continue;

                if (item.date() != null) {
                    boolean isPast = item.date().isBefore(today);
                    boolean isInWindow = showAll || (!item.date().isAfter(cutoff));

                    if (isPast && !item.done()) {
                        // Past-due: show in overdue section
                        if (showOverdue || showAll) {
                            if ("Events".equalsIgnoreCase(item.section())) {
                                overdueEvents.add(item);
                            } else {
                                overdueContent.add(item);
                            }
                        }
                    } else if (!isPast && isInWindow) {
                        // Future and within window
                        if ("Events".equalsIgnoreCase(item.section())) {
                            events.add(item);
                        } else if ("Content".equalsIgnoreCase(item.section())) {
                            content.add(item);
                        } else {
                            // Dated items in other sections go to events
                            events.add(item);
                        }
                    } else if (showAll) {
                        // --all: show everything
                        if ("Events".equalsIgnoreCase(item.section())) {
                            events.add(item);
                        } else {
                            content.add(item);
                        }
                    }
                } else if (item.type() == ItemType.TBD) {
                    tbdItems.add(item);
                    // TBD items go into their section's list
                    if ("Content".equalsIgnoreCase(item.section())) {
                        content.add(item);
                    }
                } else if (item.type() == ItemType.ACTION) {
                    actions.add(item);
                }
            }

            // Sort dated items by date
            events.sort(Comparator.comparing(i -> i.date() != null ? i.date() : LocalDate.MAX));
            overdueEvents.sort(Comparator.comparing(i -> i.date() != null ? i.date() : LocalDate.MAX));
            content.sort(Comparator.comparing(i -> i.date() != null ? i.date() : LocalDate.MAX));
            overdueContent.sort(Comparator.comparing(i -> i.date() != null ? i.date() : LocalDate.MAX));

            // Render output
            if (isMarkdown) {
                renderMarkdown(today, cutoff, fileExists, upcomingFile,
                        events, overdueEvents, actions, content, overdueContent,
                        scannedActions, scannedDocCount);
            } else {
                renderTerminal(today, cutoff, fileExists, upcomingFile,
                        events, overdueEvents, actions, content, overdueContent,
                        scannedActions, scannedDocCount);
            }

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Error: " + e.getMessage());
            return 1;
        }
    }

    // ---- Parsing ----

    private List<UpcomingItem> parseUpcomingFile(Path file) throws IOException {
        List<UpcomingItem> items = new ArrayList<>();
        String currentSection = "General";

        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("<!--") || trimmed.startsWith("#") && !trimmed.startsWith("##")) {
                if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                    // Top-level heading, skip
                }
                continue;
            }

            // Section header
            Matcher sectionMatch = SECTION_HEADER.matcher(trimmed);
            if (sectionMatch.matches()) {
                currentSection = sectionMatch.group(1).trim();
                continue;
            }

            // Done lines (checkbox)
            if (DONE_LINE.matcher(trimmed).matches()) {
                // Extract description after [x]
                String desc = trimmed.replaceFirst("^-\\s+\\[[xX]\\]\\s+", "");
                items.add(new UpcomingItem(ItemType.ACTION, currentSection, null, desc, null, extractOwner(desc), true));
                continue;
            }

            // Done dated lines
            if (DONE_DATED_LINE.matcher(trimmed).matches()) {
                Matcher dm = DATED_LINE.matcher(trimmed);
                if (dm.matches()) {
                    LocalDate date = parseDate(dm.group(1));
                    items.add(new UpcomingItem(ItemType.EVENT, currentSection, date, dm.group(2).trim(), "done", null, true));
                }
                continue;
            }

            // Dated lines
            Matcher datedMatch = DATED_LINE.matcher(trimmed);
            if (datedMatch.matches()) {
                LocalDate date = parseDate(datedMatch.group(1));
                String desc = datedMatch.group(2).trim();
                String tag = datedMatch.group(3);
                ItemType type = "Content".equalsIgnoreCase(currentSection) ? ItemType.CONTENT : ItemType.EVENT;
                items.add(new UpcomingItem(type, currentSection, date, desc, tag, null, false));
                continue;
            }

            // Action lines (checkbox)
            Matcher actionMatch = ACTION_LINE.matcher(trimmed);
            if (actionMatch.matches()) {
                String owner = actionMatch.group(1).trim();
                String desc = actionMatch.group(2).trim();
                items.add(new UpcomingItem(ItemType.ACTION, currentSection, null, desc, null, owner, false));
                continue;
            }

            // TBD lines
            Matcher tbdMatch = TBD_LINE.matcher(trimmed);
            if (tbdMatch.matches()) {
                String desc = tbdMatch.group(1).trim();
                items.add(new UpcomingItem(ItemType.TBD, currentSection, null, desc, null, null, false));
            }
        }

        return items;
    }

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String extractOwner(String description) {
        int colonIdx = description.indexOf(':');
        if (colonIdx > 0 && colonIdx < 40) {
            return description.substring(0, colonIdx).trim();
        }
        return null;
    }

    // ---- Index scanning ----

    record ScanOutput(List<ScannedAction> actions, int docCount) {}

    private ScanOutput scanIndexedActions(WorkspaceManager workspace) {
        List<ScannedAction> actions = new ArrayList<>();
        Set<String> seenFiles = new HashSet<>();

        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            // Search for documents containing "Next Actions" or "Next Steps"
            List<SearchResult> results = index.search("\"Next Actions\" OR \"Next Steps\"", "MARKDOWN", 50);

            // Filter to opportunity/client paths
            for (SearchResult result : results) {
                String relPath = result.relativePath();
                if (relPath == null) continue;

                // Focus on opportunity/client directories
                boolean isOpportunity = relPath.contains("opportunity-") ||
                        relPath.contains("clients/") ||
                        relPath.contains("client/");

                if (!isOpportunity) continue;
                if (!seenFiles.add(relPath)) continue;

                // Build a human-readable label from the path
                String label = buildSourceLabel(relPath);

                // Extract action items from the file content
                // Since search results include headings and summary, check those first
                List<String> extractedActions = extractActionsFromSearchResult(result);
                if (extractedActions.isEmpty()) {
                    // If no actions found in summary/headings, try reading the file directly
                    extractedActions = extractActionsFromFile(result.path());
                }

                for (String actionText : extractedActions) {
                    actions.add(new ScannedAction(relPath, label, actionText));
                }
            }
        } catch (Exception e) {
            // Silently skip if index not available
        }

        return new ScanOutput(actions, seenFiles.size());
    }

    private String buildSourceLabel(String relativePath) {
        // Turn "eXOReaction/clients/opportunity-Mynder/README.md" into
        // "eXOReaction > opportunity-Mynder"
        String[] parts = relativePath.split("/");
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (part.equals("README.md") || part.equals("clients") || part.equals("client")) continue;
            if (part.startsWith("opportunity-") || part.contains("eXOReaction") ||
                    part.contains("Quadim") || part.contains("Cantara")) {
                if (!label.isEmpty()) {
                    label.append(" > ");
                }
                label.append(part);
            }
        }
        return label.isEmpty() ? relativePath : label.toString();
    }

    private List<String> extractActionsFromSearchResult(SearchResult result) {
        List<String> actions = new ArrayList<>();

        // Check headings and summary for action items
        String headings = result.headings() != null ? result.headings() : "";
        String summary = result.summary() != null ? result.summary() : "";
        String combined = headings + "\n" + summary;

        for (String line : combined.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[ ]") || trimmed.startsWith("- [ ]")) {
                String action = trimmed.replaceFirst("^-?\\s*\\[\\s]\\s*", "").trim();
                if (!action.isEmpty()) {
                    actions.add(action);
                }
            }
        }

        return actions;
    }

    private List<String> extractActionsFromFile(Path filePath) {
        List<String> actions = new ArrayList<>();
        if (filePath == null || !Files.exists(filePath)) return actions;

        try {
            List<String> lines = Files.readAllLines(filePath);
            boolean inNextSection = false;

            for (String line : lines) {
                String trimmed = line.trim();

                // Detect "## Next Actions" or "## Next Steps" sections
                if (trimmed.startsWith("## ") &&
                        (trimmed.toLowerCase().contains("next action") ||
                                trimmed.toLowerCase().contains("next step"))) {
                    inNextSection = true;
                    continue;
                }

                // Exit section when hitting the next ## header
                if (inNextSection && trimmed.startsWith("## ")) {
                    break;
                }

                // Collect action items within the section
                if (inNextSection) {
                    Matcher m = Pattern.compile("^-\\s+\\[\\s]\\s+(.+)$").matcher(trimmed);
                    if (m.matches()) {
                        actions.add(m.group(1).trim());
                    }
                }
            }
        } catch (IOException e) {
            // Skip if file can't be read
        }

        return actions;
    }

    // ---- Terminal rendering ----

    private void renderTerminal(LocalDate today, LocalDate cutoff, boolean fileExists, Path upcomingFile,
                                 List<UpcomingItem> events, List<UpcomingItem> overdueEvents,
                                 List<UpcomingItem> actions, List<UpcomingItem> content,
                                 List<UpcomingItem> overdueContent,
                                 List<ScannedAction> scannedActions, int scannedDocCount) {

        String title = showAll ? "Synthesis -- Upcoming (all items)"
                : "Synthesis -- Upcoming (next " + days + " days)";
        AnsiOutput.printHeader(title);

        if (!fileExists) {
            AnsiOutput.printWarning("No UPCOMING.md found at: " + upcomingFile);
            AnsiOutput.printInfo("Create " + UPCOMING_FILE + " in your workspace root to track events and actions.");
            AnsiOutput.printInfo("Run 'synthesis upcoming --help' for format details.");
            System.out.println();
        }

        boolean hasContent = false;

        // Overdue events
        if (!overdueEvents.isEmpty() || !overdueContent.isEmpty()) {
            hasContent = true;
            System.out.println("  " + AnsiOutput.red(AnsiOutput.bold("OVERDUE")));
            for (UpcomingItem item : overdueEvents) {
                printDatedItem(item, true);
            }
            for (UpcomingItem item : overdueContent) {
                printDatedItem(item, true);
            }
            System.out.println();
        }

        // Confirmed events
        if (!events.isEmpty()) {
            hasContent = true;
            System.out.println("  " + AnsiOutput.bold("CONFIRMED EVENTS"));
            for (UpcomingItem item : events) {
                printDatedItem(item, false);
            }
            System.out.println();
        }

        // Pipeline actions
        if (!actions.isEmpty()) {
            hasContent = true;
            System.out.println("  " + AnsiOutput.bold("PIPELINE ACTIONS"));
            for (UpcomingItem item : actions) {
                String ownerPadded = item.owner() != null
                        ? String.format("%-16s", item.owner())
                        : "                ";
                System.out.println("    " + AnsiOutput.dim("[ ]") + " "
                        + AnsiOutput.cyan(ownerPadded) + item.description());
            }
            System.out.println();
        }

        // Content calendar
        if (!content.isEmpty()) {
            hasContent = true;
            System.out.println("  " + AnsiOutput.bold("CONTENT CALENDAR"));
            for (UpcomingItem item : content) {
                if (item.type() == ItemType.TBD) {
                    System.out.println("    " + AnsiOutput.dim("TBD     ") + item.description());
                } else {
                    printDatedItem(item, false);
                }
            }
            System.out.println();
        }

        // Scanned actions from indexed docs
        if (!scannedActions.isEmpty()) {
            hasContent = true;
            System.out.println("  " + AnsiOutput.bold("SCANNED ACTIONS") + "  "
                    + AnsiOutput.dim("(from indexed opportunity docs)"));
            for (ScannedAction action : scannedActions) {
                String label = String.format("%-36s", action.sourceLabel());
                System.out.println("    " + AnsiOutput.dim(label) + " "
                        + AnsiOutput.dim("[ ]") + " " + action.actionText());
            }
            System.out.println();
        }

        if (!hasContent) {
            AnsiOutput.printInfo("No upcoming items found for the selected time window.");
            if (!showAll) {
                AnsiOutput.printInfo("Try 'synthesis upcoming --all' to show all items.");
            }
            System.out.println();
        }

        // Footer
        String separator = "-".repeat(45);
        System.out.println("  " + AnsiOutput.dim(separator));
        if (fileExists) {
            String homePath = System.getProperty("user.home");
            String displayPath = upcomingFile.toString();
            if (displayPath.startsWith(homePath)) {
                displayPath = "~" + displayPath.substring(homePath.length());
            }
            System.out.print("  " + AnsiOutput.dim("Source: " + displayPath));
        } else {
            System.out.print("  " + AnsiOutput.dim("Source: (no " + UPCOMING_FILE + ")"));
        }
        if (includeScannedActions) {
            System.out.print("  " + AnsiOutput.dim("|  Scanned: " + scannedDocCount + " docs"));
        }
        System.out.println();
        if (!showAll) {
            System.out.println("  " + AnsiOutput.dim("Run 'synthesis upcoming --all' to show all items"));
        }
        System.out.println();
    }

    private void printDatedItem(UpcomingItem item, boolean overdue) {
        if (item.date() == null) return;
        String monthDay = formatMonthDay(item.date());
        String tagStr = "";
        if (item.tag() != null && !item.tag().isEmpty()) {
            tagStr = "  " + AnsiOutput.dim("[" + item.tag() + "]");
        }
        if (overdue) {
            System.out.println("    " + AnsiOutput.red(monthDay) + "  "
                    + item.description() + tagStr);
        } else {
            System.out.println("    " + AnsiOutput.green(monthDay) + "  "
                    + item.description() + tagStr);
        }
    }

    /**
     * Formats a date as "Mon DD" with right-aligned day, e.g. "Mar  5", "Feb 18".
     */
    private String formatMonthDay(LocalDate date) {
        String month = date.getMonth().name().substring(0, 1)
                + date.getMonth().name().substring(1, 3).toLowerCase();
        return String.format("%s %2d", month, date.getDayOfMonth());
    }

    // ---- Markdown rendering ----

    private void renderMarkdown(LocalDate today, LocalDate cutoff, boolean fileExists, Path upcomingFile,
                                 List<UpcomingItem> events, List<UpcomingItem> overdueEvents,
                                 List<UpcomingItem> actions, List<UpcomingItem> content,
                                 List<UpcomingItem> overdueContent,
                                 List<ScannedAction> scannedActions, int scannedDocCount) {

        String windowDesc = showAll ? "all items" : "next " + days + " days";
        System.out.println("# Synthesis -- Upcoming (" + windowDesc + ")");
        System.out.println();
        System.out.println("Generated: " + today);
        System.out.println();

        if (!overdueEvents.isEmpty() || !overdueContent.isEmpty()) {
            System.out.println("## Overdue");
            for (UpcomingItem item : overdueEvents) {
                printMarkdownDatedItem(item);
            }
            for (UpcomingItem item : overdueContent) {
                printMarkdownDatedItem(item);
            }
            System.out.println();
        }

        if (!events.isEmpty()) {
            System.out.println("## Confirmed Events");
            for (UpcomingItem item : events) {
                printMarkdownDatedItem(item);
            }
            System.out.println();
        }

        if (!actions.isEmpty()) {
            System.out.println("## Pipeline Actions");
            for (UpcomingItem item : actions) {
                String owner = item.owner() != null ? "**" + item.owner() + ":** " : "";
                System.out.println("- [ ] " + owner + item.description());
            }
            System.out.println();
        }

        if (!content.isEmpty()) {
            System.out.println("## Content Calendar");
            for (UpcomingItem item : content) {
                if (item.type() == ItemType.TBD) {
                    System.out.println("- TBD  " + item.description());
                } else {
                    printMarkdownDatedItem(item);
                }
            }
            System.out.println();
        }

        if (!scannedActions.isEmpty()) {
            System.out.println("## Scanned Actions (from indexed opportunity docs)");
            for (ScannedAction action : scannedActions) {
                System.out.println("- [ ] **" + action.sourceLabel() + ":** " + action.actionText());
            }
            System.out.println();
        }

        System.out.println("---");
        if (fileExists) {
            System.out.println("Source: `" + upcomingFile + "`");
        }
        if (includeScannedActions) {
            System.out.println("Scanned: " + scannedDocCount + " opportunity docs");
        }
    }

    private void printMarkdownDatedItem(UpcomingItem item) {
        if (item.date() == null) return;
        String tag = item.tag() != null ? "  [" + item.tag() + "]" : "";
        System.out.println("- " + item.date() + "  " + item.description() + tag);
    }
}
