package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.validate.DriftDetector;
import io.exoreaction.synthesis.validate.DriftDetector.DriftIssue;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Detects documentation drift by cross-referencing CamelCase class names
 * in skill files and documentation against the Lucene index of the workspace.
 *
 * <p>Usage:
 * <pre>
 *   synthesis validate                # Check .claude/skills/ (default)
 *   synthesis validate --skills       # Check .claude/skills/ and CLAUDE.md
 *   synthesis validate --docs         # Check docs/
 *   synthesis validate --all          # Check everything
 * </pre>
 *
 * <p>Exit codes: 0 = no drift found, 1 = drift detected or error.
 */
@Command(
        name = "validate",
        description = "Detect documentation drift — flag class names in skills/docs that don't exist in the indexed codebase",
        mixinStandardHelpOptions = true
)
public class ValidateCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--skills"},
            description = "Check .claude/skills/ directory and CLAUDE.md (default when no flag given)")
    private boolean skills;

    @Option(names = {"--docs"},
            description = "Check docs/ directory")
    private boolean docs;

    @Option(names = {"--all"},
            description = "Check all documentation (skills + docs)")
    private boolean all;

    @Override
    public Integer call() {
        // Default: check skills
        if (!skills && !docs && !all) {
            skills = true;
        }
        if (all) {
            skills = true;
            docs = true;
        }

        Path workspaceRoot = parent.getWorkspaceRoot();
        WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);

        var validation = workspace.validate();
        if (validation.isPresent()) {
            AnsiOutput.printError(validation.get());
            return 1;
        }

        try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
            List<Path> filesToCheck = new ArrayList<>();

            if (skills) {
                filesToCheck.addAll(collectSkillFiles(workspaceRoot));
            }
            if (docs) {
                filesToCheck.addAll(collectDocFiles(workspaceRoot));
            }

            if (filesToCheck.isEmpty()) {
                System.out.println("  No documentation files found to check.");
                return 0;
            }

            DriftDetector detector = new DriftDetector();
            Map<Path, List<DriftIssue>> allIssues = new LinkedHashMap<>();

            for (Path file : filesToCheck) {
                allIssues.put(file, detector.detect(file, index));
            }

            return printReport(allIssues, workspaceRoot);

        } catch (Exception e) {
            AnsiOutput.printError("Validate failed: " + e.getMessage());
            return 1;
        }
    }

    private int printReport(Map<Path, List<DriftIssue>> allIssues, Path workspaceRoot) {
        System.out.println();
        int driftFiles = 0;
        int totalIssues = 0;

        for (Map.Entry<Path, List<DriftIssue>> entry : allIssues.entrySet()) {
            Path file = entry.getKey();
            List<DriftIssue> issues = entry.getValue();

            String relPath;
            try {
                relPath = workspaceRoot.relativize(file).toString();
            } catch (IllegalArgumentException e) {
                relPath = file.toString();
            }

            if (issues.isEmpty()) {
                System.out.println("  \u2713 " + relPath + " \u2014 all identifiers verified");
            } else {
                System.out.println("  \u26a0 DRIFT DETECTED in " + relPath + ":");
                for (DriftIssue issue : issues) {
                    System.out.printf("    Line %d: \"%s\" \u2014 not found in any indexed source file%n",
                            issue.line(), issue.identifier());
                }
                driftFiles++;
                totalIssues += issues.size();
            }
        }

        System.out.println();
        System.out.printf("  Checked %d file(s): %d with drift, %d identifier(s) flagged.%n",
                allIssues.size(), driftFiles, totalIssues);
        System.out.println();

        if (driftFiles > 0) {
            System.out.println("  Tip: Run 'synthesis search <ClassName>' to find what replaced a stale name.");
            System.out.println();
        }

        return driftFiles > 0 ? 1 : 0;
    }

    /** Collects {@code .claude/skills/*.md}, {@code .claude/skills/*.yaml}, and {@code CLAUDE.md}. */
    private List<Path> collectSkillFiles(Path workspaceRoot) throws IOException {
        List<Path> files = new ArrayList<>();

        Path skillsDir = workspaceRoot.resolve(".claude").resolve("skills");
        if (Files.isDirectory(skillsDir)) {
            try (Stream<Path> walk = Files.walk(skillsDir, 1)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".md") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .forEach(files::add);
            }
        }

        Path claudeMd = workspaceRoot.resolve("CLAUDE.md");
        if (Files.isRegularFile(claudeMd)) {
            files.add(claudeMd);
        }

        return files;
    }

    /** Collects all {@code .md} files under {@code docs/}. */
    private List<Path> collectDocFiles(Path workspaceRoot) throws IOException {
        List<Path> files = new ArrayList<>();

        Path docsDir = workspaceRoot.resolve("docs");
        if (Files.isDirectory(docsDir)) {
            try (Stream<Path> walk = Files.walk(docsDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(files::add);
            }
        }

        return files;
    }
}
