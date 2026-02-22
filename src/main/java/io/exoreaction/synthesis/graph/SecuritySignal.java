package io.exoreaction.synthesis.graph;

/**
 * A security signal detected by {@link SecurityAnalyzer}.
 *
 * <p>Signals range from traditional security issues (S001-S015) to
 * prompt injection and agentic surface concerns (S016-S021).
 *
 * @param signalId    unique signal identifier, e.g. "S001_SQL_INJECTION"
 * @param severity    severity level: HIGH, MEDIUM, LOW, or INFO
 * @param cweId       CWE identifier, e.g. "CWE-89" (may be null for INFO signals)
 * @param filePath    affected file path relative to workspace root
 * @param lineNumber  line number in the file (0 if unknown)
 * @param className   Java class name (may be null)
 * @param packageName Java package name (may be null)
 * @param description human-readable description of the issue
 * @param evidence    code snippet or pattern that triggered the signal (may be null)
 * @param suggestion  actionable fix suggestion
 * @param flowType    data flow type: "direct", "indirect", "agentic", "structural", or null
 * @since v1.14.0 (Security)
 */
public record SecuritySignal(
        String signalId,
        String severity,
        String cweId,
        String filePath,
        int lineNumber,
        String className,
        String packageName,
        String description,
        String evidence,
        String suggestion,
        String flowType
) {}
