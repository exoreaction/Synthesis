package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphExtractor;
import io.exoreaction.synthesis.graph.CodeGraphRepository;
import io.exoreaction.synthesis.graph.CodeGraphStats;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code synthesis code-graph} -- manage the persisted code knowledge graph.
 *
 * <p>The code knowledge graph stores class-level dependency edges (imports,
 * extends, implements) and cross-format links (SQL&rarr;Java) in SQLite.
 * Once populated, {@code relate} and {@code impact} commands use instant
 * database lookups instead of reading source files.
 *
 * <p>Usage:
 * <pre>
 *   synthesis code-graph extract                # full extraction
 *   synthesis code-graph extract --incremental  # only changed files
 *   synthesis code-graph extract --stats        # show extraction statistics
 *   synthesis code-graph extract --dry-run      # show what would be extracted
 * </pre>
 *
 * @since v1.9.9 (CKG-1.06)
 */
@Command(
        name = "code-graph",
        aliases = {"cg"},
        description = "Manage the persisted code knowledge graph (extract, query)",
        mixinStandardHelpOptions = true,
        subcommands = {
                CodeGraphCommand.ExtractSub.class
        }
)
public class CodeGraphCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        System.out.println("  Use 'synthesis code-graph <subcommand>' for graph operations.");
        System.out.println();
        System.out.println("  Subcommands:");
        System.out.println("    extract   Extract code dependencies and persist to SQLite");
        System.out.println();
        return 0;
    }

    // -----------------------------------------------------------------------
    // Subcommand: extract
    // -----------------------------------------------------------------------

    /**
     * Extracts code dependency information from source files and persists
     * it to the code knowledge graph tables in SQLite.
     *
     * <p>Supports full extraction (clears and rebuilds), incremental updates
     * (only changed files), dry-run (reports counts without writing), and
     * stats-only mode (shows current graph statistics).
     */
    @Command(name = "extract",
            description = "Extract code dependencies and persist to SQLite",
            mixinStandardHelpOptions = true)
    static class ExtractSub implements Callable<Integer> {

        @ParentCommand
        private CodeGraphCommand parent;

        @Option(names = {"--incremental"},
                description = "Only re-extract changed files (faster for large codebases)",
                defaultValue = "false")
        private boolean incremental;

        @Option(names = {"--stats"},
                description = "Show current graph statistics without extracting",
                defaultValue = "false")
        private boolean statsOnly;

        @Option(names = {"--dry-run"},
                description = "Show what would be extracted without writing to the database",
                defaultValue = "false")
        private boolean dryRun;

        @Override
        public Integer call() {
            try {
                Path workspaceRoot = parent.parent.getWorkspaceRoot();
                WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
                var validation = workspace.validate();
                if (validation.isPresent()) {
                    AnsiOutput.printError(validation.get());
                    return 1;
                }

                SynthesisDatabase db = SynthesisDatabase.getDefault();
                Connection conn = db.getConnection();

                if (statsOnly) {
                    return showStats(conn, workspaceRoot);
                }

                if (dryRun) {
                    return showDryRun(workspaceRoot);
                }

                CodeGraphExtractor extractor = new CodeGraphExtractor();

                if (incremental) {
                    return runIncremental(extractor, conn, workspaceRoot);
                } else {
                    return runFull(extractor, conn, workspaceRoot);
                }
            } catch (Exception e) {
                AnsiOutput.printError("Code graph extraction failed: " + e.getMessage());
                return 1;
            }
        }

        private int showStats(Connection conn, Path workspaceRoot) throws Exception {
            CodeGraphRepository repo = new CodeGraphRepository();
            String wsPath = workspaceRoot.toString();

            int deps = repo.countDependencies(conn, wsPath);
            int links = repo.countCrossFormatLinks(conn, wsPath);
            boolean populated = repo.isPopulated(conn, wsPath);

            System.out.println();
            System.out.println("Code Knowledge Graph: " + workspaceRoot.getFileName());
            System.out.println();
            System.out.println("  Status:             " + (populated ? "populated" : "empty"));
            System.out.println("  Dependencies:       " + deps);
            System.out.println("  Cross-format links: " + links);
            System.out.println();

            if (!populated) {
                System.out.println("  Run 'synthesis code-graph extract' to populate the graph.");
                System.out.println();
            }
            return 0;
        }

        private int showDryRun(Path workspaceRoot) throws IOException {
            List<Path> javaFiles = findJavaFiles(workspaceRoot);
            List<Path> sqlFiles = findSqlFiles(workspaceRoot);

            System.out.println();
            System.out.println("Code Graph Extraction (dry-run)");
            System.out.println();
            System.out.println("  Java files:  " + javaFiles.size());
            System.out.println("  SQL files:   " + sqlFiles.size());
            System.out.println("  Total files: " + (javaFiles.size() + sqlFiles.size()));
            System.out.println();
            System.out.println("  No changes made. Remove --dry-run to extract.");
            System.out.println();
            return 0;
        }

        private int runFull(CodeGraphExtractor extractor, Connection conn,
                            Path workspaceRoot) throws Exception {
            System.out.println();
            System.out.println("Extracting code graph (full)...");

            CodeGraphStats stats = extractor.extractAndPersist(workspaceRoot, conn);

            System.out.println();
            System.out.println("  Files processed:    " + stats.filesProcessed());
            System.out.println("  Dependencies found: " + stats.dependenciesFound());
            System.out.println("  Cross-format links: " + stats.crossFormatLinks());
            System.out.println("  Packages found:     " + stats.packagesFound());
            System.out.println("  External deps:      " + stats.externalDeps());
            System.out.println("  Elapsed:            " + stats.elapsedMs() + " ms");
            System.out.println();
            return 0;
        }

        private int runIncremental(CodeGraphExtractor extractor, Connection conn,
                                   Path workspaceRoot) throws Exception {
            // For incremental, find all Java files as the "changed" set
            // (a more sophisticated approach would compare timestamps or checksums,
            //  but for now this re-extracts all files incrementally -- clearing per-file first)
            List<Path> javaFiles = findJavaFiles(workspaceRoot);
            Set<Path> changed = new HashSet<>(javaFiles);

            System.out.println();
            System.out.println("Extracting code graph (incremental, " + changed.size() + " files)...");

            CodeGraphStats stats = extractor.incrementalUpdate(workspaceRoot, conn, changed);

            System.out.println();
            System.out.println("  Files processed:    " + stats.filesProcessed());
            System.out.println("  Dependencies found: " + stats.dependenciesFound());
            System.out.println("  Packages found:     " + stats.packagesFound());
            System.out.println("  External deps:      " + stats.externalDeps());
            System.out.println("  Elapsed:            " + stats.elapsedMs() + " ms");
            System.out.println();
            return 0;
        }

        private List<Path> findJavaFiles(Path root) throws IOException {
            try (Stream<Path> walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains("/."))
                        .toList();
            }
        }

        private List<Path> findSqlFiles(Path root) throws IOException {
            try (Stream<Path> walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".sql"))
                        .filter(p -> !p.toString().contains("/."))
                        .toList();
            }
        }
    }
}
