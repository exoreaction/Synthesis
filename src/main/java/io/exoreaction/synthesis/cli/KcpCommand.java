package io.exoreaction.synthesis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.kcp.KcpRepository;
import io.exoreaction.synthesis.kcp.KcpVerifier;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.Version;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * KCP manifest tooling: verify declarations against evidence, and find
 * knowledge coverage gaps (issue #356, Phase 3 of the v0.25 alignment epic).
 *
 * <pre>
 *   synthesis kcp verify                    # verify all indexed manifests
 *   synthesis kcp verify --manifest path    # one manifest (suffix match)
 *   synthesis kcp verify --format json      # machine-readable, for CI
 *   synthesis kcp gaps                      # hot files with no unit coverage
 *   synthesis kcp gaps --limit 25
 * </pre>
 *
 * <p>{@code verify} exits 1 when any HIGH-severity finding (a contradicted
 * declaration) is present, so it can gate CI alongside {@code kcp-agent replay}.
 * Verdicts are persisted to the {@code kcp_verification} table (V24) beside —
 * never overwriting — the manifest's own declared {@code verification_status}.
 */
@Command(
        name = "kcp",
        description = {"KCP manifest tools: scaffold manifests, verify declarations against evidence, find coverage gaps.",
                "verify/gaps require manifests to be indexed first (synthesis scan); init works on any repo directory."},
        mixinStandardHelpOptions = true,
        subcommands = {KcpCommand.InitSub.class, KcpCommand.VerifySub.class, KcpCommand.GapsSub.class}
)
public class KcpCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        System.out.println("Usage: synthesis kcp <init|verify|gaps> — see 'synthesis kcp --help'");
        return 0;
    }

    // -----------------------------------------------------------------------
    // synthesis kcp init
    // -----------------------------------------------------------------------

    @Command(name = "init",
            description = {"Scaffold a KCP v0.25 knowledge.yaml from repository structure (issue #310).",
                    "Detects README/docs/policy markdown, Maven modules, CI workflows, and test roots. "
                            + "Never overwrites an existing knowledge.yaml. Generated manifests carry "
                            + "hints.generated_by so automated refresh can tell them from hand-authored ones."},
            mixinStandardHelpOptions = true)
    static class InitSub implements Callable<Integer> {

        @ParentCommand
        private KcpCommand kcpParent;

        @picocli.CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Repository directory to scaffold (default: the workspace root)")
        private Path targetDir;

        @Option(names = {"--batch"}, description = "Scaffold every direct child git repository under this directory")
        private Path batchRoot;

        @Option(names = {"--dry-run"}, description = "Print the manifest(s) without writing")
        private boolean dryRun;

        @Override
        public Integer call() {
            String version = Version.getVersion();
            if (batchRoot != null) {
                if (!Files.isDirectory(batchRoot)) {
                    AnsiOutput.printError("Not a directory: " + batchRoot);
                    return 2;
                }
                int written = 0, skipped = 0, empty = 0;
                try (var children = Files.list(batchRoot)) {
                    for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                        if (!Files.exists(child.resolve(".git"))) continue;
                        switch (scaffoldOne(child, version)) {
                            case WRITTEN -> written++;
                            case SKIPPED -> skipped++;
                            case EMPTY -> empty++;
                        }
                    }
                } catch (Exception e) {
                    AnsiOutput.printError("Batch scaffold failed: " + e.getMessage());
                    return 2;
                }
                System.out.printf("%nBatch complete: %d written, %d skipped (existing manifest), %d empty.%n",
                        written, skipped, empty);
                return 0;
            }

            Path target = targetDir != null ? targetDir : kcpParent.parent.getWorkspaceRoot();
            if (!Files.isDirectory(target)) {
                AnsiOutput.printError("Not a directory: " + target);
                return 2;
            }
            return scaffoldOne(target, version) == ScaffoldOutcome.WRITTEN || dryRun ? 0 : 1;
        }

        private enum ScaffoldOutcome { WRITTEN, SKIPPED, EMPTY }

        private ScaffoldOutcome scaffoldOne(Path repoDir, String version) {
            Path manifest = repoDir.resolve("knowledge.yaml");
            if (Files.exists(manifest)) {
                AnsiOutput.printWarning(repoDir + ": knowledge.yaml already exists — not overwriting "
                        + "(hand-authored manifests are never replaced).");
                return ScaffoldOutcome.SKIPPED;
            }
            Map<String, String> gitDates = ExportCommand.collectGitCommitDates(repoDir);
            String yaml = io.exoreaction.synthesis.kcp.KcpScaffolder.scaffold(repoDir, version, gitDates);
            if (yaml == null) {
                AnsiOutput.printWarning(repoDir + ": nothing recognisable to scaffold — no manifest written.");
                return ScaffoldOutcome.EMPTY;
            }
            if (dryRun) {
                System.out.println("# --- " + manifest + " (dry-run, not written) ---");
                System.out.println(yaml);
                return ScaffoldOutcome.WRITTEN;
            }
            try {
                Files.writeString(manifest, yaml);
            } catch (Exception e) {
                AnsiOutput.printError(repoDir + ": failed to write manifest: " + e.getMessage());
                return ScaffoldOutcome.EMPTY;
            }
            System.out.println("  [OK] " + manifest);
            if (io.exoreaction.synthesis.kcp.KcpManifestChecks.isManifestGitIgnored(repoDir, "knowledge.yaml")) {
                AnsiOutput.printWarning("  " + io.exoreaction.synthesis.kcp.KcpManifestChecks
                        .warningFor("knowledge.yaml"));
            }
            return ScaffoldOutcome.WRITTEN;
        }
    }

    // -----------------------------------------------------------------------
    // synthesis kcp verify
    // -----------------------------------------------------------------------

    @Command(name = "verify",
            description = {"Verify KCP manifest declarations against filesystem, content, and git evidence.",
                    "Checks: V001 missing path, V002 hash mismatch, V003 stale declaration, "
                            + "V004 dead trigger, V005 dangling reference, V006 temporal sanity, "
                            + "plus K-series health signals. Exits 1 on HIGH findings."},
            mixinStandardHelpOptions = true)
    static class VerifySub implements Callable<Integer> {

        @ParentCommand
        private KcpCommand kcpParent;

        @Option(names = {"--manifest", "-m"}, description = "Verify only manifests whose path ends with this value")
        private String manifestFilter;

        @Option(names = {"--format", "-f"}, description = "Output format: text (default) or json", defaultValue = "text")
        private String format;

        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = kcpParent.parent.getWorkspaceRoot();
            Map<String, String> gitDates = ExportCommand.collectGitCommitDates(workspaceRoot);
            String today = LocalDate.now().toString();
            long now = System.currentTimeMillis();
            String version = Version.getVersion();

            List<KcpVerifier.Result> results = new ArrayList<>();
            // Shared default instance (~/.synthesis/synthesis.db) — not closed here
            Connection conn = SynthesisDatabase.getDefault().getConnection();
            KcpRepository repo = new KcpRepository();
            List<KcpRepository.KcpManifestRow> manifests =
                    repo.getManifests(conn, workspaceRoot.toString());
            if (manifestFilter != null) {
                manifests = manifests.stream()
                        .filter(m -> m.filePath().endsWith(manifestFilter))
                        .toList();
            }
            if (manifests.isEmpty()) {
                AnsiOutput.printWarning("No indexed KCP manifests"
                        + (manifestFilter != null ? " matching '" + manifestFilter + "'" : "")
                        + " for workspace " + workspaceRoot + " — run 'synthesis scan' first.");
                return 2;
            }

            for (KcpRepository.KcpManifestRow m : manifests) {
                List<KcpRepository.KcpUnitRow> units =
                        repo.getUnitsForManifest(conn, workspaceRoot.toString(), m.filePath());
                KcpVerifier.Result result = KcpVerifier.verifyManifest(
                        m, units,
                        repo.getRelationshipsForManifest(conn, workspaceRoot.toString(), m.filePath()),
                        gitDates, workspaceRoot, today);
                results.add(result);

                // Persist verdicts beside the declarations (kcp_verification, V24)
                for (Map.Entry<String, String> verdict : result.unitVerdicts().entrySet()) {
                    List<KcpVerifier.Finding> unitFindings = result.findings().stream()
                            .filter(f -> verdict.getKey().equals(f.unitId()))
                            .toList();
                    repo.upsertVerification(conn, workspaceRoot.toString(), m.filePath(),
                            verdict.getKey(), verdict.getValue(),
                            unitFindings.isEmpty() ? null : JSON.writeValueAsString(unitFindings),
                            version, now);
                }
            }

            boolean contradicted = results.stream().anyMatch(KcpVerifier.Result::hasContradictions);
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
            } else {
                printText(results);
            }
            return contradicted ? 1 : 0;
        }

        private void printText(List<KcpVerifier.Result> results) {
            for (KcpVerifier.Result result : results) {
                System.out.println();
                System.out.println(AnsiOutput.bold("Manifest: " + result.manifestFile()));
                long observed = result.unitVerdicts().values().stream()
                        .filter("observed"::equals).count();
                System.out.printf("  Units: %d observed, %d stale, %d contradicted%n",
                        observed,
                        result.unitVerdicts().values().stream().filter("stale"::equals).count(),
                        result.unitVerdicts().values().stream().filter("contradicted"::equals).count());
                for (KcpVerifier.Finding f : result.findings()) {
                    String line = String.format("  %s [%s]%s %s",
                            f.checkId(), f.severity(),
                            f.unitId() != null ? " " + f.unitId() + ":" : "",
                            f.detail());
                    if ("HIGH".equals(f.severity())) {
                        System.out.println(AnsiOutput.error(line));
                    } else {
                        System.out.println(line);
                    }
                }
                if (result.findings().isEmpty()) {
                    System.out.println("  All declarations hold — units marked 'observed'.");
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // synthesis kcp gaps
    // -----------------------------------------------------------------------

    @Command(name = "gaps",
            description = {"Rank hot files (git churn) that no KCP unit covers.",
                    "Requires 'synthesis hotspots --refresh' to have populated git metrics (V20)."},
            mixinStandardHelpOptions = true)
    static class GapsSub implements Callable<Integer> {

        @ParentCommand
        private KcpCommand kcpParent;

        @Option(names = {"--limit", "-n"}, description = "Maximum gaps to show (default 15)", defaultValue = "15")
        private int limit;

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = kcpParent.parent.getWorkspaceRoot();

            record Gap(String path, double score, int fanIn) {}
            List<Gap> gaps = new ArrayList<>();

            // Shared default instance (~/.synthesis/synthesis.db) — not closed here
            Connection conn = SynthesisDatabase.getDefault().getConnection();

            // Paths covered by any KCP unit
            Set<String> covered = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT path FROM kcp_units WHERE workspace_path = ?")) {
                ps.setString(1, workspaceRoot.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String p = rs.getString("path");
                        if (p != null) covered.add(p.replace('\\', '/'));
                    }
                }
            }

            // Module fan-in for boost annotation (CKG-2 profiles, may be empty)
            List<String[]> modules = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT module_path, fan_in FROM module_profiles WHERE workspace_path = ?")) {
                ps.setString(1, workspaceRoot.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        modules.add(new String[]{rs.getString("module_path"),
                                String.valueOf(rs.getInt("fan_in"))});
                    }
                }
            } catch (Exception e) {
                // table absent — fan-in stays 0
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT file_path, hotspot_score FROM git_file_metrics "
                            + "WHERE workspace_path = ? ORDER BY hotspot_score DESC")) {
                ps.setString(1, workspaceRoot.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && gaps.size() < limit) {
                        String path = rs.getString("file_path").replace('\\', '/');
                        if (covered.contains(path) || isNotKnowledgeGapCandidate(path)) continue;
                        int fanIn = 0;
                        for (String[] module : modules) {
                            if (path.startsWith(module[0])) {
                                fanIn = Math.max(fanIn, Integer.parseInt(module[1]));
                            }
                        }
                        gaps.add(new Gap(path, rs.getDouble("hotspot_score"), fanIn));
                    }
                }
            }

            if (gaps.isEmpty()) {
                System.out.println("No coverage gaps found — either every hot file is covered by a "
                        + "KCP unit, or git metrics are empty (run 'synthesis hotspots --refresh').");
                return 0;
            }

            System.out.println(AnsiOutput.bold("KCP coverage gaps (hot files with no knowledge unit):"));
            for (Gap gap : gaps) {
                System.out.printf("  %.3f  %s%s%n", gap.score(), gap.path(),
                        gap.fanIn() > 0 ? "  [module fan-in: " + gap.fanIn() + "]" : "");
            }
            System.out.println();
            System.out.println("Add units for these paths to knowledge.yaml, or generate with "
                    + "'synthesis export --format kcp'.");
            return 0;
        }

        /** Manifests, Synthesis internals, and VCS metadata are never knowledge gaps. */
        static boolean isNotKnowledgeGapCandidate(String path) {
            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            return path.startsWith(".synthesis/") || path.contains("/.synthesis/")
                    || path.startsWith(".git/") || path.contains("/.git/")
                    || fileName.equals("knowledge.yaml") || fileName.equals("knowledge.yaml.sig")
                    || fileName.equals(".synthesis.md") || fileName.equals(".synthesisignore")
                    || fileName.equals(".gitignore");
        }
    }
}
