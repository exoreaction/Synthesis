package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.git.GitMetricsComputer;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code synthesis hotspots} — show files ranked by temporal hotspot score.
 *
 * <p>Uses exponential decay (half-life 180 days) over git commit history.
 * Recent commits contribute far more than old ones. A file touched every sprint
 * ranks higher than one that was rewritten once two years ago.
 *
 * <p>Data is cached in {@code git_file_metrics}. Use {@code --refresh} to
 * recompute from git history before displaying.
 */
@Command(
        name = "hotspots",
        description = "Show files ranked by temporal hotspot score (git churn with decay)",
        mixinStandardHelpOptions = true
)
public class HotspotsCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--limit", "-n"}, description = "Number of files to show (default: 20)",
            defaultValue = "20")
    private int limit;

    @Option(names = {"--path"}, description = "Filter to files under this path prefix")
    private String pathPrefix;

    @Option(names = {"--refresh"}, description = "Recompute metrics from git history before display",
            defaultValue = "false")
    private boolean refresh;

    @Option(names = {"--min-commits"}, description = "Minimum total commit count to include (default: 2)",
            defaultValue = "2")
    private int minCommits;

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

            SynthesisDatabase db = SynthesisDatabase.getDefault();
            Connection conn = db.getConnection();
            String wsPath = workspaceRoot.toString();

            if (refresh || isEmpty(conn, wsPath)) {
                System.out.println("Computing git metrics (this may take a moment)...");
                GitMetricsComputer computer = new GitMetricsComputer(conn);
                int count = computer.computeAll(workspaceRoot, wsPath, 500);
                System.out.println("Processed " + count + " files from git history.");
                System.out.println();
            }

            List<HotspotRow> rows = queryHotspots(conn, wsPath);
            if (rows.isEmpty()) {
                AnsiOutput.printInfo("No hotspot data found. Run with --refresh to compute from git history.");
                return 0;
            }

            printTable(rows);
            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Hotspot analysis failed: " + e.getMessage());
            return 1;
        }
    }

    private boolean isEmpty(Connection conn, String wsPath) throws SQLException {
        String sql = "SELECT COUNT(*) FROM git_file_metrics WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wsPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 0;
            }
        }
    }

    private List<HotspotRow> queryHotspots(Connection conn, String wsPath) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT file_path, hotspot_score, commit_count_total,
                       commit_count_90d, commit_count_30d, bus_factor, last_commit_at
                FROM git_file_metrics
                WHERE workspace_path = ?
                  AND commit_count_total >= ?
                """);

        if (pathPrefix != null && !pathPrefix.isBlank()) {
            sql.append("  AND file_path LIKE ?\n");
        }
        sql.append("ORDER BY hotspot_score DESC\nLIMIT ?");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, wsPath);
            ps.setInt(idx++, minCommits);
            if (pathPrefix != null && !pathPrefix.isBlank()) {
                ps.setString(idx++, pathPrefix.replace("%", "\\%") + "%");
            }
            ps.setInt(idx, limit);

            List<HotspotRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HotspotRow(
                            rs.getString("file_path"),
                            rs.getDouble("hotspot_score"),
                            rs.getInt("commit_count_total"),
                            rs.getInt("commit_count_90d"),
                            rs.getInt("commit_count_30d"),
                            rs.getInt("bus_factor"),
                            rs.getLong("last_commit_at")
                    ));
                }
            }
            return rows;
        }
    }

    private void printTable(List<HotspotRow> rows) {
        System.out.println();
        AnsiOutput.printHeader("Git Hotspots");
        System.out.println();

        System.out.printf("  %-4s  %-60s  %8s  %14s  %5s%n",
                "Rank", "File", "Score", "30d / 90d / all", "Bus");
        System.out.println("  " + "-".repeat(100));

        for (int i = 0; i < rows.size(); i++) {
            HotspotRow r = rows.get(i);
            String path = truncatePath(r.filePath(), 60);
            String score = String.format("%.2f", r.hotspotScore());
            String counts = String.format("%3d / %3d / %3d",
                    r.count30d(), r.count90d(), r.countTotal());
            String trend = trend(r.count30d(), r.count90d());

            String scoreColored = r.hotspotScore() > 10.0
                    ? AnsiOutput.red(score)
                    : r.hotspotScore() > 5.0
                    ? AnsiOutput.yellow(score)
                    : score;

            String busWarning = r.busFactor() == 1 ? AnsiOutput.yellow("  1 ⚠") : String.format("  %d", r.busFactor());

            System.out.printf("  %-4d  %-60s  %8s  %14s  %s  %s%n",
                    i + 1, path, scoreColored, counts, trend, busWarning);
        }
        System.out.println();
        System.out.println("  Score uses exponential decay (half-life 180 days). ↑ rising · → stable · ↓ cooling.");
        System.out.println("  Bus = authors covering 80% of commits. ⚠ = single-author knowledge silo.");
        System.out.println();
    }

    /** Trend indicator: compare 30d rate vs 90d average rate. */
    static String trend(int count30d, int count90d) {
        double rate90dMonthly = count90d / 3.0;
        if (rate90dMonthly < 0.5) return "→";
        if (count30d > rate90dMonthly * 1.5) return AnsiOutput.red("↑");
        if (count30d < rate90dMonthly / 2.0) return "↓";
        return "→";
    }

    private static String truncatePath(String path, int maxLen) {
        if (path.length() <= maxLen) return path;
        return "…" + path.substring(path.length() - (maxLen - 1));
    }

    record HotspotRow(
            String filePath,
            double hotspotScore,
            int countTotal,
            int count90d,
            int count30d,
            int busFactor,
            long lastCommitAt
    ) {}
}
