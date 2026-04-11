package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.git.GitIntegration;
import io.exoreaction.synthesis.util.AnsiOutput;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * {@code synthesis archaeology} — surface architectural decisions buried in git history.
 *
 * <p>Scans commit messages using regex patterns at three confidence levels:
 * <ul>
 *   <li>0.95 — explicit inline markers: WHY:, DECISION:, TRADEOFF:, ADR:, RATIONALE:, REJECTED:
 *   <li>0.80 — migration/adoption signals: migrate, switch to, replace with, adopt, rewrite, redesign
 *   <li>0.65 — fix signals with implicit decisions: workaround, hotfix
 * </ul>
 *
 * <p>No LLM, no database -- always runs live against git log.
 */
@Command(
        name = "archaeology",
        description = "Surface architectural decisions from git commit history",
        mixinStandardHelpOptions = true
)
public class ArchaeologyCommand implements Callable<Integer> {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    // Confidence 0.95 — explicit decision markers
    static final Pattern INLINE_MARKERS = Pattern.compile(
            "(?i)\\b(WHY|DECISION|TRADEOFF|ADR|RATIONALE|REJECTED)\\s*:",
            Pattern.CASE_INSENSITIVE);

    // Confidence 0.80 — migration / adoption signals
    static final Pattern MIGRATION_SIGNALS = Pattern.compile(
            "(?i)\\b(migrat(e|ed|ing|ion)|switch(ed|ing)?\\s+to|replac(e|ed|ing)(\\s+\\S+)*\\s+(with|by)|deprecat(e|ed|ing)|adopt(ed|ing)?|rewrit(e|ten|ing)|redesign(ed|ing)?)\\b");

    // Confidence 0.65 — fix signals with implicit decisions
    static final Pattern FIX_SIGNALS = Pattern.compile(
            "(?i)\\b(workaround|hot.?fix)\\b");

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--since"}, description = "Days of history to scan (default: 365)",
            defaultValue = "365")
    private int sinceDays;

    @Option(names = {"--limit"}, description = "Maximum findings to show (default: 30)",
            defaultValue = "30")
    private int limit;

    @Option(names = {"--min-confidence"}, description = "Minimum confidence threshold 0.0-1.0 (default: 0.65)",
            defaultValue = "0.65")
    private double minConfidence;

    @Option(names = {"--path"}, description = "Filter findings to commits touching files under this prefix")
    private String pathPrefix;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            FileRepositoryBuilder builder = new FileRepositoryBuilder()
                    .readEnvironment()
                    .findGitDir(workspaceRoot.toFile());
            if (builder.getGitDir() == null) {
                AnsiOutput.printError("Not a git repository: " + workspaceRoot);
                return 1;
            }

            List<Finding> findings = new ArrayList<>();
            Instant cutoff = Instant.now().minus(sinceDays, ChronoUnit.DAYS);

            try (Repository repo = builder.build();
                 Git git = new Git(repo)) {

                Iterable<RevCommit> commits = git.log().call();
                for (RevCommit commit : commits) {
                    Instant commitTime = Instant.ofEpochSecond(commit.getCommitTime());
                    if (commitTime.isBefore(cutoff)) break;

                    String message = commit.getFullMessage();
                    List<String> changedFiles = getChangedFiles(git, repo, commit);

                    if (pathPrefix != null && !pathPrefix.isBlank()) {
                        boolean relevant = changedFiles.stream()
                                .anyMatch(f -> f.startsWith(pathPrefix));
                        if (!relevant) continue;
                    }

                    // Check each line of the commit message
                    for (String line : message.lines().toList()) {
                        String trimmed = line.trim();
                        if (trimmed.isBlank()) continue;

                        if (INLINE_MARKERS.matcher(trimmed).find() && 0.95 >= minConfidence) {
                            findings.add(new Finding(
                                    commit.getName().substring(0, 8),
                                    commitTime,
                                    commit.getAuthorIdent().getName(),
                                    SignalType.INLINE_MARKER,
                                    0.95,
                                    trimmed,
                                    changedFiles));
                            break;
                        } else if (MIGRATION_SIGNALS.matcher(trimmed).find() && 0.80 >= minConfidence) {
                            findings.add(new Finding(
                                    commit.getName().substring(0, 8),
                                    commitTime,
                                    commit.getAuthorIdent().getName(),
                                    SignalType.MIGRATION,
                                    0.80,
                                    trimmed,
                                    changedFiles));
                            break;
                        } else if (FIX_SIGNALS.matcher(trimmed).find() && 0.65 >= minConfidence) {
                            findings.add(new Finding(
                                    commit.getName().substring(0, 8),
                                    commitTime,
                                    commit.getAuthorIdent().getName(),
                                    SignalType.FIX_SIGNAL,
                                    0.65,
                                    trimmed,
                                    changedFiles));
                            break;
                        }
                    }

                    if (findings.size() >= limit) break;
                }
            }

            printFindings(findings);
            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Archaeology failed: " + e.getMessage());
            return 1;
        }
    }

    private void printFindings(List<Finding> findings) {
        System.out.println();
        AnsiOutput.printHeader("Decision Archaeology");
        System.out.println();

        if (findings.isEmpty()) {
            System.out.println("  No decision signals found in the last " + sinceDays + " days.");
            System.out.println();
            return;
        }

        // Group by confidence tier
        List<Finding> high = findings.stream().filter(f -> f.confidence() >= 0.95).toList();
        List<Finding> medium = findings.stream()
                .filter(f -> f.confidence() >= 0.80 && f.confidence() < 0.95).toList();
        List<Finding> low = findings.stream().filter(f -> f.confidence() < 0.80).toList();

        if (!high.isEmpty()) {
            System.out.println(AnsiOutput.bold("Explicit markers") + " (confidence 0.95+) — " + high.size() + " finding(s):");
            for (Finding f : high) printFinding(f);
        }
        if (!medium.isEmpty()) {
            System.out.println(AnsiOutput.bold("Migration signals") + " (confidence 0.80) — " + medium.size() + " finding(s):");
            for (Finding f : medium) printFinding(f);
        }
        if (!low.isEmpty()) {
            System.out.println(AnsiOutput.bold("Fix signals") + " (confidence 0.65) — " + low.size() + " finding(s):");
            for (Finding f : low) printFinding(f);
        }

        System.out.println("  Total: " + findings.size() + " decision signal(s) in the last " + sinceDays + " days.");
        System.out.println();
    }

    private void printFinding(Finding f) {
        System.out.printf("  %s  %s  %s%n",
                AnsiOutput.dim(f.hash()),
                AnsiOutput.dim(DATE_FMT.format(f.timestamp())),
                f.author());
        System.out.println("    " + AnsiOutput.yellow(f.matchedLine()));

        List<String> files = f.changedFiles();
        if (!files.isEmpty()) {
            String preview = String.join(", ", files.subList(0, Math.min(3, files.size())));
            if (files.size() > 3) preview += " (+" + (files.size() - 3) + " more)";
            System.out.println("    " + AnsiOutput.dim("files: " + preview));
        }
        System.out.println();
    }

    private List<String> getChangedFiles(Git git, Repository repo, RevCommit commit)
            throws Exception {
        AbstractTreeIterator newTree = treeParser(repo, commit);
        AbstractTreeIterator oldTree = commit.getParentCount() > 0
                ? treeParser(repo, commit.getParent(0))
                : new EmptyTreeIterator();

        return git.diff()
                .setOldTree(oldTree)
                .setNewTree(newTree)
                .call()
                .stream()
                .map(d -> d.getNewPath().equals("/dev/null") ? d.getOldPath() : d.getNewPath())
                .filter(p -> !p.equals("/dev/null"))
                .toList();
    }

    private AbstractTreeIterator treeParser(Repository repo, RevCommit commit) throws Exception {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (var reader = repo.newObjectReader()) {
            parser.reset(reader, commit.getTree().getId());
        }
        return parser;
    }

    enum SignalType {
        INLINE_MARKER, MIGRATION, FIX_SIGNAL
    }

    record Finding(
            String hash,
            Instant timestamp,
            String author,
            SignalType signalType,
            double confidence,
            String matchedLine,
            List<String> changedFiles
    ) {}
}
