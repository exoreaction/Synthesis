package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code synthesis feedback} — record whether routing decisions were correct.
 *
 * <p>Provides a human-in-the-loop mechanism for routing quality improvement.
 * Records accepted and rejected routing decisions in the {@code routing_feedback}
 * table, which is used by future bidding weight adjustments (Phase 4).
 *
 * <p>Usage:
 * <pre>
 *   synthesis feedback accept &lt;file&gt;                  # confirm file is in the right place
 *   synthesis feedback reject &lt;file&gt;                  # this file should be elsewhere
 *   synthesis feedback reject &lt;file&gt; --correct &lt;dir&gt;  # file should be in &lt;dir&gt; instead
 *   synthesis feedback list                             # show recent feedback
 * </pre>
 *
 * @since v1.15.0 (P3-08)
 */
@Command(
        name = "feedback",
        description = "Record routing feedback (accept/reject file placement)",
        mixinStandardHelpOptions = true,
        subcommands = {
                FeedbackCommand.Accept.class,
                FeedbackCommand.Reject.class,
                FeedbackCommand.ListFeedback.class
        }
)
public class FeedbackCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Override
    public Integer call() {
        System.out.println("Usage: synthesis feedback <accept|reject|list>");
        System.out.println();
        System.out.println("  accept <file>                Confirm file is in the right directory");
        System.out.println("  reject <file>                Mark file as misplaced");
        System.out.println("  reject <file> --correct <d>  Mark file as misplaced, suggest correct directory");
        System.out.println("  list                         Show recent feedback history");
        return 0;
    }

    // ---- Accept subcommand ----

    @Command(name = "accept", description = "Confirm a file is in the right directory")
    static class Accept implements Callable<Integer> {
        @ParentCommand
        private FeedbackCommand feedbackParent;

        @Parameters(index = "0", description = "File to accept")
        private Path file;

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = feedbackParent.parent.getWorkspaceRoot();
            Path resolvedFile = workspaceRoot.resolve(file).normalize();

            if (!Files.exists(resolvedFile)) {
                System.err.println("File not found: " + file);
                return 1;
            }

            // Determine the directory containing this file
            Path directory = resolvedFile.getParent();
            String relFile = workspaceRoot.relativize(resolvedFile).toString();
            String relDir = workspaceRoot.relativize(directory).toString();

            try (SynthesisDatabase db = new SynthesisDatabase(
                    workspaceRoot.resolve(".synthesis/synthesis.db"))) {
                FeedbackRecorder recorder = new FeedbackRecorder(db);
                recorder.recordAccept(workspaceRoot.toString(), relFile, relDir);
                System.out.println(AnsiOutput.green("  Accepted") + ": " + relFile
                        + " in " + relDir);
            }
            return 0;
        }
    }

    // ---- Reject subcommand ----

    @Command(name = "reject", description = "Mark a file as misplaced")
    static class Reject implements Callable<Integer> {
        @ParentCommand
        private FeedbackCommand feedbackParent;

        @Parameters(index = "0", description = "File to reject")
        private Path file;

        @Option(names = "--correct", description = "The correct directory for this file")
        private Path correctDir;

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = feedbackParent.parent.getWorkspaceRoot();
            Path resolvedFile = workspaceRoot.resolve(file).normalize();

            if (!Files.exists(resolvedFile)) {
                System.err.println("File not found: " + file);
                return 1;
            }

            // Current directory = the proposed (wrong) destination
            Path currentDir = resolvedFile.getParent();
            String relFile = workspaceRoot.relativize(resolvedFile).toString();
            String relCurrentDir = workspaceRoot.relativize(currentDir).toString();
            String relCorrectDir = correctDir != null
                    ? workspaceRoot.relativize(workspaceRoot.resolve(correctDir).normalize()).toString()
                    : null;

            try (SynthesisDatabase db = new SynthesisDatabase(
                    workspaceRoot.resolve(".synthesis/synthesis.db"))) {
                FeedbackRecorder recorder = new FeedbackRecorder(db);
                if (relCorrectDir != null) {
                    recorder.recordReject(workspaceRoot.toString(), relFile,
                            relCurrentDir, relCorrectDir);
                    System.out.println(AnsiOutput.yellow("  Rejected") + ": " + relFile
                            + " in " + relCurrentDir + " -> should be in " + relCorrectDir);
                } else {
                    recorder.recordReject(workspaceRoot.toString(), relFile, relCurrentDir);
                    System.out.println(AnsiOutput.yellow("  Rejected") + ": " + relFile
                            + " in " + relCurrentDir);
                }
            }
            return 0;
        }
    }

    // ---- List subcommand ----

    @Command(name = "list", description = "Show recent routing feedback")
    static class ListFeedback implements Callable<Integer> {
        @ParentCommand
        private FeedbackCommand feedbackParent;

        @Option(names = {"--limit", "-n"}, description = "Number of entries to show",
                defaultValue = "20")
        private int limit;

        @Override
        public Integer call() throws Exception {
            Path workspaceRoot = feedbackParent.parent.getWorkspaceRoot();

            try (SynthesisDatabase db = new SynthesisDatabase(
                    workspaceRoot.resolve(".synthesis/synthesis.db"))) {
                FeedbackRecorder recorder = new FeedbackRecorder(db);

                int accepted = recorder.countAccepted(workspaceRoot.toString());
                int rejected = recorder.countRejected(workspaceRoot.toString());

                System.out.println();
                AnsiOutput.printHeader("Routing Feedback");
                System.out.printf("  Accepted: %d  |  Rejected: %d%n%n", accepted, rejected);

                List<FeedbackEntry> entries = recorder.getRecentFeedback(
                        workspaceRoot.toString(), limit);

                if (entries.isEmpty()) {
                    System.out.println("  No feedback recorded yet.");
                    System.out.println("  Use: synthesis feedback accept <file>");
                    System.out.println("       synthesis feedback reject <file>");
                } else {
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(ZoneId.systemDefault());
                    for (FeedbackEntry entry : entries) {
                        String status = entry.accepted()
                                ? AnsiOutput.green("ACCEPT")
                                : AnsiOutput.yellow("REJECT");
                        String time = fmt.format(Instant.ofEpochSecond(entry.timestamp()));
                        System.out.printf("  [%s] %s  %s -> %s%n",
                                status, time, entry.filePath(),
                                entry.actualDestination() != null
                                        ? entry.actualDestination()
                                        : entry.proposedDestination());
                    }
                }
                System.out.println();
            }
            return 0;
        }
    }

    // ---- Data types ----

    /**
     * A feedback entry for display.
     *
     * @param filePath             the file path
     * @param proposedDestination  where routing put it
     * @param actualDestination    where it should be (null if rejected without correction)
     * @param accepted             whether the placement was accepted
     * @param timestamp            epoch seconds
     */
    public record FeedbackEntry(
            String filePath,
            String proposedDestination,
            String actualDestination,
            boolean accepted,
            long timestamp
    ) {}

    // ---- Database recorder (package-visible for testing) ----

    /**
     * Records and queries routing feedback in the database.
     * Extracted as a non-CLI class to enable direct testing without picocli.
     */
    static class FeedbackRecorder {

        private final SynthesisDatabase database;

        FeedbackRecorder(SynthesisDatabase database) {
            this.database = database;
        }

        /**
         * Records an accept: file is confirmed in the right place.
         */
        void recordAccept(String workspacePath, String filePath, String directory)
                throws SQLException {
            insertFeedback(workspacePath, filePath, directory, directory, true, 0.0);
        }

        /**
         * Records a reject: file is in the wrong place.
         */
        void recordReject(String workspacePath, String filePath, String proposedDir)
                throws SQLException {
            insertFeedback(workspacePath, filePath, proposedDir, null, false, 0.0);
        }

        /**
         * Records a reject with correction: file is wrong here, should be in correctDir.
         */
        void recordReject(String workspacePath, String filePath,
                          String proposedDir, String correctDir) throws SQLException {
            insertFeedback(workspacePath, filePath, proposedDir, correctDir, false, 0.0);
        }

        /**
         * Counts accepted feedback entries for a workspace.
         */
        int countAccepted(String workspacePath) throws SQLException {
            return countByAccepted(workspacePath, true);
        }

        /**
         * Counts rejected feedback entries for a workspace.
         */
        int countRejected(String workspacePath) throws SQLException {
            return countByAccepted(workspacePath, false);
        }

        /**
         * Returns recent feedback entries, most recent first.
         */
        List<FeedbackEntry> getRecentFeedback(String workspacePath, int limit)
                throws SQLException {
            Connection conn = database.getConnection();
            String sql = "SELECT file_path, proposed_destination, actual_destination, "
                    + "accepted, timestamp FROM routing_feedback "
                    + "WHERE workspace_path = ? ORDER BY timestamp DESC LIMIT ?";
            List<FeedbackEntry> result = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new FeedbackEntry(
                                rs.getString("file_path"),
                                rs.getString("proposed_destination"),
                                rs.getString("actual_destination"),
                                rs.getInt("accepted") == 1,
                                rs.getLong("timestamp")));
                    }
                }
            }
            return result;
        }

        private void insertFeedback(String workspacePath, String filePath,
                                     String proposedDestination, String actualDestination,
                                     boolean accepted, double confidenceDelta)
                throws SQLException {
            Connection conn = database.getConnection();
            String sql = "INSERT INTO routing_feedback "
                    + "(workspace_path, file_path, proposed_destination, actual_destination, "
                    + "accepted, confidence_delta, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ps.setString(2, filePath);
                ps.setString(3, proposedDestination);
                ps.setString(4, actualDestination);
                ps.setInt(5, accepted ? 1 : 0);
                ps.setDouble(6, confidenceDelta);
                ps.setLong(7, Instant.now().getEpochSecond());
                ps.executeUpdate();
            }
        }

        private int countByAccepted(String workspacePath, boolean accepted) throws SQLException {
            Connection conn = database.getConnection();
            String sql = "SELECT COUNT(*) FROM routing_feedback "
                    + "WHERE workspace_path = ? AND accepted = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ps.setInt(2, accepted ? 1 : 0);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }
}
