package io.exoreaction.synthesis.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Computes git-based file quality metrics: temporal hotspot scores, co-change coupling,
 * and bus factor. Results are persisted to {@code git_file_metrics} and {@code git_cochange}.
 *
 * <p>All metrics use exponential decay with a 180-day half-life, so recent activity
 * weights far more than historical churn. The SQLite tables are a reconstructible cache --
 * deleting them and re-running loses no information.
 */
public class GitMetricsComputer {

    /** Half-life in days for exponential decay. */
    private static final double HALFLIFE_DAYS = 180.0;

    /** Maximum commits to scan (performance cap). */
    private static final int DEFAULT_MAX_COMMITS = 500;

    private final Connection connection;

    public GitMetricsComputer(Connection connection) {
        this.connection = connection;
    }

    /**
     * Computes all git metrics for a repository and upserts them into the database.
     *
     * @param repoPath      path to the git repository root
     * @param workspacePath workspace path key used in DB records
     * @param maxCommits    maximum number of commits to scan
     * @return number of files processed
     */
    public int computeAll(Path repoPath, String workspacePath, int maxCommits)
            throws IOException, GitAPIException, SQLException {

        FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .readEnvironment()
                .findGitDir(repoPath.toFile());
        if (builder.getGitDir() == null) {
            return 0;
        }

        try (Repository repo = builder.build();
             Git git = new Git(repo)) {

            // Per-file aggregates
            Map<String, Double> hotspotScores = new HashMap<>();
            Map<String, Integer> commitCountTotal = new HashMap<>();
            Map<String, Integer> commitCount90d = new HashMap<>();
            Map<String, Integer> commitCount30d = new HashMap<>();
            Map<String, Set<String>> fileAuthors = new HashMap<>();
            Map<String, Long> lastCommitAt = new HashMap<>();

            // Co-change: file_a -> file_b -> (score, count, lastAt)
            Map<String, Map<String, double[]>> cochange = new HashMap<>(); // [score, count, lastEpoch]

            Instant now = Instant.now();
            Instant cutoff90d = now.minus(90, ChronoUnit.DAYS);
            Instant cutoff30d = now.minus(30, ChronoUnit.DAYS);

            int scanned = 0;
            Iterable<RevCommit> commits = git.log().setMaxCount(maxCommits).call();

            for (RevCommit commit : commits) {
                scanned++;
                Instant commitTime = Instant.ofEpochSecond(commit.getCommitTime());
                double ageDays = ChronoUnit.DAYS.between(commitTime, now);
                double decayWeight = Math.exp(-Math.log(2) * ageDays / HALFLIFE_DAYS);
                String author = commit.getAuthorIdent().getName();

                List<String> changedFiles = getChangedFiles(git, repo, commit);

                for (String file : changedFiles) {
                    hotspotScores.merge(file, decayWeight, Double::sum);
                    commitCountTotal.merge(file, 1, Integer::sum);
                    if (commitTime.isAfter(cutoff90d)) {
                        commitCount90d.merge(file, 1, Integer::sum);
                    }
                    if (commitTime.isAfter(cutoff30d)) {
                        commitCount30d.merge(file, 1, Integer::sum);
                    }
                    fileAuthors.computeIfAbsent(file, k -> new HashSet<>()).add(author);
                    lastCommitAt.merge(file, (long) commit.getCommitTime(), Math::max);
                }

                // Co-change: all pairs in this commit
                if (changedFiles.size() > 1 && changedFiles.size() <= 50) {
                    long epochSecs = commit.getCommitTime();
                    for (int i = 0; i < changedFiles.size(); i++) {
                        for (int j = i + 1; j < changedFiles.size(); j++) {
                            String a = changedFiles.get(i);
                            String b = changedFiles.get(j);
                            updateCoChange(cochange, a, b, decayWeight, epochSecs);
                            updateCoChange(cochange, b, a, decayWeight, epochSecs);
                        }
                    }
                }
            }

            // Compute bus factors
            Map<String, Integer> busFactors = computeBusFactors(git, repo,
                    Math.min(maxCommits, DEFAULT_MAX_COMMITS));

            // Upsert into DB
            long computedAt = Instant.now().getEpochSecond();
            upsertFileMetrics(workspacePath, hotspotScores, commitCountTotal, commitCount90d,
                    commitCount30d, fileAuthors, busFactors, lastCommitAt, computedAt);
            upsertCoChange(workspacePath, cochange, computedAt);

            return hotspotScores.size();
        }
    }

    /**
     * Computes exponential decay score:
     * score = exp(-ln(2) * ageDays / halfLifeDays)
     * At age=0: 1.0. At age=halfLife: 0.5. At age=2*halfLife: 0.25.
     */
    public static double decayScore(double ageDays) {
        return Math.exp(-Math.log(2) * ageDays / HALFLIFE_DAYS);
    }

    private void updateCoChange(Map<String, Map<String, double[]>> cochange,
                                String a, String b, double weight, long epochSecs) {
        cochange.computeIfAbsent(a, k -> new HashMap<>())
                .merge(b, new double[]{weight, 1, epochSecs}, (old, neu) -> {
                    old[0] += neu[0];
                    old[1] += 1;
                    old[2] = Math.max(old[2], epochSecs);
                    return old;
                });
    }

    private List<String> getChangedFiles(Git git, Repository repo, RevCommit commit)
            throws IOException, GitAPIException {
        AbstractTreeIterator newTree = treeParser(repo, commit);
        AbstractTreeIterator oldTree;

        if (commit.getParentCount() > 0) {
            oldTree = treeParser(repo, commit.getParent(0));
        } else {
            oldTree = new EmptyTreeIterator();
        }

        List<DiffEntry> diffs = git.diff()
                .setOldTree(oldTree)
                .setNewTree(newTree)
                .call();

        return diffs.stream()
                .map(d -> d.getNewPath().equals("/dev/null") ? d.getOldPath() : d.getNewPath())
                .filter(p -> !p.equals("/dev/null"))
                .toList();
    }

    private AbstractTreeIterator treeParser(Repository repo, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (var reader = repo.newObjectReader()) {
            parser.reset(reader, commit.getTree().getId());
        }
        return parser;
    }

    /**
     * Computes bus factor for each file: number of authors needed to cover 80% of commits.
     */
    private Map<String, Integer> computeBusFactors(Git git, Repository repo, int maxCommits)
            throws IOException, GitAPIException {
        // file -> (author -> count)
        Map<String, Map<String, Integer>> fileAuthorCommits = new HashMap<>();

        Iterable<RevCommit> commits = git.log().setMaxCount(maxCommits).call();
        for (RevCommit commit : commits) {
            String author = commit.getAuthorIdent().getName();
            List<String> files = getChangedFiles(git, repo, commit);
            for (String file : files) {
                fileAuthorCommits.computeIfAbsent(file, k -> new HashMap<>())
                        .merge(author, 1, Integer::sum);
            }
        }

        Map<String, Integer> busFactors = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : fileAuthorCommits.entrySet()) {
            int total = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            List<Integer> sorted = entry.getValue().values().stream()
                    .sorted(Comparator.reverseOrder())
                    .toList();
            int target = (int) Math.ceil(total * 0.8);
            int accumulated = 0;
            int busFactor = 0;
            for (int count : sorted) {
                accumulated += count;
                busFactor++;
                if (accumulated >= target) break;
            }
            busFactors.put(entry.getKey(), busFactor);
        }
        return busFactors;
    }

    private void upsertFileMetrics(String workspacePath,
                                   Map<String, Double> hotspotScores,
                                   Map<String, Integer> commitCountTotal,
                                   Map<String, Integer> commitCount90d,
                                   Map<String, Integer> commitCount30d,
                                   Map<String, Set<String>> fileAuthors,
                                   Map<String, Integer> busFactors,
                                   Map<String, Long> lastCommitAt,
                                   long computedAt) throws SQLException {
        String sql = """
                INSERT INTO git_file_metrics
                    (workspace_path, file_path, hotspot_score, commit_count_total,
                     commit_count_90d, commit_count_30d, author_count, bus_factor,
                     last_commit_at, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(workspace_path, file_path) DO UPDATE SET
                    hotspot_score = excluded.hotspot_score,
                    commit_count_total = excluded.commit_count_total,
                    commit_count_90d = excluded.commit_count_90d,
                    commit_count_30d = excluded.commit_count_30d,
                    author_count = excluded.author_count,
                    bus_factor = excluded.bus_factor,
                    last_commit_at = excluded.last_commit_at,
                    computed_at = excluded.computed_at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (String file : hotspotScores.keySet()) {
                ps.setString(1, workspacePath);
                ps.setString(2, file);
                ps.setDouble(3, hotspotScores.getOrDefault(file, 0.0));
                ps.setInt(4, commitCountTotal.getOrDefault(file, 0));
                ps.setInt(5, commitCount90d.getOrDefault(file, 0));
                ps.setInt(6, commitCount30d.getOrDefault(file, 0));
                ps.setInt(7, fileAuthors.getOrDefault(file, Set.of()).size());
                ps.setInt(8, busFactors.getOrDefault(file, 1));
                Long last = lastCommitAt.get(file);
                if (last != null) {
                    ps.setLong(9, last);
                } else {
                    ps.setNull(9, java.sql.Types.INTEGER);
                }
                ps.setLong(10, computedAt);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void upsertCoChange(String workspacePath,
                                Map<String, Map<String, double[]>> cochange,
                                long computedAt) throws SQLException {
        String sql = """
                INSERT INTO git_cochange
                    (workspace_path, file_a, file_b, coupling_score, cochange_count,
                     last_cochange_at, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(workspace_path, file_a, file_b) DO UPDATE SET
                    coupling_score = excluded.coupling_score,
                    cochange_count = excluded.cochange_count,
                    last_cochange_at = excluded.last_cochange_at,
                    computed_at = excluded.computed_at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Map.Entry<String, Map<String, double[]>> outer : cochange.entrySet()) {
                for (Map.Entry<String, double[]> inner : outer.getValue().entrySet()) {
                    double[] vals = inner.getValue();
                    ps.setString(1, workspacePath);
                    ps.setString(2, outer.getKey());
                    ps.setString(3, inner.getKey());
                    ps.setDouble(4, vals[0]);
                    ps.setInt(5, (int) vals[1]);
                    ps.setLong(6, (long) vals[2]);
                    ps.setLong(7, computedAt);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }
}
