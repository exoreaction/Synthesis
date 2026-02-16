package io.exoreaction.synthesis.changelog;

import io.exoreaction.synthesis.util.FileUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates human-readable change reports from change events.
 * Supports daily, weekly, and cross-workspace reports.
 */
public class ChangeReportGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Generates a cross-workspace change report in Markdown format.
     *
     * @param events         all change events to include
     * @param fromDate       start of reporting period
     * @param toDate         end of reporting period
     * @param minSignificance minimum significance to show in detail
     * @return formatted Markdown report
     */
    public String generateReport(List<ChangeEvent> events, Instant fromDate, Instant toDate,
                                  ChangeSignificance minSignificance) {
        StringBuilder sb = new StringBuilder();

        String from = formatDate(fromDate);
        String to = formatDate(toDate);

        sb.append("# Change Report (").append(from).append(" - ").append(to).append(")\n\n");
        sb.append("**Generated:** ").append(formatTime(Instant.now())).append("\n\n");

        // Summary
        Map<ChangeSignificance, Long> bySig = events.stream()
                .collect(Collectors.groupingBy(ChangeEvent::significance, Collectors.counting()));
        Map<ChangeEvent.ChangeType, Long> byType = events.stream()
                .collect(Collectors.groupingBy(ChangeEvent::changeType, Collectors.counting()));
        Map<String, Long> byWorkspace = events.stream()
                .collect(Collectors.groupingBy(ChangeEvent::workspacePath, Collectors.counting()));

        long noise = bySig.getOrDefault(ChangeSignificance.NOISE, 0L);
        long normal = bySig.getOrDefault(ChangeSignificance.NORMAL, 0L);
        long notable = bySig.getOrDefault(ChangeSignificance.NOTABLE, 0L);
        long critical = bySig.getOrDefault(ChangeSignificance.CRITICAL, 0L);

        sb.append("## Summary\n\n");
        sb.append("- **").append(byWorkspace.size()).append("** workspaces scanned\n");
        sb.append("- **").append(events.size()).append("** total changes detected\n");
        sb.append("  - ").append(critical).append(" critical, ")
                .append(notable).append(" notable, ")
                .append(normal).append(" normal, ")
                .append(noise).append(" noise (filtered)\n");
        sb.append("- **").append(byType.getOrDefault(ChangeEvent.ChangeType.ADDED, 0L)).append("** added, ");
        sb.append("**").append(byType.getOrDefault(ChangeEvent.ChangeType.MODIFIED, 0L)).append("** modified, ");
        sb.append("**").append(byType.getOrDefault(ChangeEvent.ChangeType.DELETED, 0L)).append("** deleted\n\n");

        // Critical changes
        List<ChangeEvent> criticalEvents = events.stream()
                .filter(e -> e.significance() == ChangeSignificance.CRITICAL)
                .toList();
        if (!criticalEvents.isEmpty()) {
            sb.append("## Critical Changes\n\n");
            for (ChangeEvent e : criticalEvents) {
                sb.append("- **[").append(shortWorkspace(e.workspacePath())).append("]** ");
                sb.append(changeIcon(e.changeType())).append(" `").append(e.relativePath()).append("`");
                if (e.fileSize() > 0) {
                    sb.append(" (").append(FileUtils.formatSize(e.fileSize())).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Notable changes
        List<ChangeEvent> notableEvents = events.stream()
                .filter(e -> e.significance() == ChangeSignificance.NOTABLE)
                .toList();
        if (!notableEvents.isEmpty()) {
            sb.append("## Notable Changes\n\n");
            for (ChangeEvent e : notableEvents) {
                sb.append("- **[").append(shortWorkspace(e.workspacePath())).append("]** ");
                sb.append(changeIcon(e.changeType())).append(" `").append(e.relativePath()).append("`");
                if (e.fileSize() > 0) {
                    sb.append(" (").append(FileUtils.formatSize(e.fileSize())).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Per-workspace breakdown
        sb.append("## By Workspace\n\n");
        for (Map.Entry<String, Long> entry : byWorkspace.entrySet()) {
            String ws = entry.getKey();
            long count = entry.getValue();

            List<ChangeEvent> wsEvents = events.stream()
                    .filter(e -> e.workspacePath().equals(ws))
                    .filter(e -> e.significance().isAtLeast(
                            minSignificance != null ? minSignificance : ChangeSignificance.NORMAL))
                    .toList();

            sb.append("### ").append(shortWorkspace(ws)).append(" (").append(count).append(" changes)\n\n");

            if (wsEvents.isEmpty()) {
                sb.append("_All changes below significance threshold_\n\n");
            } else {
                for (ChangeEvent e : wsEvents) {
                    sb.append("- ").append(changeIcon(e.changeType()))
                            .append(" `").append(e.relativePath()).append("`\n");
                }
                sb.append("\n");
            }
        }

        sb.append("---\n");
        sb.append("_Generated by Synthesis v1.3.0_\n");

        return sb.toString();
    }

    /**
     * Generates a compact summary (suitable for CLI output).
     */
    public String generateSummary(List<ChangeEvent> events) {
        Map<ChangeEvent.ChangeType, Long> byType = events.stream()
                .collect(Collectors.groupingBy(ChangeEvent::changeType, Collectors.counting()));

        long notable = events.stream().filter(e -> e.significance().isAtLeast(ChangeSignificance.NOTABLE)).count();

        return String.format("%d changes (%d added, %d modified, %d deleted) | %d notable",
                events.size(),
                byType.getOrDefault(ChangeEvent.ChangeType.ADDED, 0L),
                byType.getOrDefault(ChangeEvent.ChangeType.MODIFIED, 0L),
                byType.getOrDefault(ChangeEvent.ChangeType.DELETED, 0L),
                notable);
    }

    private String shortWorkspace(String workspacePath) {
        if (workspacePath == null) return "unknown";
        int lastSlash = workspacePath.lastIndexOf('/');
        return lastSlash >= 0 ? workspacePath.substring(lastSlash + 1) : workspacePath;
    }

    private String changeIcon(ChangeEvent.ChangeType type) {
        return switch (type) {
            case ADDED -> "+";
            case MODIFIED -> "~";
            case DELETED -> "-";
            case MOVED -> ">";
        };
    }

    private String formatDate(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DATE_FMT);
    }

    private String formatTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(TIME_FMT);
    }
}
