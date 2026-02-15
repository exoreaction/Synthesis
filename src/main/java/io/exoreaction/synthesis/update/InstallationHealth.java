package io.exoreaction.synthesis.update;

import java.util.Collections;
import java.util.List;

/**
 * Health report for a Synthesis installation.
 *
 * <p>Identifies missing, outdated, or corrupted components and provides
 * recommendations for fixing issues.
 *
 * @author Thor Henning Hetland / eXOReaction
 */
public class InstallationHealth {

    private final String version;
    private final String installDate;
    private final String installMethod;
    private final long installedComponentCount;
    private final long totalComponentCount;
    private final List<Issue> issues;

    public InstallationHealth(String version, String installDate, String installMethod,
                              long installedComponentCount, long totalComponentCount,
                              List<Issue> issues) {
        this.version = version;
        this.installDate = installDate;
        this.installMethod = installMethod;
        this.installedComponentCount = installedComponentCount;
        this.totalComponentCount = totalComponentCount;
        this.issues = issues != null ? issues : Collections.emptyList();
    }

    public String getVersion() { return version; }
    public String getInstallDate() { return installDate; }
    public String getInstallMethod() { return installMethod; }
    public long getInstalledComponentCount() { return installedComponentCount; }
    public long getTotalComponentCount() { return totalComponentCount; }
    public List<Issue> getIssues() { return issues; }

    /** Whether the installation is completely healthy (no issues). */
    public boolean isHealthy() {
        return issues.isEmpty();
    }

    /** Whether there are critical issues that affect basic operation. */
    public boolean hasCriticalIssues() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.CRITICAL);
    }

    /** Whether there are warnings. */
    public boolean hasWarnings() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.WARNING);
    }

    /** Get issues of a specific severity. */
    public List<Issue> getIssues(Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity).toList();
    }

    /** Issue severity levels. */
    public enum Severity {
        /** Installation cannot function. */
        CRITICAL,
        /** Something is wrong but installation still works. */
        WARNING,
        /** Optional component missing or informational. */
        INFO
    }

    /** A single health issue. */
    public record Issue(String component, String message, Severity severity) {
        @Override
        public String toString() {
            return "[" + severity + "] " + component + ": " + message;
        }
    }
}
