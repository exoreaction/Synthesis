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
        subcommands = {KcpCommand.InitSub.class, KcpCommand.RefreshSub.class,
                KcpCommand.VerifySub.class, KcpCommand.GapsSub.class,
                KcpCommand.CatalogSub.class, KcpCommand.FederateSub.class,
                KcpCommand.PlanSub.class}
)
public class KcpCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        System.out.println("Usage: synthesis kcp <init|refresh|verify|gaps|catalog|federate|plan> "
                + "— see 'synthesis kcp --help'");
        return 0;
    }

    // -----------------------------------------------------------------------
    // synthesis kcp plan
    // -----------------------------------------------------------------------

    @Command(name = "plan",
            description = {"Produce an ordered read plan for a task from indexed KCP units (RFC-0007 scoring).",
                    "Deterministic, no model: trigger match 5pts, intent 3pts, id/path 1pt. Expired and "
                            + "superseded units are skipped; --budget caps total token estimate."},
            mixinStandardHelpOptions = true)
    static class PlanSub implements Callable<Integer> {

        @ParentCommand
        private KcpCommand kcpParent;

        @picocli.CommandLine.Parameters(index = "0", description = "Task or question to plan a read order for")
        private String task;

        @Option(names = {"--budget"}, description = "Max total token estimate to admit (0 = unlimited)",
                defaultValue = "0")
        private int budget;

        @Option(names = {"--format", "-f"}, description = "Output format: text (default) or json",
                defaultValue = "text")
        private String format;

        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = kcpParent.parent.getWorkspaceRoot();
            var plan = io.exoreaction.synthesis.kcp.KcpPlanner.plan(
                    task, collectCandidates(workspaceRoot), LocalDate.now().toString(), budget);

            if ("json".equalsIgnoreCase(format)) {
                System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
                return plan.units().isEmpty() ? 1 : 0;
            }

            if (plan.units().isEmpty()) {
                System.out.println("No KCP units matched '" + task + "'. "
                        + "Ensure manifests are indexed (synthesis scan).");
                return 1;
            }
            System.out.println(AnsiOutput.bold("Read plan for: " + task));
            System.out.printf("  %d unit(s), ~%d tokens%n", plan.units().size(), plan.totalTokenEstimate());
            int n = 1;
            for (var u : plan.units()) {
                System.out.printf("  %d. %s  (score %d, ~%d tok) — %s%n",
                        n++, u.path() != null ? u.path() : u.unitId(), u.score(),
                        u.tokenEstimate(), u.matchReason());
                if (u.intent() != null) System.out.println("       ↳ " + u.intent());
            }
            return 0;
        }

        /** Aggregates every indexed unit across all manifests into planner candidates. */
        static List<io.exoreaction.synthesis.kcp.KcpPlanner.Candidate> collectCandidates(Path workspaceRoot)
                throws Exception {
            List<io.exoreaction.synthesis.kcp.KcpPlanner.Candidate> candidates = new ArrayList<>();
            Connection conn = SynthesisDatabase.getDefault().getConnection();
            KcpRepository repo = new KcpRepository();
            for (KcpRepository.KcpManifestRow m : repo.getManifests(conn, workspaceRoot.toString())) {
                for (KcpRepository.KcpUnitRow u :
                        repo.getUnitsForManifest(conn, workspaceRoot.toString(), m.filePath())) {
                    candidates.add(new io.exoreaction.synthesis.kcp.KcpPlanner.Candidate(
                            u, m.filePath(), workspaceRoot));
                }
            }
            return candidates;
        }
    }

    // -----------------------------------------------------------------------
    // Estate scan shared by catalog + federate
    // -----------------------------------------------------------------------

    private static List<io.exoreaction.synthesis.kcp.KcpFederationBuilder.RepoEntry>
            scanEstate(KcpCommand kcpParent, Path estateRoot) {
        io.exoreaction.synthesis.org.OrganizationRegistry registry;
        try {
            registry = new io.exoreaction.synthesis.org.OrganizationRegistry(
                    kcpParent.parent.getWorkspaceRoot());
            registry.load();
        } catch (Exception e) {
            registry = null;
        }
        final var reg = registry != null && registry.hasOrganizations() ? registry : null;
        java.util.function.Function<Path, String> orgResolver = reg == null ? null
                : dir -> {
                    try {
                        return reg.resolveOrganization(dir);
                    } catch (Exception e) {
                        return null;
                    }
                };
        return io.exoreaction.synthesis.kcp.KcpFederationBuilder.scanEstate(
                estateRoot, orgResolver, LocalDate.now().toString());
    }

    // -----------------------------------------------------------------------
    // synthesis kcp catalog
    // -----------------------------------------------------------------------

    @Command(name = "catalog",
            description = {"Emit a catalog.yaml (catalog spec v0.1) for an estate of repos with manifests.",
                    "One cartridge per child repo carrying knowledge.yaml: git source, version, commit, "
                            + "and cycle-safe depends_on from cross-repo dependency edges."},
            mixinStandardHelpOptions = true)
    static class CatalogSub implements Callable<Integer> {

        @ParentCommand
        private KcpCommand kcpParent;

        @picocli.CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Estate root directory (default: the workspace root)")
        private Path estateDir;

        @Option(names = {"-o", "--output"}, description = "Write to file (default: stdout)")
        private Path output;

        @Option(names = {"--name"}, description = "Catalog name (default: estate directory name)")
        private String catalogName;

        @Override
        public Integer call() throws Exception {
            Path estate = estateDir != null ? estateDir : kcpParent.parent.getWorkspaceRoot();
            if (!Files.isDirectory(estate)) {
                AnsiOutput.printError("Not a directory: " + estate);
                return 2;
            }
            var entries = scanEstate(kcpParent, estate);
            if (entries.isEmpty()) {
                AnsiOutput.printWarning("No child repositories with a knowledge.yaml under " + estate
                        + " — run 'synthesis kcp init --batch' first.");
                return 2;
            }
            String name = catalogName != null ? catalogName
                    : io.exoreaction.synthesis.kcp.KcpScaffolder.slug(estate.getFileName().toString());
            String yaml = io.exoreaction.synthesis.kcp.KcpFederationBuilder.toCatalogYaml(
                    entries, name, null);
            if (output != null) {
                Files.writeString(output, yaml);
                System.out.println("  [OK] " + output + " (" + entries.size() + " entries)");
            } else {
                System.out.print(yaml);
            }
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // synthesis kcp federate
    // -----------------------------------------------------------------------

    @Command(name = "federate",
            description = {"Emit a root knowledge.yaml federating every repo manifest in an estate.",
                    "manifests[] entries carry relationship/local_mirror/context; external_relationships "
                            + "come from cross-repo dependency edges. Estates over 50 repos are sharded."},
            mixinStandardHelpOptions = true)
    static class FederateSub implements Callable<Integer> {

        @ParentCommand
        private KcpCommand kcpParent;

        @picocli.CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Estate root directory (default: the workspace root)")
        private Path estateDir;

        @Option(names = {"--project"}, description = "Root manifest project id (default: estate directory name)")
        private String project;

        @Option(names = {"--write"}, description = "Write manifest file(s) into the estate root (default: print)")
        private boolean write;

        @Override
        public Integer call() throws Exception {
            Path estate = estateDir != null ? estateDir : kcpParent.parent.getWorkspaceRoot();
            if (!Files.isDirectory(estate)) {
                AnsiOutput.printError("Not a directory: " + estate);
                return 2;
            }
            var entries = scanEstate(kcpParent, estate);
            if (entries.isEmpty()) {
                AnsiOutput.printWarning("No child repositories with a knowledge.yaml under " + estate
                        + " — run 'synthesis kcp init --batch' first.");
                return 2;
            }
            String proj = project != null ? project
                    : io.exoreaction.synthesis.kcp.KcpScaffolder.slug(estate.getFileName().toString());
            var files = io.exoreaction.synthesis.kcp.KcpFederationBuilder.toFederationManifests(
                    entries, proj, LocalDate.now().toString());
            if (files.size() > 1) {
                System.out.println("  Estate exceeds " + io.exoreaction.synthesis.kcp
                        .KcpFederationBuilder.MAX_MANIFESTS_PER_ROOT
                        + " repos — sharded into " + (files.size() - 1) + " sub-manifest file(s).");
            }
            for (var file : files) {
                if (write) {
                    Files.writeString(estate.resolve(file.relativePath()), file.yaml());
                    System.out.println("  [OK] " + estate.resolve(file.relativePath()));
                } else {
                    System.out.println("# --- " + file.relativePath() + " ---");
                    System.out.print(file.yaml());
                    System.out.println();
                }
            }
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // synthesis kcp refresh
    // -----------------------------------------------------------------------

    @Command(name = "refresh",
            description = {"Refresh volatile fields (dates, content hashes, git timestamps) of "
                    + "Synthesis-generated manifests.",
                    "Only touches manifests carrying hints.generated_by: synthesis@…, and only when "
                            + "nothing but volatile fields changed — structural hand-edits are "
                            + "reported and left alone (use 'kcp verify' to manage their drift)."},
            mixinStandardHelpOptions = true)
    static class RefreshSub implements Callable<Integer> {

        @ParentCommand
        private KcpCommand kcpParent;

        @picocli.CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Repository directory (default: the workspace root)")
        private Path targetDir;

        @Option(names = {"--batch"}, description = "Refresh every direct child git repository under this directory")
        private Path batchRoot;

        @Option(names = {"--dry-run"}, description = "Report what would change without writing")
        private boolean dryRun;

        @Override
        public Integer call() {
            String version = Version.getVersion();
            List<Path> targets = new ArrayList<>();
            if (batchRoot != null) {
                if (!Files.isDirectory(batchRoot)) {
                    AnsiOutput.printError("Not a directory: " + batchRoot);
                    return 2;
                }
                try (var children = Files.list(batchRoot)) {
                    children.filter(Files::isDirectory)
                            .filter(c -> Files.exists(c.resolve(".git")))
                            .sorted()
                            .forEach(targets::add);
                } catch (Exception e) {
                    AnsiOutput.printError("Batch refresh failed: " + e.getMessage());
                    return 2;
                }
            } else {
                targets.add(targetDir != null ? targetDir : kcpParent.parent.getWorkspaceRoot());
            }

            int refreshed = 0, upToDate = 0, handAuthored = 0, structural = 0;
            for (Path repoDir : targets) {
                Path manifest = repoDir.resolve("knowledge.yaml");
                if (!Files.isRegularFile(manifest)) continue;
                String existing;
                try {
                    existing = Files.readString(manifest);
                } catch (Exception e) {
                    AnsiOutput.printError(manifest + ": unreadable — " + e.getMessage());
                    continue;
                }
                if (!io.exoreaction.synthesis.kcp.KcpScaffolder.isSynthesisGenerated(existing)) {
                    System.out.println("  [hand-authored] " + manifest + " — left alone");
                    handAuthored++;
                    continue;
                }
                Map<String, String> gitDates = ExportCommand.collectGitCommitDates(repoDir);
                String fresh = io.exoreaction.synthesis.kcp.KcpScaffolder
                        .scaffold(repoDir, version, gitDates);
                if (fresh == null) {
                    AnsiOutput.printWarning(manifest + ": repo no longer yields units — left alone.");
                    structural++;
                    continue;
                }
                String normalizedExisting = io.exoreaction.synthesis.kcp.KcpScaffolder
                        .normalizeVolatile(existing);
                String normalizedFresh = io.exoreaction.synthesis.kcp.KcpScaffolder
                        .normalizeVolatile(fresh);
                if (!normalizedExisting.equals(normalizedFresh)) {
                    System.out.println("  [modified] " + manifest
                            + " — structure differs from a fresh scaffold (hand-edited or repo "
                            + "changed shape); not touched. Drift is managed by 'kcp verify'.");
                    structural++;
                    continue;
                }
                if (existing.equals(fresh)) {
                    upToDate++;
                    continue;
                }
                if (dryRun) {
                    System.out.println("  [would refresh] " + manifest);
                    refreshed++;
                    continue;
                }
                try {
                    Files.writeString(manifest, fresh);
                    System.out.println("  [refreshed] " + manifest);
                    refreshed++;
                } catch (Exception e) {
                    AnsiOutput.printError(manifest + ": failed to write — " + e.getMessage());
                }
            }
            System.out.printf("%nRefresh complete: %d refreshed, %d up to date, %d hand-authored, "
                    + "%d structurally modified (untouched).%n",
                    refreshed, upToDate, handAuthored, structural);
            return 0;
        }
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
