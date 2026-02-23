package io.exoreaction.synthesis.report;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists report generation history so that {@code synthesis report}
 * can default to "since last report" when no {@code -p} flag is provided.
 *
 * <p>Each (target, topic) combination has at most one row (UPSERT semantics).
 * After each successful generation the timestamp, period, source count,
 * and output file are recorded.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/250">#250</a>
 */
public class ReportHistoryRepository {

    private final Connection connection;

    public ReportHistoryRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * Records that a report was generated for the given (target, topic).
     *
     * @param target          report target (e.g. "ceo")
     * @param topic           report topic (e.g. "weekly")
     * @param generatedAt     when the report was generated
     * @param periodDays      the coverage period in days
     * @param sourceDocuments number of source documents analyzed (nullable)
     * @param outputFile      output file path (nullable)
     */
    public void recordGeneration(String target, String topic, Instant generatedAt,
                                  int periodDays, Integer sourceDocuments, String outputFile) {
        String sql = """
            INSERT INTO report_history (target, topic, generated_at, period_days, source_documents, output_file)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(target, topic) DO UPDATE SET
                generated_at = excluded.generated_at,
                period_days = excluded.period_days,
                source_documents = excluded.source_documents,
                output_file = excluded.output_file
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, target);
            stmt.setString(2, topic);
            stmt.setString(3, generatedAt.toString());
            stmt.setInt(4, periodDays);
            if (sourceDocuments != null) {
                stmt.setInt(5, sourceDocuments);
            } else {
                stmt.setNull(5, Types.INTEGER);
            }
            stmt.setString(6, outputFile);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Warning: Failed to record report history: " + e.getMessage());
        }
    }

    /**
     * Returns the last generation timestamp for the given (target, topic).
     *
     * @param target report target (e.g. "ceo")
     * @param topic  report topic (e.g. "weekly")
     * @return the last generated instant, or empty if no history exists
     */
    public Optional<Instant> getLastGenerated(String target, String topic) {
        String sql = "SELECT generated_at FROM report_history WHERE target = ? AND topic = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, target);
            stmt.setString(2, topic);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(Instant.parse(rs.getString("generated_at")));
            }
            return Optional.empty();
        } catch (SQLException e) {
            System.err.println("Warning: Failed to query report history: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Computes the number of days since the last report was generated for
     * the given (target, topic). Returns empty if no history exists.
     *
     * @param target report target
     * @param topic  report topic
     * @return days since last generation, or empty if no history
     */
    public Optional<Integer> daysSinceLastReport(String target, String topic) {
        return getLastGenerated(target, topic).map(lastGenerated -> {
            long days = ChronoUnit.DAYS.between(
                    lastGenerated.atZone(ZoneId.systemDefault()).toLocalDate(),
                    LocalDate.now());
            // Minimum 1 day (if run same day, still cover at least 1 day)
            return Math.max(1, (int) days);
        });
    }

    /**
     * Returns all report history entries, ordered by most recent first.
     *
     * @return list of history entries
     */
    public List<ReportHistoryEntry> getAllHistory() {
        String sql = """
            SELECT target, topic, generated_at, period_days, source_documents, output_file
            FROM report_history
            ORDER BY generated_at DESC
            """;

        List<ReportHistoryEntry> entries = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                entries.add(new ReportHistoryEntry(
                        rs.getString("target"),
                        rs.getString("topic"),
                        Instant.parse(rs.getString("generated_at")),
                        rs.getInt("period_days"),
                        rs.getObject("source_documents") != null ? rs.getInt("source_documents") : null,
                        rs.getString("output_file")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Warning: Failed to query report history: " + e.getMessage());
        }
        return entries;
    }

    /**
     * A single report history entry.
     */
    public record ReportHistoryEntry(
            String target,
            String topic,
            Instant generatedAt,
            int periodDays,
            Integer sourceDocuments,
            String outputFile
    ) {}
}
