package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
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

@Command(
        name = "validate",
        description = "Detect documentation drift — flag class names in skills/docs that don't exist in the indexed codebase",
        mixinStandardHelpOptions = true
)
public class ValidateCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--skills"}, description = "Check .claude/skills/ directory and CLAUDE.md (default when no flag given)")
    private boolean skills;

    @Option(names = {"--docs"}, description = "Check docs/ directory")
    private boolean docs;

    @Option(names = {"--all"}, description = "Check all documentation (skills + docs + gaps + integrity)")
    private boolean all;

    @Option(names = {"--gaps"}, description = "Find indexed source files that have no mention in any skill file")
    private boolean gaps;

    @Option(names = {"--integrity"}, description = "Verify factual claims in skill files against the codebase")
    private boolean integrity;

    @Override
    public Integer call() {
        if (!skills && !docs && !all && !gaps && !integrity) skills = true;
        if (all) { skills = true; docs = true; gaps = true; integrity = true; }

        Path workspaceRoot = parent.getWorkspaceRoot();
        WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
        var validation = workspace.validate();
        if (validation.isPresent()) { AnsiOutput.printError(validation.get()); return 1; }

        try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
            int exitCode = 0;

            if (skills || docs) {
                List<Path> filesToCheck = new ArrayList<>();
                if (skills) filesToCheck.addAll(collectSkillFiles(workspaceRoot));
                if (docs) filesToCheck.addAll(collectDocFiles(workspaceRoot));
                if (filesToCheck.isEmpty()) {
                    System.out.println("  No documentation files found to check.");
                } else {
                    DriftDetector detector = new DriftDetector();
                    Map<Path, List<DriftIssue>> allIssues = new LinkedHashMap<>();
                    for (Path file : filesToCheck) allIssues.put(file, detector.detect(file, index));
                    exitCode = Math.max(exitCode, printReport(allIssues, workspaceRoot));
                }
            }

            if (gaps) {
                List<Path> skillFiles = collectSkillFiles(workspaceRoot);
                GapDetector gapDetector = new GapDetector();
                exitCode = Math.max(exitCode, printGapReport(gapDetector.detectGaps(index, skillFiles), skillFiles.size()));
            }

            if (integrity) {
                List<Path> skillFiles = collectSkillFiles(workspaceRoot);
                IntegrityChecker checker = new IntegrityChecker();
                exitCode = Math.max(exitCode, printIntegrityReport(checker.checkAll(skillFiles, workspaceRoot), skillFiles.size()));
            }

            return exitCode;
        } catch (Exception e) { AnsiOutput.printError("Validate failed: " + e.getMessage()); return 1; }
    }

    private int printReport(Map<Path, List<DriftIssue>> allIssues, Path workspaceRoot) {
        System.out.println();
        int driftFiles = 0, totalIssues = 0;
        for (Map.Entry<Path, List<DriftIssue>> entry : allIssues.entrySet()) {
            Path file = entry.getKey(); List<DriftIssue> issues = entry.getValue();
            String relPath;
            try { relPath = workspaceRoot.relativize(file).toString(); } catch (IllegalArgumentException e) { relPath = file.toString(); }
            if (issues.isEmpty()) {
                System.out.println("  ✓ " + relPath + " — all identifiers verified");
            } else {
                System.out.println("  ⚠ DRIFT DETECTED in " + relPath + ":");
                for (DriftIssue issue : issues)
                    System.out.printf("    Line %d: \"%s\" — not found in any indexed source file%n", issue.line(), issue.identifier());
                driftFiles++; totalIssues += issues.size();
            }
        }
        System.out.println();
        System.out.printf("  Checked %d file(s): %d with drift, %d identifier(s) flagged.%n", allIssues.size(), driftFiles, totalIssues);
        System.out.println();
        if (driftFiles > 0) { System.out.println("  Tip: Run 'synthesis search <ClassName>' to find what replaced a stale name."); System.out.println(); }
        return driftFiles > 0 ? 1 : 0;
    }

    private int printGapReport(List<GapDetector.GapResult> gapResults, int totalSkillFiles) {
        System.out.println();
        if (gapResults.isEmpty()) { System.out.println("  ✓ Gap Analysis — all indexed source files have skill coverage"); System.out.println(); return 0; }
        System.out.printf("  ⚠ GAP ANALYSIS: %d source file(s) have no skill coverage:%n%n", gapResults.size());
        int prevPriority = -1;
        for (GapDetector.GapResult gap : gapResults) {
            if (gap.priority() != prevPriority) {
                System.out.println("  " + (gap.priority() >= 5 ? "HIGH PRIORITY" : gap.priority() >= 3 ? "MEDIUM PRIORITY" : "LOW PRIORITY") + ":");
                prevPriority = gap.priority();
            }
            System.out.printf("    %-50s  %s%n", gap.className() + ".java", gap.relativePath() != null ? gap.relativePath() : "");
        }
        System.out.println();
        System.out.printf("  Checked against %d skill file(s). Run 'synthesis validate --gaps' regularly to track coverage.%n%n", totalSkillFiles);
        return 1;
    }

    private int printIntegrityReport(List<IntegrityChecker.IntegrityIssue> issues, int totalSkillFiles) {
        System.out.println();
        if (issues.isEmpty()) { System.out.println("  ✓ Integrity Check — all factual claims verified"); System.out.println(); return 0; }
        System.out.printf("  ⚠ INTEGRITY CHECK: %d factual claim(s) may be incorrect:%n%n", issues.size());
        for (IntegrityChecker.IntegrityIssue issue : issues) {
            String relPath;
            try { relPath = issue.file().getFileName().toString(); } catch (Exception e) { relPath = issue.file().toString(); }
            System.out.printf("    [%s] %s:%d%n", issue.ruleName(), relPath, issue.line());
            System.out.printf("      Claim:  %s%n", issue.claim());
            System.out.printf("      Actual: %s%n%n", issue.actual());
        }
        System.out.printf("  Checked %d skill file(s). Run 'synthesis validate --integrity' to verify claims.%n%n", totalSkillFiles);
        return 1;
    }

    private List<Path> collectSkillFiles(Path workspaceRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        Path skillsDir = workspaceRoot.resolve(".claude").resolve("skills");
        if (Files.isDirectory(skillsDir)) {
            try (Stream<Path> walk = Files.walk(skillsDir, 1)) {
                walk.filter(Files::isRegularFile).filter(p -> { String n = p.getFileName().toString(); return n.endsWith(".md") || n.endsWith(".yaml"); }).sorted().forEach(files::add);
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
                walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(files::add);
            }
        }
        return files;
    }
}
