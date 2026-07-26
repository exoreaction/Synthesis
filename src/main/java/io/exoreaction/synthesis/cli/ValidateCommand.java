package io.exoreaction.synthesis.cli;
import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.graph.TestCoverageAnalyzer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.validate.DriftDetector;
import io.exoreaction.synthesis.validate.DriftDetector.DriftIssue;
import io.exoreaction.synthesis.validate.GapDetector;
import io.exoreaction.synthesis.validate.IntegrityChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
@Command(name = "validate",
        description = {"Validation suite: skill/doc drift (default), coverage gaps, false claims, untested code.",
                "Flags: --gaps, --integrity, --untested, --all."},
        mixinStandardHelpOptions = true)
public class ValidateCommand implements Callable<Integer> {
    @ParentCommand private SynthesisApp parent;
    @Option(names = {"--skills"}, description = "Check skills") private boolean skills;
    @Option(names = {"--docs"}, description = "Check docs") private boolean docs;
    @Option(names = {"--all"}, description = "Check all") private boolean all;
    @Option(names = {"--gaps"}, description = "Find gaps") private boolean gaps;
    @Option(names = {"--integrity"}, description = "Verify claims") private boolean integrity;
    @Option(names = {"--untested"}, description = "Find untested") private boolean untested;
    @Override
    public Integer call() {
        if (!skills && !docs && !all && !gaps && !integrity && !untested) skills = true;
        if (all) {
            skills = true; docs = true; gaps = true;
            integrity = true; untested = true;
        }
        Path workspaceRoot = parent.getWorkspaceRoot();
        WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
        var validation = workspace.validate();
        if (validation.isPresent()) { AnsiOutput.printError(validation.get()); return 1; }

        // Always print a header so stdout is never empty (#278)
        System.out.println();
        System.out.println("Validate: " + workspaceRoot.getFileName());
        System.out.println("  Workspace: " + workspaceRoot);
        scopeWarning(workspaceRoot, Path.of("").toAbsolutePath().normalize(),
                parent.getExplicitWorkspaceRoot().isPresent())
                .ifPresent(w -> System.out.println("  " + w));

        List<String> checks = new ArrayList<>();
        if (skills) checks.add("skills");
        if (docs) checks.add("docs");
        if (gaps) checks.add("gaps");
        if (integrity) checks.add("integrity");
        if (untested) checks.add("untested");
        System.out.println("  Checks:    " + String.join(", ", checks));

        try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
            int exitCode = 0;
            if (skills || docs) {
                List<Path> filesToCheck = new ArrayList<>();
                if (skills) filesToCheck.addAll(collectSkillFiles(workspaceRoot));
                if (docs) filesToCheck.addAll(collectDocFiles(workspaceRoot));
                if (filesToCheck.isEmpty()) {
                    System.out.println();
                    System.out.println("  No documentation files found to check.");
                    System.out.println("  Looked for: .claude/skills/*.md, .claude/skills/*.yaml, CLAUDE.md"
                            + (docs ? ", docs/**/*.md" : ""));
                } else {
                    // Say what was NOT in scope. Checking a lone CLAUDE.md and printing
                    // "Result: OK" reads as a pass over the skill library when no skill
                    // was looked at (#480) — the same failure the kcp-skill linter had
                    // before SK009 reported its own coverage.
                    if (skills && !Files.isDirectory(workspaceRoot.resolve(".claude").resolve("skills"))) {
                        System.out.println();
                        System.out.println("  no .claude/skills directory under this workspace — "
                                + "no skills were checked");
                    }
                    DriftDetector detector = new DriftDetector();
                    Map<Path, List<DriftIssue>> allIssues = new LinkedHashMap<>();
                    for (Path f : filesToCheck) allIssues.put(f, detector.detect(f, index));
                    exitCode = Math.max(exitCode, printReport(allIssues, workspaceRoot));
                }
            }
            if (gaps) {
                List<Path> sf = collectSkillFiles(workspaceRoot);
                GapDetector gd = new GapDetector();
                exitCode = Math.max(exitCode, printGapReport(gd.detectGaps(index, sf), sf.size()));
            }
            if (integrity) {
                List<Path> sf2 = collectSkillFiles(workspaceRoot);
                IntegrityChecker ic = new IntegrityChecker();
                exitCode = Math.max(exitCode, printIntegrityReport(ic.checkAll(sf2, workspaceRoot), sf2.size()));
            }
            if (untested) {
                List<SearchResult> af = index.listAll(null, 5000);
                TestCoverageAnalyzer tca = new TestCoverageAnalyzer();
                exitCode = Math.max(exitCode, printUntestedReport(tca.findUntested(af)));
            }

            // Summary line
            System.out.println("  Result: " + (exitCode == 0 ? "OK" : "issues found"));
            System.out.println();

            return exitCode;
        } catch (Exception e) { AnsiOutput.printError("Validate failed: " + e.getMessage()); return 1; }
    }

    /**
     * A note to print when the workspace being validated is probably not the one the
     * user meant (#480).
     *
     * <p>{@code getWorkspaceRoot()} resolves {@code -d} &rarr; {@code SYNTHESIS_WORKSPACE}
     * &rarr; {@code ~/.synthesis/workspace} &rarr; cwd. When the config file is set,
     * cwd is never reached — so running {@code validate --skills} inside a repo silently
     * validated the configured workspace instead, found no {@code .claude/skills} there,
     * checked one {@code CLAUDE.md} and reported {@code Result: OK}. A green pass over a
     * scope of one is indistinguishable from a green pass over a real library.
     *
     * <p>The precedence itself is fine and is left alone: changing it would break every
     * caller that relies on a configured default. What was missing is saying so.
     *
     * @param resolvedRoot the workspace {@code getWorkspaceRoot()} chose
     * @param cwd          the directory the user actually ran from
     * @param wasExplicit  whether {@code -d} was passed on this invocation
     * @return a warning to print, or empty when the scope is unsurprising
     */
    static Optional<String> scopeWarning(Path resolvedRoot, Path cwd, boolean wasExplicit) {
        if (wasExplicit) return Optional.empty();       // the user said which one
        if (cwd.equals(resolvedRoot) || cwd.startsWith(resolvedRoot)) return Optional.empty();
        return Optional.of("NOTE: workspace was defaulted, not requested — you are in "
                + cwd + ". Pass -d " + cwd + " to validate there instead.");
    }

    private int printReport(Map<Path, List<DriftIssue>> allIssues, Path workspaceRoot) {
        System.out.println();
        int driftFiles = 0, totalIssues = 0;
        for (Map.Entry<Path, List<DriftIssue>> entry : allIssues.entrySet()) {
            Path file = entry.getKey(); List<DriftIssue> issues = entry.getValue();
            String relPath;
            try { relPath = workspaceRoot.relativize(file).toString(); }
            catch (IllegalArgumentException e) { relPath = file.toString(); }
            if (issues.isEmpty()) {
                System.out.println("  OK " + relPath);
            } else {
                System.out.println("  DRIFT in " + relPath + ":");
                for (DriftIssue issue : issues)
                    System.out.printf("    Line %d: %s%n", issue.line(), issue.identifier());
                driftFiles++; totalIssues += issues.size();
            }
        }
        System.out.println();
        System.out.printf("  Checked %d files: %d with drift, %d flagged.%n", allIssues.size(), driftFiles, totalIssues);
        System.out.println();
        if (driftFiles > 0) { System.out.println("  Tip: synthesis search <Name> to find replacements."); System.out.println(); }
        return driftFiles > 0 ? 1 : 0;
    }

    private int printGapReport(List<GapDetector.GapResult> gaps, int n) {
        System.out.printf("  GAP: %d source file(s) have no skill coverage:%n%n", gaps.size());
        int prevP = -1;
        for (GapDetector.GapResult gap : gaps) {
            if (gap.priority() != prevP) {
                String label = gap.priority() >= 5 ? "HIGH" : gap.priority() >= 3 ? "MED" : "LOW";
                System.out.println("  " + label + " PRIORITY:");
                prevP = gap.priority();
            }
            System.out.printf("    %-50s  %s%n", gap.className() + ".java", gap.relativePath() != null ? gap.relativePath() : "");
        }
        System.out.println();
        System.out.printf("  Checked against %d skill file(s).%n%n", n);
        return 1;
    }

    private int printIntegrityReport(List<IntegrityChecker.IntegrityIssue> issues, int n) {
        System.out.println();
        if (issues.isEmpty()) { System.out.println("  Integrity Check passed."); System.out.println(); return 0; }
        System.out.printf("  INTEGRITY: %d claim(s) may be incorrect:%n%n", issues.size());
        for (IntegrityChecker.IntegrityIssue issue : issues) {
            String relPath;
            try { relPath = issue.file().getFileName().toString(); }
            catch (Exception e) { relPath = issue.file().toString(); }
            System.out.printf("    [%s] %s:%d%n", issue.ruleName(), relPath, issue.line());
            System.out.printf("      Claim:  %s%n", issue.claim());
            System.out.printf("      Actual: %s%n%n", issue.actual());
        }
        System.out.printf("  Checked %d skill file(s).%n%n", n);
        return 1;
    }

    private int printUntestedReport(List<SearchResult> uf) {
        System.out.println();
        if (uf.isEmpty()) { System.out.println("  Test Coverage passed."); System.out.println(); return 0; }
        System.out.printf("  UNTESTED: %d source file(s) have no test class:%n%n", uf.size());
        for (SearchResult f : uf) {
            System.out.printf("    %-50s  %s%n", f.fileName(), f.relativePath());
        }
        System.out.println();
        System.out.println("  Tip: synthesis relate <file> --tests");
        System.out.println();
        return 1;
    }

    /** Depth of the skills tree we descend. Enough for both supported layouts —
     *  flat {@code <name>.yaml}, grouped {@code <group>/<name>.yaml}, and
     *  {@code <name>/SKILL.md} — without walking an arbitrarily deep tree (#481). */
    private static final int SKILLS_MAX_DEPTH = 3;

    private List<Path> collectSkillFiles(Path workspaceRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        Path skillsDir = workspaceRoot.resolve(".claude").resolve("skills");
        if (Files.isDirectory(skillsDir)) {
            // Was maxDepth 1, which silently skipped every grouped skill — 19 of them
            // in ~/.claude/skills/common/ alone — and skipped the <name>/SKILL.md
            // layout entirely (#340). A bounded enumeration is fine; an invisible bound
            // is not, so the depth is named and the count is reported by the caller.
            try (Stream<Path> walk = Files.walk(skillsDir, SKILLS_MAX_DEPTH)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") || n.endsWith(".yaml");
                    })
                    .sorted().forEach(files::add);
            }
        }
        Path claudeMd = workspaceRoot.resolve("CLAUDE.md");
        if (Files.isRegularFile(claudeMd)) files.add(claudeMd);
        return files;
    }

    private List<Path> collectDocFiles(Path workspaceRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        Path docsDir = workspaceRoot.resolve("docs");
        if (Files.isDirectory(docsDir)) {
            try (Stream<Path> walk = Files.walk(docsDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted().forEach(files::add);
            }
        }
        return files;
    }

}
